# cloud-itonami-isic-9102

Open Business Blueprint for **ISIC Rev.5 9102**: Museums activities and
operation of historical sites and buildings. This repository publishes
a museum/cultural-heritage actor -- collection-item intake,
jurisdiction assessment, incident screening, item-loan finalization
and item-deaccession finalization -- as an OSS business that any
qualified museum/heritage-site operator can fork, deploy, run, improve
and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730)) --
the first cultural-heritage vertical (ISIC division 91) in this fleet.
Here it is **CuratorOps-LLM ⊣ Collections Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> collection-item intake summary, normalizing records, and checking
> whether an item's own documented ownership history has an
> undocumented gap beyond due-diligence norms -- but it has **no
> notion of which jurisdiction's cultural-property requirements are
> official, no license to loan out or deaccession a real collection
> item, and no way to know on its own whether an item's own incident
> (theft/damage) flag is still unresolved**. Letting it loan or
> deaccession an item directly invites fabricated jurisdiction
> citations, a loan or deaccession of an item with an undocumented
> provenance gap beyond due-diligence norms, and an unresolved
> security/damage incident being quietly signed off -- and liability,
> and cultural-property risk, for whoever runs it. This project seals
> the CuratorOps-LLM into a single node and wraps it with an
> independent **Collections Governor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers collection-item intake through jurisdiction
assessment, incident screening, item-loan finalization and item-
deaccession finalization. It does **not**, by itself, hold any license
required to operate a museum/heritage site in a given jurisdiction,
and it does not claim to. It also does **not** model a full
collections-management system -- no CIDOC-CRM-style cataloguing
standard, no digital-rights-management for images, no environmental-
monitoring integration (see `museum.registry/max-provenance-gap-
years`'s own docstring for the honest simplification this makes: a
single representative due-diligence threshold, not a jurisdiction-by-
jurisdiction survey of every institution's own acquisition policy).
Whoever deploys and operates a live instance (a museum/heritage-site
operator) supplies any jurisdiction-specific license, the real
curatorial/conservation expertise and the real collections-management-
system integrations, and bears that jurisdiction's liability -- the
software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch for every new market.

### Actuation

**Loaning out or permanently deaccessioning a real collection item is
never autonomous, at any phase, by construction.** Two independent
layers enforce this (`museum.governor`'s `:actuation/loan-item`/
`:actuation/deaccession-item` high-stakes gate and `museum.phase`'s
phase table, which never puts `:item/loan`/`:item/deaccession` in any
phase's `:auto` set) -- see `museum.phase`'s docstring and `test/
museum/phase_test.clj`'s `item-loan-never-auto-at-any-phase`/`item-
deaccession-never-auto-at-any-phase`. The actor may draft, check and
recommend; a human curator/collections committee is always the one who
actually loans out or deaccessions an item. Like `6512`/`6622`/`6520`/
`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`, this actor has
TWO actuation events.

## The core contract

```
item intake + jurisdiction facts (museum.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ CuratorOps-  │ ─────────────▶ │ Collections                 │  (independent system)
   │ LLM (sealed) │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ provenance-gap-
                                 │             │           │ exceeds-threshold
                           record + ledger  escalate ─▶ human   (MAXIMUM-ceiling
                                             (ALWAYS for         history gap) ·
                                              :item/loan /         incident-flag-unresolved
                                              :item/deaccession)     · already-loaned/
                                                                       -deaccessioned
```

**The CuratorOps-LLM never loans out or deaccessions an item the
Collections Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated jurisdiction requirements;
unsupported item evidence; a provenance gap beyond the due-diligence
ceiling; an unresolved incident flag; a double loan or deaccession)
force **hold** and *cannot* be approved past; a clean loan/deaccession
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (item loan, item deaccession) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a collection-handling robot
assists physical artifact movement and climate-controlled storage,
under the actor, gated by the independent **Collections Governor**.
The governor never dispatches hardware itself; `:high`/`:safety-
critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Collections Governor, item-loan + item-deaccession draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9102`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`, this vertical's collection records are practice-specific
rather than a shared cross-operator data contract, so `museum.*` runs
on the generic identity/forms/dmn/bpmn/audit-ledger stack only -- no
bespoke domain capability lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/museum/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate item-loan/item-deaccession history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded item, and the double-loan/double-deaccession guards check dedicated `:loan-finalized?`/`:deaccessioned?` booleans rather than a `:status` value |
| `src/museum/registry.cljc` | Item-loan + item-deaccession draft records, plus `provenance-gap-exceeds-threshold?`/`max-provenance-gap-years` -- the SECOND check in this fleet's temporal-sufficiency family to enforce a MAXIMUM ceiling (established by `eldercare.registry/care-plan-review-overdue?`), and the first to apply it to a documented-history GAP rather than an elapsed-time-since-event figure |
| `src/museum/facts.cljc` | Per-jurisdiction museum/cultural-property catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/museum/curatoropsllm.cljc` | **CuratorOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/incident-screening/item-loan/item-deaccession proposals |
| `src/museum/governor.cljc` | **Collections Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · provenance-gap-exceeds-threshold, pure ground-truth MAXIMUM-ceiling recompute · incident-flag-unresolved, unconditional evaluation, the ELEVENTH grounding of this discipline) + already-loaned/already-deaccessioned guards + 1 soft (confidence/actuation gate) |
| `src/museum/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (both loan and deaccession always human; item intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/museum/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/museum/sim.cljc` | demo driver |
| `test/museum/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers collection-item intake through jurisdiction
assessment, incident screening, item-loan finalization and item-
deaccession finalization -- the core governed lifecycle this
blueprint's own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Item intake + per-jurisdiction museum/cultural-property checklisting, HARD-gated on an official spec-basis citation (`:item/intake`/`:jurisdiction/assess`) | A full collections-management system (CIDOC-CRM-style cataloguing, digital-rights-management for images, environmental-monitoring integration -- see `provenance-gap-exceeds-threshold?`'s docstring) |
| Incident screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:incident/screen`) | Real collections-management-system integration, exhibition-design/visitor-experience workflows |
| Item-loan finalization, HARD-gated on the item's own provenance gap not exceeding the due-diligence ceiling and a double-loan guard (`:item/loan`) | Ongoing conservation-treatment workflows themselves |
| Item-deaccession finalization, HARD-gated on the item's incident flag being resolved and a double-deaccession guard (`:item/deaccession`) | |
| Immutable audit ledger for every intake/assessment/screening/loan/deaccession decision | |

Extending coverage is additive: add the next gate (e.g. a conservation-
condition check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`museum.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `museum.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `museum.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `CuratorOps-LLM` + `Collections Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the twenty-
one prior actors' architecture. See `docs/adr/0001-architecture.md`
for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
