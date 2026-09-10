(ns museum.registry-test
  (:require [clojure.test :refer [deftest is]]
            [museum.registry :as r]))

;; ----------------------------- provenance-gap-exceeds-threshold? -----------------------------

(deftest gap-not-exceeding-when-within-threshold
  (is (not (r/provenance-gap-exceeds-threshold? {:provenance-gap-years 3})))
  (is (not (r/provenance-gap-exceeds-threshold? {:provenance-gap-years 10}))
      "exactly at the ceiling does not yet exceed it"))

(deftest gap-exceeding-when-over-threshold
  (is (r/provenance-gap-exceeds-threshold? {:provenance-gap-years 11}))
  (is (r/provenance-gap-exceeds-threshold? {:provenance-gap-years 15})))

(deftest gap-exceeds-is-false-on-missing-or-non-numeric-field
  (is (not (r/provenance-gap-exceeds-threshold? {})))
  (is (not (r/provenance-gap-exceeds-threshold? {:provenance-gap-years nil})))
  (is (not (r/provenance-gap-exceeds-threshold? {:provenance-gap-years "15"}))))

;; ----------------------------- register-item-loan -----------------------------

(deftest item-loan-is-a-draft-not-a-real-loan
  (let [result (r/register-item-loan "item-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest item-loan-assigns-loan-number
  (let [result (r/register-item-loan "item-1" "JPN" 7)]
    (is (= (get result "loan_number") "JPN-LON-000007"))
    (is (= (get-in result ["record" "item_id"]) "item-1"))
    (is (= (get-in result ["record" "kind"]) "item-loan-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest item-loan-validation-rules
  (is (thrown? Exception (r/register-item-loan "" "JPN" 0)))
  (is (thrown? Exception (r/register-item-loan "item-1" "" 0)))
  (is (thrown? Exception (r/register-item-loan "item-1" "JPN" -1))))

(deftest loan-history-is-append-only
  (let [c1 (r/register-item-loan "item-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-item-loan "item-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-LON-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-LON-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-item-deaccession -----------------------------

(deftest item-deaccession-is-a-draft-not-a-real-deaccession
  (let [result (r/register-item-deaccession "item-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest item-deaccession-assigns-deaccession-number
  (let [result (r/register-item-deaccession "item-1" "JPN" 7)]
    (is (= (get result "deaccession_number") "JPN-DAC-000007"))
    (is (= (get-in result ["record" "item_id"]) "item-1"))
    (is (= (get-in result ["record" "kind"]) "item-deaccession-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest item-deaccession-validation-rules
  (is (thrown? Exception (r/register-item-deaccession "" "JPN" 0)))
  (is (thrown? Exception (r/register-item-deaccession "item-1" "" 0)))
  (is (thrown? Exception (r/register-item-deaccession "item-1" "JPN" -1))))

(deftest deaccession-history-is-append-only
  (let [d1 (r/register-item-deaccession "item-1" "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-item-deaccession "item-2" "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DAC-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DAC-000001" (get-in hist2 [1 "record_id"])))))
