(ns museum.curatoropsllm
  "CuratorOps-LLM client -- the *contained intelligence node* for the
  museum/cultural-heritage actor.

  It normalizes collection-item intake, drafts a per-jurisdiction
  museum/cultural-property evidence checklist, screens items for an
  unresolved incident (theft/damage/security) flag, drafts the item-
  loan action, and drafts the item-deaccession action. CRITICAL: it is
  a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  loan/deaccession. Every output is censored downstream by `museum.
  governor` before anything touches the SSoT, and `:item/loan`/`:item/
  deaccession` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/loan-item | :actuation/deaccession-item | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [museum.facts :as facts]
            [museum.registry :as registry]
            [museum.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the item, provenance-gap figure or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "資料記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :item/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction museum/cultural-property evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `museum.facts` -- the Collections Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [it (store/item db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction it))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "museum.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-incident
  "Incident-flag screening draft. `:incident-flag-resolved?` on the
  item record injects the failure mode: the Collections Governor must
  HOLD, un-overridably, on any unresolved incident flag."
  [db {:keys [subject]}]
  (let [it (store/item db subject)]
    (cond
      (nil? it)
      {:summary "対象資料が見つかりません" :rationale "no item record"
       :cites [] :effect :incident-screening/set :value {:item-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:incident-flag-resolved? it))
      {:summary    (str (:item-name it) ": 未解決の事故(盗難/損傷)フラグを検出")
       :rationale  "スクリーニングが未解決の事故フラグを検出。人手確認とホールドが必須。"
       :cites      [:incident-check]
       :effect     :incident-screening/set
       :value      {:item-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:item-name it) ": 事故フラグ解決済み")
       :rationale  "事故フラグスクリーニング完了。"
       :cites      [:incident-check]
       :effect     :incident-screening/set
       :value      {:item-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-item-loan
  "Draft the actual ITEM-LOAN action -- loaning out a real collection
  item. ALWAYS `:stake :actuation/loan-item` -- this is a REAL-WORLD
  act (a real item leaves institutional custody), never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`museum.phase`); the governor also
  always escalates on `:actuation/loan-item`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [it (store/item db subject)
        gapped? (and it (registry/provenance-gap-exceeds-threshold? it))]
    {:summary    (str subject " 向け貸出提案"
                      (when it (str " (item=" (:item-name it) ")")))
     :rationale  (if it
                   (str "provenance-gap-years=" (:provenance-gap-years it)
                        " max-provenance-gap-years=" registry/max-provenance-gap-years)
                   "資料が見つかりません")
     :cites      (if it [subject] [])
     :effect     :item/mark-loaned
     :value      {:item-id subject}
     :stake      :actuation/loan-item
     :confidence (if gapped? 0.3 0.9)}))

(defn- propose-item-deaccession
  "Draft the actual ITEM-DEACCESSION action -- permanently removing a
  real collection item. ALWAYS `:stake :actuation/deaccession-item` --
  this is a REAL-WORLD, irreversible act, never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`museum.phase`); the governor also always
  escalates on `:actuation/deaccession-item`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [it (store/item db subject)
        gapped? (and it (registry/provenance-gap-exceeds-threshold? it))]
    {:summary    (str subject " 向け除籍提案"
                      (when it (str " (item=" (:item-name it) ")")))
     :rationale  (if it
                   (str "provenance-gap-years=" (:provenance-gap-years it)
                        " incident-flag-resolved?=" (:incident-flag-resolved? it))
                   "資料が見つかりません")
     :cites      (if it [subject] [])
     :effect     :item/mark-deaccessioned
     :value      {:item-id subject}
     :stake      :actuation/deaccession-item
     :confidence (if gapped? 0.3 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :item/intake              (normalize-intake db request)
    :jurisdiction/assess          (assess-jurisdiction db request)
    :incident/screen                 (screen-incident db request)
    :item/loan                          (propose-item-loan db request)
    :item/deaccession                      (propose-item-deaccession db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは博物館の貸出・除籍エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:item/upsert|:assessment/set|:incident-screening/set|"
       ":item/mark-loaned|:item/mark-deaccessioned) "
       ":stake(:actuation/loan-item か :actuation/deaccession-item か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:item (store/item st subject)}
    :incident/screen      {:item (store/item st subject)}
    :item/loan            {:item (store/item st subject)}
    :item/deaccession     {:item (store/item st subject)}
    {:item (store/item st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Collections Governor
  escalates/holds -- an LLM hiccup can never auto-loan or auto-
  deaccession an item."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :curatoropsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
