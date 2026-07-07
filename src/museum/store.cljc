(ns museum.store
  "SSoT for the museum/cultural-heritage actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/museum/store_contract_test.clj), which is the whole point:
  the actor, the Collections Governor and the audit ledger never know
  which SSoT they run on.

  Like `marketadmin.store`'s dual admission/halt-lift history,
  `registrar.store`'s dual grade/degree history, `wagering.store`'s
  dual acceptance/settlement history, `repairshop.store`'s dual
  completion/return history and `eldercare.store`'s dual care-plan/
  incident-response-finalization history, this actor has TWO actuation
  events (loaning out an item, deaccessioning an item) acting on the
  SAME entity (a collection item), each with its OWN history
  collection, sequence counter and dedicated double-actuation-guard
  boolean (`:loan-finalized?`/`:deaccessioned?`, never a `:status`
  value) -- the same discipline `accounting.governor`'s/`marketadmin.
  governor`'s/`testlab.governor`'s/`clinic.governor`'s/`registrar.
  governor`'s/`wagering.governor`'s/`veterinary.governor`'s/`funeral.
  governor`'s/`repairshop.governor`'s/`parksafety.governor`'s/
  `eldercare.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which item was
  screened for an unresolved incident flag, which item was loaned out,
  which item was deaccessioned, on what jurisdictional basis, approved
  by whom' is always a query over an immutable log -- the audit trail
  a lender/donor/regulator trusting an institution needs, and the
  evidence an operator needs if a loan or deaccession is later
  disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [museum.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (item [s id])
  (all-items [s])
  (incident-screening-of [s item-id] "committed incident screening verdict for an item, or nil")
  (assessment-of [s item-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (loan-history [s] "the append-only item-loan history (museum.registry drafts)")
  (deaccession-history [s] "the append-only item-deaccession history (museum.registry drafts)")
  (next-loan-sequence [s jurisdiction] "next item-loan-number sequence for a jurisdiction")
  (next-deaccession-sequence [s jurisdiction] "next item-deaccession-number sequence for a jurisdiction")
  (item-already-loaned? [s item-id] "has this item's loan already been finalized?")
  (item-already-deaccessioned? [s item-id] "has this item already been deaccessioned?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-items [s items] "replace/seed the item directory (map id->item)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained collection-item set covering both actuation
  lifecycles (loaning out an item, deaccessioning an item) so the
  actor + tests run offline."
  []
  {:items
   {"item-1" {:id "item-1" :item-name "Edo-period lacquer box"
              :provenance-gap-years 3 :incident-flag-resolved? true
              :loan-finalized? false :deaccessioned? false
              :jurisdiction "JPN" :status :intake}
    "item-2" {:id "item-2" :item-name "Unregistered relic"
              :provenance-gap-years 3 :incident-flag-resolved? true
              :loan-finalized? false :deaccessioned? false
              :jurisdiction "ATL" :status :intake}
    "item-3" {:id "item-3" :item-name "Undocumented bronze mirror"
              :provenance-gap-years 15 :incident-flag-resolved? true
              :loan-finalized? false :deaccessioned? false
              :jurisdiction "JPN" :status :intake}
    "item-4" {:id "item-4" :item-name "Ceramic vessel (theft-flagged)"
              :provenance-gap-years 3 :incident-flag-resolved? false
              :loan-finalized? false :deaccessioned? false
              :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-loan!
  "Backend-agnostic `:item/mark-loaned` -- looks up the item via the
  protocol and drafts the item-loan record, and returns {:result ..
  :item-patch ..} for the caller to persist."
  [s item-id]
  (let [it (item s item-id)
        seq-n (next-loan-sequence s (:jurisdiction it))
        result (registry/register-item-loan item-id (:jurisdiction it) seq-n)]
    {:result result
     :item-patch {:loan-finalized? true
                  :loan-number (get result "loan_number")}}))

(defn- finalize-deaccession!
  "Backend-agnostic `:item/mark-deaccessioned` -- looks up the item via
  the protocol and drafts the item-deaccession record, and returns
  {:result .. :item-patch ..} for the caller to persist."
  [s item-id]
  (let [it (item s item-id)
        seq-n (next-deaccession-sequence s (:jurisdiction it))
        result (registry/register-item-deaccession item-id (:jurisdiction it) seq-n)]
    {:result result
     :item-patch {:deaccessioned? true
                  :deaccession-number (get result "deaccession_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (item [_ id] (get-in @a [:items id]))
  (all-items [_] (sort-by :id (vals (:items @a))))
  (incident-screening-of [_ id] (get-in @a [:incident-screenings id]))
  (assessment-of [_ item-id] (get-in @a [:assessments item-id]))
  (ledger [_] (:ledger @a))
  (loan-history [_] (:loans @a))
  (deaccession-history [_] (:deaccessions @a))
  (next-loan-sequence [_ jurisdiction] (get-in @a [:loan-sequences jurisdiction] 0))
  (next-deaccession-sequence [_ jurisdiction] (get-in @a [:deaccession-sequences jurisdiction] 0))
  (item-already-loaned? [_ item-id] (boolean (get-in @a [:items item-id :loan-finalized?])))
  (item-already-deaccessioned? [_ item-id] (boolean (get-in @a [:items item-id :deaccessioned?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :item/upsert
      (swap! a update-in [:items (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :incident-screening/set
      (swap! a assoc-in [:incident-screenings (first path)] payload)

      :item/mark-loaned
      (let [item-id (first path)
            {:keys [result item-patch]} (finalize-loan! s item-id)
            jurisdiction (:jurisdiction (item s item-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:loan-sequences jurisdiction] (fnil inc 0))
                       (update-in [:items item-id] merge item-patch)
                       (update :loans registry/append result))))
        result)

      :item/mark-deaccessioned
      (let [item-id (first path)
            {:keys [result item-patch]} (finalize-deaccession! s item-id)
            jurisdiction (:jurisdiction (item s item-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:deaccession-sequences jurisdiction] (fnil inc 0))
                       (update-in [:items item-id] merge item-patch)
                       (update :deaccessions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-items [s items] (when (seq items) (swap! a assoc :items items)) s))

(defn seed-db
  "A MemStore seeded with the demo item set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :incident-screenings {} :ledger [] :loan-sequences {}
                           :loans [] :deaccession-sequences {} :deaccessions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/incident-screening payloads, ledger
  facts, loan/deaccession records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:item/id                             {:db/unique :db.unique/identity}
   :assessment/item-id                  {:db/unique :db.unique/identity}
   :incident-screening/item-id          {:db/unique :db.unique/identity}
   :ledger/seq                          {:db/unique :db.unique/identity}
   :loan/seq                            {:db/unique :db.unique/identity}
   :deaccession/seq                     {:db/unique :db.unique/identity}
   :loan-sequence/jurisdiction          {:db/unique :db.unique/identity}
   :deaccession-sequence/jurisdiction   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- item->tx [{:keys [id item-name provenance-gap-years incident-flag-resolved?
                         loan-finalized? deaccessioned?
                         jurisdiction status loan-number deaccession-number]}]
  (cond-> {:item/id id}
    item-name                          (assoc :item/item-name item-name)
    provenance-gap-years               (assoc :item/provenance-gap-years provenance-gap-years)
    (some? incident-flag-resolved?)    (assoc :item/incident-flag-resolved? incident-flag-resolved?)
    (some? loan-finalized?)            (assoc :item/loan-finalized? loan-finalized?)
    (some? deaccessioned?)             (assoc :item/deaccessioned? deaccessioned?)
    jurisdiction                       (assoc :item/jurisdiction jurisdiction)
    status                             (assoc :item/status status)
    loan-number                        (assoc :item/loan-number loan-number)
    deaccession-number                 (assoc :item/deaccession-number deaccession-number)))

(def ^:private item-pull
  [:item/id :item/item-name :item/provenance-gap-years
   :item/incident-flag-resolved? :item/loan-finalized? :item/deaccessioned?
   :item/jurisdiction :item/status :item/loan-number :item/deaccession-number])

(defn- pull->item [m]
  (when (:item/id m)
    {:id (:item/id m) :item-name (:item/item-name m)
     :provenance-gap-years (:item/provenance-gap-years m)
     :incident-flag-resolved? (boolean (:item/incident-flag-resolved? m))
     :loan-finalized? (boolean (:item/loan-finalized? m))
     :deaccessioned? (boolean (:item/deaccessioned? m))
     :jurisdiction (:item/jurisdiction m) :status (:item/status m)
     :loan-number (:item/loan-number m) :deaccession-number (:item/deaccession-number m)}))

(defrecord DatomicStore [conn]
  Store
  (item [_ id]
    (pull->item (d/pull (d/db conn) item-pull [:item/id id])))
  (all-items [_]
    (->> (d/q '[:find [?id ...] :where [?e :item/id ?id]] (d/db conn))
         (map #(pull->item (d/pull (d/db conn) item-pull [:item/id %])))
         (sort-by :id)))
  (incident-screening-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?iid
                :where [?k :incident-screening/item-id ?iid] [?k :incident-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ item-id]
    (dec* (d/q '[:find ?p . :in $ ?iid
                :where [?a :assessment/item-id ?iid] [?a :assessment/payload ?p]]
              (d/db conn) item-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (loan-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :loan/seq ?s] [?e :loan/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (deaccession-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :deaccession/seq ?s] [?e :deaccession/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-loan-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :loan-sequence/jurisdiction ?j] [?e :loan-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-deaccession-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :deaccession-sequence/jurisdiction ?j] [?e :deaccession-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (item-already-loaned? [s item-id]
    (boolean (:loan-finalized? (item s item-id))))
  (item-already-deaccessioned? [s item-id]
    (boolean (:deaccessioned? (item s item-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :item/upsert
      (d/transact! conn [(item->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/item-id (first path) :assessment/payload (enc payload)}])

      :incident-screening/set
      (d/transact! conn [{:incident-screening/item-id (first path) :incident-screening/payload (enc payload)}])

      :item/mark-loaned
      (let [item-id (first path)
            {:keys [result item-patch]} (finalize-loan! s item-id)
            jurisdiction (:jurisdiction (item s item-id))
            next-n (inc (next-loan-sequence s jurisdiction))]
        (d/transact! conn
                     [(item->tx (assoc item-patch :id item-id))
                      {:loan-sequence/jurisdiction jurisdiction :loan-sequence/next next-n}
                      {:loan/seq (count (loan-history s)) :loan/record (enc (get result "record"))}])
        result)

      :item/mark-deaccessioned
      (let [item-id (first path)
            {:keys [result item-patch]} (finalize-deaccession! s item-id)
            jurisdiction (:jurisdiction (item s item-id))
            next-n (inc (next-deaccession-sequence s jurisdiction))]
        (d/transact! conn
                     [(item->tx (assoc item-patch :id item-id))
                      {:deaccession-sequence/jurisdiction jurisdiction :deaccession-sequence/next next-n}
                      {:deaccession/seq (count (deaccession-history s)) :deaccession/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-items [s items]
    (when (seq items) (d/transact! conn (mapv item->tx (vals items)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:items ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [items]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-items s items))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo item set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
