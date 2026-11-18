# Restructure baseline

Measured on `feat/restructure-foundation` at its fork point `0893749` (tip of `feat/roles`), before any slice moves. Re-run the commands after each wave and compare; the goal of the restructure is that a typical feature touches fewer packages, not that the code gets shorter.

Machine: Windows 11, JDK 21, Maven 3.9.10, `MAVEN_OPTS=-Xmx448m`, `JAVA_TOOL_OPTIONS="-Xmx448m -XX:+UseSerialGC"`, warm `~/.m2`.

## Size

| | Files | Lines |
|---|---:|---:|
| `src/main/java` | 111 | 10 295 |
| `src/test/java` | 58 | 9 821 |

### Per package, main

| Package (`dev.thiru.bms.`) | Files | Lines |
|---|---:|---:|
| (root: `Main`, `AppConfig`) | 2 | 572 |
| `domain` | 25 | 1 237 |
| `domain.port` | 15 | 560 |
| `application` | 20 | 1 924 |
| `web` | 23 | 2 821 |
| `adapters.db` | 13 | 2 572 |
| `adapters.payment` | 6 | 291 |
| `adapters.live` | 3 | 270 |
| `adapters.auth` | 2 | 24 |
| `adapters.ids` | 2 | 24 |

### Per package, test

| Package (`dev.thiru.bms.`) | Files | Lines |
|---|---:|---:|
| (root: E2E, Main, AppConfig, Architecture) | 4 | 976 |
| `domain` | 8 | 950 |
| `application` | 13 | 1 832 |
| `application.fakes` (`FakeWorld`) | 1 | 1 016 |
| `web` | 12 | 2 117 |
| `adapters.db` | 13 | 2 328 |
| `adapters.payment` | 3 | 254 |
| `adapters.live` | 2 | 294 |
| `adapters.auth` | 1 | 34 |
| `adapters.ids` | 1 | 20 |

Largest single files: `FakeWorld` 1 016, `EndToEndTest` 598, `SqlBookings` 492, `SqlAdmin` 490, `Dtos` 485, `SqlCatalog` 480, `AdminModule` 405.

## Tests and build time

| Metric | Value |
|---|---:|
| Tests run by `mvn -B verify` | 356 (0 failures, 0 skipped) |
| `@Test`/`@ParameterizedTest` methods in source | 343 |
| `mvn -B verify` wall time (incl. spotless, jacoco) | 35 s (Maven "Total time" 31.8 s) |

## What a feature costs today

Files and directories (Java packages, plus `docs`, migrations and the repo root) each commit touched, from git.

| Commit | What | Files | Dirs | +/- lines |
|---|---|---:|---:|---|
| `1026c7f` | rename role USER to AUDIENCE, add `cinema_owner` | 13 | 10 | +131 / -27 |
| `63cbbfb` | admin repositories fix: `SqlRepositories` gains `catalogWrites()`/`reports()` that used to throw `UnsupportedOperationException` | 3 | 2 | +595 / -0 |
| `d63fbfe` | owner feature, db lane: owner links, user roles, owner-scoped reports | 8 | 5 | +433 / -14 |
| `5666343` | owner feature, application lane: scoped use cases, role management | 8 | 2 | +542 / -16 |
| `5199850` | owner feature, web lane: `/api/owner` and admin user routes | 13 | 3 | +792 / -100 |
| `0893749` | owner feature, E2E test | 1 | 1 | +233 / -1 |
| `4833360^..0893749` | **the whole owner feature** (spec, rename, db, application, web, E2E) | 39 | 14 | +2 180 / -163 |

Reading: the rename alone touched 10 directories across every layer, and the owner feature needed 4 commits in 3 lanes over 14 directories, with the hot spots `Repositories`, `FakeWorld`, `Dtos`, `Permissions` and `WebModules` edited by several lanes. The `63cbbfb` fix exists because `Repositories` had throwing `default` methods that no test exercised against the real adapter; the port contract tests added in this phase are the guard for that class of bug.

Target after the restructure: a feature like "owner routes" lands in one `features/<slice>` directory plus at most the integration hot spots (`Main`, `Permissions`/RBAC.md, a migration).

## Reproduce

Run from the repo root in Git Bash.

```bash
# files and lines, main and test
for s in main test; do
  echo "$s files=$(find src/$s/java -name '*.java' | wc -l) lines=$(cat $(find src/$s/java -name '*.java') | wc -l)"
done
# per package
for s in main test; do
  for d in $(find src/$s/java -name '*.java' | xargs -n1 dirname | sort -u); do
    echo "$s $(ls $d/*.java | wc -l) files $(cat $d/*.java | wc -l) lines ${d#src/$s/java/}"
  done
done
# largest files
wc -l $(find src -name '*.java') | sort -n | tail -8

# tests and wall time
export MAVEN_OPTS="-Xmx448m"; export JAVA_TOOL_OPTIONS="-Xmx448m -XX:+UseSerialGC"
start=$(date +%s); mvn -B verify > verify.log 2>&1; echo "rc=$? wall=$(( $(date +%s) - start ))s"
grep -E "Tests run:.*Skipped: [0-9]+$|Total time" verify.log | tail -2
grep -rcE "@Test|@ParameterizedTest" src/test/java | awk -F: '{s+=$2} END {print s}'

# files and directories touched per commit
for c in 1026c7f 63cbbfb d63fbfe 5666343 5199850 0893749; do
  f=$(git show --name-only --format= $c)
  echo "$c files=$(echo "$f" | grep -c .) dirs=$(echo "$f" | xargs -n1 dirname | sort -u | wc -l) $(git show --shortstat --format= $c)"
done
f=$(git diff --name-only 4833360^ 0893749)
echo "owner feature files=$(echo "$f" | wc -l) dirs=$(echo "$f" | xargs -n1 dirname | sort -u | wc -l)"
git diff --shortstat 4833360^ 0893749
```
