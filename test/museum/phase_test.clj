(ns museum.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:item/loan`/`:item/deaccession` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [museum.phase :as phase]))

(deftest item-loan-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real item loan"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :item/loan))
          (str "phase " n " must not auto-commit :item/loan")))))

(deftest item-deaccession-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real item deaccession"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :item/deaccession))
          (str "phase " n " must not auto-commit :item/deaccession")))))

(deftest incident-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety-test/inspection/incident-flag screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :incident/screen))
          (str "phase " n " must not auto-commit :incident/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":item/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:item/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :item/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :item/loan} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :item/deaccession} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :item/intake} :commit)))))
