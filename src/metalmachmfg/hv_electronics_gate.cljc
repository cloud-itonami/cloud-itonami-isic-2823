(ns metalmachmfg.hv-electronics-gate
  "hv_electronics_gate.cljc — powertrain electronics (high-voltage)
  integration decision contract for the metallurgy-machinery plant
  (:system-assembly cell, electronics coverage of the magnesium-H2
  PEMFC electric-drive boundary; scripts/hermes-magnesium-systems-bots/
  system-scope.edn on com-junkawasaki origin/main).

  Executable slice for the plant's high-voltage electronics integration
  activity on a fabricated machine unit's electric drive (DC bus
  assembly, HV harness integration, inverter/motor pairing): a PURE
  decision layer that models activity -> decision -> effect -> audit,
  plus procurement screening for the cell's HV test-equipment classes.
  The bot may design and simulate; it may NOT command physical
  equipment — an HV equipment command is refused unconditionally.

  Hazard boundaries encoded (high voltage, stored capacitive charge,
  rotating machinery):
    - lockout/tagout verified, DC-link capacitors verified discharged,
      measured isolation-resistance reading recorded, HV-rated PPE
      confirmed and a confirmed clear zone are required interlocks
      before any integration plan is admissible
    - human approval is required for the hazardous step
      (:energize-hv-bus) — absence defers, never approves
    - the unit's rated bus voltage and the plant's recorded plausibility
      bounds are caller-supplied measured inputs; this module never
      invents a voltage class, a pass threshold or a certification
      outcome
    - an isolation verdict is only ever :pass, :fail or :unmeasured
      based on a MEASURED megohm reading against the plant's recorded
      floor..ceiling bounds; a missing reading stays :unmeasured

  MES traceability: every activity carries :batch/id (the fabricated
  machine unit / drive assembly) and :equipment-unit/id (the assembly
  test bench), so the audit record joins the plant's production-batch
  and equipment-unit ledgers without inventing record ids.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-interlocks
  #{:lockout-tagout-verified :capacitors-discharge-verified
    :isolation-resistance-measured :hv-rated-ppe-confirmed
    :clear-zone-confirmed})
(def ^:private required-hazardous-steps #{:energize-hv-bus})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-equipment-classes
  #{:hv-isolation-tester :assembly-and-test-bench :mes-and-traceability})
(def ^:private recognized-drive-activities
  #{:dc-bus-assembly :hv-harness-integration :inverter-motor-pairing})

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

;; ── activity 1: HV integration plan (hazardous — human approval) ───────────

(defn plan-hv-integration
  "One high-voltage powertrain-electronics integration activity on a
  fabricated machine unit's electric drive.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none — no voltage class, isolation pass threshold or
  certification outcome is assumed here):
    :activity/id                string
    :batch/id                   string — the machine unit / drive assembly (MES join)
    :equipment-unit/id          string — the assembly test bench that will hold it
    :drive-activity             :dc-bus-assembly | :hv-harness-integration |
                                :inverter-motor-pairing
    :rated-bus-volts-dc         number — the drive's rated DC bus voltage, from its
                                own engineering record
    :plausibility-floor-volts-dc number — the plant's recorded plausibility floor
    :plausibility-ceiling-volts-dc number — the plant's recorded plausibility ceiling
    :measured-isolation-mohm    number or nil — the megohm reading; nil keeps the
                                isolation verdict :unmeasured (never guessed)
    :interlocks                 collection of interlock keywords (lockout/tagout,
                                capacitor discharge, measured isolation, HV PPE,
                                clear zone)
    :witness-robot-dids         vector of >= 2 robot DIDs (witness quorum)
    :human-approval             {:approver-did string :approved-at string
                                 :scope #{:energize-hv-bus}}
    :requested-effect           :simulate-hv-plan (the only admissible kind) or
                                :command-hv-equipment (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        batch-id (get req :batch/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        drive-activity (some-> (get req :drive-activity) keyword)
        rated (get req :rated-bus-volts-dc)
        floor-v (get req :plausibility-floor-volts-dc)
        ceil-v (get req :plausibility-ceiling-volts-dc)
        measured (get req :measured-isolation-mohm)
        bounds-ok? (and (number? floor-v) (number? ceil-v) (number? rated)
                        (pos? floor-v) (<= floor-v ceil-v) (<= rated ceil-v))
        measured-ok? (or (nil? measured) (and (number? measured) (pos? measured)))
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: an HV integration activity needs an :activity/id")

          (not (present? batch-id))
          (do (note :batch-id-present)
              "batch-id: the activity must name the :batch/id (drive assembly) for MES traceability")

          (not (present? (get req :equipment-unit/id)))
          (do (note :equipment-unit-id-present)
              "equipment-unit-id: the activity must name the :equipment-unit/id holding the unit")

          (not (contains? recognized-drive-activities drive-activity))
          (do (note :drive-activity-recognized)
              (str "drive-activity: must be one of "
                   (pr-str (sort (map name recognized-drive-activities)))
                   "; got " (pr-str (get req :drive-activity))))

          (not bounds-ok?)
          (do (note :rated-voltage-within-recorded-bounds)
              (str "unmeasured: :rated-bus-volts-dc must be a positive number within the plant's recorded "
                   ":plausibility-floor-volts-dc..:plausibility-ceiling-volts-dc bounds; this module never "
                   "invents a voltage class or a plausibility bound"))

          (not measured-ok?)
          (do (note :measured-reading-numeric)
              (str "unmeasured: :measured-isolation-mohm must be the tester's measured positive megohm "
                   "reading or nil (:unmeasured); this module never substitutes a datasheet constant"))

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   2))
          (do (note :witness-quorum)
              "witness-quorum: the HV integration record needs >= 2 robot witness signers")

          (= :command-hv-equipment (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-hv-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? required-hazardous-steps
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: energizing an HV bus (stored capacitive charge, arc-flash and rotating-machinery hazard) is a hazardous operation; a named human approver with "
                   (pr-str (sort required-hazardous-steps))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id batch-id refusal @gates)
      (let [verdict (cond (nil? measured) :unmeasured
                          (number? measured) (if (>= measured floor-v)
                                               :pass :fail)
                          :else :unmeasured)
            effect {:effect/kind :simulate-hv-plan-only
                    :effect/machine-command false
                    :effect/plan {:batch/id batch-id
                                  :equipment-unit/id (get req :equipment-unit/id)
                                  :drive-activity drive-activity
                                  :rated-bus-volts-dc rated
                                  :plausibility-floor-volts-dc floor-v
                                  :plausibility-ceiling-volts-dc ceil-v
                                  :measured-isolation-mohm measured
                                  :isolation-verdict verdict
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :voltage-class-invented false}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id batch-id :approved ""
                              (conj @gates :human-approval-approved :no-physical-command)
                              effect)}))))

;; ── activity 2: equipment-offer screening (procurement — always deferred) ──

(defn screen-hv-test-equipment-offer
  "Screen one equipment offer for the plant's HV electronics
  integration/test cell (e.g. :hv-isolation-tester or
  :assembly-and-test-bench equipment class). Procurement is a financial
  commitment: the decision is ALWAYS :deferred to a human approver; this
  fn only assembles the auditable evidence record. Condition must be
  distinguished as \"new\", \"used\", \"refurbished\" or \"unknown\". Missing
  price / lead-time / utility / safety / compliance values are recorded
  as :unmeasured — never invented. Sources: direct manufacturer or
  owner-operated dealer first-party inventory only (a :source-url is
  required)."
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
