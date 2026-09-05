(ns metalmachmfg.smt-test-gate
  "smt_test_gate.cljc — electronics SMT assembly-and-test decision
  contract for the metallurgy-machinery plant (:electronics-smt-and-test
  cell of the magnesium-H2 PEMFC electric-drive boundary;
  scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  Executable slice for the plant's surface-mount assembly-and-test
  activity on the electric-drive control board (solder-paste print,
  component placement, reflow, AOI inspection, in-circuit test): a PURE
  decision layer that models activity -> decision -> effect -> audit,
  plus procurement screening for the cell's equipment classes. The bot
  may design and simulate; it may NOT command physical equipment — an
  SMT equipment command is refused unconditionally.

  Hazard boundaries encoded (molten solder / high oven temperature,
  solder fume, ESD-sensitive components, powered in-circuit test):
    - ESD grounding verified, fume extraction verified, oven thermal
      interlock verified and a confirmed clear zone are required
      interlocks before any SMT plan is admissible
    - human approval is required for the hazardous step
      (:start-reflow-oven) — absence defers, never approves
    - the board's reflow profile bounds come from the caller-supplied
      solder-paste engineering record; this module never invents a
      temperature class, a pass threshold or a certification outcome
    - a reflow verdict is only ever :pass, :fail or :unmeasured based
      on a MEASURED peak-temperature reading against the recorded
      floor..ceiling bounds; a missing reading stays :unmeasured
    - component lots are traced, not invented: an activity without the
      board's :component-lot-ids is refused, so the audit record joins
      the plant's production-batch ledger without fabricating ids

  MES traceability: every activity carries :batch/id (the control
  board batch) and :equipment-unit/id (the SMT line / ICT fixture), so
  the audit record joins the plant's production-batch and
  equipment-unit ledgers without inventing record ids.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-interlocks
  #{:esd-grounding-verified :fume-extraction-verified
    :oven-thermal-interlock-verified :clear-zone-confirmed})
(def ^:private required-hazardous-steps #{:start-reflow-oven})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-equipment-classes
  #{:smt-and-power-electronics-test :mes-and-traceability})
(def ^:private recognized-smt-activities
  #{:solder-paste-print :component-placement :reflow :aoi-inspection
    :in-circuit-test})

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
  "The audit tail every decision returns: what was decided, which gates
  were checked, and the explicit no-physical-command attestation."
  [activity-id batch-id decision refusal gates-checked effect]
  {:audit/activity-id activity-id
   :audit/batch-id batch-id
   :audit/decision decision
   :audit/refusal refusal
   :audit/gates-checked gates-checked
   :audit/effect effect
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id batch-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id batch-id :refused refusal gates {:effect/kind :none})})

;; ── activity 1: SMT build plan (reflow is hazardous — human approval) ──────

