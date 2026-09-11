(ns metalmachmfg.smt-test-gate-test
  "Focused tests for the electronics SMT assembly-and-test decision
  contract (activity -> decision -> effect -> audit).
  Pure; deterministic; stdlib only."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [metalmachmfg.smt-test-gate :as smt]))

(def ^:private valid-req
  {:activity/id "act-smt-0001"
   :batch/id "batch-pcb-2026-0901"
   :equipment-unit/id "smt-line-ict-001"
   :smt-activity :reflow
   :component-lot-ids ["lot-cap-2026-0812" "lot-mcu-2026-0820"]
   :measured-peak-temp-c 246.5
   :plausibility-floor-temp-c 235
   :plausibility-ceiling-temp-c 250
   :interlocks [:esd-grounding-verified :fume-extraction-verified
                :oven-thermal-interlock-verified :clear-zone-confirmed]
   :witness-robot-dids ["did:web:etzhayyim.com:itonami:otete"
                        "did:web:etzhayyim.com:itonami:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:start-reflow-oven}}
   :requested-effect :simulate-smt-plan})

(def ^:private valid-offer
  {:activity/id "act-smt-eq-0001"
   :equipment-class :smt-and-power-electronics-test
   :manufacturer "Example SMT Instruments"
   :model "ICT-8"
   :condition "refurbished"
   :seller "Example owner-operated dealer"
   :source-url "https://example-smt.example/inventory/ict-8"
   :observed-at "2026-09-01T00:00:00Z"})

;; ── plan-smt-build ──────────────────────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (smt/plan-smt-build valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-smt-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:effect :effect/plan :temperature-profile-invented])))
    (is (= :pass (get-in r [:effect :effect/plan :reflow-verdict])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= "batch-pcb-2026-0901" (:audit/batch-id (:audit r))))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-missing-measured-peak-stays-unmeasured-not-fail
  (let [r (smt/plan-smt-build (assoc valid-req :measured-peak-temp-c nil))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :reflow-verdict])))))

(deftest test-out-of-bounds-peak-verdict-is-fail-not-refused
  (let [over (smt/plan-smt-build (assoc valid-req :measured-peak-temp-c 260.0))
        under (smt/plan-smt-build (assoc valid-req :measured-peak-temp-c 200.0))]
    (is (= :approved (:decision over)))
    (is (= :fail (get-in over [:effect :effect/plan :reflow-verdict])))
    (is (= :approved (:decision under)))
    (is (= :fail (get-in under [:effect :effect/plan :reflow-verdict])))))

(deftest test-missing-component-lots-is-refused-not-invented
  (let [r (smt/plan-smt-build (assoc valid-req :component-lot-ids []))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "component-lots"))
    (is (contains? (set (:audit/gates-checked (:audit r))) :component-lots-traced))))

(deftest test-unknown-smt-activity-refused
  (let [r (smt/plan-smt-build (assoc valid-req :smt-activity :wave-solder))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "smt-activity"))))

(deftest test-incomplete-interlocks-refused
  (let [r (smt/plan-smt-build (assoc valid-req :interlocks [:esd-grounding-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "interlocks"))))

(deftest test-missing-human-approval-defers-never-approves
  (let [r (smt/plan-smt-build (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "human-approval"))))

(deftest test-human-approval-without-reflow-scope-defers
  (let [r (smt/plan-smt-build (assoc-in valid-req [:human-approval :scope] #{:something-else}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "human-approval"))))

(deftest test-physical-command-refused-unconditionally
  (let [r (smt/plan-smt-build (assoc valid-req :requested-effect :command-smt-equipment))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "no-physical-command"))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

(deftest test-missing-batch-id-refused-for-mes-traceability
  (let [r (smt/plan-smt-build (assoc valid-req :batch/id ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "batch-id"))))

;; ── screen-smt-equipment-offer ──────────────────────────────────────────────

(deftest test-offer-screening-always-defers
  (let [r (smt/screen-smt-equipment-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (= "refurbished" (get-in r [:effect :effect/screening :condition])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

(deftest test-offer-missing-condition-refused
  (let [r (smt/screen-smt-equipment-offer (dissoc valid-offer :condition))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "condition"))))

(deftest test-offer-missing-source-url-refused
  (let [r (smt/screen-smt-equipment-offer (dissoc valid-offer :source-url))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "source"))))

(deftest test-offer-unrecognized-equipment-class-refused
  (let [r (smt/screen-smt-equipment-offer (assoc valid-offer :equipment-class :magnesium-hpdc-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "equipment-class"))))
