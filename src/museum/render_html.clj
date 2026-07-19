(ns museum.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger). Drives the REAL actor stack (museum.operation ->
  museum.governor -> museum.store) through a scenario built from real
  seeded demo data (`museum.store/demo-data`) -- the same scenario
  `museum.sim` walks (verified by running it: every id/op it uses exists
  in `store.cljc`'s real seed data, and every disposition matches
  `museum.governor`'s real rules). No invented numbers, no timestamps,
  byte-identical across reruns against the same seed."
  (:require [clojure.string :as str]
            [museum.store :as store]
            [museum.operation :as op]
            [museum.governor :as governor]
            [museum.phase :as phase]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :curator :phase 3})

(defn- exec!
  [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve!
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor graph (museum.operation/build, bound
  to a freshly-seeded MemStore) through the same scenario
  `museum.sim/-main` walks -- one auto-commit at phase 3
  (`:item/intake`, no capital risk), the two always-escalate
  high-stakes actuation ops (`:item/loan`/`:item/deaccession`, both
  approved by the human curator), and every one of this actor's 5
  distinct HARD-hold rules (no-spec-basis, provenance-gap-exceeds-
  threshold, incident-flag-unresolved, already-loaned,
  already-deaccessioned) -- none of which ever reaches a human.
  Returns the seeded, now-mutated `db` for the renderer to read."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; item-1 (JPN, clean; provenance-gap-years 3, incident-flag resolved)
    ;; :item/intake auto-commits clean at phase 3 -- the only op in
    ;; phase 3's :auto set (museum.phase).
    (exec! actor "t1" {:op :item/intake :subject "item-1"
                        :patch {:id "item-1" :item-name "Edo-period lacquer box"}})

    ;; jurisdiction/assess and incident/screen are phase-3 writes but
    ;; never phase-3 auto -- always escalate, approved here.
    (exec! actor "t2" {:op :jurisdiction/assess :subject "item-1"})
    (approve! actor "t2")
    (exec! actor "t3" {:op :incident/screen :subject "item-1"})
    (approve! actor "t3")

    ;; item/loan / item/deaccession are ALWAYS high-stakes
    ;; (governor/high-stakes #{:actuation/loan-item
    ;; :actuation/deaccession-item}) -- always escalate regardless of
    ;; phase or governor cleanliness, approved by the human curator.
    (exec! actor "t4" {:op :item/loan :subject "item-1"})
    (approve! actor "t4")
    (exec! actor "t5" {:op :item/deaccession :subject "item-1"})
    (approve! actor "t5")

    ;; item-2 (ATL, no spec-basis in museum.facts/catalog) -> HARD hold
    ;; :no-spec-basis. Never reaches a human.
    (exec! actor "t6" {:op :jurisdiction/assess :subject "item-2" :no-spec? true})

    ;; item-3 (JPN, provenance-gap-years 15 > registry/max-provenance-
    ;; gap-years 10) -- assess+approve first so the HOLD below is
    ;; attributable to the provenance-gap check alone (not
    ;; evidence-incomplete).
    (exec! actor "t7" {:op :jurisdiction/assess :subject "item-3"})
    (approve! actor "t7")
    (exec! actor "t8" {:op :item/loan :subject "item-3"})

    ;; item-4 (JPN, incident-flag-resolved? false) -- HARD hold
    ;; :incident-flag-unresolved, screened DIRECTLY (never via an
    ;; actuation op against an unscreened item -- see governor.cljc's
    ;; own docstring).
    (exec! actor "t9" {:op :incident/screen :subject "item-4"})

    ;; item-1 again -- double-loan / double-deaccession guards, off
    ;; the dedicated :loan-finalized?/:deaccessioned? facts (never a
    ;; :status value).
    (exec! actor "t10" {:op :item/loan :subject "item-1"})
    (exec! actor "t11" {:op :item/deaccession :subject "item-1"})

    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for
  "The most recent persisted audit-ledger fact for `subject` (the real
  subject-key field this repo's operation.cljc/governor.cljc use is
  `:subject` -- see commit-fact/hold-fact)."
  [db subject]
  (last (filter #(= subject (:subject %)) (store/ledger db))))

(defn- status-cell
  [db subject]
  (if-let [f (last-fact-for db subject)]
    (case (:t f)
      :committed         "<span class=\"ok\">committed</span>"
      :approval-granted   "<span class=\"ok\">approved &amp; committed</span>"
      :governor-hold      (str "<span class=\"err\">HARD hold &mdash; "
                               (esc (str/join ", " (map (comp name :rule) (:violations f))))
                               "</span>")
      :approval-rejected  "<span class=\"err\">approval rejected</span>"
      "<span class=\"warn\">approval-requested</span>")
    "<span class=\"muted\">no activity</span>"))

(defn- bool-cell
  [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- items-table
  [db]
  (str
   "<table><thead><tr>"
   "<th>id</th><th>item</th><th>jurisdiction</th><th>provenance-gap-years</th>"
   "<th>incident-flag-resolved?</th><th>loan-finalized?</th><th>deaccessioned?</th>"
   "<th>loan #</th><th>deaccession #</th><th>current status</th>"
   "</tr></thead><tbody>\n"
   (str/join "\n"
             (for [it (store/all-items db)]
               (str "<tr><td><code>" (esc (:id it)) "</code></td>"
                    "<td>" (esc (:item-name it)) "</td>"
                    "<td>" (esc (:jurisdiction it)) "</td>"
                    "<td>" (esc (:provenance-gap-years it)) "</td>"
                    "<td>" (bool-cell (:incident-flag-resolved? it)) "</td>"
                    "<td>" (bool-cell (:loan-finalized? it)) "</td>"
                    "<td>" (bool-cell (:deaccessioned? it)) "</td>"
                    "<td>" (if-let [n (:loan-number it)] (str "<code>" (esc n) "</code>") "<span class=\"muted\">&mdash;</span>") "</td>"
                    "<td>" (if-let [n (:deaccession-number it)] (str "<code>" (esc n) "</code>") "<span class=\"muted\">&mdash;</span>") "</td>"
                    "<td>" (status-cell db (:id it)) "</td></tr>")))
   "\n</tbody></table>"))

(defn- committed-table
  [db]
  (let [rows (filter #(= :committed (:t %)) (store/ledger db))]
    (str
     "<table><thead><tr>"
     "<th>op</th><th>subject</th><th>basis</th><th>summary</th>"
     "</tr></thead><tbody>\n"
     (str/join "\n"
               (for [f rows]
                 (str "<tr><td><code>" (esc (:op f)) "</code></td>"
                      "<td><code>" (esc (:subject f)) "</code></td>"
                      "<td>" (esc (str/join "; " (:basis f))) "</td>"
                      "<td>" (esc (:summary f)) "</td></tr>")))
     "\n</tbody></table>")))

(def ^:private op-stake
  "How each write op is proposed by museum.curatoropsllm -- literal
  transcription of the :stake each proposal fn sets, not invented."
  {:item/intake          nil
   :jurisdiction/assess  nil
   :incident/screen      nil
   :item/loan            :actuation/loan-item
   :item/deaccession     :actuation/deaccession-item})

(defn- action-gate-table
  []
  (let [{:keys [writes auto]} (get phase/phases phase/default-phase)]
    (str
     "<table><thead><tr>"
     "<th>op</th><th>phase-3 write allowed?</th><th>phase-3 auto-commit?</th>"
     "<th>always human (high-stakes)?</th>"
     "</tr></thead><tbody>\n"
     (str/join "\n"
               (for [op-kw (sort-by str phase/write-ops)
                     :let [stake (get op-stake op-kw)]]
                 (str "<tr><td><code>" (esc op-kw) "</code></td>"
                      "<td>" (bool-cell (contains? writes op-kw)) "</td>"
                      "<td>" (bool-cell (contains? auto op-kw)) "</td>"
                      "<td>" (bool-cell (contains? governor/high-stakes stake)) "</td></tr>")))
     "\n</tbody></table>")))

(defn- ledger-table
  [db]
  (str
   "<table><thead><tr>"
   "<th>#</th><th>t</th><th>op</th><th>subject</th><th>disposition</th><th>rule / basis</th>"
   "</tr></thead><tbody>\n"
   (str/join "\n"
             (map-indexed
              (fn [i f]
                (str "<tr><td>" (inc i) "</td>"
                     "<td><code>" (esc (:t f)) "</code></td>"
                     "<td><code>" (esc (:op f)) "</code></td>"
                     "<td><code>" (esc (:subject f)) "</code></td>"
                     "<td>" (if (= :hold (:disposition f))
                              "<span class=\"critical\">hold</span>"
                              "<span class=\"ok\">commit</span>")
                     "</td>"
                     "<td>" (if (seq (:basis f))
                              (esc (str/join ", " (:basis f)))
                              "<span class=\"muted\">&mdash;</span>")
                     "</td></tr>"))
              (store/ledger db)))
   "\n</tbody></table>"))

(def ^:private style
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render
  [db]
  (str
   "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
   "<title>cloud-itonami-isic-9102 &middot; museum.render-html</title>"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
   "<style>" style "</style></head><body>"
   "<header class=\"bar\"><h1>cloud-itonami-isic-9102 &middot; museum operator console</h1>"
   "<span class=\"badge\">ISIC 9102 &middot; museum activities &middot; generated by museum.render-html</span></header>\n"
   "<main>\n"

   "<section class=\"card\"><h2>Collection items</h2>\n"
   "<p class=\"muted\">Real seeded items from <code>museum.store/demo-data</code>, "
   "after driving the real actor graph (<code>museum.operation/build</code> &rarr; "
   "<code>museum.governor/check</code> &rarr; <code>museum.store</code>) through the scenario below.</p>\n"
   (items-table db)
   "\n</section>\n"

   "<section class=\"card\"><h2>Committed records (this run)</h2>\n"
   "<p class=\"muted\">Every <code>:t :committed</code> fact actually appended to the audit ledger "
   "by the <code>:commit</code> node during this run.</p>\n"
   (committed-table db)
   "\n</section>\n"

   "<section class=\"card\"><h2>Action gate (museum.governor / museum.phase, phase 3)</h2>\n"
   "<p class=\"muted\">This actor's own op contract, read directly off "
   "<code>museum.phase/phases</code> and <code>museum.governor/high-stakes</code> -- "
   "<code>:item/loan</code>/<code>:item/deaccession</code> are deliberately absent from every "
   "phase's <code>:auto</code> set (a permanent structural fact, not a rollout milestone still to come) "
   "and are always human-gated via the high-stakes actuation check, independently of the phase gate.</p>\n"
   (action-gate-table)
   "\n</section>\n"

   "<section class=\"card\"><h2>Audit ledger (this run)</h2>\n"
   "<p class=\"muted\">The full append-only <code>museum.store/ledger</code> after this run, in order. "
   "Every HARD hold below (<code>:no-spec-basis</code>, <code>:provenance-gap-exceeds-threshold</code>, "
   "<code>:incident-flag-unresolved</code>, <code>:already-loaned</code>, <code>:already-deaccessioned</code>) "
   "is un-overridable by a human approver -- see <code>museum.governor</code>.</p>\n"
   (ledger-table db)
   "</section>"

   "</main></body></html>"))

(defn -main
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
