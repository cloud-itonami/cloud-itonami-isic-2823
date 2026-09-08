(ns metalmachmfg.eol-gate-test
  "Focused tests for the system assembly-and-EOL proof-load decision
  contract (activity -> decision -> effect -> audit).
  Pure; deterministic; stdlib only."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [metalmachmfg.eol-gate :as eol]))

(def ^:private valid-req
  {:activity/id "act-eol-0001"
   :batch/id "batch-2026-0101"
   :equipment-unit/id "bench-001"
   :machine-type :die-casting-machine
   :rated-proof-load-tf 320
   :plausibility-floor-tf 0.1
   :plausibility-ceiling-tf 450
   :measured-proof-load-tf 318.5
   :interlocks [:rotating-machinery-guard-verified :hydraulic-relief-verified
                :restrain-tooling-rated :clear-zone-confirmed]
   :witness-robot-dids ["did:web:etzhayyim.com:itonami:otete"
                        "did:web:etzhayyim.com:itonami:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:apply-proof-load}}
   :requested-effect :simulate-eol-plan})

(def ^:private valid-offer
  {:activity/id "act-eol-eq-0001"
   :equipment-class :proof-load-test-bench
   :manufacturer "Example Proof Load Systems"
   :model "PLS-400"
   :condition "refurbished"
   :seller "Example owner-operated dealer"
   :source-url "https://example-proofload.example/inventory/pls-400"
   :observed-at "2026-09-01T00:00:00Z"})

;; ── plan-proof-load-eol-test ────────────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (eol/plan-proof-load-eol-test valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-eol-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:effect :effect/plan :capacity-invented])))
    (is (= :pass (get-in r [:effect :effect/plan :eol-verdict])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= "batch-2026-0101" (:audit/batch-id (:audit r))))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-bench-command-refused-unconditionally
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :requested-effect :command-test-bench))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-missing-batch-id-refused
  (let [r (eol/plan-proof-load-eol-test (dissoc valid-req :batch/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "MES traceability"))))

(deftest test-missing-equipment-unit-refused
  (let [r (eol/plan-proof-load-eol-test (dissoc valid-req :equipment-unit/id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "equipment-unit"))))

(deftest test-unknown-machine-type-refused
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :machine-type :bread-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "machine-type"))))

(deftest test-missing-rated-load-refused-not-invented
  (let [r (eol/plan-proof-load-eol-test (dissoc valid-req :rated-proof-load-tf))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never invents"))))

(deftest test-rated-load-outside-recorded-bounds-refused
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :plausibility-ceiling-tf 300))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))))

(deftest test-nil-measured-reading-yields-unmeasured-verdict
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :measured-proof-load-tf nil))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :eol-verdict])))
    (is (nil? (get-in r [:effect :effect/plan :measured-proof-load-tf])))))

(deftest test-measured-reading-below-floor-is-fail-not-pass
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :measured-proof-load-tf 0.05))]
    (is (= :approved (:decision r)))
    (is (= :fail (get-in r [:effect :effect/plan :eol-verdict])))))

(deftest test-non-numeric-measured-reading-refused
  (let [r (eol/plan-proof-load-eol-test (assoc valid-req :measured-proof-load-tf "about 320"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (eol/plan-proof-load-eol-test
           (assoc valid-req :interlocks [:hydraulic-relief-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "safety: interlocks incomplete"))
    (is (str/includes? (:audit/refusal (:audit r)) "rotating-machinery-guard-verified"))))

(deftest test-single-witness-refused-quorum
  (let [r (eol/plan-proof-load-eol-test
           (assoc valid-req :witness-robot-dids ["did:web:etzhayyim.com:itonami:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "witness-quorum"))))

(deftest test-missing-human-approval-refused
  (let [r (eol/plan-proof-load-eol-test (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))
    (is (str/includes? (:audit/refusal (:audit r)) "absence defers, never approves"))))

(deftest test-approval-wrong-scope-refused
  (let [r (eol/plan-proof-load-eol-test
           (assoc-in valid-req [:human-approval :scope] #{:pressurize}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-refusal-audit-always-attests-no-command
  (let [r (eol/plan-proof-load-eol-test (dissoc valid-req :human-approval))]
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

;; ── screen-equipment-offer ─────────────────────────────────────────────────

(deftest test-offer-deferred-to-human
  (let [r (eol/screen-equipment-offer (assoc valid-offer :price 84000 :currency "USD"))]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (= "refurbished" (get-in r [:effect :effect/screening :condition])))
    (is (= 84000 (get-in r [:effect :effect/screening :unmeasured :price])))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "human decision"))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))))

(deftest test-offer-unknown-condition-distinguished
  (let [r (eol/screen-equipment-offer (assoc valid-offer :condition "unknown"))]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-offer-missing-price-recorded-unmeasured-not-invented
  (let [r (eol/screen-equipment-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (nil? (get-in r [:effect :effect/screening :unmeasured :price])))
    (is (contains? (set (get-in r [:effect :effect/screening :unmeasured-fields]))
                   :price))))

(deftest test-offer-bad-condition-refused
  (let [r (eol/screen-equipment-offer (assoc valid-offer :condition "like-new"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-offer-non-first-party-source-refused
  (let [r (eol/screen-equipment-offer (assoc valid-offer :source-url ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "source"))))

(deftest test-offer-unrecognized-equipment-class-refused
  (let [r (eol/screen-equipment-offer (assoc valid-offer :equipment-class :coffee-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "equipment-class"))))

(deftest test-offer-refusal-attests-no-command
  (let [r (eol/screen-equipment-offer (assoc valid-offer :condition "scrap"))]
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

