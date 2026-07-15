(ns metalmachmfg.registry-test
  (:require [clojure.test :refer [deftest is]]
            [metalmachmfg.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- batch-verified? / batch-registered? / batch-ready? -----------------------------

(deftest batch-is-verified-when-flagged
  (is (true? (r/batch-verified? {:id "b1" :verified? true}))))

(deftest batch-is-not-verified-when-false-or-missing
  (is (false? (r/batch-verified? {:id "b1" :verified? false})))
  (is (false? (r/batch-verified? {:id "b1"}))))

(deftest batch-is-registered-when-flagged
  (is (true? (r/batch-registered? {:registered? true}))))

(deftest batch-is-not-registered-when-false-or-missing
  (is (false? (r/batch-registered? {:registered? false})))
  (is (false? (r/batch-registered? {}))))

(deftest batch-ready-requires-both
  (is (true? (r/batch-ready? {:verified? true :registered? true})))
  (is (false? (r/batch-ready? {:verified? true :registered? false})))
  (is (false? (r/batch-ready? {:verified? false :registered? true})))
  (is (false? (r/batch-ready? {}))))

;; ----------------------------- shipment-quantity-exceeded? -----------------------------

(deftest small-shipment-within-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 40.0 :shipped-units 12.0} 10.0))))

(deftest shipment-that-pushes-past-quantity-exceeds
  (is (true? (r/shipment-quantity-exceeded?
              {:quantity-units 15.0 :shipped-units 14.0} 5.0))))

(deftest shipment-exactly-at-quantity-does-not-exceed
  (is (false? (r/shipment-quantity-exceeded?
               {:quantity-units 15.0 :shipped-units 14.0} 1.0))
      "exactly at quantity is not over, only strictly beyond"))

(deftest missing-quantity-is-not-flagged-exceeded
  (is (false? (r/shipment-quantity-exceeded? {} 100.0)))
  (is (false? (r/shipment-quantity-exceeded? {:quantity-units 15.0} nil))))

;; ----------------------------- product-type-valid? -----------------------------

(deftest known-product-types-are-valid
  (doseq [pt [:rolling-mill-stand :continuous-caster :die-casting-machine
              :extrusion-press :forging-press :wire-drawing-machine]]
    (is (r/product-type-valid? pt))))

(deftest fabricated-product-type-is-invalid
  (is (not (r/product-type-valid? :unobtainium)))
  (is (not (r/product-type-valid? nil))))

;; ----------------------------- load-test-tonnes-valid? -----------------------------

(deftest typical-load-test-tonnes-is-valid
  (is (r/load-test-tonnes-valid? 0.0))
  (is (r/load-test-tonnes-valid? 350.0))
  (is (r/load-test-tonnes-valid? 2200.0))
  (is (r/load-test-tonnes-valid? 100000.0)))

(deftest negative-load-test-tonnes-is-invalid
  (is (not (r/load-test-tonnes-valid? -1.0))))

(deftest excessive-load-test-tonnes-is-invalid
  (is (not (r/load-test-tonnes-valid? 99999999.0)))
  (is (not (r/load-test-tonnes-valid? 100000.01))))

(deftest non-numeric-or-missing-load-test-tonnes-is-invalid
  (is (not (r/load-test-tonnes-valid? nil)))
  (is (not (r/load-test-tonnes-valid? "350.0"))))

;; ----------------------------- defect-rate-valid? -----------------------------

(deftest typical-defect-rate-is-valid
  (is (r/defect-rate-valid? 1.2))
  (is (r/defect-rate-valid? 0.0))
  (is (r/defect-rate-valid? 50.0))
  (is (r/defect-rate-valid? 100.0)))

(deftest negative-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? -1.0))))

(deftest excessive-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? 999.0)))
  (is (not (r/defect-rate-valid? 100.01))))

(deftest non-numeric-or-missing-defect-rate-is-invalid
  (is (not (r/defect-rate-valid? nil)))
  (is (not (r/defect-rate-valid? "1.5"))))

;; ----------------------------- register-maintenance -----------------------------

(deftest maintenance-is-a-draft-not-a-real-actuation
  (let [result (r/register-maintenance "mnt-1" "fab-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-assigns-maintenance-number
  (let [result (r/register-maintenance "mnt-1" "fab-001" 7)]
    (is (= (get result "maintenance_number") "MNT-000007"))
    (is (= (get-in result ["record" "maintenance_id"]) "mnt-1"))
    (is (= (get-in result ["record" "equipment_id"]) "fab-001"))
    (is (= (get-in result ["record" "kind"]) "maintenance-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "" "fab-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "fab-001" -1))))

;; ----------------------------- register-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-shipment "ship-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-shipment "ship-1" 7)]
    (is (= (get result "shipment_number") "SHP-000007"))
    (is (= (get-in result ["record" "shipment_id"]) "ship-1"))
    (is (= (get-in result ["record" "kind"]) "shipment-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "ship-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-maintenance "mnt-1" "fab-001" 0)
        hist (r/append [] c1)
        c2 (r/register-maintenance "mnt-2" "fab-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "MNT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "MNT-000001" (get-in hist2 [1 "record_id"])))))
