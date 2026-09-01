(ns metalmachmfg.unit-genealogy-test
  "Executable decision-contract tests for metalmachmfg.unit-genealogy —
  cross-cell serialized-unit genealogy + quarantine HOLD for the
  magnesium-hydrogen PEMFC electric-drive system assembly cell."
  (:require [clojure.test :refer [deftest is testing]]
            [metalmachmfg.unit-genealogy :as ug]))

(defn- rec [cell disposition] {:cell cell :disposition disposition
                               :component/lot (str "lot-" (name cell))})

(def complete-genealogy
  [(rec :magnesium-hpdc :ok)
   (rec :cartridge-dry-inert-handling :ok)
   (rec :hydrogen-reactor-fabrication :ok)
   (rec :pem-stack-assembly-and-test :ok)
   (rec :powertrain-electronics :ok)])

(deftest complete-genealogy-releases
  (is (= :release (:decision (ug/validate-genealogy
                              {:system-serial "sys-001" :records complete-genealogy})))))

(deftest missing-serial-refused
  (is (= :refused (:decision (ug/validate-genealogy
                              {:system-serial "" :records complete-genealogy}))))
  (is (= :refused (:decision (ug/validate-genealogy
                              {:system-serial "sys-001" :records []}))))
  (is (= :refused (:decision (ug/validate-genealogy
                              {:system-serial "sys-001"})))))

(deftest unrecognized-cell-refused-not-held
  (let [v (ug/validate-genealogy
           {:system-serial "sys-001"
            :records (conj complete-genealogy (rec :made-up-cell :ok))})]
    (is (= :refused (:decision v)))
    (is (= :unrecognized-cell (:reason v)))
    (is (= #{:made-up-cell} (:cells v)))))

(deftest quarantine-holds-unconditionally
  (let [v (ug/validate-genealogy
           {:system-serial "sys-002"
            :records (assoc-in complete-genealogy [2 :disposition] :quarantined)})]
    (is (= :hold (:decision v)))
    (is (= :quarantined-upstream-cell (:reason v)))
    (is (= #{:hydrogen-reactor-fabrication} (:quarantined-cells v)))))

(deftest incomplete-genealogy-holds-with-missing-cells
  (let [v (ug/validate-genealogy
           {:system-serial "sys-003"
            :records (take 3 complete-genealogy)})]
    (is (= :hold (:decision v)))
    (is (= :incomplete-genealogy (:reason v)))
    (is (= #{:pem-stack-assembly-and-test :powertrain-electronics}
           (:missing-cells v)))))

(deftest unmeasured-cells-require-human-approval
  (let [records (assoc-in complete-genealogy [4 :disposition] :unmeasured)
        v (ug/release-activity {:activity-id "act-1" :system-serial "sys-004"
                                :records records})]
    (is (= :release (:decision v)))
    (is (true? (get-in v [:effect :approval :required])))
    (is (= #{:powertrain-electronics}
           (get-in v [:effect :approval :cells])))
    (is (false? (get-in v [:audit :audit/bot-commanded-equipment])))))

(deftest clean-release-needs-no-approval
  (let [v (ug/release-activity {:activity-id "act-2" :system-serial "sys-005"
                                :records complete-genealogy})]
    (is (= :release (:decision v)))
    (is (false? (get-in v [:effect :approval :required])))
    (is (= :draft-release-record (get-in v [:effect :effect/kind])))))

(deftest hold-never-commands-equipment
  (let [v (ug/release-activity {:activity-id "act-3" :system-serial "sys-006"
                                :records []})]
    (is (= :refused (:decision v)))
    (is (= :none (get-in v [:effect :effect/kind])))
    (is (false? (get-in v [:audit :audit/bot-commanded-equipment])))))

(deftest extra-cells-do-not-block
  (let [v (ug/validate-genealogy
           {:system-serial "sys-007"
            :records (conj complete-genealogy (rec :mes-traceability :ok))})]
    (is (= :release (:decision v)))))
