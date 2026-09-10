(ns museum.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [museum.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Edo-period lacquer box" (:item-name (store/item s "item-1"))))
      (is (= "JPN" (:jurisdiction (store/item s "item-1"))))
      (is (= 3 (:provenance-gap-years (store/item s "item-1"))))
      (is (true? (:incident-flag-resolved? (store/item s "item-1"))))
      (is (= 15 (:provenance-gap-years (store/item s "item-3"))))
      (is (false? (:incident-flag-resolved? (store/item s "item-4"))))
      (is (false? (:loan-finalized? (store/item s "item-1"))))
      (is (false? (:deaccessioned? (store/item s "item-1"))))
      (is (= ["item-1" "item-2" "item-3" "item-4"]
             (mapv :id (store/all-items s))))
      (is (nil? (store/incident-screening-of s "item-1")))
      (is (nil? (store/assessment-of s "item-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/loan-history s)))
      (is (= [] (store/deaccession-history s)))
      (is (zero? (store/next-loan-sequence s "JPN")))
      (is (zero? (store/next-deaccession-sequence s "JPN")))
      (is (false? (store/item-already-loaned? s "item-1")))
      (is (false? (store/item-already-deaccessioned? s "item-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :item/upsert
                                 :value {:id "item-1" :item-name "Edo-period lacquer box"}})
        (is (= "Edo-period lacquer box" (:item-name (store/item s "item-1"))))
        (is (= 3 (:provenance-gap-years (store/item s "item-1"))) "unrelated field preserved"))
      (testing "assessment / incident-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["item-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "item-1")))
        (store/commit-record! s {:effect :incident-screening/set :path ["item-1"]
                                 :payload {:item-id "item-1" :verdict :resolved}})
        (is (= {:item-id "item-1" :verdict :resolved} (store/incident-screening-of s "item-1"))))
      (testing "item loan drafts a loan record and advances the loan sequence"
        (store/commit-record! s {:effect :item/mark-loaned :path ["item-1"]})
        (is (= "JPN-LON-000000" (get (first (store/loan-history s)) "record_id")))
        (is (= "item-loan-draft" (get (first (store/loan-history s)) "kind")))
        (is (true? (:loan-finalized? (store/item s "item-1"))))
        (is (= 1 (count (store/loan-history s))))
        (is (= 1 (store/next-loan-sequence s "JPN")))
        (is (true? (store/item-already-loaned? s "item-1")))
        (is (false? (store/item-already-loaned? s "item-2"))))
      (testing "item deaccession drafts a deaccession record and advances the deaccession sequence"
        (store/commit-record! s {:effect :item/mark-deaccessioned :path ["item-1"]})
        (is (= "JPN-DAC-000000" (get (first (store/deaccession-history s)) "record_id")))
        (is (= "item-deaccession-draft" (get (first (store/deaccession-history s)) "kind")))
        (is (true? (:deaccessioned? (store/item s "item-1"))))
        (is (= 1 (count (store/deaccession-history s))))
        (is (= 1 (store/next-deaccession-sequence s "JPN")))
        (is (true? (store/item-already-deaccessioned? s "item-1")))
        (is (false? (store/item-already-deaccessioned? s "item-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/item s "nope")))
    (is (= [] (store/all-items s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/loan-history s)))
    (is (= [] (store/deaccession-history s)))
    (is (zero? (store/next-loan-sequence s "JPN")))
    (is (zero? (store/next-deaccession-sequence s "JPN")))
    (store/with-items s {"x" {:id "x" :item-name "n" :provenance-gap-years 1
                              :incident-flag-resolved? true :loan-finalized? false
                              :deaccessioned? false :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:item-name (store/item s "x"))))))
