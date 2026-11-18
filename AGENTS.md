# Agent rules

1. **Contract first.** Build against [docs/SPEC.md](docs/SPEC.md). If the spec is wrong or silent, say so to the main session; do not invent behaviour. Spec changes go in their own commit.
2. **Test first.** Write the failing test, then the code. `mvn -B verify` must be green before every commit (tests, `spotless:check`, jacoco). Run `mvn spotless:apply` to format.
3. **Layering.** Vertical slices around a thin hexagon (SPEC section 10, `docs/restructure/PLAN.md`). `kernel` imports only the JDK; `ports` imports only `kernel` and the JDK; `features.<slice>` never imports another slice or any adapter, and otherwise imports only `kernel`, `ports`, `web`, the generated `api.generated` records, Jolt and the JDK; adapters never import `web` or `features`; in `web` only `WebModules` (the route table) names slices; only `Main` names concrete adapters. A slice's SQL is tested on SQLite (`testing.TestDb` or `testing.SliceKit`). `TargetArchitectureTest` fails the build otherwise.
4. **Jolt APIs.** Use only APIs in the Jolt version pinned by `jolt.version` (see Jolt's `llms.txt` and `llms-extras.txt`). Never copy Jolt internals.
5. **Workarounds.** A workaround for a Jolt gap is allowed only if it is isolated (one class or adapter), marked `TODO(jolt#N)` citing an existing issue, and listed in [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md). No issue, no workaround.
6. **Raising a Jolt issue.** Check open issues first (`gh issue list -R THIRU-1074/custom-Http-Server --label from-bms`). Then:

   ```bash
   gh issue create -R THIRU-1074/custom-Http-Server \
     --title "<short capability>" \
     --label from-bms --label feature --label priority:P2 --label area:Routing \
     --body "<summary, minimal failing JoltTest reproduction, acceptance criteria>"
   ```

   Use `bug` instead of `feature` for wrong behaviour. Priority: `priority:P1` blocks a lane, `P2` needs a workaround, `P3` nice to have. Area: one of `area:Request`, `area:Response`, `area:Routing`, `area:Protocol`, `area:Security`, `area:Auth`, `area:Observability`, `area:Architecture`, `area:Ecosystem`, `area:Testing and DX`. Never edit or close other issues.
7. **Tell the main session at once.** Right after filing, `SendMessage` to `main` with the issue number and one line on the workaround. Do not wait until your task ends.
8. **Commits.** Atomic Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `build:`, `ci:`, `refactor:`). Never push to `main`; never use `git stash`; never skip hooks. End messages with the attribution line the session gives you.
9. **Style.** No emojis. Comments explain why, not what. Money in paise as `long`; time through `Clock`; secrets only from env.
10. **ERD.** Any change to a table, column, constraint, index, migration or kernel entity MUST update [docs/ERD.md](docs/ERD.md) in the same commit: the Mermaid diagram, the table notes and the kernel mapping. `ErdDriftTest` enforces it for tables, columns, types, keys, nullability, unique indexes and relationships by comparing the diagram with a freshly migrated database; it prints every difference. To see the expected listing run `mvn -q test -Dtest=ErdDriftTest#printExpectedErd -Derd.print=true`.
11. **Design docs.** A change to how seat contention, holds, payments, idempotency, live updates or caching are kept correct MUST update docs/SYSTEM_DESIGN.md in the same commit; `DesignDocPointersTest` fails on pointers to code or tests that no longer exist.

## Definition of done

A schema or entity change is done only with all three: a new migration (never an edit of an applied one), an updated `docs/ERD.md`, and a green `ErdDriftTest` inside a green `mvn -B verify`.
