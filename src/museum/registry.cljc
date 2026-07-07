(ns museum.registry
  "Pure-function loan + deaccession record construction -- an append-
  only museum book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a loan or deaccession
  reference number -- every institution/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `museum.facts` uses.

  `provenance-gap-exceeds-threshold?`/`max-provenance-gap-years` is the
  SECOND check in this fleet's temporal-sufficiency family to enforce
  a MAXIMUM ceiling (established by `eldercare.registry/care-plan-
  review-overdue?`), but the FIRST to apply that MAXIMUM-ceiling
  direction to a documented-history GAP (the largest undocumented
  interval within an item's ownership chain) rather than an elapsed-
  time-since-a-periodic-event figure. `10` is a single representative
  due-diligence threshold commonly referenced in museum-ethics
  guidance (AAMD/ICOM acquisition due-diligence practice, informed by
  the 1970 UNESCO Convention's provenance-research expectations for
  cultural property), not a jurisdiction-by-jurisdiction survey of
  every institution's own acquisition policy (see `museum.facts`'s own
  docstring for the honest scope this makes).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real collections-management system. It builds the
  RECORD an institution would keep, not the act of loaning out or
  deaccessioning the item itself (that is `museum.operation`'s
  `:item/loan`/`:item/deaccession`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  institution's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(def max-provenance-gap-years
  "A single representative maximum undocumented-ownership-gap
  threshold -- see ns docstring for the honest simplification this
  makes (not a jurisdiction-by-jurisdiction survey of every
  institution's own acquisition/due-diligence policy)."
  10)

(defn provenance-gap-exceeds-threshold?
  "Does `item`'s own `:provenance-gap-years` EXCEED `max-provenance-
  gap-years`? A pure ground-truth check against the item's own
  permanent field -- the SECOND check in this fleet's temporal-
  sufficiency family to enforce a MAXIMUM ceiling (see ns docstring),
  and the first to apply it to a documented-history gap rather than an
  elapsed-time-since-event figure."
  [{:keys [provenance-gap-years]}]
  (and (number? provenance-gap-years)
       (> provenance-gap-years max-provenance-gap-years)))

(defn register-item-loan
  "Validate + construct the ITEM-LOAN registration DRAFT -- the
  institution's own legal act of loaning out a real collection item.
  Pure function -- does not touch any real collections-management
  system; it builds the RECORD an institution would keep. `museum.
  governor` independently re-verifies the item's own provenance-gap
  sufficiency and incident-flag status, and blocks a double-loan of
  the same item, before this is ever allowed to commit."
  [item-id jurisdiction sequence]
  (when-not (and item-id (not= item-id ""))
    (throw (ex-info "item-loan: item_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "item-loan: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "item-loan: sequence must be >= 0" {})))
  (let [loan-number (str (str/upper-case jurisdiction) "-LON-" (zero-pad sequence 6))
        record {"record_id" loan-number
                "kind" "item-loan-draft"
                "item_id" item-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "loan_number" loan-number
     "certificate" (unsigned-certificate "ItemLoan" loan-number loan-number)}))

(defn register-item-deaccession
  "Validate + construct the ITEM-DEACCESSION registration DRAFT -- the
  institution's own legal act of permanently removing a real
  collection item from the collection. Pure function -- does not
  touch any real collections-management system; it builds the RECORD
  an institution would keep. `museum.governor` independently re-
  verifies the item's own provenance-gap sufficiency and incident-flag
  status, and blocks a double-deaccession of the same item, before
  this is ever allowed to commit."
  [item-id jurisdiction sequence]
  (when-not (and item-id (not= item-id ""))
    (throw (ex-info "item-deaccession: item_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "item-deaccession: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "item-deaccession: sequence must be >= 0" {})))
  (let [deaccession-number (str (str/upper-case jurisdiction) "-DAC-" (zero-pad sequence 6))
        record {"record_id" deaccession-number
                "kind" "item-deaccession-draft"
                "item_id" item-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "deaccession_number" deaccession-number
     "certificate" (unsigned-certificate "ItemDeaccession" deaccession-number deaccession-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
