# AGENTS.md — rules for AI agents and reviewers on this repo

This file is the single source of truth for agent behavior. It is written to be read before doing
any work here. The one-line summary: **the port is a statement-parallel translation of the pinned
AssetsTools.NET C# source — never a rewrite.** The `ttmap/` package and `api/AssetService.kt` are
new code (not ports) and are exempt from statement-parity, but must follow the JSON/order rules.

## Hard rules

### 1. Statement-level parity is the contract (NOT just functional parity)

- Every C# statement, branch, call, and loop in a ported method must appear, **in the same order**,
  in the Kotlin method — translated only through [`docs/mapping.md`](docs/mapping.md).
- "Functional parity" (producing the same output) is **necessary but never sufficient**. A patch
  that changes the C# structure is a **parity violation even if tests pass**.
- When the literal translation is awkward in Kotlin, the *first* resort is a new `docs/mapping.md`
  entry (so it becomes a canonical pattern), not a bespoke rewrite.
- **This rule applies to *fixes*, not just initial ports.**

**Verbatim rule to include in every porting/parity task prompt:**

> Statement-level parity is a hard rule. Every C# statement must appear, in order, in the Kotlin
> port, translated only through docs/mapping.md. Functional parity is necessary but never
> sufficient — do not rewrite the C# structure to make it work. When the literal translation is
> awkward, add a canonical mapping entry to docs/mapping.md first.

### 2. Establish the baseline before making changes

Before changing anything, record and report:

- `git status --short` and `git diff --stat` (the tree may hold uncommitted work — build on it,
  never revert it without asking).
- The reference "already passing" checks, and re-verify them after every change:
  - `./gradlew build` → green.
  - `python3 tools/sweep.py ...` → corpus differential (once Phase 11 lands).
  - `python3 tools/sweep_cs.py ...` → write-path parity: round-trip bytes vs the pinned
    AssetsTools.NET C# reference (via the ttmapgen harness). Target `MATCH N, BUG 0`
    (BUG = decoded object content differs; DEVIATION = layout-only, acceptable).

A fix that doesn't show its baseline (before→after) is incomplete. Regressions must be caught and
fixed before the task is considered done.

## Conventions that must not be violated

- **NO explanatory comments** in ported Kotlin source files. The attribution header at the top of
  each ported file is fine; do not add prose comments. (The `ttmap/`, `api/`, CLI, tools, and
  tests may carry doc comments where they explain non-obvious behavior.)
- **`$type` strings** in the JSON layer use `asset4j.<Ns>.<Class>` names (documented exception in
  `docs/mapping.md` §ttmap and plan §3 — asset4j is new code, not a UAssetAPI port).
- **No new dependencies** without explicit approval. The compression seams (lz4-java, xz) are the
  only third-party entry points; `ttmapgen` uses a .NET subprocess (no MonoCecil/Cpp2IL JVM dep).
- **Do not change binary Read/Write behavior** unless fixing a genuine parity bug — and if you do,
  re-run the corpus differential to prove no regression.
- **`docs/parity-exceptions.json`** is the sole place a deliberate divergence may be recorded.
  Agents must never add/modify/revoke entries on their own.
- **Do not commit** unless the task explicitly says to.

## Review checklist (for reviewers / reviewers-as-agents)

Reject a ported file if ANY of:

1. A Kotlin method can't be diffed statement-by-statement against its C# source (order, branch
   shape, call sites differ) — even if the output is byte-identical.
2. A C# construct was solved without a corresponding `docs/mapping.md` entry.
3. New public members/classes don't mirror the C# names (PascalCase preserved).
4. The port touches a file marked `ported` in `docs/port-tracker.md` without updating the tracker.
5. Algorithmic complexity diverges from the C# source (O(1)/O(n) vs O(n)/O(n²) rewrites).
6. A ported file contains `//@parity:on`/`//@parity:off` markers that are unbalanced, nested,
   duplicated, or reference an id not present in `docs/parity-exceptions.json`.

Acceptance bar for a parity task: statement-level review done, `./gradlew build` green, and the
corpus differential at the target stated in the task with no regressions.

## Reading order (context onboarding)

1. `README.md` — overview, pinned AssetsTools.NET sha, how the port tracks upstream.
2. `docs/mapping.md` — the C#→Kotlin translation contract (consult before any port).
3. `docs/port-tracker.md` — which files are ported, deferred, and how to regenerate oracle data.
4. This file — agent rules (always).
5. `tools/fetch_corpus.py` + `tools/sweep.py` + `tools/sweep_cs.py` — how functional parity
   (UnityPy decode oracle) and write-path parity (AssetsTools.NET C# byte oracle) are verified.

## Key paths

- Pinned C# source (READ-ONLY reference): `/tmp/automod/AssetsTools.NET/AssetTools.NET/`
- Oracle binary: the small C# harness built from the pinned source (once Phase 11 lands);
  JVM jar: `assetcli/build/libs/assetcli.jar`.
- Corpus: `assetapi/src/test/resources/testassets/` (fetched by `tools/fetch_corpus.py`).
- Parity tools: `tools/sweep.py` (parallel corpus differential vs UnityPy),
  `tools/sweep_cs.py` (write-path byte oracle vs AssetsTools.NET C#), `tools/oracle_diff.py`,
  `tools/audit_parity.py` (member-name audit), `tools/fetch_corpus.py` (corpus download).
