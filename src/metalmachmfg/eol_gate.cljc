(ns metalmachmfg.eol-gate
  "eol_gate.cljc — system assembly-and-EOL decision contract for the
  metallurgy-machinery plant (:system-assembly-and-eol cell applied to
  the machinery this plant fabricates; scripts/hermes-magnesium-systems-bots/
  system-scope.edn on com-junkawasaki origin/main).

  First executable slice for the plant's end-of-line (EOL) proof-load
  gate on a fabricated machine unit (rolling-mill stand, die-casting
  machine, extrusion press, ...): a PURE decision layer that models
  activity -> decision -> effect -> audit for proof-load EOL testing,
  plus procurement screening for the cell's assembly/test-bench
  equipment classes. The bot may design and simulate; it may NOT
  command physical equipment — a test-bench command is refused
  unconditionally.

  Hazard boundaries encoded (hydraulic force, rotating machinery):
    - rotating-machinery guard verified, hydraulic relief verified,
      rated restrain tooling and a confirmed clear zone are required
      interlocks before any proof-load plan is admissible
    - human approval is required for the hazardous step
      (:apply-proof-load) — absence defers, never approves
    - the machine's rated proof load and the plant's recorded
      plausibility bounds are caller-supplied measured inputs; this
      module never invents a capacity, a cycle time, a pass threshold
      or a certification outcome
    - an EOL verdict is only ever :pass, :fail or :unmeasured based on
      a MEASURED reading; a missing reading stays :unmeasured

  MES traceability: every activity carries :batch/id (the fabricated
  machine unit) and :equipment-unit/id (the test bench), so the audit
  record joins the plant's production-batch and equipment-unit ledgers
  without inventing record ids.

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-interlocks
  #{:rotating-machinery-guard-verified :hydraulic-relief-verified
    :restrain-tooling-rated :clear-zone-confirmed})
(def ^:private required-hazardous-steps #{:apply-proof-load})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-equipment-classes
  #{:proof-load-test-bench :assembly-and-test-bench :mes-and-traceability})
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

;; ── activity 1: proof-load EOL test plan (hazardous — human approval) ──────

(defn plan-proof-load-eol-test
  "One end-of-line proof-load activity on a fabricated machine unit.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none — no capacity, cycle time, pass threshold or
  certification outcome is assumed here):
    :activity/id            string
    :batch/id               string — the fabricated machine unit (MES join)
    :equipment-unit/id      string — the assembly/test bench that will hold it
    :machine-type           :rolling-mill-stand | :continuous-caster |
                            :die-casting-machine | :extrusion-press |
                            :forging-press | :wire-drawing-machine
    :rated-proof-load-tf    number — the machine unit's rated proof load in
                            tonnes-force, from its own engineering record
    :plausibility-floor-tf  number — the plant's recorded plausibility floor
    :plausibility-ceiling-tf number — the plant's recorded plausibility ceiling
    :measured-proof-load-tf number or nil — the bench reading; nil keeps the
                            EOL verdict :unmeasured (never guessed)
    :interlocks             collection of interlock keywords (rotating
                            machinery guard, hydraulic relief, rated restrain
                            tooling, confirmed clear zone)
    :witness-robot-dids     vector of >= 2 robot DIDs (witness quorum)
    :human-approval         {:approver-did string :approved-at string
                             :scope #{:apply-proof-load}}
    :requested-effect       :simulate-eol-plan (the only admissible kind) or
                            :command-test-bench (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        batch-id (get req :batch/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        machine-type (some-> (get req :machine-type) keyword)
        machine-types #{:rolling-mill-stand :continuous-caster :die-casting-machine
                        :extrusion-press :forging-press :wire-drawing-machine}
        rated (get req :rated-proof-load-tf)
        floor-tf (get req :plausibility-floor-tf)
        ceil-tf (get req :plausibility-ceiling-tf)
        measured (get req :measured-proof-load-tf)
        bounds-ok? (and (number? floor-tf) (number? ceil-tf) (number? rated)
                        (<= floor-tf rated ceil-tf))
        measured-ok? (or (nil? measured) (number? measured))
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a proof-load EOL activity needs an :activity/id")

          (not (present? batch-id))
          (do (note :batch-id-present)
              "batch-id: the activity must name the :batch/id (machine unit) for MES traceability")

          (not (present? (get req :equipment-unit/id)))
          (do (note :equipment-unit-id-present)
              "equipment-unit-id: the activity must name the :equipment-unit/id holding the unit")

          (not (contains? machine-types machine-type))
          (do (note :machine-type-recognized)
              (str "machine-type: must be one of " (pr-str (sort (map name machine-types)))
                   "; got " (pr-str (get req :machine-type))))

          (not bounds-ok?)
          (do (note :rated-load-within-recorded-bounds)
              (str "unmeasured: :rated-proof-load-tf must be a number within the plant's recorded "
                   ":plausibility-floor-tf..:plausibility-ceiling-tf bounds; this module never "
                   "invents a machine capacity or a plausibility bound"))

          (not measured-ok?)
          (do (note :measured-reading-numeric)
              (str "unmeasured: :measured-proof-load-tf must be the bench's measured reading or nil "
                   "(:unmeasured); this module never substitutes a datasheet constant"))

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   2))
          (do (note :witness-quorum)
              "witness-quorum: the EOL record needs >= 2 robot witness signers")

          (= :command-test-bench (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-eol-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? required-hazardous-steps
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: applying a proof load to a fabricated machine is a hazardous operation; a named human approver with "
                   (pr-str (sort required-hazardous-steps))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id batch-id refusal @gates)
      (let [verdict (cond (nil? measured) :unmeasured
                          (number? measured) (if (and (>= measured floor-tf)
                                                      (<= measured ceil-tf))
                                               :pass :fail)
                          :else :unmeasured)
            effect {:effect/kind :simulate-eol-plan-only
                    :effect/machine-command false
                    :effect/plan {:batch/id batch-id
                                  :equipment-unit/id (get req :equipment-unit/id)
                                  :machine-type machine-type
                                  :rated-proof-load-tf rated
                                  :plausibility-floor-tf floor-tf
                                  :plausibility-ceiling-tf ceil-tf
                                  :measured-proof-load-tf measured
                                  :eol-verdict verdict
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :capacity-invented false}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id batch-id :approved ""
                              (conj @gates :human-approval-approved :no-physical-command)
                              effect)}))))

;; ── activity 2: equipment-offer screening (procurement — always deferred) ──

(defn screen-equipment-offer
  "Screen one equipment offer for the plant's EOL/assembly-test cell
  (e.g. :proof-load-test-bench or :assembly-and-test-bench equipment
  class). Procurement is a financial commitment: the decision is ALWAYS
  :deferred to a human approver; this fn only assembles the auditable
  evidence record. Condition must be distinguished as 'new', 'used',
  'refurbished' or 'unknown'. Missing price / lead-time / utility /
  safety / compliance values are recorded as :unmeasured — never
  invented. Sources: direct manufacturer or owner-operated dealer
  first-party inventory only (a :source-url is required)."
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
