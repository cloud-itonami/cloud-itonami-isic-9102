(ns museum.governor-contract-test
  "The governor contract as executable tests -- the museum/cultural-
  heritage analog of `cloud-itonami-isic-6512`'s `casualty.governor-
  contract-test`. The single invariant under test:

    CuratorOps-LLM never loans out or deaccessions an item the
    Collections Governor would reject, `:item/loan`/`:item/
    deaccession` NEVER auto-commit at any phase, `:item/intake` (no
    direct capital risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [museum.store :as store]
            [museum.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :curator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through incident screening -> approve, leaving a
  screening on file. Only safe to call for an item whose incident flag
  is already resolved -- an unresolved flag HARD-holds the screen
  itself (see `incident-flag-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :incident/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :item/intake :subject "item-1"
                   :patch {:id "item-1" :item-name "Edo-period lacquer box"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Edo-period lacquer box" (:item-name (store/item db "item-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "item-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "item-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "item-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "item-1")) "no assessment written"))))

(deftest item-loan-without-assessment-is-held
  (testing "item/loan before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :item/loan :subject "item-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest provenance-gap-exceeds-threshold-is-held
  (testing "an item whose provenance-gap-years exceeds the max-provenance-gap-years ceiling -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "item-3")
          res (exec-op actor "t5" {:op :item/loan :subject "item-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:provenance-gap-exceeds-threshold} (-> (store/ledger db) last :basis)))
      (is (empty? (store/loan-history db))))))

(deftest incident-flag-unresolved-is-held-and-unoverridable
  (testing "an unresolved incident flag on an item -> HOLD, and never reaches request-approval -- exercised via :incident/screen DIRECTLY, not via an actuation op against an unscreened item (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's ADR-0001)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :incident/screen :subject "item-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:incident-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/incident-screening-of db "item-4")) "no clearance written"))))

(deftest item-loan-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, provenance-current, incident-clear item still ALWAYS interrupts for human approval -- actuation/loan-item is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "item-1")
          _ (screen! actor "t7pre2" "item-1")
          r1 (exec-op actor "t7" {:op :item/loan :subject "item-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, loan record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:loan-finalized? (store/item db "item-1"))))
          (is (= 1 (count (store/loan-history db))) "one draft loan record"))))))

(deftest item-deaccession-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, incident-clear item still ALWAYS interrupts for human approval -- actuation/deaccession-item is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "item-1")
          _ (screen! actor "t8pre2" "item-1")
          r1 (exec-op actor "t8" {:op :item/deaccession :subject "item-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, deaccession record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:deaccessioned? (store/item db "item-1"))))
          (is (= 1 (count (store/deaccession-history db))) "one draft deaccession record"))))))

(deftest item-loan-double-loan-is-held
  (testing "loaning the same item twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "item-1")
          _ (screen! actor "t9pre2" "item-1")
          _ (exec-op actor "t9a" {:op :item/loan :subject "item-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :item/loan :subject "item-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-loaned} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/loan-history db))) "still only the one earlier loan"))))

(deftest item-deaccession-double-deaccession-is-held
  (testing "deaccessioning the same item twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "item-1")
          _ (screen! actor "t10pre2" "item-1")
          _ (exec-op actor "t10a" {:op :item/deaccession :subject "item-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :item/deaccession :subject "item-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-deaccessioned} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/deaccession-history db))) "still only the one earlier deaccession"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :item/intake :subject "item-1"
                          :patch {:id "item-1" :item-name "Edo-period lacquer box"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "item-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
