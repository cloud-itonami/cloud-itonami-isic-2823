(ns metalmachmfg.powertrain-test-gate-test
  "Focused tests for the powertrain integration test-bench decision
  contract (activity -> decision -> effect -> audit).
  Pure; deterministic; stdlib only."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [metalmachmfg.powertrain-test-gate :as pt]))

(def ^:private valid-req
  {:activity/id "act-pt-0001"
   :batch/id "batch-pt-2026-0903"
   :equipment-unit/id "pt-bench-001"
   :bench-activity :end-of-line-validation
   :powertrain-unit-ids ["ptu-2026-0903-001" "ptu-2026-0903-002"]
   :interlocks [:hv-discharge-verified :rotating-guard-verified
                :hot-surface-clearance-verified :clear-zone-confirmed]
   :hydrogen-leak-ppm 12.0
   :hydrogen-leak-alarm-ppm 500
   :measured-thrust-n 118.0
   :recorded-thrust-floor-n 110
   :recorded-thrust-ceiling-n 125
   :measured-speed-rpm 3400
   :recorded-speed-floor-rpm 3200
   :recorded-speed-ceiling-rpm 3600
   :measured-winding-temp-c 74.0
   :recorded-winding-temp-floor-c 20
   :recorded-winding-temp-ceiling-c 90
   :measured-vibration-mm-s 1.4
   :recorded-vibration-floor-mm-s 0.1
   :recorded-vibration-ceiling-mm-s 2.5
   :witness-robot-dids ["did:web:etzhayyim.com:itonami:otete"
                        "did:web:etzhayyim.com:itonami:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-03T00:00:00Z"
                    :scope #{:energize-powertrain-test-bench}}
   :requested-effect :simulate-bench-plan})

(def ^:private valid-offer
  {:activity/id "act-pt-eq-0001"
   :equipment-class :motor-thrust-vibration-and-thermal-test
   :manufacturer "Example Test Systems"
   :model "Dyno-8"
   :condition "used"
   :seller "Example owner-operated dealer"
   :source-url "https://example-dyno.example/inventory/dyno-8"
   :observed-at "2026-09-03T00:00:00Z"})

;; ── plan-powertrain-bench-run ───────────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (pt/plan-powertrain-bench-run valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-bench-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:effect :effect/plan :rating-or-threshold-invented])))
    (is (= :pass (get-in r [:effect :effect/plan :channel-verdicts :thrust-n])))
    (is (= :pass (get-in r [:effect :effect/plan :hydrogen-area-verdict])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= "batch-pt-2026-0903" (:audit/batch-id (:audit r))))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-failing-channel-is-fail-not-invented
  (let [r (pt/plan-powertrain-bench-run (assoc valid-req :measured-thrust-n 130.0))]
    (is (= :approved (:decision r)))
    (is (= :fail (get-in r [:effect :effect/plan :channel-verdicts :thrust-n])))))

(deftest test-missing-reading-stays-unmeasured
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :measured-winding-temp-c))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :channel-verdicts :winding-temp-c])))))

(deftest test-missing-hydrogen-reading-stays-unmeasured
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :hydrogen-leak-ppm))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :hydrogen-area-verdict])))))

(deftest test-hydrogen-above-alarm-is-fail
  (let [r (pt/plan-powertrain-bench-run (assoc valid-req :hydrogen-leak-ppm 900.0))]
    (is (= :approved (:decision r)))
    (is (= :fail (get-in r [:effect :effect/plan :hydrogen-area-verdict])))))

(deftest test-refused-without-units-traced
  (let [r (pt/plan-powertrain-bench-run (assoc valid-req :powertrain-unit-ids []))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "powertrain-units"))
    (is (contains? (set (get-in r [:audit :audit/gates-checked])) :units-traced))))

(deftest test-refused-with-missing-recorded-bounds
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :recorded-vibration-ceiling-mm-s))]
    (is (= :refused (:decision r)))
    (is (contains? (set (get-in r [:audit :audit/gates-checked])) :recorded-bounds-present))))

(deftest test-refused-with-missing-alarm-threshold
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :hydrogen-leak-alarm-ppm))]
    (is (= :refused (:decision r)))
    (is (contains? (set (get-in r [:audit :audit/gates-checked])) :alarm-threshold-recorded))))

(deftest test-refused-with-incomplete-interlocks
  (let [r (pt/plan-powertrain-bench-run (update valid-req :interlocks #(drop-last %)))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "interlocks"))))

(deftest test-refused-without-human-approval
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "human-approval"))
    (is (contains? (set (get-in r [:audit :audit/gates-checked])) :human-approval-required))))

(deftest test-approval-with-wrong-scope-refuses
  (let [bad (assoc-in valid-req [:human-approval :scope] #{:start-reflow-oven})
        r (pt/plan-powertrain-bench-run bad)]
    (is (= :refused (:decision r)))))

(deftest test-physical-command-refused-unconditionally
  (let [r (pt/plan-powertrain-bench-run
           (assoc valid-req :requested-effect :command-test-bench))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "no-physical-command"))
    (is (contains? (set (get-in r [:audit :audit/gates-checked])) :no-physical-command))))

(deftest test-unrecognized-bench-activity-refused
  (let [r (pt/plan-powertrain-bench-run (assoc valid-req :bench-activity :spin-the-motor))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "bench-activity"))))

(deftest test-missing-batch-id-refused-for-mes-join
  (let [r (pt/plan-powertrain-bench-run (dissoc valid-req :batch/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "batch-id"))))

;; ── screen-powertrain-test-equipment-offer ─────────────────────────────────

(deftest test-offer-screening-defers-to-human
  (let [r (pt/screen-powertrain-test-equipment-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (= "used" (get-in r [:effect :effect/screening :condition])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))))

(deftest test-offer-screening-records-unmeasured-fields
  (let [r (pt/screen-powertrain-test-equipment-offer valid-offer)]
    (is (= [:price :currency :lead-time :utility :safety :compliance]
           (get-in r [:effect :effect/screening :unmeasured-fields])))
    (is (empty? (get-in r [:effect :effect/screening :unmeasured])))))

(deftest test-offer-screening-rejects-unknown-class
  (let [r (pt/screen-powertrain-test-equipment-offer
           (assoc valid-offer :equipment-class :hydraulic-press))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "equipment-class"))))

(deftest test-offer-screening-rejects-undistinguished-condition
  (let [r (pt/screen-powertrain-test-equipment-offer (dissoc valid-offer :condition))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "condition"))))

(deftest test-offer-screening-requires-first-party-source
  (let [r (pt/screen-powertrain-test-equipment-offer (dissoc valid-offer :source-url))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "source"))))
