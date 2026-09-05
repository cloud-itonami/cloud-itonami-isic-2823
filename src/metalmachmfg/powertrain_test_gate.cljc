(ns metalmachmfg.powertrain-test-gate
  "powertrain_test_gate.cljc — powertrain integration test bench
  decision contract for the metallurgy-machinery plant
  (:system-assembly-and-eol cell, powertrain-integration retention of
  the magnesium-H2 PEMFC electric-drive boundary;
  scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  Executable slice for the plant's electric powertrain integration and
  test-bench activity on the magnesium-H2 PEMFC electric-drive system
  (motor + ESC + DC bus + buffer battery + DC-DC bench coupling,
  thrust/dynamometer measurement, thermal soak, vibration sweep,
  end-of-line validation): a PURE decision layer that models
  activity -> decision -> effect -> audit, plus procurement screening
  for the cell's equipment classes. The bot may design and simulate;
  it may NOT command physical equipment — a bench command is refused
  unconditionally.

  Hazard boundaries encoded (high-voltage DC bus, rotating machinery,
  hot motor surfaces, hydrogen-adjacent bench area):
    - HV discharge verified, rotating-machine guarding verified,
      hot-surface thermal clearance verified and a confirmed clear
      zone are required interlocks before any bench plan is admissible
    - hydrogen leak-check evidence for the bench area is required
      because the system's cartridge/reactor neighbors the bench; a
      missing reading stays :unmeasured and defers, never approves
    - human approval is required for the hazardous step
      (:energize-powertrain-test-bench) — absence defers, never
      approves
    - performance verdicts (:pass / :fail / :unmeasured) come only
      from MEASURED readings against caller-supplied recorded bounds
      (thrust, speed, winding temperature, vibration); this module
      never invents a rating, threshold or certification outcome
    - serialized units are traced, not invented: an activity without
      the :powertrain-unit-ids is refused, so the audit record joins
      the plant's production-batch ledger without fabricating ids

  MES traceability: every activity carries :batch/id (the powertrain
  integration batch) and :equipment-unit/id (the test bench), so the
  audit record joins the plant's production-batch and equipment-unit
  ledgers without inventing record ids.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-interlocks
  #{:hv-discharge-verified :rotating-guard-verified
    :hot-surface-clearance-verified :clear-zone-confirmed})
(def ^:private required-hazardous-steps #{:energize-powertrain-test-bench})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-equipment-classes
  #{:motor-thrust-vibration-and-thermal-test :mes-and-traceability})
(def ^:private recognized-bench-activities
  #{:bench-coupling :thrust-measurement :thermal-soak :vibration-sweep
    :end-of-line-validation})

;; measured channels: reading key -> bound keys on the recorded envelope
(def ^:private measured-channels
  {:thrust-n        {:reading :measured-thrust-n
                     :floor   :recorded-thrust-floor-n
                     :ceiling :recorded-thrust-ceiling-n}
   :speed-rpm       {:reading :measured-speed-rpm
                     :floor   :recorded-speed-floor-rpm
                     :ceiling :recorded-speed-ceiling-rpm}
   :winding-temp-c  {:reading :measured-winding-temp-c
                     :floor   :recorded-winding-temp-floor-c
                     :ceiling :recorded-winding-temp-ceiling-c}
   :vibration-mm-s  {:reading :measured-vibration-mm-s
                     :floor   :recorded-vibration-floor-mm-s
                     :ceiling :recorded-vibration-ceiling-mm-s}})

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

(defn- channel-verdict
  "One channel's verdict from a measured reading against recorded
  bounds. A missing reading is :unmeasured — never :fail, never
  invented."
  [req {:keys [reading floor ceiling]}]
  (let [m (get req reading)
        lo (get req floor)
        hi (get req ceiling)]
    (cond
      (or (nil? m) (not (number? m))) :unmeasured
      (or (not (number? lo)) (not (number? hi))) :unmeasured
      (and (>= m lo) (<= m hi)) :pass
      :else :fail)))

(defn- verdicts
  "Measured verdicts for every channel; a channel with no reading stays
  :unmeasured. Missing bounds are recorded, not invented."
  [req]
  (into (sorted-map)
        (map (fn [[ch spec]] [ch (channel-verdict req spec)])
             measured-channels)))

(defn- all-bounds-present? [req]
  (every? (fn [{:keys [floor ceiling]}]
            (and (number? (get req floor)) (pos? (get req floor))
                 (number? (get req ceiling)) (pos? (get req ceiling))
                 (<= (get req floor) (get req ceiling))))
          (vals measured-channels)))

;; ── activity 1: bench plan (energizing is hazardous — human approval) ─────

