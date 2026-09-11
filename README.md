# cloud-itonami-isic-2823: Manufacture of machinery for metallurgy

Open Business Blueprint for **ISIC 2823**: manufacture of machinery for metallurgy — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **metallurgy-machinery plant operations**: production-batch data logging (product-type/load-test/quantity/defect-rate), fabrication/assembly-test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for metallurgy-
machinery-plant operations: run by a qualified operator so a plant
keeps its own operating records instead of renting a closed SaaS.

## Concrete illustration: rolling-mill and casting-machinery manufacturing

ISIC 2823 covers manufacture of machinery for metallurgy — rolling
mills, casting machinery, and metal-processing plant equipment — and
is distinct from siblings 2812 (fluid power equipment), 2815 (ovens/
furnaces and furnace burners), and 2819 (other general-purpose
machinery). This build picks ONE concrete illustrative product line:
**rolling-mill and casting-machinery manufacturing** — rolling-mill
stands, continuous casters, die-casting machines, extrusion presses,
forging presses, wire-drawing machines.

## Scope: plant operations coordination, not fabrication/assembly-line control

This vertical covers the **manufacturing plant** that fabricates and
assembly/test-bench-tests finished metallurgy-processing machinery
(rolling-mill stands, continuous casters, die-casting machines,
extrusion presses, forging presses, wire-drawing machines) — including
proof-load testing — before shipment. This actor coordinates the
back-office record keeping around that plant — it never touches the
fabrication/assembly-line equipment directly, and it is never a
machinery-safety certification authority (e.g. EU Machinery Directive
2006/42/EC CE conformity marking, or ANSI B11.19 machine-safeguarding
conformity marking).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — fabrication/assembly-test batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — fabrication/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/structural-integrity/quality-defect concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(fabrication/assembly/test-bench line equipment hazards -- heavy
rolling/casting/forging/extrusion machinery involves very large
static/dynamic loads and moving parts -- downstream structural/
mechanical-safety consequence via the metal-processing plants the
batch's machinery ends up installed in):

- Does NOT control fabrication or assembly-test-bench-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate fabrication/assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue a CE (EU Machinery Directive 2006/42/EC) or ANSI B11.19 machinery-safety conformity mark (the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`metalmachmfg.operation/build`, a langgraph-clj StateGraph):
1. **`metalmachmfg.advisor`** (sealed intelligence node, `MetalMachAdvisor`): proposes decisions only, never commits
2. **`metalmachmfg.governor`** (independent, `Metallurgy Machinery Plant Operations Governor`): validates against domain rules, re-derived from `metalmachmfg.registry`'s pure functions and `metalmachmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct fabrication/assembly-line-equipment control)
     - Directly actuating fabrication/assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a CE (EU Machinery Directive 2006/42/EC) or ANSI B11.19 machinery-safety conformity mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:load-test-tonnes` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`metalmachmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`metalmachmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
kbb -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
kbb -M:dev:test

# Run the demo
kbb -M:dev:run

# Lint
kbb -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
