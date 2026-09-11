(ns metalmachmfg.hv-electronics-gate-test
  "Focused tests for the powertrain HV electronics integration decision
  contract (activity -> decision -> effect -> audit).
  Pure; deterministic; stdlib only."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [metalmachmfg.hv-electronics-gate :as hv]))

(def ^:private valid-req
  {:activity/id "act-hv-0001"
   :batch/id "batch-2026-0201"
   :equipment-unit/id "bench-hv-001"
   :drive-activity :inverter-motor-pairing
   :rated-bus-volts-dc 400
   :plausibility-floor-volts-dc 48
   :plausibility-ceiling-volts-dc 800
   :measured-isolation-mohm 120.5
   :interlocks [:lockout-tagout-verified :capacitors-discharge-verified
                :isolation-resistance-measured :hv-rated-ppe-confirmed
                :clear-zone-confirmed]
   :witness-robot-dids ["did:web:etzhayyim.com:itonami:otete"
                        "did:web:etzhayyim.com:itonami:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:energize-hv-bus}}
   :requested-effect :simulate-hv-plan})

(def ^:private valid-offer
  {:activity/id "act-hv-eq-0001"
   :equipment-class :hv-isolation-tester
   :manufacturer "Example HV Test Instruments"
   :model "HIT-5k"
   :condition "used"
   :seller "Example owner-operated dealer"
   :source-url "https://example-hvtest.example/inventory/hit-5k"
   :observed-at "2026-09-01T00:00:00Z"})

;; ── plan-hv-integration ─────────────────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (hv/plan-hv-integration valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-hv-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:effect :effect/plan :voltage-class-invented])))
    (is (= :pass (get-in r [:effect :effect/plan :isolation-verdict])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= "batch-2026-0201" (:audit/batch-id (:audit r))))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-missing-measured-reading-stays-unmeasured-not-fail
  (let [r (hv/plan-hv-integration (assoc valid-req :measured-isolation-mohm nil))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :isolation-verdict])))))

(deftest test-below-floor-verdict-is-fail-not-refused
  (let [r (hv/plan-hv-integration (assoc valid-req :measured-isolation-mohm 10.0))]
    (is (= :approved (:decision r)))
    (is (= :fail (get-in r [:effect :effect/plan :isolation-verdict])))))

(deftest test-hv-equipment-command-refused-unconditionally
  (let [r (hv/plan-hv-integration (assoc valid-req :requested-effect :command-hv-equipment))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-missing-batch-id-refused
  (let [r (hv/plan-hv-integration (dissoc valid-req :batch/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "MES traceability"))))

(deftest test-missing-equipment-unit-refused
  (let [r (hv/plan-hv-integration (dissoc valid-req :equipment-unit/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "equipment-unit"))))

(deftest test-unknown-drive-activity-refused
  (let [r (hv/plan-hv-integration (assoc valid-req :drive-activity :firmware-flash))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "drive-activity"))))

(deftest test-missing-rated-voltage-refused-not-invented
  (let [r (hv/plan-hv-integration (dissoc valid-req :rated-bus-volts-dc))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never invents"))))

(deftest test-rated-voltage-outside-recorded-bounds-refused
  (let [r (hv/plan-hv-integration (assoc valid-req :plausibility-ceiling-volts-dc 300))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))))

(deftest test-negative-measured-reading-refused
  (let [r (hv/plan-hv-integration (assoc valid-req :measured-isolation-mohm -5))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))))

(deftest test-missing-capacitor-discharge-interlock-refused
  (let [r (hv/plan-hv-integration (update valid-req :interlocks
                                          #(remove #{:capacitors-discharge-verified} %)))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "interlocks incomplete"))))

(deftest test-missing-human-approval-refused-not-deferred-to-silence
  (let [r (hv/plan-hv-integration (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-approval-without-energize-scope-refused
  (let [r (hv/plan-hv-integration (assoc-in valid-req [:human-approval :scope] #{:dc-bus-assembly}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-single-witness-refused
  (let [r (hv/plan-hv-integration (assoc valid-req :witness-robot-dids
                                         ["did:web:etzhayyim.com:itonami:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "witness-quorum"))))

;; ── screen-hv-test-equipment-offer ──────────────────────────────────────────

(deftest test-offer-screening-defers-with-evidence
  (let [r (hv/screen-hv-test-equipment-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-required))
    (is (= "used" (get-in r [:effect :effect/screening :condition])))))

(deftest test-offer-missing-price-recorded-unmeasured
  (let [r (hv/screen-hv-test-equipment-offer valid-offer)]
    (is (= [:price :currency :lead-time :utility :safety :compliance]
           (get-in r [:effect :effect/screening :unmeasured-fields])))))

(deftest test-offer-unknown-condition-refused
  (let [r (hv/screen-hv-test-equipment-offer (assoc valid-offer :condition "looks-fine"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-offer-unknown-equipment-class-refused
  (let [r (hv/screen-hv-test-equipment-offer (assoc valid-offer :equipment-class :roadster))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "equipment-class"))))

(deftest test-offer-missing-source-url-refused
  (let [r (hv/screen-hv-test-equipment-offer (dissoc valid-offer :source-url))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "source-url"))))
