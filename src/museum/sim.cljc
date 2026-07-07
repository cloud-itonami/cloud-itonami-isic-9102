(ns museum.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean item through
  intake -> jurisdiction assessment -> incident screening -> item-loan
  proposal (always escalates) -> human approval -> commit, then through
  item-deaccession proposal (always escalates) -> human approval ->
  commit, then shows five HARD holds (a jurisdiction with no spec-
  basis, a provenance gap beyond the due-diligence ceiling, an
  unresolved incident flag screened directly via `:incident/screen`
  [never via an actuation op against an unscreened item -- see this
  actor's own governor ns docstring / the lesson `parksafety`'s
  ADR-2607071922 Decision 5 and `eldercare`'s ADR-0001 already
  recorded], and a double loan/deaccession of an already-processed
  item) that never reach a human at all, and prints the audit ledger +
  the draft item-loan and item-deaccession records."
  (:require [langgraph.graph :as g]
            [museum.store :as store]
            [museum.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :curator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== item/intake item-1 (JPN, clean; provenance-gap-years 3, incident-flag resolved) ==")
    (println (exec! actor "t1" {:op :item/intake :subject "item-1"
                                :patch {:id "item-1" :item-name "Edo-period lacquer box"}} operator))

    (println "== jurisdiction/assess item-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "item-1"} operator))
    (println (approve! actor "t2"))

    (println "== incident/screen item-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :incident/screen :subject "item-1"} operator))
    (println (approve! actor "t3"))

    (println "== item/loan item-1 (always escalates -- actuation/loan-item) ==")
    (let [r (exec! actor "t4" {:op :item/loan :subject "item-1"} operator)]
      (println r)
      (println "-- human curator approves --")
      (println (approve! actor "t4")))

    (println "== item/deaccession item-1 (always escalates -- actuation/deaccession-item) ==")
    (let [r (exec! actor "t5" {:op :item/deaccession :subject "item-1"} operator)]
      (println r)
      (println "-- human curator approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess item-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "item-2" :no-spec? true} operator))

    (println "== jurisdiction/assess item-3 (escalates -- human approves; sets up the provenance-gap test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "item-3"} operator))
    (println (approve! actor "t7"))

    (println "== item/loan item-3 (provenance-gap-years 15 > 10 -> HARD hold) ==")
    (println (exec! actor "t8" {:op :item/loan :subject "item-3"} operator))

    (println "== incident/screen item-4 (unresolved incident flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :incident/screen :subject "item-4"} operator))

    (println "== item/loan item-1 AGAIN (double-loan -> HARD hold) ==")
    (println (exec! actor "t10" {:op :item/loan :subject "item-1"} operator))

    (println "== item/deaccession item-1 AGAIN (double-deaccession -> HARD hold) ==")
    (println (exec! actor "t11" {:op :item/deaccession :subject "item-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft item-loan records ==")
    (doseq [r (store/loan-history db)] (println r))

    (println "== draft item-deaccession records ==")
    (doseq [r (store/deaccession-history db)] (println r))))