(defn plan-smt-build
  "One electronics SMT assembly-and-test activity on the electric-drive
  control board batch.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none — no temperature class, reflow pass threshold
  or certification outcome is assumed here):
    :activity/id              string
    :batch/id                 string — the control board batch (MES join)
    :equipment-unit/id        string — the SMT line / ICT fixture that will hold it
    :smt-activity             :solder-paste-print | :component-placement | :reflow |
                              :aoi-inspection | :in-circuit-test
    :component-lot-ids        vector of component lot ids — traced, never invented
    :measured-peak-temp-c     number or nil — the profiler's measured reflow peak;
                              nil keeps the reflow verdict :unmeasured
    :plausibility-floor-temp-c number — recorded solder-paste profile floor
    :plausibility-ceiling-temp-c number — recorded solder-paste profile ceiling
    :interlocks               collection of interlock keywords (ESD grounding,
                              fume extraction, oven thermal interlock, clear zone)
    :witness-robot-dids       vector of >= 2 robot DIDs (witness quorum)
    :human-approval           {:approver-did string :approved-at string
                               :scope #{:start-reflow-oven}}
    :requested-effect         :simulate-smt-plan (the only admissible kind) or
                              :command-smt-equipment (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        batch-id (get req :batch/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        smt-activity (some-> (get req :smt-activity) keyword)
        floor-t (get req :plausibility-floor-temp-c)
        ceil-t (get req :plausibility-ceiling-temp-c)
        measured (get req :measured-peak-temp-c)
        lot-ids (vec (remove str/blank? (map str (get req :component-lot-ids))))
        bounds-ok? (and (number? floor-t) (number? ceil-t)
                        (pos? floor-t) (<= floor-t ceil-t))
        measured-ok? (or (nil? measured) (and (number? measured) (pos? measured)))
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: an SMT build activity needs an :activity/id")

          (not (present? batch-id))
          (do (note :batch-id-present)
              "batch-id: the activity must name the :batch/id (control board batch) for MES traceability")

          (not (present? (get req :equipment-unit/id)))
          (do (note :equipment-unit-id-present)
              "equipment-unit-id: the activity must name the :equipment-unit/id (SMT line / ICT fixture) holding the board")

          (not (contains? recognized-smt-activities smt-activity))
          (do (note :smt-activity-recognized)
              (str "smt-activity: must be one of "
                   (pr-str (sort (map name recognized-smt-activities)))
                   "; got " (pr-str (get req :smt-activity))))

          (empty? lot-ids)
          (do (note :component-lots-traced)
              "component-lots: the activity must carry the board's :component-lot-ids; this module never invents component lot ids for traceability")

          (not bounds-ok?)
          (do (note :profile-within-recorded-bounds)
              (str "unmeasured: :plausibility-floor-temp-c..:plausibility-ceiling-temp-c must be positive recorded bounds from the solder-paste engineering record; this module never invents a temperature profile"))

          (not measured-ok?)
          (do (note :measured-reading-numeric)
              (str "unmeasured: :measured-peak-temp-c must be the profiler's measured positive reading or nil (:unmeasured); this module never substitutes a datasheet constant"))

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (< (count (remove str/blank? (map str (get req :witness-robot-dids))))
             2)
          (do (note :witness-quorum)
              "witness-quorum: the SMT build record needs >= 2 robot witness signers")

          (= :command-smt-equipment (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-smt-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? required-hazardous-steps
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: starting a reflow oven (molten solder, high-temperature and fume hazard) is a hazardous operation; a named human approver with "
                   (pr-str (sort required-hazardous-steps))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id batch-id refusal @gates)
      (let [verdict (cond (nil? measured) :unmeasured
                          (number? measured) (if (and (>= measured floor-t)
                                                      (<= measured ceil-t))
                                               :pass :fail)
                          :else :unmeasured)
            effect {:effect/kind :simulate-smt-plan-only
                    :effect/machine-command false
                    :effect/plan {:batch/id batch-id
                                  :equipment-unit/id (get req :equipment-unit/id)
                                  :smt-activity smt-activity
                                  :component-lot-ids lot-ids
                                  :measured-peak-temp-c measured
                                  :plausibility-floor-temp-c floor-t
                                  :plausibility-ceiling-temp-c ceil-t
                                  :reflow-verdict verdict
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :temperature-profile-invented false}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id batch-id :approved ""
                              (conj @gates :human-approval-approved :no-physical-command)
                              effect)}))))

;; ── activity 2: equipment-offer screening (procurement — always deferred) ──

(defn screen-smt-equipment-offer
  "Screen one equipment offer for the plant's electronics SMT
  assembly-and-test cell (e.g. :smt-and-power-electronics-test or
  :mes-and-traceability equipment class). Procurement is a financial
  commitment: the decision is ALWAYS :deferred to a human approver;
  this fn only assembles the auditable evidence record. Condition must
  be distinguished as \"new\", \"used\", \"refurbished\" or \"unknown\".
  Missing price / lead-time / utility / safety / compliance values are
  recorded as :unmeasured — never invented. Sources: direct
  manufacturer or owner-operated dealer first-party inventory only (a
  :source-url is required)."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [])
        condition (get offer :condition)
        source-url (get offer :source-url)
        equipment-class (some-> (get offer :equipment-class) keyword)]
    (swap! gates conj :condition-distinguished :source-recorded)
    (cond
      (not (present? activity-id))
      (refuse activity-id "" "activity-id: an equipment screening needs an :activity/id" @gates)

      (not (contains? recognized-equipment-classes equipment-class))
      (refuse activity-id ""
              (str "equipment-class: must be one of "
                   (pr-str (sort (map name recognized-equipment-classes)))
                   "; got " (pr-str (get offer :equipment-class)))
              @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id ""
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions)) "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "" "source: an offer needs a first-party :source-url (manufacturer or owner-operated dealer)" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:manufacturer (get offer :manufacturer)
                     :model (get offer :model)
                     :equipment-class equipment-class
                     :condition condition
                     :seller (get offer :seller)
                     :source-url source-url
                     :observed-at (get offer :observed-at)
                     :unmeasured (dissoc (select-keys offer [:price :currency :lead-time
                                                             :utility :safety :compliance])
                                         nil)
                     :unmeasured-fields [:price :currency :lead-time :utility :safety :compliance]}}]
        {:decision :deferred
         :effect effect
         :audit (audit-record activity-id "" :deferred
                              "procurement is a human decision; screening evidence assembled only"
                              (conj @gates :human-approval-required :no-financial-commitment)
                              effect)}))))
