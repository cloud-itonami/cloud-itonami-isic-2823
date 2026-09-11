# ADR-0001: MetalMachAdvisor ⊣ Metallurgy Machinery Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2823` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2823` publishes an OSS blueprint for ISIC 2823
(Manufacture of machinery for metallurgy) -- rolling mills, casting
machinery and metal-processing plant equipment, distinct from siblings
2812 (fluid power equipment), 2815 (ovens, furnaces and furnace
burners), and 2819 (other general-purpose machinery). This build picks
ONE concrete illustrative product line, documented plainly in the
README: rolling-mill and casting-machinery manufacturing (rolling-mill
stands, continuous casters, die-casting machines, extrusion presses,
forging presses, wire-drawing machines) -- **plant operations
coordination** (production-batch product-type/load-test/quantity/
defect-rate data logging, fabrication/assembly-test-bench-equipment
maintenance scheduling, safety-concern flagging, and outbound product
shipment coordination). Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analogs are `cloud-itonami-isic-2812` (Manufacture
of fluid power equipment) and `cloud-itonami-isic-2819` (Manufacture of
other general-purpose machinery): all three are back-office
coordination actors for a fixed manufacturing plant with QC-tested,
discrete-unit finished-goods output and a real physical/consumer
safety dimension, and all three share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`), the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination), and the same permanent equipment-actuation
and certification-authority blocks. This build mirrors
`cloud-itonami-isic-2819`'s architecture closely but adapts the hazard
profile, equipment vocabulary, and product taxonomy to the metallurgy-
machinery plant: its finished goods are fabricated and assembly/test-
bench-tested metallurgy-processing machines (rolling-mill stands,
continuous casters, die-casting machines, extrusion presses, forging
presses, wire-drawing machines) rather than weighing/packaging
machinery, so its equipment kinds are `:fabrication-line` and
`:assembly-test-bench` rather than 2819's assembly line and
calibration-test bench, and its routine QC field is
`:load-test-tonnes` (tonnes-force applied during fabrication/assembly-
test-bench proof-load testing, plausibility-checked 0-100000t,
informed by real heavy-metallurgy-machinery test practice: rolling-
mill separating forces and forging/extrusion-press ratings span from a
few hundred to tens of thousands of tonnes-force, and even the largest
publicly documented forging presses in the world (e.g. China's
~80,000-tonne-force class closed-die forging press) sit well under
this ceiling -- this ceiling generously bounds well above any real
routine proof-load test reading) rather than 2819's `:calibration-
accuracy-percent`. Like 2819, shipment quantity is tracked in
finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-units`),
since metallurgy-processing machinery is likewise discrete counted
units rather than a bulk weight.

This vertical shares 2819's structural DOMAIN-SPECIFIC permanent
block, adapted to the machinery-safety certification regime: rolling
mills, casting machinery and metal-processing plant equipment are
subject to machinery-safety conformity regimes (e.g. EU Machinery
Directive 2006/42/EC CE marking, ANSI B11.19 machine-safeguarding
performance criteria). This actor is never the certification authority
-- any proposal (regardless of op) that declares `:issue-
certification? true` is a HARD, PERMANENT, unconditional block
(`metalmachmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical has NO pre-existing `kotoba-lang/metalmachmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`metalmachmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, load-test plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2819`'s `weighpkgmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:metallurgy-machinery-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"metallurgy-machinery-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created). The
`metalmachmfg` namespace is likewise grep-verified UNIQUE fleet-wide
(`gh search code "metalmachmfg" --owner cloud-itonami`, zero hits
before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external metallurgy-machinery-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
metallurgy-machinery vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity /
product-type / load-test / defect-rate validation functions live as
pure functions in `metalmachmfg.registry` and are re-verified
independently by `metalmachmfg.governor` -- the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2819`'s `weighpkgmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of metallurgy-
machinery plant operations. It does NOT:
- Control fabrication or assembly-test-bench-line equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate fabrication/assembly-line equipment
- Self-issue a CE (EU Machinery Directive 2006/42/EC) or ANSI B11.19 machinery-safety conformity mark

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: metallurgy-machinery manufacturing
involves very large static/dynamic loads and moving parts (rolling-
mill stands, continuous casters, die-casting machines, extrusion
presses, forging presses, wire-drawing machines all carry real crush/
pinch/structural-failure hazards during fabrication, assembly, and
proof-load testing), with quality-defect consequences downstream in
whatever metal-processing plant the batch's machinery ends up
installed in. Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (equipment-safety/structural-integrity/quality-
defect concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2812` and `cloud-itonami-isic-2819`, this
vertical has TWO entity kinds each gating a different op:
`:schedule-maintenance` independently verifies the referenced
**equipment** unit's own `:verified?`/`:registered?` fields;
`:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity -- never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `metalmachmfg.governor`, mirroring `cloud-itonami-isic-2819`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct fabrication/assembly-line-equipment control, equipment actuation, or self-issued machinery-safety certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Metallurgy-machinery plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no machinery-safety certification mark can
ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2823`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-load-test-tonnes, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
