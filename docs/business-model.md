# Business Model: Museums activities and operation of historical sites and bu...

## Classification

- Repository: `cloud-itonami-isic-9102`
- ISIC Rev.5: `9102`
- Activity: museum activities and operation of historical sites and buildings -- collection curation, exhibition and public access to cultural heritage
- Social impact: cultural/recreational access, data sovereignty, transparent audit

## Customer

- independent museums
- cooperative heritage-site operators
- community history/cultural preservation programs

## Offer

- collection-item intake
- exhibition/catalog proposal
- loan/deaccession proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per institution
- support: monthly retainer with SLA
- migration: import from an incumbent collections-management system
- per-exhibition fee

## Trust Controls

- no item is loaned out or deaccessioned without human sign-off (a
  curator/collections committee)
- a fabricated jurisdiction citation, incomplete item evidence, a
  provenance gap beyond the due-diligence ceiling, or an unresolved
  incident (theft/damage) flag -- each forces a hold, not an override
- an item's loan/deaccession cannot each be finalized twice: a
  double-loan or double-deaccession attempt is held off this actor's
  own item facts alone, with no upstream comparison needed
- every intake, assessment, screening and loan/deaccession path is
  auditable
- donor/lender personal data stays outside Git
- emergency manual override paths remain outside LLM control