(defn plan-powertrain-bench-run
  "One powertrain integration test-bench activity on the magnesium-H2
  PEMFC electric-drive system.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none — no thrust rating, temperature class,
  vibration limit or certification outcome is assumed here):
    :activity/id              string
    :batch/id                 string — the powertrain integration batch (MES join)
    :equipment-unit/id        string — the test bench holding the unit
    :bench-activity           :bench-coupling | :thrust-measurement |
                              :thermal-soak | :vibration-sweep |
                              :end-of-line-validation
    :powertrain-unit-ids      vector of serialized unit ids — traced, never invented
    :interlocks               collection of interlock keywords (HV discharge,
                              rotating guard, hot-surface clearance, clear zone)
    :hydrogen-leak-ppm        number or nil — measured bench-area H2 reading;
                              nil keeps the area verdict :unmeasured
    :hydrogen-leak-alarm-ppm  number — recorded alarm threshold from the bench's
                              gas-safety record (bounds for the reading)
    plus one recorded bound pair per measured channel (see measured-channels),
    e.g. :measured-thrust-n with :recorded-thrust-floor-n /
    :recorded-thrust-ceiling-n — missing readings stay :unmeasured
    :witness-robot-dids       vector of >= 2 robot DIDs (witness quorum)
    :human-approval           {:approver-did string :approved-at string
                               :scope #{:energize-powertrain-test-bench}}
    :requested-effect         :simulate-bench-plan (the only admissible kind) or
                              :command-test-bench (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        batch-id (get req :batch/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        bench-activity (some-> (get req :bench-activity) keyword)
        unit-ids (vec (remove str/blank? (map str (get req :powertrain-unit-ids))))
        leak (get req :hydrogen-leak-ppm)
        alarm (get req :hydrogen-leak-alarm-ppm)
        leak-ok? (or (nil? leak) (and (number? leak) (not (neg? leak))))
        alarm-ok? (and (number? alarm) (pos? alarm))
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a bench activity needs an :activity/id")

          (not (present? batch-id))
          (do (note :batch-id-present)
              "batch-id: the activity must name the :batch/id (powertrain integration batch) for MES traceability")

          (not (present? (get req :equipment-unit/id)))
          (do (note :equipment-unit-id-present)
              "equipment-unit-id: the activity must name the :equipment-unit/id (test bench) holding the unit")

          (not (contains? recognized-bench-activities bench-activity))
          (do (note :bench-activity-recognized)
              (str "bench-activity: must be one of "
                   (pr-str (sort (map name recognized-bench-activities)))
                   "; got " (pr-str (get req :bench-activity))))

          (empty? unit-ids)
          (do (note :units-traced)
              "powertrain-units: the activity must carry the system's :powertrain-unit-ids; this module never invents serialized unit ids for traceability")

          (not (all-bounds-present? req))
          (do (note :recorded-bounds-present)
              (str "unmeasured: one positive recorded floor..ceiling bound pair per channel "
                   (pr-str (sort (map name (keys measured-channels))))
                   " is required from the motor/system engineering record; this module never invents a rating or threshold"))

          (not alarm-ok?)
          (do (note :alarm-threshold-recorded)
              "unmeasured: :hydrogen-leak-alarm-ppm must be the bench gas-safety record's positive alarm threshold; this module never invents one")

          (not leak-ok?)
          (do (note :leak-reading-numeric)
              "unmeasured: :hydrogen-leak-ppm must be the sensor's measured non-negative reading or nil (:unmeasured); this module never substitutes a datasheet constant")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (< (count (remove str/blank? (map str (get req :witness-robot-dids))))
             2)
          (do (note :witness-quorum)
              "witness-quorum: the bench record needs >= 2 robot witness signers")

          (= :command-test-bench (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-bench-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? required-hazardous-steps
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: energizing a powertrain test bench (high-voltage DC bus, rotating machinery, hot surfaces) is a hazardous operation; a named human approver with "
                   (pr-str (sort required-hazardous-steps))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id batch-id refusal @gates)
      (let [h2-verdict (cond (nil? leak) :unmeasured
                             (and (number? leak) (< leak alarm)) :pass
                             :else :fail)
            vs (verdicts req)
            effect {:effect/kind :simulate-bench-plan-only
                    :effect/machine-command false
                    :effect/plan {:batch/id batch-id
                                  :equipment-unit/id (get req :equipment-unit/id)
                                  :bench-activity bench-activity
                                  :powertrain-unit-ids unit-ids
                                  :channel-verdicts vs
                                  :hydrogen-leak-ppm leak
                                  :hydrogen-leak-alarm-ppm alarm
                                  :hydrogen-area-verdict h2-verdict
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :rating-or-threshold-invented false}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id batch-id :approved ""
                              (conj @gates :human-approval-approved :no-physical-command)
                              effect)}))))

;; ── activity 2: equipment-offer screening (procurement — always deferred) ──

(defn screen-powertrain-test-equipment-offer
  "Screen one equipment offer for the plant's powertrain integration
  test-bench cell (e.g. :motor-thrust-vibration-and-thermal-test or
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
