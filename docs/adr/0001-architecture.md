# ADR-0001: cloud-itonami-isic-9102 -- CuratorOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730` ADR-0001s (the pattern this ADR
  ports); ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`, the thirteen verticals
  built outside ADR-2607032000's original insurance/real-estate batch
  -- this is the fourteenth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8730`, this ADR deepens `cloud-itonami-
  isic-9102` (museums activities and operation of historical sites and
  buildings) from `:blueprint` to `:implemented`, the twenty-second
  actor in this fleet -- the FIRST cultural-heritage vertical (ISIC
  division 91).

## Problem

A museum's item-loan/item-deaccession workflow bundles several
distinct concerns under one governed workflow:

1. **Jurisdiction cultural-property correctness** -- an official
   spec-basis citation from a real regulator (文化庁/NAGPRA via the
   National Park Service/Arts Council England's Museum Accreditation
   Scheme/the Beauftragte der Bundesregierung für Kultur und Medien),
   never fabricated.
2. **Provenance due-diligence** -- does an item's own documented
   ownership history have an undocumented gap beyond the due-diligence
   ceiling museum-ethics guidance (AAMD/ICOM, informed by the 1970
   UNESCO Convention's provenance-research expectations) commonly
   references? The SECOND check in this fleet's temporal-sufficiency
   family to enforce a MAXIMUM ceiling (`eldercare.registry/care-plan-
   review-overdue?` established the first), and the FIRST to apply
   that direction to a documented-history GAP rather than an elapsed-
   time-since-event figure.
3. **Incident-flag resolution verification** -- has an item's own
   security/damage incident flag actually been resolved before either
   a loan or a deaccession is finalized? The museum-specific reuse of
   the unconditional-evaluation screening discipline this fleet's
   `casualty.governor/sanctions-violations` originally established --
   an ELEVENTH distinct grounding.
4. **Real, high-stakes actuation, twice** -- loaning out a real
   collection item and permanently deaccessioning a real collection
   item are two independently-gated real-world acts on the SAME
   entity (an item).

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a museum with an LLM" but "seal the LLM
inside a trust boundary and layer evidence-sufficiency, provenance-
due-diligence verification, incident-flag-resolution verification,
audit and human-approval on top of it, while structurally fixing both
real actuation events as human-only."

## Decision

### 1. CuratorOps-LLM is sealed into the bottom node; it never loans or deaccessions directly

`museum.curatoropsllm` returns exactly five kinds of proposal: intake
normalization, jurisdiction museum/cultural-property checklist,
incident screening, item-loan draft, and item-deaccession draft. No
proposal writes the SSoT or commits a real loan/deaccession directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 museum operation

`museum.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. `provenance-gap-exceeds-threshold?` extends the MAXIMUM-ceiling temporal-sufficiency family to a documented-history gap

`eldercare.registry/care-plan-review-overdue?` established the FIRST
check in this fleet's temporal-sufficiency family to enforce a MAXIMUM
elapsed-time ceiling ("not too much time may pass"), applied to a
periodic recurring event (a care-plan review). `provenance-gap-
exceeds-threshold?` is the SECOND application of that MAXIMUM-ceiling
direction, but generalizes it further: it does not measure time since
a recurring event at all, but the size of the largest undocumented GAP
within a historical ownership-custody chain. This is a genuine
structural extension -- "too much time has passed since X happened
last" and "too large a gap exists somewhere in a historical record"
are related but distinct concerns sharing the same comparison
direction (`>` against a ceiling).

### 4. Incident-flag-unresolved screening reuses the unconditional-evaluation discipline for an eleventh distinct grounding

`incident-flag-unresolved-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:incident/screen`, `:item/loan` AND `:item/deaccession`
-- the ELEVENTH distinct application of this exact discipline in this
fleet.

### 5. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson `parksafety`'s ADR-2607071922 Decision 5 and `eldercare`'s ADR-0001 already recorded

`incident-flag-unresolved-is-held-and-unoverridable` calls `:incident/
screen` directly against `item-4` (an unresolved incident flag), NOT
`:item/loan`/`:item/deaccession` against an un-screened item -- because
a failing screen is itself a HARD hold whose payload never persists to
the store, so the actuation ops alone could never discover the bad
ground-truth flag through this check family without the screening op
having actually been run first. This build applied that lesson
PROACTIVELY for a second consecutive vertical (after `eldercare`),
reinforcing that lessons recorded in this fleet's ADRs transfer
forward reliably.

### 6. Dual actuation, matching `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`'s shape

`museum.governor`'s `high-stakes` set has exactly two members
(`:actuation/loan-item`, `:actuation/deaccession-item`), each acting on
the SAME item entity, each with its OWN history collection
(`loan-history`/`deaccession-history`), sequence counter and dedicated
double-actuation-guard boolean.

### 7. Double-loan/double-deaccession guards check dedicated booleans, not `:status`

`already-loaned-violations`/`already-deaccessioned-violations` check
`:loan-finalized?`/`:deaccessioned?`, dedicated booleans set once and
never cleared, rather than a `:status` value that could legitimately
advance past a checked state (the exact trap `cloud-itonami-isic-
6492`'s ADR-0001 documents in detail, explicitly avoided BY DESIGN in
every sibling actor's equivalent guard since). This actor's `:status`
never needs to encode "has this actuation already happened" at all --
a deliberate architectural choice applied here for a twelfth
consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`, and unlike most other actors in this fleet, this vertical's
collection records are practice-specific rather than a shared cross-
operator data contract -- `museum.*` runs on the generic identity/
forms/dmn/bpmn/audit-ledger stack only, per the blueprint's own
explicit statement.

## Consequences

- (+) Cultural heritage gets the same governed, auditable-actor
  treatment as the twenty-one prior actors, extending the pattern to a
  genuinely different economic sector (culture & heritage, ISIC
  division 91) for the first time.
- (+) `provenance-gap-exceeds-threshold?` is a genuine structural
  contribution: it generalizes the MAXIMUM-ceiling temporal-
  sufficiency family from "elapsed time since a recurring event" to
  "size of a gap in a historical record chain."
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/museum/phase_test.clj`'s `item-loan-
  never-auto-at-any-phase`/`item-deaccession-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/museum/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The incident-flag-unresolved test/demo again correctly applied
  the SCREENING-op-directly pattern from the start, for a second
  consecutive vertical after `eldercare` -- further evidence that
  lessons recorded in this fleet's ADRs transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `museum.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `max-provenance-gap-years` models a single representative due-
  diligence threshold (10 years), not a jurisdiction-by-jurisdiction
  survey of every institution's own acquisition policy, nor a full
  collections-management system (CIDOC-CRM-style cataloguing,
  digital-rights-management for images, environmental-monitoring
  integration are out of scope -- see that fn's own docstring); real
  collections-management-system integration and ongoing conservation-
  treatment workflows are all out of scope for this OSS actor -- each
  operator's responsibility (see README's coverage table).
- 37 tests / 175 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All thirteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`; mixing a different ISIC division (91, distinct from all of those thirteen's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9102` at `:blueprint` only | ❌ | The standing direction continues past `8730`; cultural heritage is a natural, well-precedented next domain, further diversifying this fleet into a sector (culture & heritage) not yet touched |
| Model `provenance-gap-exceeds-threshold?` as a wholly new check family rather than an extension of eldercare's MAXIMUM-ceiling direction | ❌ | Would misrepresent the actual relationship -- both checks share the identical comparison shape (`>` against a ceiling); honestly framing this as an extension/generalization, not a brand-new family, is more accurate and keeps the fleet's check-family taxonomy legible |
| Test `incident-flag-unresolved-violations` via an actuation op against an un-screened item (the shape `parksafety`'s ORIGINAL, buggy test used) | ❌ | Already proven wrong by `parksafety`'s ADR-2607071922 Decision 5 and reconfirmed by `eldercare`'s ADR-0001 -- a failing screen never persists its payload to the store, so the actuation op alone cannot discover the bad ground-truth flag through this check family; this build tested the SCREENING op directly from the start |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/museum`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |
