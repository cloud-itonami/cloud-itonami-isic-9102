(ns museum.facts
  "Per-jurisdiction museum/cultural-property regulatory catalog -- the
  G2-style spec-basis table the Collections Governor checks every
  jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's museum/cultural-
  property requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official cultural-
  property/museum regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a real
  source, done -- never invent a jurisdiction's requirements to make
  coverage look bigger.

  The USA entry cites NAGPRA/the National Park Service's National
  NAGPRA Program specifically (rather than a general museum regulator,
  since the US has no single federal museum-licensing authority) --
  an honest, deliberately narrower citation reflecting the actual US
  regulatory structure for this domain, the same posture `wagering.
  facts`'s/`parksafety.facts`'s federated-jurisdiction entries take
  when a jurisdiction's real regulatory structure has no single
  national body.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  provenance-documentation/condition-report/insurance-valuation/
  curatorial-approval evidence set submitted in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "文化庁 (Agency for Cultural Affairs)"
          :legal-basis "文化財保護法 (Act on Protection of Cultural Properties)"
          :national-spec "重要文化財等の管理・公開・譲渡に関する規定"
          :provenance "https://www.bunka.go.jp/"
          :required-evidence ["来歴/来歴調査記録 (provenance documentation)"
                              "状態/保存修復報告書 (condition/conservation report)"
                              "保険評価書 (insurance valuation)"
                              "学芸委員会承認記録 (curatorial/board approval record)"]}
   "USA" {:name "United States"
          :owner-authority "National Park Service, National NAGPRA Program"
          :legal-basis "Native American Graves Protection and Repatriation Act (NAGPRA)"
          :national-spec "NAGPRA inventory, consultation and repatriation requirements"
          :provenance "https://www.nps.gov/subjects/nagpra/index.htm"
          :required-evidence ["Provenance documentation"
                              "Condition/conservation report"
                              "Insurance valuation"
                              "Curatorial/board approval record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Arts Council England"
          :legal-basis "Museum Accreditation Scheme + Treasure Act 1996"
          :national-spec "Accreditation Standard collections-management requirements"
          :provenance "https://www.artscouncil.org.uk/supporting-museums/accreditation-scheme"
          :required-evidence ["Provenance documentation"
                              "Condition/conservation report"
                              "Insurance valuation"
                              "Curatorial/board approval record"]}
   "DEU" {:name "Germany"
          :owner-authority "Die Beauftragte der Bundesregierung für Kultur und Medien (BKM)"
          :legal-basis "Kulturgutschutzgesetz (KGSG)"
          :national-spec "Sorgfaltspflichten bei Erwerb und Verbringung von Kulturgut"
          :provenance "https://www.kulturgutschutz-deutschland.de/"
          :required-evidence ["Herkunftsnachweis (provenance documentation)"
                              "Zustands-/Restaurierungsbericht (condition/conservation report)"
                              "Versicherungsgutachten (insurance valuation)"
                              "Kuratoriums-/Vorstandsgenehmigung (curatorial/board approval record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to loan or
  deaccession a collection item on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9102 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `museum.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
