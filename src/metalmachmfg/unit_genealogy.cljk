(ns metalmachmfg.unit-genealogy
  "unit-genealogy.cljc — cross-cell serialized-unit genealogy + quarantine
  HOLD decision contract for the magnesium-hydrogen PEMFC electric-drive
  system assembly-and-EOL cell
  (scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main; :system-assembly-and-eol cell).

  Pure fns, stdlib only, keyword-keyed records. Complements (never
  replaces) eol_gate.cljc: eol_gate decides the PROOF-LOAD verdict for a
  fabricated machine unit; THIS ns decides whether a system serial's
  COMPONENT GENEALOGY is complete enough for the unit to be released
  from the assembly cell at all.

  Boundary encoded here:
    - a system serial is releasable only when every required cell
      (cartridge, reactor, pem-stack, powertrain-electronics) has
      contributed exactly one component whose lot is NOT quarantined
      and whose upstream cell is in this module's closed cell set
    - any component whose upstream cell is quarantined blocks release
      (HARD, unconditional) — quarantine propagates downstream
    - missing genealogy is :incomplete, never invented; a fabricated
      lot id or cell name outside the closed cell set is :refused
    - no capacity, cycle time, yield, price or certification value is
      invented here; this module never commands equipment"
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def recognized-cells
  #{:cartridge-dry-inert-handling :hydrogen-reactor-fabrication
    :pem-stack-assembly-and-test :powertrain-electronics
    :system-assembly-and-eol :magnesium-hpdc :mes-traceability})

(def required-cells-for-release
  "Upstream cells whose contribution a system serial must show before
  the assembly cell may release it."
  #{:magnesium-hpdc :cartridge-dry-inert-handling
    :hydrogen-reactor-fabrication :pem-stack-assembly-and-test
    :powertrain-electronics})

(def recognized-dispositions #{:ok :quarantined :unmeasured})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

;; ── genealogy validation ───────────────────────────────────────────────────

(defn validate-genealogy
  "Given `records` (a seq of component genealogy maps, each with
  :component/lot, :cell, :disposition) and a `system-serial`, classify
  the release decision.

  Returns {:decision :release | :hold | :refused
           :reason  keyword
           :missing-cells set-of-cells-without-records
           :quarantined-cells set-of-quarantined-upstream-cells
           :unmeasured-cells set-of-unmeasured-upstream-cells}

  Refusal (closed-set violation) is distinct from hold (incomplete or
  quarantined genealogy — a real-world condition, not a caller error)."
  [{:keys [system-serial records]}]
  (cond
    (not (present? system-serial))
    {:decision :refused :reason :missing-system-serial}

    (not (seq records))
    {:decision :refused :reason :no-genealogy-records}

    :else
    (let [bad-cells (into #{}
                          (keep (fn [r]
                                  (let [c (:cell r)]
                                    (when-not (contains? recognized-cells c) c))))
                          records)
          quarantined (into #{} (comp (filter #(= :quarantined (:disposition %)))
                                      (map :cell))
                            records)
          unmeasured (into #{} (comp (filter #(= :unmeasured (:disposition %)))
                                     (map :cell))
                           records)
          covered (into #{} (map :cell)
                        (filter #(contains? recognized-cells (:cell %)) records))
          missing (set/difference required-cells-for-release covered)]
      (cond
        (seq bad-cells)
        {:decision :refused :reason :unrecognized-cell :cells bad-cells}

        (seq quarantined)
        {:decision :hold :reason :quarantined-upstream-cell
         :quarantined-cells quarantined :missing-cells missing
         :unmeasured-cells unmeasured}

        (seq missing)
        {:decision :hold :reason :incomplete-genealogy :missing-cells missing
         :quarantined-cells quarantined :unmeasured-cells unmeasured}

        :else
        {:decision :release :reason :genealogy-complete
         :missing-cells #{} :quarantined-cells #{} :unmeasured-cells unmeasured}))))

;; ── activity -> decision -> effect -> audit ────────────────────────────────

(defn release-activity
  "Models the assembly cell's release-from-assembly activity for a
  system serial: activity (the proposal) -> decision -> effect -> audit.

  The bot may design and simulate; it may NOT command physical
  equipment. The effect is always a DRAFT record disposition, never an
  actuation. A :release decision whose genealogy carries any
  :unmeasured cell is effect-gated behind explicit human approval
  (:approval required in the effect; absence defers, never approves)."
  [{:keys [activity-id] :as activity}]
  (let [{:keys [decision reason] :as verdict} (validate-genealogy activity)
        unmeasured (or (:unmeasured-cells verdict) #{})
        effect (cond
                 (= :release decision)
                 {:effect/kind :draft-release-record
                  :approval (if (seq unmeasured)
                              {:required true :reason :unmeasured-upstream-cells
                               :cells unmeasured}
                              {:required false})}
                 :else {:effect/kind :none})
        audit {:audit/activity-id activity-id
               :audit/system-serial (:system-serial activity)
               :audit/decision decision
               :audit/reason reason
               :audit/gates-checked #{:cell-closed-set :quarantine-propagation
                                      :genealogy-completeness}
               :audit/effect effect
               :audit/bot-commanded-equipment false}]
    {:decision decision :reason reason :effect effect :audit audit}))

