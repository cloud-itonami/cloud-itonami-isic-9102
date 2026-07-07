(ns museum.governor
  "Collections Governor -- the independent compliance layer that earns
  the CuratorOps-LLM the right to commit. The LLM has no notion of
  jurisdictional cultural-property law, whether an item's own
  ownership history has an undocumented gap beyond due-diligence
  norms, whether an item's own incident flag is still unresolved, or
  when an act stops being a draft and becomes a real-world loan or
  deaccession, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the museum/cultural-heritage analog
  of `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete evidence, a
  provenance gap beyond the due-diligence ceiling, an unresolved
  incident flag, or a double loan/deaccession). The confidence/
  actuation gate is SOFT: it asks a human to look (low confidence /
  actuation), and the human may approve -- but see `museum.phase`: for
  `:stake :actuation/loan-item`/`:actuation/deaccession-item` (a real
  loan or deaccession) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`museum.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:item/loan`/`:item/
                                       deaccession`, has the
                                       jurisdiction actually been
                                       assessed with a full item-
                                       evidence checklist on file?
    3. Provenance gap exceeds
       threshold                    -- for `:item/loan`/`:item/
                                       deaccession`, INDEPENDENTLY
                                       recompute whether the item's own
                                       `:provenance-gap-years` exceeds
                                       `museum.registry/max-provenance-
                                       gap-years` (`museum.registry/
                                       provenance-gap-exceeds-
                                       threshold?`) -- needs no
                                       proposal inspection or stored-
                                       verdict lookup at all. The
                                       SECOND check in this fleet's
                                       temporal-sufficiency family to
                                       enforce a MAXIMUM ceiling
                                       (`eldercare.governor/care-plan-
                                       review-overdue-violations`
                                       established the first), and the
                                       FIRST to apply that direction to
                                       a documented-history GAP rather
                                       than an elapsed-time-since-event
                                       figure.
    4. Incident flag unresolved    -- for `:item/loan`/`:item/
                                       deaccession`, reported by THIS
                                       proposal itself (an `:incident/
                                       screen` that just found an
                                       unresolved flag), or already on
                                       file for the item (`:incident/
                                       screen`/either actuation op).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       `marketadmin.governor/
                                       surveillance-flag-unresolved-
                                       violations`/`testlab.governor/
                                       calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations`/`parksafety.
                                       governor/inspection-not-passed-
                                       violations`/`eldercare.governor/
                                       incident-flag-unresolved-
                                       violations` established -- the
                                       ELEVENTH distinct application of
                                       this exact discipline. Like
                                       `parksafety.governor`'s/
                                       `eldercare.governor`'s
                                       equivalent checks, this is
                                       exercised in tests/demo via
                                       `:incident/screen` DIRECTLY, not
                                       via an actuation op against an
                                       unscreened item -- see this ns's
                                       own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:item/loan`/
                                       `:item/deaccession` (REAL acts)
                                       -> escalate.

  Two more guards, double-loan/double-deaccession prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-loaned-violations`/
  `already-deaccessioned-violations` refuse to loan/deaccession the
  SAME item twice, off dedicated `:loan-finalized?`/`:deaccessioned?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s/`clinic.governor`'s/
  `registrar.governor`'s/`wagering.governor`'s/`veterinary.
  governor`'s/`funeral.governor`'s/`repairshop.governor`'s/
  `parksafety.governor`'s/`eldercare.governor`'s guards establish,
  informed by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [museum.facts :as facts]
            [museum.registry :as registry]
            [museum.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Loaning out a real collection item and permanently deaccessioning a
  real collection item are the two real-world actuation events this
  actor performs -- a two-member set, matching `cloud-itonami-isic-
  6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s/`6611`'s/
  `8530`'s/`9200`'s/`9521`'s/`8730`'s dual-actuation shape."
  #{:actuation/loan-item :actuation/deaccession-item})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:item/loan`/`:item/deaccession`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's cultural-property requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :item/loan :item/deaccession} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:item/loan`/`:item/deaccession`, the jurisdiction's required
  provenance/condition/insurance/curatorial-approval evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:item/loan :item/deaccession} op)
    (let [it (store/item st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction it) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(来歴調査記録/保存修復報告書/保険評価書/学芸委員会承認記録等)が充足していない状態での提案"}]))))

(defn- provenance-gap-exceeds-threshold-violations
  "For `:item/loan`/`:item/deaccession`, INDEPENDENTLY recompute
  whether the item's own provenance-gap-years exceeds `museum.
  registry/max-provenance-gap-years` via `museum.registry/
  provenance-gap-exceeds-threshold?` -- needs no proposal inspection
  or stored-verdict lookup at all, since its input is a permanent
  ground-truth field already on the item."
  [{:keys [op subject]} st]
  (when (contains? #{:item/loan :item/deaccession} op)
    (let [it (store/item st subject)]
      (when (registry/provenance-gap-exceeds-threshold? it)
        [{:rule :provenance-gap-exceeds-threshold
          :detail (str subject " の来歴に" (:provenance-gap-years it)
                      "年の未確認期間があり、上限(" registry/max-provenance-gap-years
                      "年)を超過している")}]))))

(defn- incident-flag-unresolved-violations
  "An unresolved incident flag -- reported by THIS proposal (e.g. an
  `:incident/screen` that itself just found an unresolved flag), or
  already on file in the store for the item (`:incident/screen`/either
  actuation op) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        item-id (when (contains? #{:incident/screen :item/loan :item/deaccession} op) subject)
        hit-on-file? (and item-id (= :unresolved (:verdict (store/incident-screening-of st item-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :incident-flag-unresolved
        :detail "未解決の事故(盗難/損傷等)フラグが残っている資料に対する提案は進められない"}])))

(defn- already-loaned-violations
  "For `:item/loan`, refuses to finalize the SAME item's loan twice,
  off a dedicated `:loan-finalized?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :item/loan)
    (when (store/item-already-loaned? st subject)
      [{:rule :already-loaned
        :detail (str subject " は既に貸出確定済み")}])))

(defn- already-deaccessioned-violations
  "For `:item/deaccession`, refuses to deaccession the SAME item
  twice, off a dedicated `:deaccessioned?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :item/deaccession)
    (when (store/item-already-deaccessioned? st subject)
      [{:rule :already-deaccessioned
        :detail (str subject " は既に除籍済み")}])))

(defn check
  "Censors a CuratorOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (provenance-gap-exceeds-threshold-violations request st)
                           (incident-flag-unresolved-violations request proposal st)
                           (already-loaned-violations request st)
                           (already-deaccessioned-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
