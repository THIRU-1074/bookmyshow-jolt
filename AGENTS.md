# Agent rules

1. **Contract first.** Build against [docs/SPEC.md](docs/SPEC.md). If the spec is wrong or silent, say so to the main session; do not invent behaviour. Spec changes go in their own commit.
2. **Test first.** Write the failing test, then the code. `mvn -B verify` must be green before every commit (tests, `spotless:check`, jacoco). Run `mvn spotless:apply` to format.
3. **Layering.** `domain` imports only the JDK; `application` imports `domain` only; `web` imports `application` and `domain`, never adapters; adapters never import `web`; only `Main` names concrete adapters. `ArchitectureTest` fails the build otherwise.
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
