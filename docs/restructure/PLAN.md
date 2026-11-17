# ADR 0001: vertical feature slices around a thin hexagon

- **Status:** accepted (user-approved), **in migration**. Wave 0 (guardrails) is merged onto `integration` after the Jolt v0.4.1 upgrade; wave 1 (kernel, ports, `ports.Db`) is merged too. Wave 2 (catalog, live and accounts slices; legacy modules deleted) is merged; see [Wave 2 record](#wave-2-record). Wave 3 (booking slice) is merged; see [Wave 3 record](#wave-3-record). Wave 5 (admin and owner slice) is done on `feat/restructure-wave5`; see [Wave 5 record](#wave-5-record). In progress: wave 4, payments; see [Wave 4 brief](#wave-4-brief-payments) and [How to build a slice](#how-to-build-a-slice).
- **Date:** 2026-11-17
- **Supersedes:** the layer-first layering of SPEC section 10 once the last wave lands.

## Context

The app is layered by kind: `domain`, `domain.port`, `application`, `web`, `adapters.*` (see [BASELINE.md](BASELINE.md)). One feature crosses all of them. The owner feature touched 39 files in 14 directories over 4 commits and 3 lanes; the USER to AUDIENCE rename alone touched 10 directories. The shared hot spots (`Repositories`, `FakeWorld`, `Dtos`, `Permissions`, `WebModules`) were edited by every lane. `Repositories` grew throwing `default` methods so that lanes could compile without each other, and `SqlRepositories.catalogWrites()` shipped as `UnsupportedOperationException` until `63cbbfb`, because the use case tests ran only on `FakeWorld`.

## Decision

```
dev.thiru.bms
  Main, AppConfig          composition root; the only code that names adapters (integration lane)
  kernel/                  pure shared rules and value objects; imports only java.*
    booking/  catalog/  account/  payment/
  ports/                   real I/O seams only; imports only kernel and java.*
  adapters/<seam>/         one adapter per port
  web/                     shared HTTP kit (problems, RBAC gate, principal, paging, idempotency); no feature code
  api/generated            records generated from api/openapi.yaml (shared wire types, see api/README.md)
  features/
    catalog/  live/  accounts/  booking/  payments/  admin/     (admin = the owner/admin slice)
```

Rules, enforced by `TargetArchitectureTest` (passes today because the packages are absent; each rule has a fixture proving it fires):

1. `kernel` imports only the JDK.
2. `features.<a>` never imports `features.<b>` or `adapters`.
3. `ports` import only `kernel` and the JDK (`java.sql.ResultSet` is fine for row mapping).
4. Only `Main` names adapters.

Further decisions:

- **A slice owns its routes, request and response types, use cases, SQL and tests.** Simple CRUD is a transaction script: route handler, then SQL through `ports.Db`. A use case class exists only where there is a rule to orchestrate (holds, booking, checkout, webhook, cancellations, sweep).
- **No port per entity.** The 15 repository interfaces (`CatalogRepositories.*`, `BookingRepositories.*`, `AdminRepositories.*`, `SeatLockRepository`) are deleted as ceremony. Their SQL moves into the slices that run it. Several slices may write the same table (booking and payments both move a booking), but only through `kernel` transitions (`Booking.paymentSucceeded`, ...) and the `version` column check. Read SQL may be duplicated across slices; that is the price of independence and it is small.
- **`ports.Db`** replaces `UnitOfWork` + `Repositories`: `inTransaction(Function<Tx, T>)` where `Tx` has `query`, `queryOne`, `update` with JDK row mappers and `afterCommit(Runnable)`. `afterCommit` replaces `EventBuffer`: seat events are queued on the transaction and published by the adapter after commit.
- **`java.time.Clock` is the clock port.** No wrapper.
- **Slice tests run on SQLite** (temp file, migrated, as `DbTest` does: about 50 ms per test). `FakeWorld` shrinks as repositories go and is deleted with the last one. Fakes stay for the non-database ports (gateway, seat events, live, hasher, ids, tokens, idempotency) and each fake is held to its port contract.
- **Wire types come from `api/openapi.yaml`.** Since wave 6 the build generates one record per object schema into `target/generated-sources/openapi` (package `dev.thiru.bms.api.generated`, a main source root), and the slices serialise those records. Shared shapes such as `SeatMap` (catalog and live) are shared through `api.generated`, never through another slice. During waves 2 to 5 slices kept local records and `RecordGeneratorTest.WEB_RECORDS` mapped each schema to the record serialised; wave 6 removed that map. Today `RecordGeneratorTest` checks that the generated records match the schema, that every object schema is generated or listed with what serves it instead, and that no slice declares a duplicate (`ALLOWED_DUPLICATES`, each with a reason).

## Guardrails (replace line-by-line review)

| Guard | Added | Catches |
|---|---|---|
| `TargetArchitectureTest` + `src/test/resources/arch-fixtures` | wave 0 | a slice importing another slice or an adapter, an impure kernel, a port leaking Jolt, wiring outside `Main` |
| `ArchitectureTest` (legacy) | before | layer rules for code not moved yet; retired in wave 6 |
| port contracts, `dev.thiru.bms.contract` | wave 0 | a fake and its adapter disagreeing (seven real mismatches fixed in the fakes, see below) |
| `RbacMatrixTest` | before | a route without a permission rule, or RBAC.md drifting |
| `ApiCoverageTest` | wave 0 | `openapi.yaml` missing a route or disagreeing with RBAC.md on access |
| `ApiSchemaTest` | wave 0 | any JSON response, problem or SSE payload drifting from the schema; any operation never exercised |
| `RecordGeneratorTest` | wave 0 | generated records not compiling or not matching the web records |
| `EndToEndTest` | before | the booking and owner journeys on real adapters over a socket |

A slice lane is done when all of these are green; review then looks at the slice directory, not every line.

### Contract mismatches found in wave 0 (all fixed in test fakes)

| Fake | Diverged from | Now |
|---|---|---|
| `FakeWorld.users().findByEmail` | `SqlBookings.Users` | lower-cases the lookup |
| `FakeWorld.users().insert` | `app_user.email UNIQUE` | duplicate email is `VALIDATION` on `email` |
| `FakeWorld.holds().findExpiredActive` | `ORDER BY expires_at, id` | oldest expiry first |
| `FakeWorld.bookings().findOpenExpired` | `ORDER BY expires_at, id` | oldest expiry first |
| `FakeWorld.locks().findByHold/findByShow/deleteExpiredHeld` | `SqlSeatLocks` sorts by seat | sorted by seat |
| `FakeWorld.refund` | `MockPaymentGateway` | negative amount is `IllegalArgumentException` |
| `WebKit.MemoryIdempotency.release` | `SqlIdempotency` | keeps a completed response replaying (since v0.4.1 the web tests use Jolt's `IdempotencyStore.inMemory()`, which meets the same contract) |
| `FakeWorld.cities().findAll`, `screens().findByCinema` | `SqlCatalog` `ORDER BY name` and `ORDER BY name, id` | sorted the same; found in wave 2 step 0 when the web tests moved onto SQLite, now in `RepositoriesContract` |

No contract had to be disabled. The SQLite adapters met every contract as written.

### Spec drift found while writing the schema (not fixed: spec changes are their own PR)

- SPEC 7 said the seat map's `categories` are `{code, name, amount}`; the server and the React client use `{category, name, amount}` (`Dtos.PriceDto`). Settled for the server: SPEC 7 fixed in its own `docs(spec)` commit.

## Class map

Every production class, its target and why. "Split" means the code is divided across the named slices and the original is deleted when the last part has moved. Paths are under `dev.thiru.bms`.

### Root

| Class | Target | Reason |
|---|---|---|
| `Main` | stays (integration lane) | composition root: builds adapters, passes ports to each slice's routes, schedules the sweep |
| `AppConfig` | stays (integration lane) | startup configuration, read once |

### `domain` to `kernel`

| Class | Target | Reason |
|---|---|---|
| `Booking`, `BookingStatus` | `kernel.booking` | the booking state machine; written by booking, payments and admin |
| `BookingCode`, `BookingPolicy`, `BookingSeat`, `RefundPolicy` | `kernel.booking` | pure rules shared by booking, payments, admin |
| `SeatHold`, `HoldStatus` | `kernel.booking` | hold rules; used by booking, the sweep and show cancel |
| `Payment`, `PaymentStatus` | `kernel.payment` | payment transitions; booking (checkout), payments (webhook), admin (refunds) |
| `SeatId` | `kernel` | value object used everywhere |
| `SeatLayout`, `Screen`, `Show`, `ShowFormat`, `ShowStatus`, `Movie`, `Certificate`, `Cinema`, `City` | `kernel.catalog` | validating records: catalog reads them, admin writes them, booking prices seats from them; one set of invariants |
| `Role`, `User` | `kernel.account` | account invariants (email normalisation, roles); accounts and admin |
| `DomainException` | `kernel` | stable error codes mapped by `web.Problems` |
| `Page` | `kernel` | paging value, no I/O |
| `package-info` | replaced | new `kernel` package docs |

### `domain.port` to `ports` (or deleted)

| Class | Target | Reason |
|---|---|---|
| `UnitOfWork`, `Repositories` | deleted, replaced by `ports.Db` | per-entity accessors are ceremony; `Db` + slice SQL is the seam |
| `CatalogRepositories` | deleted; SQL split to `features.catalog`, `features.live`, `features.booking`, `features.admin` | no port per entity |
| `BookingRepositories` | deleted; SQL split to `features.booking`, `features.payments`, `features.accounts` (users), `features.admin` | no port per entity |
| `AdminRepositories` | deleted; SQL to `features.admin` (writes, reports, owners, roles) | no port per entity |
| `SeatLockRepository` | deleted; SQL to `features.booking`, `features.catalog` and `features.live` (reads), `features.admin` (show cancel) | no port per entity |
| `SeatLock` | `kernel.booking` | `isLiveAt` is a rule the seat map and holds share |
| `PaymentGateway` | `ports` | real outbound I/O |
| `SeatEvent`, `SeatEventPublisher` | `ports.SeatEvents` (event types stay records in it) | real I/O (fan-out to streams) |
| `LiveSeats` | `ports` | the SSE subscription seam |
| `PasswordHasher` | `ports` | crypto library seam |
| `IdGenerator` | `ports.Ids` | randomness seam for deterministic tests |
| `RefreshTokenStore` | `ports` | persistence outside the request transaction |
| `IdempotencyStore` | gone in v0.4.1 | Jolt's `in.jolt.IdempotencyStore` SPI replaced it; `adapters.db.SqlIdempotency` implements it and `Main` hands it to `web.Idempotent`. Not a port: ports never import Jolt |
| new: `Db` (+ `Tx`) | `ports` | the transaction seam, with `afterCommit` |

### `application` to `features`

| Class | Target | Reason |
|---|---|---|
| `CatalogQueries` | split: `features.catalog` (cities, movies, cinemas, shows, seat map), `features.live` (show + snapshot reads); seat-map assembly to `kernel.catalog.SeatMap` | live needs the snapshot without importing catalog; the assembly (layout + locks + prices) is pure |
| `Accounts` | `features.accounts` | register, login, find; `ensureAdmin` is called by `Main` |
| `CreateHold`, `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries`, `ExpireSweep` | `features.booking` | holds and bookings; `Main` schedules `ExpireSweep` |
| `Outcome` | `features.booking` | only booking use cases commit-then-fail (lazy expiry) |
| `BookingOps` | split: `ownedBooking` to `features.booking`; `apply`/`expire` copied into each slice that moves bookings (booking, payments, admin) | about 30 lines; slices never import each other, and the rule itself is in `kernel.booking.Booking` |
| `EventBuffer` | deleted | replaced by `ports.Db.Tx.afterCommit` |
| `ProcessPaymentWebhook`, `MockGatewayPayments` | `features.payments` | webhook and mock checkout |
| `WebhookVerifier` | gone in v0.4.1 | Jolt's `Webhooks.hmacSha256` middleware verifies signatures in the web pipeline |
| `MockGatewayPayments.Sender` | `ports.GatewayCallback` | the mock gateway's real HTTP call back to the app |
| `AdminCatalog` | `features.admin` | catalog writes for admins and owners; mostly transaction scripts |
| `AdminReports` | `features.admin` | reports and stats |
| `CancelShow` | `features.admin` | an admin or owner action; refunds through `ports.PaymentGateway` |
| `UserAdmin` | `features.admin` | roles and owner links |
| `CinemaScope` | rule to `kernel.account.CinemaScope`, link lookup SQL to `features.admin` | the rule (all cinemas, or these ids) is pure; the lookup is I/O |
| `package-info` | deleted | |

### `web`

| Class | Target | Reason |
|---|---|---|
| `AuthModule`, `JoltRefreshStore` | `features.accounts` | auth routes and their Jolt token-store bridge |
| `CatalogModule` | `features.catalog` | |
| `LiveModule` | `features.live` | |
| `BookingModule` | `features.booking` | |
| `PaymentModule` | `features.payments` | |
| `AdminModule`, `OwnerModule` | `features.admin` | one slice, two route classes (`/api/admin`, `/api/owner`) |
| `Dtos` | split per slice, then replaced by `api.generated` records (integration lane until split) | the wire types of each slice; shared shapes via `api.generated` |
| `Money` | `kernel.Money` | paise formatting, pure |
| `ApiException`, `Problems`, `Principal`, `Query`, `Paging`, `Idempotent` | stay in `web` (shared HTTP kit) | cross-cutting HTTP plumbing every slice uses; not a feature (v0.4.1 replaced `Guards`, `PageRequest`, `PageResult`, `IdempotencyFilter` with these and Jolt) |
| `CatalogCache` | stays in `web` (integration lane) | the 60 s Jolt `TtlCache` on public catalog GETs; catalog reads and every catalog write (admin) share it, so no single slice owns it |
| `Permissions`, `Rbac` | stay in `web` (integration lane) | the one RBAC table, tied to RBAC.md by `RbacMatrixTest` |
| `WebModules` | split in wave 2 step 0: the middleware chain is `web.HttpPipeline`; `WebModules.routes` (route registration) moves to `Main` in wave 6 | wiring slices together is composition |
| `HttpPipeline` (new in step 0) | stays in `web` (integration lane) | the SPEC 9 chain, RBAC gate, problems and idempotency that every route, legacy or slice, runs behind |
| `WebDeps` | deleted in wave 6 (carries `ports.Db` since step 0) | ceremony: `Main` passes each slice only the ports it uses |
| `package-info` | updated | |

### `adapters`

| Class | Target | Reason |
|---|---|---|
| `db.SqliteUnitOfWork` | `adapters.db.SqliteDb` implementing `ports.Db` | same transaction semantics (BEGIN IMMEDIATE), plus after-commit callbacks |
| `db.SqlRepositories` | deleted | accessor ceremony |
| `db.SqlCatalog`, `db.SqlBookings`, `db.SqlAdmin`, `db.SqlSeatLocks` | split into the slices' SQL (see `domain.port`), then deleted | SQL lives with the feature that runs it |
| `db.Json` | `kernel.Json` | JDK-only codec for JSON columns that several slices read |
| `db.Sqls` | folded into `ports.Db` row helpers | instant and list conversions every slice's SQL needs |
| `db.Sqlite`, `db.SchemaRunner` | stay in `adapters.db` | connection setup and migrations |
| `db.SqlRefreshTokens`, `db.SqlIdempotency` | stay in `adapters.db` | adapters of `ports.RefreshTokenStore` and of Jolt's `IdempotencyStore` SPI |
| `auth.Pbkdf2PasswordHasher` | stays | adapter of `ports.PasswordHasher` |
| `ids.SecureIds` | stays | adapter of `ports.Ids` |
| `live.LiveSeatHub` | stays | one Jolt `Hub` behind both `ports.SeatEvents` and `ports.LiveSeats` (v0.4.1 folded `SeatEvents` into it) |
| `payment.MockPaymentGateway` | stays | adapter of `ports.PaymentGateway` |
| `payment.WebhookSigner` | stays | the mock gateway's signer (Jolt verifies, it has no signer) |
| `payment.MockGatewayClient`, `payment.MockGatewaySender` | stay | adapter of `ports.GatewayCallback` |
| `package-info` files | stay | |

### Tests

| Test | Target |
|---|---|
| `domain.*Test` | `kernel` with the classes |
| `application.*Test` | the slice of the use case; rewritten onto SQLite where they used `FakeWorld` repositories |
| `web.*ApiTest` | the slice of the routes; `WebKit` becomes a per-slice harness on SQLite plus port fakes |
| `adapters.db.*RepositoriesTest` | deleted with their repository once the slice SQL has its own tests |
| `contract.*` | stay; `RepositoriesContract` is deleted with `Repositories` and replaced by a `ports.Db` contract |
| `ArchitectureTest` | deleted in wave 6, when `TargetArchitectureTest` covers everything |

## Hot spots: integration lane only

Slice lanes never edit these. They hand the integration lane a short note ("register `CatalogRoutes`, delete `CatalogModule`, `Dtos.CityDto` ...") and the integration lane applies it in one commit per slice:

- `Main`, `AppConfig`, `WebModules`, `web.HttpPipeline`, `web.CatalogCache`
- the rest of the shared `web` kit (`Query`, `Paging`, `Principal`, `ApiException`, `Problems`, `Idempotent`): slices use it, never edit it
- `kernel` and `ports`, except new files a brief hands to a lane (wave 2: `kernel.catalog.SeatMap`)
- the shared test kit in `src/test/java/dev/thiru/bms/testing` (`TestDb`, `SliceKit`, `MutableClock`, `PlainHasher`, `MemoryRefreshTokens`, `FakeLive`, and since wave 3 `SequentialIds`, `RecordingGateway`, `RecordingEvents`)
- `web.Permissions`, `web.Rbac` and `docs/RBAC.md`
- `src/main/resources/db/migrations/*` (one numbering sequence)
- `pom.xml`, `api/openapi.yaml`, `docs/SPEC.md`
- `TargetArchitectureTest`, `RecordGeneratorTest.ALLOWED_DUPLICATES`

## Waves

Each wave starts from a green `mvn -B verify` on `integration`, and ends green with the guards above. Move code with history (`git mv` first, edit after, separate commits). Do not start wave 1 until `feat/jolt-0.4.1` and `feat/react-ui` have merged, because wave 1 renames every package they touch.

| Wave | Lane(s) | Scope | Done when |
|---|---|---|---|
| 0 | foundation | baseline, target rules, port contracts, schema, codegen, this plan | **done** (merged onto `integration` with v0.4.1) |
| 1 | integration, alone | `domain` to `kernel` (package moves only); surviving port interfaces to `ports`; add `ports.Db` + `Tx.afterCommit` and `adapters.db.SqliteDb` with a `Db` contract test; replace `ArchitectureTest`'s `domain` rule with the kernel rule; `web.Money` to `kernel.Money` | **done**, see [Wave 1 record](#wave-1-record) |
| 2 | catalog+live, accounts (parallel) | `features.catalog` (public catalog, seat map; `kernel.catalog.SeatMap`), `features.live` (SSE), `features.accounts` (auth, me, `JoltRefreshStore`) | **done**, see [Wave 2 record](#wave-2-record) |
| 3 | booking, alone | `features.booking`: holds, bookings, checkout, cancel, ticket, `ExpireSweep`, `Outcome`, owned-booking SQL; events via `afterCommit` | **done**, see [Wave 3 record](#wave-3-record). `BookingModule`, `CreateHold`, `ExpireSweep` deleted; `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries`, `Outcome` kept as test fixtures until wave 5; `EventBuffer` and `BookingOps` stay until waves 4 and 5 |
| 4 | payments | `features.payments`: webhook, mock gateway, their booking/payment transition SQL | **done**, see [Wave 4 record](#wave-4-record). `PaymentModule`, `ProcessPaymentWebhook`, `MockGatewayPayments` and the last `application` fixtures deleted; `application` holds only `package-info` |
| 5 | admin | `features.admin`: catalog writes, reports, stats, users and roles, owner links, show cancel, `CinemaScope` lookup | **done**, see [Wave 5 record](#wave-5-record) (ran before wave 4; `application.CancelShow` stays as a wave 4 test fixture) |
| 6 | two lanes, then integration | see [Wave 6 brief](#wave-6-brief-retire-the-legacy-layer): delete the repository layer, `FakeWorld`, `WebKit`, `WebDeps`, `ArchitectureTest` and `application`; codegen in `generate-sources`, with slices on the generated records; SPEC 10 done; re-measure BASELINE | `src/main/java` has only root, `kernel`, `ports`, `adapters`, `web`, `features` |

Wave order reasoning: read-only slices first (no shared writes, lowest risk, proves the pattern and `ports.Db`); booking before payments because payments reuses booking's transitions; admin last because it touches the most tables and depends on every earlier slice's SQL being settled.

## Wave 1 record

Done on `feat/restructure-wave1`, with no behaviour change: no schema edit, `ApiSchemaTest`, every contract suite and both `EndToEndTest` journeys green; 512 tests (495 after the wave 0 merge). The packaged app was smoke-tested (index, health, register, login, hold, booking, checkout, pay, ticket, admin stats, owner route refused for an audience member).

- **Moves** (renames in one `git mv` commit, edits in the next, so `git log --follow` works; the rename commit alone does not compile, the pair is green): 27 files into `kernel`: the 25 of `domain` (with `package-info`), `SeatLock` from `domain.port` and `Money` from `web` (`kernel` 5, `kernel.booking` 9, `kernel.catalog` 9, `kernel.account` 2, `kernel.payment` 2); 7 port interfaces to `ports` (`PaymentGateway`, `SeatEvent`, `LiveSeats`, `PasswordHasher`, `RefreshTokenStore`, `Ids` from `IdGenerator`, `SeatEvents` from `SeatEventPublisher`); 9 test classes followed.
- **Kept in place, by design:** the repository ports (`UnitOfWork`, `Repositories`, `CatalogRepositories`, `BookingRepositories`, `AdminRepositories`, `SeatLockRepository`) stay in `domain.port` until wave 6. `ArchitectureTest` holds them to kernel, ports and the JDK, and `TargetArchitectureTest` forbids `features.*` from importing `domain.*` or `application.*`, so no slice can lean on them.
- **Deviations from the class map:** `SeatEvent` stays its own file next to `ports.SeatEvents` (nesting it would have touched every use for no gain). `MockGatewayPayments.Sender` is not yet `ports.GatewayCallback`; it moves with the payments slice in wave 4.
- **New:** `ports.Db` (`inTransaction`, `Tx.query/queryOne/update/insert/batch/afterCommit`, `Db.ConstraintViolation`, `millis/instant/marks` helpers) and `adapters.db.SqliteDb` on `Db.inTransaction(TxMode.IMMEDIATE, ...)`, held to `contract.DbContract` (12 checks). There is no fake, because slice tests run on SQLite. `SqliteDb` is not wired into `Main` yet; wave 2 wires it.
- **Prepared for wave 2:** `web.Query`, `web.Paging`, `web.Principal` and `CatalogCache.get` are public; `testing.TestDb` gives slice tests a migrated, seeded SQLite behind `ports.Db`.
- **Closed since:** AGENTS.md rule 3 now names `kernel`, `ports`, the slices and the legacy `domain.port` (`ec925f7`).
- **Guards:** `TargetArchitectureTest` now asserts that the real `kernel` (27 files) and `ports` (9 files) exist. It also copies the real tree, adds one bad import per rule and expects exactly those violations, so a rule that stopped matching real code fails the build.

## Wave 2 record

Done on `feat/restructure-wave2`. Both lanes were merged (`feat/slice-catalog-live` and `feat/slice-accounts`, each cut from step 0 at `229abe5`), then step 3 was applied in one commit per lane. No schema edit. `ApiSchemaTest`, both `EndToEndTest` journeys, `RbacTest`, `RbacMatrixTest`, `RecordGeneratorTest`, the architecture tests and every contract suite are green: **567 tests** (523 at step 0).

The packaged app was smoke-tested with `STATIC_DIR` unset:

- index, health, and cities (`ETag`, `Cache-Control`, 304)
- register, login, refresh and `/api/me`
- hold, booking, checkout, pay and ticket
- admin stats, and owner cinemas refused (403) for an audience member
- the SSE snapshot
- the seat map `ETag` unchanged, and still a 304, after a restart on the same database

**Step 3, lane A** (`2d590f1`):

- `WebModules.routes` mounts `CatalogRoutes` and `LiveRoutes`. `LiveRoutes` also takes `BookingPolicy`, for the snapshot's `holdTtlSeconds`, which `LiveModule` got through `CatalogQueries`. This is a deviation from the brief.
- Deleted: `CatalogModule` (131 lines), `LiveModule` (100), `CatalogQueries` (309) and 13 `Dtos` records (162).
- Deleted tests: `CatalogQueriesTest` (267), `CatalogApiTest` (157) and `LiveSseTest` (102).
- `WEB_RECORDS` points the catalog schemas at `CatalogDtos`. It also points `Price`, `Seat`, `SeatRow` and `SeatMap` at live's copies.

**Step 3, lane B** (`34aeae0`):

- `WebModules.routes` mounts `AuthRoutes.of(...)`. The `accounts`/`tokens` wiring and `claimsFor` left `WebModules`.
- `Main` seeds the admin through the slice's `Accounts` on `sliceDb`.
- Deleted: `AuthModule` (120), `web.JoltRefreshStore` (49), `application.Accounts` (129) and three `Dtos` records (16).
- Deleted tests: `AccountsTest` (94) and `AuthApiTest` (222). `aTokenIssuedBeforeTheRoleRenameStillCountsAsAudience` was first moved to `RbacTest`.

**Behaviour change, decided by the main session** (`e8b4c0a`):

- The seat map `version` now hashes each status by name, not by the enum constant's identity hash. The same seat state therefore keeps the same `version` and `ETag` across restarts.
- The type and range (unsigned 32 bits) are unchanged.
- `SeatMapTest` loads the kernel in a second class loader and expects the same value. It also pins one state's value, and checks that holding or booking any single seat changes it.

**Proxy diagnostics** (`07184ba`, `fe41157`):

- `AppConfig` checks `TRUST_PROXY` with Jolt's parser at load. A bad entry fails startup with a message naming the variable.
- `render.yaml` adds `fc00::/7`.
- The `::ffff:a.b.c.d/N` blocks are not valid in Jolt v0.4.1, and are not needed. Jolt folds an IPv4-mapped literal to four bytes, so `/104` exceeds 32 bits, and a mapped peer already matches the IPv4 blocks.
- At startup, `Main` logs `trustProxy=[...]`, or `trustProxy=none`.
- Once per boot, `Main` logs the first non-health request:
  - the peer address, only when untrusted
  - whether `X-Forwarded-For` and `X-Forwarded-Proto` were present
  - the proto, as `https`, `http` or `other`
  - the inferred trust decision
  - `req.scheme()`

**Deleted in wave 2:** 1 047 main lines (7 classes and 16 `Dtos` records) and 842 test lines (5 test classes; one case was moved first).

**Size** (`src/main/java` and `src/test/java`, files / lines):

| | Baseline (`0893749`) | Wave 1 (`ec925f7`) | Step 0 (`229abe5`) | Wave 2 |
|---|---:|---:|---:|---:|
| main | 111 / 10 295 | 110 / 10 349 | 111 / 10 479 | 118 / 11 222 |
| test | 58 / 9 821 | 93 / 13 836 | 100 / 14 760 | 105 / 16 029 |
| tests run | 356 | 512 | 523 | 567 |

Per package, from step 0 to wave 2:

| Package | Step 0 (files / lines) | Wave 2 (files / lines) |
|---|---:|---:|
| `application` | 19 / 1 906 | 17 / 1 468 |
| `web` | 22 / 2 948 | 18 / 2 357 |
| `features` | 0 / 0 | 11 / 1 576 (`accounts` 432, `catalog` 854, `live` 290) |
| `kernel` | 27 / 1 298 | 28 / 1 380 (adds `SeatMap`) |
| root | 2 files | 3 files (adds `ProxyDiagnostics`) |

`adapters.db` (2 740 lines), `domain.port` (386) and `ports` (217) are unchanged: `SqlCatalog`, `SqlSeatLocks` and `SqlBookings.Users` still serve admin and booking. Main code grew by 743 lines because each slice owns SQL that the legacy repositories still hold. That duplication goes in wave 6.

**What a feature costs now.** Each wave 2 slice commit touched 2 directories: its `features/<slice>` main and test packages.

| Commit | What | Files |
|---|---|---:|
| `d526543` | catalog | 5 |
| `0a2a758` | live | 5 |
| `d479c4f` | accounts | 4 |
| `73e2e84` | accounts | 4 |
| `6fc31aa` | `SeatMap` | 2 |

At baseline, the owner feature touched 14 directories in 4 commits.

## Wave 3 record

Done on `feat/restructure-wave3`, from `integration` at `170dc99`, the wave 2 merge.

- `feat/slice-booking` was merged with `--no-ff` (`90b80c4`). It holds 5 commits: the slice, the three test fakes, the brief's constructor order, the ported `BookingQueries` cases with the sweep racing the buyers, and the hand-back `docs/restructure/handback-booking.md`.
- Step 3 was then applied with **option (a)**, decided by the main session (`28e62c5`).
- `SliceKit` gained the port fakes (`79a4cac`).

There was no schema edit. These are green: `ApiSchemaTest`, both `EndToEndTest` journeys, `RbacTest`, `RbacMatrixTest`, `RecordGeneratorTest`, `ProcessPaymentWebhookTest`, `CancelShowTest`, the architecture tests and every contract suite. **616 tests** (567 after wave 2).

**Step 3** (`28e62c5`):

- `WebModules.routes` mounts `BookingRoutes(deps.db(), deps.publisher(), deps.ids(), deps.clock(), deps.policy(), deps.gateway(), idempotent, pipeline.holdLimit())`, and the `BookingQueries bookings` line is gone.
- `Main` schedules `features.booking.ExpireSweep` on `sliceDb`, with `live` as the events port.
- `WEB_RECORDS` points `Hold`, `ShowInfo`, `BookingSeat`, `Booking`, `Checkout`, `HoldRequest` and `BookingRequest` at `features.booking.BookingDtos` (replace).
- **Deleted:** `web.BookingModule` (181 lines), `application.CreateHold` (110), `application.ExpireSweep` (61) and the `Dtos` booking block (71: `HoldDto`, `ShowInfoDto`, `BookingSeatDto`, `BookingDto`, `CheckoutDto`).
- **Deleted tests:** `application.CreateHoldTest` (153), `application.ExpireSweepTest` (96) and `adapters.db.ConcurrentBuyersTest` (171). Their cases are in `features.booking`. The race now runs on `SqliteDb`, with the sweep running alongside the 200 buyers.
- **Kept as test fixtures until wave 5**, each with a Javadoc note: `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries` and `Outcome`, with their own tests. The brief's full deletion list was tried on a scratch merge first: main code compiled, but `AdminReportsAndQueriesTest` (24 errors), `CancelShowTest` (16) and `ProcessPaymentWebhookTest` (6) use these classes to build their state.
- `EventBuffer` and `BookingOps` stay for `ProcessPaymentWebhook` (wave 4) and `CancelShow` (wave 5).

**`SliceKit` fakes** (`79a4cac`): `ids()`, `gateway()` and `events()` return one `SequentialIds`, `RecordingGateway` and `RecordingEvents` per kit. Each fake is held to its contract suite (`IdGeneratorContract`, `PaymentGatewayContract`, `SeatEventPublisherContract`). `BookingRoutesTest` takes them from the kit, and the existing kit behaviour is unchanged.

**Equivalence.** Before step 3, legacy `BookingModule` and `BookingRoutes` ran the same 121-request script on identical V2 databases. There were 0 differences in status, body (with `requestId` normalised) and headers. The 63 seat events were identical, the refunds identical, and the final rows identical. See the hand-back, section 4.

**Smoke test.** The packaged app (`java -cp "target/lib/*;target/bookmyshow-jolt-*.jar"`) ran with `STATIC_DIR` unset, `PORT=18098`, a temp DB, the admin env vars, `MOCK_GATEWAY=true` and `TRUST_PROXY=127.0.0.1`, after `npm run build` in `web/`. Results:

- index 200 (`Houselights`), health `UP`
- register 201, login
- hold 201 (server price 66000), and its replay 201 with `Idempotency-Replayed: true` and the same body
- booking 201 `HELD`, checkout 201 (mock gateway URL)
- pay through `/mock-gateway/:id/complete` 303, then the booking `CONFIRMED`
- ticket 200 `text/html` with the seats and `INR 660.00`
- a second booking three days out, paid, then cancelled: 200 `REFUNDED` with a full refund (18000 of 18000)
- admin stats 200
- owner cinemas as an audience member: 403

**Deleted in wave 3:** 423 main lines (3 classes and 5 `Dtos` records) and 420 test lines (3 test classes).

**Size** (`src/main/java` and `src/test/java`, files / lines):

| | Baseline (`0893749`) | Wave 2 | Wave 3 |
|---|---:|---:|---:|
| main | 111 / 10 295 | 118 / 11 222 | 126 / 12 241 |
| test | 58 / 9 821 | 105 / 16 029 | 117 / 17 445 |
| tests run | 356 | 567 | 616 |

Per package, from wave 2 to wave 3:

| Package | Wave 2 (files / lines) | Wave 3 (files / lines) |
|---|---:|---:|
| `application` | 17 / 1 468 | 15 / 1 323 |
| `web` | 18 / 2 357 | 17 / 2 088 |
| `features` | 11 / 1 576 | 22 / 3 009 (`booking` 1 433) |
| test `testing` | 11 files | 17 / 1 031 (3 fakes and 3 contract tests) |

`kernel` (1 380), `ports` (217), `adapters.db` (2 740) and `domain.port` (386) are unchanged. Main code grew by 1 019 lines. That is mostly `BookingSql` (608), which duplicates the parts of `SqlBookings` and `SqlSeatLocks` that payments and admin still use until wave 6, plus the five fixture use cases (about 340 lines) that wave 5 deletes.

**Open items for wave 4:**

1. **Gateway calls inside the IMMEDIATE transaction.** `Checkout` calls `PaymentGateway.createSession`, and `CancelBooking` calls `refund`, inside `BEGIN IMMEDIATE`. So do the webhook's full refund and `CancelShow`. This keeps "a failed refund rolls the cancel back", but a slow real gateway would hold SQLite's only writer lock for the length of an HTTP call. Wave 4 must either accept this for the mock gateway, recording the decision here, or design an outbox: commit the state change with a pending refund, call the gateway after commit, and settle it in a second transaction. Nothing changes behaviour until a real gateway is planned.
2. **No contract covers a refund failure.** `PaymentGatewayContract` checks sessions and negative amounts, but not that a failing refund throws. The booking slice's cancel rollback, the webhook's late-success refund and `CancelShow` all depend on that. `MockPaymentGateway` never fails, so only `RecordingGateway.failRefunds` exercises it. Wave 4 should add the rule to the contract, with a way for each implementation to be made to fail, or record why the mock is exempt.

## Wave 4 record

Done on `feat/restructure-wave4`, from `integration` at `50f1988` (wave 5 integrated), after wave 5.

- `feat/slice-payments` was merged with `--no-ff` (`351d3df`). It was already rebased onto `50f1988`.
- It holds step 0 (`858b00c`, `4c821ca`), the refund-failure clause (`0526e79`), the slice (`f7b8a8c`), the webhook-versus-sweep race (`32eb157`), and ADR 0002 with the hand-back (`3ebaf74`, `docs/restructure/handback-payments.md`).
- Step 3 ran in one commit (`f974622`).

No schema edit. These are green: `ApiSchemaTest`, both `EndToEndTest` journeys (the payment journey over a real socket through `MockGatewaySender` into `PaymentRoutes`), `MainTest`, `RbacTest`, `RbacMatrixTest`, `ApiCoverageTest`, `RecordGeneratorTest`, `AdminRoutesTest`, `OwnerRoutesTest`, the architecture tests and every contract suite, `GatewayCallbackContract` included. **623 tests** (620 at `50f1988`, 657 on the lane branch before the deletions).

**Step 0** (done in the lane, at the main session's request):

- `ports.GatewayCallback` holds the event record, with `type` as the wire string, and `int send(Event) throws Exception`.
- `adapters.payment.MockGatewaySender` implements it and no longer imports `application`. `WebDeps.mockSender` is a `GatewayCallback`.
- `MockGatewayPayments` kept compiling through a bridge constructor. `WebModules` was not touched.
- `contract.GatewayCallbackContract` checks that the receiver's status comes back as is, a rejection is a status rather than an exception, and a failed delivery throws. It runs against `MockGatewaySender` over a real HTTP stub, and against `testing.RecordingCallbacks`, exposed as `SliceKit.callbacks()`.

**The slice** (`features.payments`, 6 files, 632 lines):

- `PaymentRoutes(Db, PaymentGateway, SeatEvents, Ids, Clock, Middleware signature, GatewayCallback mockSender, JsonMapper)` registers the mock routes only when `mockSender` is not null.
- `ProcessWebhook` is `ProcessPaymentWebhook` ported rule for rule: dedup, expiry re-check, fail or mismatch, confirm only when the booking owns all its locks (otherwise expire), and a full refund for a late success.
- `MockGateway`, `PaymentSql`, a local `BookingOps` (versioned update with one retry) and `PaymentDtos`.
- Seat events leave only through `Tx.afterCommit`.

**Tests** (28):

- `ProcessWebhookTest` (15) is the port of `ProcessPaymentWebhookTest`.
- `PaymentRoutesTest` (9) holds `BookingApiTest`'s webhook and mock gateway cases, plus a payment through the mock page into the signed webhook.
- `MockGatewayTest` (3) holds the copied `AdminReportsAndQueriesTest` case.
- `WebhookSweepRaceTest` runs 10 rounds on virtual threads, with the sweeper's clock 8 minutes ahead.
  - Every round ends `CONFIRMED` with no refund, or `EXPIRED` with a full refund and the seats free again. Runs split about evenly.
  - No seat is locked twice.

**Equivalence.** Before step 3, the legacy `PaymentModule` (through the bridge) and `PaymentRoutes` ran the same script on identical V2 databases (hand-back, section 4). With the mock on it was 102 requests, with it off 6. It covered every signature and validation path, mismatches, duplicates, late success with refund, expiry, lost locks, a cancelled booking, a failing refund and its redelivery, and every mock page and completion path, including rejected and unreachable callbacks. There were 0 differences in status, body and 16 headers. The 20 seat events, 6 refunds, 6 callback events and 144 final rows were also identical.

**Open items from wave 3, settled:**

1. **Gateway calls inside the IMMEDIATE transaction.** Accepted for the mock gateway, with no behaviour change. [ADR 0002](ADR-0002-gateway-calls-in-transactions.md) records the risk with a real gateway: the writer lock is held for an HTTP call, the rollback is false when a response is lost, and a retry can refund twice. It recommends an outbox before any real gateway: record intent in the transaction, call the gateway after commit with an idempotency key, and reconcile through the webhook. That needs a SPEC, OpenAPI and schema PR first.
2. **No contract covered a refund failure.** Closed: `PaymentGatewayContract.aFailedRefundThrowsSoTheCallersTransactionRollsBack`, through `failNextRefund`. The fakes flip their outage switch. The mock has no outage mode, so it is held to the clause through an amount it rejects.

**Step 3** (`f974622`):

- `WebModules.routes` mounts `PaymentRoutes(deps.db(), deps.gateway(), deps.publisher(), deps.ids(), deps.clock(), <the Webhooks.hmacSha256 chain with WEBHOOK_TOLERANCE>, settings.mockGateway() ? deps.mockSender() : null, mapper)`. This replaces the `MockGatewayPayments mock` statement and the `PaymentModule` block.
- `WEB_RECORDS` points `WebhookEvent` and `CompleteRequest` at `features.payments.PaymentDtos` (replace). The unused `WEB` constant is gone.
- **Deleted main code** (799 lines):
  - `web.PaymentModule` (161), `application.ProcessPaymentWebhook` (153), and `MockGatewayPayments` with its bridge (109)
  - the last fixtures: `CancelShow` (78), `CreateBooking` (99), `Checkout` (82), `Outcome` (28), `BookingOps` (50) and `EventBuffer` (39)
- **Deleted tests** (811 lines): `ProcessPaymentWebhookTest` (165), `web.BookingApiTest` (367), `AdminReportsAndQueriesTest` (65, by then only the mock gateway case), `CheckoutTest` (121) and `CreateBookingTest` (93). `WebKit.sentWebhooks` is gone too.
- **Kept:** `application/package-info.java`, because `ArchitectureTest.applicationNeverImportsAdaptersWebOrInfrastructure` expects the package to exist (tried). Wave 6 deletes both.

**Smoke test.** After `npm ci` and `npm run build` in `web/`, the packaged app (`java -cp "target/lib/*;target/bookmyshow-jolt-*.jar"`, with `target/lib` from `dependency:copy-dependencies`) ran with `STATIC_DIR` unset, `PORT=18100`, a temp DB, the admin env vars, `MOCK_GATEWAY=true` and `TRUST_PROXY=127.0.0.1`. Results:

- register 201, login 200
- hold 201 (36000), booking 201 `HELD`, checkout 201 (`/mock-gateway/<id>` URL)
- mock gateway page 200 (`text/html`, INR amount, a CSP header)
- pay with SUCCESS: 303 `Location: /bookings/<id>`, then the booking is `CONFIRMED`
- ticket 200 `text/html` with the booking code
- a signed webhook for the settled payment: 200 `{"status":"CONFIRMED"}`; the same event again: 200 `{"duplicate":true}`, with the booking unchanged (`CONFIRMED`, version 2)
- forged signature 401 `UNAUTHENTICATED`, stale timestamp (301 s) 401
- a second booking paid with FAILURE: 303, then the booking is `HELD` (SPEC 4: back to `HELD` for a retry until it expires), the page shows `FAILED`, and its ticket is 409 `BOOKING_STATE`
- admin stats 200 (`bookingsConfirmedToday` 1), owner cinemas as an audience member 403, health `UP`, index 200 (`Houselights`)
- no `SEVERE` line or exception in the log; the process was killed afterwards

**Flaky test, recorded for wave 6.** One full verify on the lane failed in `adapters.db.AdminRepositoriesTest.moviesRoundTripWithLanguagesAndGenres` and `CatalogRepositoriesTest.findByScreenIsOrderedByStart`, in `DbTest.openDb`, with `IllegalStateException: migration failed: [SQLITE_ERROR] ... (cannot commit - no transaction is active)`. Memory was tight at the time.

- It did not reproduce in 5 more runs of both classes, or in any later full verify.
- The prefix `migration failed:` (not `migration V<n> failed:`) shows that the `SQLException` escaped from `SchemaRunner.apply`'s `catch` or `finally`, not from its `try`. Most likely `c.rollback()` or `c.setAutoCommit(true)` (which commits) ran after SQLite had already ended the transaction, and its error replaced the first one.
- A small fix would be to add the cleanup exceptions as suppressed instead of letting them replace the original. It was not made because the failure could not be reproduced.
- `SchemaRunner` stays in production. Its two flaky callers are repository tests that wave 6 deletes, but `TestDb` and `SqliteDb`'s tests migrate through it too, so wave 6 should make that fix (see the brief).

**Size** (`src/main/java` and `src/test/java`, files / lines):

| | Baseline (`0893749`) | Wave 3 | Wave 5 | Wave 4 |
|---|---:|---:|---:|---:|
| main | 111 / 10 295 | 126 / 12 241 | 126 / 12 864 | 124 / 12 752 |
| test | 58 / 9 821 | 117 / 17 445 | 117 / 18 191 | 121 / 18 649 |
| tests run | 356 | 616 | 617 | 623 |

Per package, main, from wave 5 to wave 4:

| Package | Wave 5 (files / lines) | Wave 4 (files / lines) |
|---|---:|---:|
| `application` | 9 / 613 | 1 / 5 (`package-info`) |
| `web` | 14 / 1 287 | 13 / 1 123 |
| `features` | 30 / 5 094 | 36 / 5 726 (`payments` 632) |
| `ports` | 9 / 217 | 10 / 251 (adds `GatewayCallback`) |
| `adapters.payment` | 5 / 212 | 5 / 206 |

`kernel` (1 429), `adapters.db` (2 740) and `domain.port` (386) are unchanged. Main code is 2 457 lines above the baseline. About 2 240 lines of that are legacy code that wave 6 deletes: `domain.port` 386, `SqlRepositories`, `SqlCatalog`, `SqlBookings`, `SqlAdmin` and `SqlSeatLocks` 1 745, `SqliteUnitOfWork` 26, `WebDeps` 80. The rest of the gap is the per-slice SQL that replaced them.

## Wave 5 record

Done on `feat/restructure-wave5`, from `integration` at `ee03672` (wave 3 merged), before wave 4.

- `feat/slice-admin` was merged with `--no-ff` (`371fc1f`). It holds `kernel.account.CinemaScope`, the two ported test fakes, the slice and `docs/restructure/handback-admin.md`.
- The fakes `RecordingGateway` and `RecordingEvents` were byte-identical to wave 3's, so they merged with no change.
- Step 3 then ran in two commits.

No schema edit. These are green: `ApiSchemaTest`, both `EndToEndTest` journeys (the owner journey on the slice), `RbacTest`, `RbacMatrixTest`, `RecordGeneratorTest`, `CatalogCacheTest`, `ProcessPaymentWebhookTest`, the architecture tests and every contract suite. **617 tests** (616 after wave 3).

**The slice** (`features.admin`, 8 files, 2 085 lines):

- `AdminRoutes` serves the 22 `/api/admin` routes and `OwnerRoutes` the 7 `/api/owner` routes.
- The use cases are `AdminCatalog`, `CancelShow`, `AdminReports` and `UserAdmin`, on `ports.Db`.
- `AdminSql` holds the shared reads, the `cinema_owner` lookup, and the JSON column writers, byte for byte as the legacy adapter writes them.
- `AdminDtos` holds the records.
- Owners are scoped by `kernel.account.CinemaScope.forCaller(userId, isAdmin)`, with the links read in the request's transaction. Anything out of scope is 404.
- The pipeline still clears the public catalog cache after every successful admin or owner write.

**Equivalence.** Before step 3, the legacy modules and the slice ran the same 132-request script on identical V2 databases (hand-back, section 4). There were 0 differences in status, body and headers. The seat events, the refunds and 1 026 rows across 14 tables were also identical.

**Step 3, admin** (`f79d21f`):

- `WebModules.routes` mounts `AdminRoutes(deps.db(), deps.clock(), deps.gateway(), deps.publisher(), deps.live(), idempotent)` and `OwnerRoutes(deps.db(), deps.clock(), deps.gateway(), deps.publisher(), idempotent)`. These replace the five legacy statements.
- `WEB_RECORDS` points the 14 admin schemas and the 7 admin request records at `features.admin.AdminDtos` (replace). `City`, `Cinema` and `MovieDetail` list both the catalog and admin copies.
- **Deleted main code** (1 369 lines): `web.AdminModule` (414), `web.OwnerModule` (138) and `web.Dtos` (236, now empty: its last records were the admin ones). Also `application.AdminCatalog` (287), `AdminReports` (113), `UserAdmin` (117) and `CinemaScope` (34), and `Principal.cinemaScope()`.
- **Deleted tests** (1 130 lines), all ported to the slice:
  - `AdminApiTest`, `AdminUsersApiTest` and `OwnerApiTest`
  - `application.AdminCatalogTest`, `CinemaScopeTest`, `UserAdminTest` and `CancelShowTest`
  - the two report cases of `AdminReportsAndQueriesTest`
- `RbacTest` now keeps its own owner request helpers instead of borrowing `OwnerApiTest`'s.
- `RBAC.md` names the slice classes. Behaviour is unchanged.
- **Kept, decided by the main session:**
  - `application.CancelShow`, without its scope overload, as a test fixture for `ProcessPaymentWebhookTest` until wave 4.
  - Live streams of a cancelled show still end at their next heartbeat. Jolt issue #83 tracks closing a Hub topic.

**Step 3, booking fixtures** (`aa33e22`). Wave 3 had kept five fixtures for waves 4 and 5. Once the admin tests were gone, two of them had no remaining users:

- **Deleted:** `CancelBooking` (67 lines) and `BookingQueries` (90), with `CancelBookingTest` (158) and the two `BookingQueries` cases of `AdminReportsAndQueriesTest`. `features.booking` covers them.
- **Kept for wave 4:** `CreateBooking`, `Checkout` and `Outcome`. Their notes now name the users: `ProcessPaymentWebhookTest` and the mock gateway case, which is all that is left of `AdminReportsAndQueriesTest`. `application.CreateBookingTest` and `CheckoutTest` stay with them.

**Smoke test.** After `npm run build` in `web/`, the packaged app (`java -cp "target/lib/*;target/bookmyshow-jolt-*.jar"`) ran with `STATIC_DIR` unset, `PORT=18099`, a temp DB, the admin env vars, `MOCK_GATEWAY=true` and `TRUST_PROXY=127.0.0.1`. Results:

- **Admin:**
  - login 200
  - create city 201: `GET /api/cities` listed `pune` straight after, though the same URL had been cached a moment before
  - create movie 201, cinema 201, screen 201 (capacity 20) and show 201
  - register a user, promote to `THEATRE_OWNER` 200, link the cinema 200 (`cinemaIds [7]`)
- **Owner:**
  - my cinemas lists only cinema 7 with its screen
  - create show 201, reports 200 (total 2)
  - another cinema's screens 404 `Cinema not found`, another cinema's show report 404, `/api/admin/stats` 403
- **Audience and cancel:**
  - hold 201 (50000), booking 201, checkout 201, mock gateway pay 303, booking `CONFIRMED`
  - the owner's report showed booked 2 and revenue 50000
  - the owner cancelled the show: 200, `refunded 1`. The booking became `REFUNDED` with a 50000 of 50000 refund, and the report shows `CANCELLED`, booked 0, revenue 0, refunded 50000
- **Admin again:** self-demote 409 `You cannot remove your own ADMIN role`, stats 200, health `UP`, index 200 (`Houselights`)

**Deleted in wave 5:** 1 534 main lines (9 classes, the last of `Dtos`, and `Principal.cinemaScope()`) and 1 317 test lines (9 test classes and 4 cases).

**Size** (`src/main/java` and `src/test/java`, files / lines):

| | Baseline (`0893749`) | Wave 2 | Wave 3 | Wave 5 |
|---|---:|---:|---:|---:|
| main | 111 / 10 295 | 118 / 11 222 | 126 / 12 241 | 126 / 12 864 |
| test | 58 / 9 821 | 105 / 16 029 | 117 / 17 445 | 117 / 18 191 |
| tests run | 356 | 567 | 616 | 617 |

Per package, from wave 3 to wave 5:

| Package | Wave 3 (files / lines) | Wave 5 (files / lines) |
|---|---:|---:|
| `application` | 15 / 1 323 | 9 / 613 |
| `web` | 17 / 2 088 | 14 / 1 287 |
| `features` | 22 / 3 009 | 30 / 5 094 (`admin` 2 085) |
| `kernel` | 28 / 1 380 | 29 / 1 429 (adds `account.CinemaScope`) |

`ports` (217), `adapters.db` (2 740) and `domain.port` (386) are unchanged. Main code grew by 623 lines. The slice owns SQL that `SqlAdmin`, `SqlCatalog`, `SqlBookings` and `SqlSeatLocks` still hold for `FakeWorld` and the repository tests, and wave 6 deletes that duplicate.

**What remains.**

- **Wave 4 (payments):**
  - move `PaymentModule`, `ProcessPaymentWebhook` and `MockGatewayPayments` to `features.payments`
  - then delete the last `application` classes: `CancelShow`, `CreateBooking`, `Checkout`, `Outcome`, `BookingOps` and `EventBuffer`, with `ProcessPaymentWebhookTest`, `AdminReportsAndQueriesTest`, `application.CreateBookingTest` and `CheckoutTest`
  - also settle wave 3's two open items (gateway calls inside the write lock, and the refund-failure contract)
- **Wave 6 (integration):**
  - delete `Repositories`, `UnitOfWork`, the `domain.port` interfaces including `AdminRepositories`, `SqlRepositories`, `SqlCatalog/Bookings/Admin/SeatLocks`, their repository tests and `RepositoriesContract`, `FakeWorld`, `WebKit` (the legacy web tests that remain move to `SliceKit` first), `WebDeps` and `ArchitectureTest`
  - move generation to `generate-sources`
  - re-measure BASELINE

## Wave 2 briefs

Two lanes run in parallel, **A: catalog + live** and **B: accounts**, after **step 0** below has landed on `integration`. Read [How to build a slice](#how-to-build-a-slice) first. They share no files: each writes only under its own `features/<slice>` directories (A also adds one new kernel file). Both hand registrations and deletions back to the integration lane, which applies them in one commit per lane (**step 3**).

Common to both lanes:

- Branch from `integration` after step 0 has merged. Do not edit any hot spot.
- Allowed imports: `kernel.*`, `ports.*`, `web.{Query, Paging, Principal, ApiException, CatalogCache}`, `java.*` and Jolt (`in.jolt.*`). Never `application.*`, `domain.*`, `adapters.*` or the other lane's slice (`TargetArchitectureTest` fails the build).
- SQL goes through `ports.Db` only. Instants are `Db.millis`/`Db.instant`, IN lists `Db.marks`, and a UNIQUE clash is `Db.ConstraintViolation`.
- Response records are local to the slice (`api.generated` is not on the main class path yet) and must serialise exactly as today's `web.Dtos` records: same component names, order and types. Copy, do not import, the `Dtos` record.
- Keep every status, header, problem `code` and message identical. `ApiSchemaTest` must stay green **with no schema edit**.
- Tests: SQL and use cases on `testing.TestDb`; routes on `testing.SliceKit` (step 0). Write them before the code.
- Done when `mvn -B verify` is green on the lane branch with the old classes still in place, and the lane has handed over its step 3 note (registrations, deletions, `WEB_RECORDS` entries, tests to move or delete).

### Step 0: integration lane (done on `feat/restructure-wave2-step0`)

Done with no behaviour change: no schema edit; `ApiSchemaTest`, both `EndToEndTest` journeys and every contract suite green; 523 tests (512 before). The packaged app was smoke-tested as in wave 1.

1. **`WebModules` split.** `web.HttpPipeline.install(app, config)` registers views, problem mapping and the SPEC 9 middleware: request id, log, security headers, problems, gzip, the auth IP limits, CSRF, the RBAC gate and role layers, catalog cache clearing, and the mock gateway guards. It registers no route. It hands out `jwt()`, `catalogCache()`, `idempotent()`, `holdLimit()` and `mapper()`, and mounts modules with `module(RouteModule)`. `WebModules.install` is the pipeline plus `WebModules.routes(pipeline, deps)`, where each legacy module is one `pipeline.module(...)` line. `HttpPipelineTest` proves that a mounted route gets the gate, layers, headers and problems with no legacy module present. The mock gateway middleware now registers before the route modules; Jolt runs middleware before routing, so nothing changed.
2. **`testing.SliceKit`**, see [How to build a slice](#how-to-build-a-slice). The fakes (`PlainHasher`, `MemoryRefreshTokens`, `FakeLive`) moved from `WebKit` into `testing` with their contract tests, and `MutableClock` is new. `TestDb` migrates once per JVM into a template and copies it per test.
3. **`WebKit` runs on SQLite.** It is now `SliceKit` plus `WebModules.routes`: the legacy repositories go through `SqliteUnitOfWork` over the kit's `TestDb`, so a swapped slice and the legacy routes share one database. `FakeWorld` is only the gateway, ids, clock and publisher. **Deviation from the brief:** the web tests keep `FakeWorld.seeded()`'s catalog rather than moving to the V2 seed. `WebKit` deletes the V2 catalog and writes FakeWorld's city, cinema 2, screen 5, movie 1 and show 9 through the production repositories. The reason is that V2 schedules its shows from the day of migration, while the web tests run on the fixed `FakeWorld.T0`; moving about 80 tests onto a seed that moves with the date would make them depend on the date. Slice tests use V2 through `SliceKit`. Only the tests that inspected `FakeWorld` changed (they now use SQL), plus two expectations that follow the adapter's name ordering. Those two exposed the fake mismatch recorded in the table above.
4. **`RecordGeneratorTest.WEB_RECORDS`** is `Map<String, List<String>>`, and each record listed for a schema is checked. Record types resolve by binary name first, so two slices may both have a `CityDto`.
5. **`ports.Db` is wired:** `Main` builds `new SqliteDb(db)` (as `sliceDb`) over the same pool as `SqliteUnitOfWork`, and `WebDeps.db()` carries it.

### How to build a slice

For lane agents. The briefs below say *what* to build; this section says *how*.

**Layout** (catalog as the example; a slice may use subpackages, all of them the same slice):

```
src/main/java/dev/thiru/bms/features/catalog/
  CatalogRoutes.java     the RouteModule: the slice's whole HTTP surface
  CatalogSql.java        SQL through ports.Db (split into several *Sql classes if large)
  CatalogDtos.java       local request and response records, copied from web.Dtos
  ...                    a use case class only where there is a rule to orchestrate
src/test/java/dev/thiru/bms/features/catalog/
  CatalogSqlTest.java    SQL and use cases on testing.TestDb
  CatalogRoutesTest.java routes on testing.SliceKit
```

**Allowed imports.**

- Main code may import `kernel.*`, `ports.*`, `web.{Query, Paging, Principal, ApiException, CatalogCache}`, Jolt (`in.jolt.*`, `in.jolt.auth.*`) and `java.*`.
- It never imports `application.*`, `domain.*`, `adapters.*`, another `features.*`, `web.Dtos`, a legacy module, or `web.HttpPipeline`, `WebModules` or `WebDeps` (that is composition). `TargetArchitectureTest` enforces the first four.
- Tests may also import `testing.*`, JUnit and `in.jolt.test.JoltTest`. They never import `application.fakes.FakeWorld` or `web.WebKit`.

**Constructors are the slice's ports.** A `RouteModule` takes exactly what it uses, for example `new CatalogRoutes(Db db, Clock clock, BookingPolicy policy, CatalogCache cache)`. Route middleware (`Idempotent`, the hold limit) is passed in as an `in.jolt.Middleware`; a slice never builds its own. Domain failures are `kernel.DomainException` or `web.ApiException`. The pipeline's `Problems` maps them, so the slice never writes a problem body.

**A route test:**

```java
class CatalogRoutesTest {
    @RegisterExtension
    final SliceKit kit = SliceKit.boot(k -> List.of(
            new CatalogRoutes(k.db(), k.clock(), k.policy(), k.pipeline().catalogCache())));

    @Test
    void citiesAreCacheable() {
        kit.http().get("/api/cities").expect(200)
                .jsonEquals("0.id", "bengaluru")
                .header("Cache-Control", "public, max-age=60");
    }
}
```

`SliceKit` reference:

- **Boot:** `SliceKit.boot(k -> List.of(...))`, or `SliceKit.builder()` with `.routes(fn)`, `.mount(kit -> ...)`, `.dir(@TempDir)`, `.clock(c)`, `.mockGateway(bool)`, `.liveStreams(n)` and `.viewsDir(p)`, then `.boot()`. As a `@RegisterExtension` field it closes after each test. Otherwise use try-with-resources.
- **Pieces:**
  - `db()` is `ports.Db`, and `testDb()` the `TestDb` behind it.
  - `clock()` is a `MutableClock` (`mutableClock().advance(d)`).
  - `policy()`, `hasher()` (`PlainHasher`), `refreshTokens()`, `idempotency()` (Jolt in-memory) and `live()` (`FakeLive`, cap 5) are the port fakes.
  - `app()`, `pipeline()` and `http()` (`JoltTest`) are the web side.
- **Accounts:**
  - `audience(email)`, `admin()` and `owner(email, cinemaIds...)` insert the account (and the `cinema_owner` rows) and return an access token signed as the auth routes sign it.
  - `createUser(email, roles...)` and `token(id)` are the lower-level forms, and `idOf(email)` looks up an id.
  - `SliceKit.bearer(token)` builds the header value.
  - Every account's password is `SliceKit.PASSWORD`, stored through `PlainHasher`, so a slice login works.
  - For the auth IP limits, pass `.remoteAddress(kit.nextAddress())`. `key()` gives a fresh Idempotency-Key.
- **Seed facts (V2):**
  - Cities `bengaluru`, `chennai` and `mumbai`; cinemas 1 to 6; screens 1 to 14; movies 1 to 12; no users.
  - Shows on the 14 days from `seedDate()`, at 10:00, 13:30, 17:00 and 20:30 IST, plus 23:15 on even screens.
  - The clock starts at 08:00 IST on `seedDate()`, so every seeded show is upcoming. Build `date=` queries from `kit.seedDate()`, never from a literal.
- **The gate is real.** A path missing from `Permissions` is a 404. Every lane A and B path is already in the table.

A SQL test opens `TestDb.open(dir)` on a `@TempDir` and closes it after the test (see the `TestDb` Javadoc).

**Registering in the pipeline** (the integration lane does this at step 3). In `WebModules.routes`, the legacy line becomes the slice's:

```java
pipeline.module(new CatalogModule(catalog, pipeline.catalogCache()));
// becomes
pipeline.module(new CatalogRoutes(deps.db(), deps.clock(), deps.policy(), pipeline.catalogCache()));
```

`deps` holds every port: `db()`, `uow()` (legacy), `clock()`, `policy()`, `ids()`, `hasher()`, `refreshTokens()`, `idempotency()`, `gateway()`, `publisher()`, `live()` and `mockSender()`. `deps.settings()` holds the static settings, and `pipeline` the shared HTTP pieces. If a slice needs something that is in neither, ask the integration lane. Never widen `WebDeps` yourself.

**Proving the swap before handing back.** Apply the swap line(s) in your working tree only, then run `mvn -B verify`. The legacy web tests (`CatalogApiTest`, `AuthApiTest`, `AdminUsersApiTest`, ...) then run against your routes on the same database. Revert with `git checkout -- src/main/java/dev/thiru/bms/web/WebModules.java` and report the result. Never commit a hot spot.

**Hand back** one note, in your final report:

1. The exact swap line(s) for `WebModules.routes` and for `Main`, if any, written against the code as it stands.
2. The classes and tests to delete, and the `Dtos` records to delete.
3. The `WEB_RECORDS` entries, as `schema -> binary name` (for example `City -> dev.thiru.bms.features.catalog.CatalogDtos$CityDto`), each marked *replace* or *add alongside*.
4. The legacy web tests to move or delete, and the result of the swap proof above.
5. Any port behaviour the slice relies on that no contract covers.

**Commands** (in your worktree, one build at a time):

```bash
export MAVEN_OPTS="-Xmx448m"; export JAVA_TOOL_OPTIONS="-Xmx448m -XX:+UseSerialGC"   # 384m on native OOM
mvn -B test -Djacoco.skip=true -Dtest='dev/thiru/bms/features/catalog/**/*Test.java'   # your slice
mvn -B test -Djacoco.skip=true -Dtest='TargetArchitectureTest,ArchitectureTest,RecordGeneratorTest,ApiSchemaTest,ApiCoverageTest,RbacMatrixTest,EndToEndTest'
mvn spotless:apply
mvn -B verify   # green before every commit (tests, spotless:check, jacoco)
```

### Lane A: catalog + live (`feat/slice-catalog-live`)

**Owns** (new files only):

- `src/main/java/dev/thiru/bms/features/catalog/`: `CatalogRoutes` (a `RouteModule` for `GET /api/cities`, `/api/movies`, `/api/movies/:id`, `/api/movies/:id/shows`, `/api/cinemas`, `/api/cinemas/:id`, `/api/shows/:id`, `/api/shows/:id/seats`), `CatalogSql` (reads of `city`, `movie`, `cinema`, `screen`, `show` and live `seat_lock` rows, from `SqlCatalog` and `SqlSeatLocks.findByShow/countLive`), and local records `CityDto`, `MovieSummary`, `MovieDetail`, `CinemaDto`, `ScreenSummary`, `CinemaDetailDto`, `SlotDto`, `CinemaShowsDto`, `ShowTimesDto`, `MovieRef`, `NamedRef`, `PriceDto`, `ShowDetailDto`, `SeatDto`, `SeatRowDto` and `SeatMapDto`, plus the movie filter and sort parsing.
- `src/main/java/dev/thiru/bms/features/live/`: `LiveRoutes` (`GET /api/shows/:id/events`), `LiveSql` (the show's status and start time, and the seat map snapshot reads) and its own `SeatMapDto` copy.
- `src/main/java/dev/thiru/bms/kernel/catalog/SeatMap.java` (plus `SeatStatus` if needed): the pure assembly of layout, live locks and prices into rows, categories and the content-hash `version`, taken from `CatalogQueries.seatMap`, so catalog and live share the rule without importing each other. Test: `src/test/java/dev/thiru/bms/kernel/catalog/SeatMapTest.java`.
- Tests: `src/test/java/dev/thiru/bms/features/catalog/**` and `features/live/**`, porting the cases of `CatalogQueriesTest`, `CatalogApiTest` and `LiveSseTest` to `SliceKit` and the V2 seed. Take every date from `kit.seedDate()`. `LiveRoutes` tests drive `kit.live()` (`push`, `endAll`, `subs`) as `LiveSseTest` does, with a short heartbeat.

**Preserve exactly:**

- Catalog reads: weak `ETag` with 304 on a match, and `Cache-Control: public, max-age=60` through `CatalogCache.get(req, ...)` for cities, movies and show times.
- Seat map: `ETag` and `Cache-Control: no-cache`.
- Validation: the 422 for a missing `city` on show times and for a bad `sort` value, and `Paging` errors.
- Live stream: a missing show is a 404 before the stream opens; a full stream cap is a 503 `UNAVAILABLE`. The subscription is taken before the snapshot is read. The stream sends `retry: 3000`, then a `snapshot` with no id, then hub events with their ids, a `heartbeat` every interval, and ends at show start or on cancellation (no pump at all for a show that is already cancelled).

**Hands back** (step 3):

- In `WebModules.routes`, replace `pipeline.module(new CatalogModule(catalog, pipeline.catalogCache()))` with `pipeline.module(new CatalogRoutes(deps.db(), deps.clock(), deps.policy(), pipeline.catalogCache()))`, and `pipeline.module(new LiveModule(catalog, deps.live(), deps.clock(), settings.sseHeartbeat(), mapper))` with `pipeline.module(new LiveRoutes(deps.db(), deps.live(), deps.clock(), settings.sseHeartbeat(), mapper))`. Then delete the `CatalogQueries catalog = ...` line, which only those two modules use.
- Delete `web.CatalogModule`, `web.LiveModule`, `application.CatalogQueries` and `application.CatalogQueriesTest`.
- Delete the `Dtos` records no one else uses: `MovieSummary`, `CinemaDetailDto`, `ScreenSummary`, `SlotDto`, `CinemaShowsDto`, `ShowTimesDto`, `ShowDetailDto`, `SeatDto`, `SeatRowDto`, `SeatMapDto`, and also `MovieRef`, `NamedRef`, `PriceDto`: checked at step 0, only `ShowDetailDto` and `SeatMapDto` use those three, and no booking record does. Keep `CityDto`, `CinemaDto` and `MovieDetail` for `AdminModule`. `Dtos` then no longer imports `CatalogQueries`.
- In `WEB_RECORDS`, point `MovieSummary`, `CinemaDetail`, `ScreenSummary`, `Slot`, `CinemaShows`, `ShowTimes`, `ShowDetail`, `Seat`, `SeatRow`, `SeatMap`, `MovieRef`, `NamedRef` and `Price` at the slice records (replace), and add the slice records for `City`, `Cinema` and `MovieDetail` alongside the `Dtos` ones. List `SeatMap` twice, once for catalog's record and once for live's copy.
- Move or delete `CatalogApiTest` and `LiveSseTest` once their cases live in the slice. Since step 0 both run on SQLite, so after the swap they exercise the slice's routes. Prove that before handing back (see [How to build a slice](#how-to-build-a-slice)). `CatalogCacheTest` stays in `web`, and must pass against the swapped `CatalogRoutes` unchanged.

**Must stay green:** `CatalogCacheTest`, `HttpPipelineTest`, `SliceKitTest`, `RbacTest`, `RbacMatrixTest`, `ApiCoverageTest`, `ApiSchemaTest`, `RecordGeneratorTest`, `EndToEndTest` (both journeys), `TargetArchitectureTest`, `ArchitectureTest`, `LiveSeatsContract` (hub and fake), `DbContract`, and until step 3 `CatalogApiTest`, `LiveSseTest` and `CatalogQueriesTest`. No `Permissions` or `RBAC.md` change, because the paths and access are unchanged.

### Lane B: accounts (`feat/slice-accounts`)

**Owns** (new files only):

- `src/main/java/dev/thiru/bms/features/accounts/`:
  - `AuthRoutes`: a `RouteModule` for `POST /api/auth/register`, `/login`, `/refresh`, `/logout` and `GET /api/me`, with the `bms_refresh` cookie handling, `ACCESS_TTL` and `REFRESH_TTL`. Give it a factory `AuthRoutes.of(JwtAuth jwt, Accounts accounts, RefreshTokenStore store)`. It builds the Jolt `RefreshTokens` with the two TTLs, the slice's `JoltRefreshStore` and the claims function, which today is the private `WebModules.claimsFor`: re-read the roles through `Accounts.find`, and a non-numeric subject or an unknown user is `HttpException(401, "Invalid token")`. The swap is then one line, and `claimsFor` leaves `WebModules`.
  - `Accounts`: `register`, `login`, `find`, `ensureAdmin`, with the same field-by-field validation, email normalisation, 409 `CONFLICT` duplicate-email pre-check, decoy-hash login (unknown email and wrong password give the same 401) and 12-character admin password rule as `application.Accounts`.
  - `AccountsSql`: `app_user` insert, find by email and by id, roles. A racing duplicate that passes the pre-check hits the UNIQUE index: catch `Db.ConstraintViolation` and give `VALIDATION` on `email`, as `SqlBookings.Users.insert` does today.
  - `JoltRefreshStore`: a copy of the `ports.RefreshTokenStore` to Jolt `RefreshStore` bridge.
  - Local records `UserDto`, `AuthResponse`, `TokenResponse`, `RegisterRequest` and `LoginRequest`.
- Tests: `src/test/java/dev/thiru/bms/features/accounts/**`, porting `AccountsTest` (on `TestDb` with `PlainHasher` and a `MutableClock`) and `AuthApiTest` (on `SliceKit`, mounting `AuthRoutes.of(k.pipeline().jwt(), new Accounts(k.db(), k.hasher(), k.clock()), k.refreshTokens())`) to SQLite. Cookie flows use `kit.http().session()`, and every auth call passes `.remoteAddress(kit.nextAddress())` because of the per-IP limits. `PasswordHasherContract` and `RefreshTokenStoreContract` already cover the ports.

**Preserve exactly:**

- Per-IP rate limits and CSRF on `/api/auth/*`. These stay in the pipeline, not in the slice.
- `SameSite`/`HttpOnly`/`Path` on the cookie, and `Secure` over HTTPS.
- Refresh rotation and reuse detection (Jolt `RefreshTokens`).
- A 401 for a bad token subject or an unknown user.
- Claims that re-read roles on every issue and refresh.

**Hands back** (step 3):

- In `WebModules.routes`, delete the legacy `Accounts accounts = ...` and `RefreshTokens tokens = ...` statements and the `claimsFor` method. Replace `pipeline.module(new AuthModule(accounts, tokens))` with `pipeline.module(AuthRoutes.of(pipeline.jwt(), new dev.thiru.bms.features.accounts.Accounts(deps.db(), deps.hasher(), deps.clock()), deps.refreshTokens()))`.
- In `Main`, replace `new Accounts(uow, hasher, clock).ensureAdmin(...)` with the slice's `new Accounts(sliceDb, hasher, clock).ensureAdmin(...)` (`sliceDb` is the `SqliteDb` that `Main` builds since step 0).
- Delete `web.AuthModule`, `web.JoltRefreshStore`, `application.Accounts` and `application.AccountsTest`, and the `Dtos` records `UserDto`, `AuthResponse` and `TokenResponse`.
- In `WEB_RECORDS`, point `User`, `AuthResponse`, `TokenResponse`, `RegisterRequest` and `LoginRequest` (today `AuthModule$RegisterRequest` and `AuthModule$LoginRequest`) at the slice records (replace).
- Move or delete `AuthApiTest`. Since step 0 it runs on SQLite, so after the swap it exercises `AuthRoutes`; prove that before handing back.

**Must stay green:** `AdminUsersApiTest` (admin reads users the slice wrote, which is why step 0 puts `WebKit` on SQLite), `HttpPipelineTest`, `SliceKitTest`, `RbacTest`, `RbacMatrixTest`, `MainTest` (admin bootstrap), `ApiCoverageTest`, `ApiSchemaTest`, `RecordGeneratorTest`, `EndToEndTest` (both journeys), `TargetArchitectureTest`, `ArchitectureTest`, `PasswordHasherContract`, `RefreshTokenStoreContract` (SQL and memory), `DbContract`, and until step 3 `AuthApiTest` and `AccountsTest`.

### Step 3: integration lane, per lane, in one commit each

The integration lane applies the lane's note, deletes the listed classes and `Dtos` records, updates `WEB_RECORDS`, and runs `mvn -B verify` and the packaged smoke run. Then it re-measures files and lines per package against [BASELINE.md](BASELINE.md).

## Wave 3 brief: booking

**Done**, see [Wave 3 record](#wave-3-record). Kept for reference.

One lane, **booking** (`feat/slice-booking`), runs after a short **step 0** by the integration lane. Read [How to build a slice](#how-to-build-a-slice) first. Everything there holds, with booking in place of catalog.

### Step 0: integration lane

- **SliceKit port fakes.** `SliceKit` has no `Ids`, `PaymentGateway` or `SeatEvents` yet. Add these fakes under `testing`, each with its contract suite (`IdGeneratorContract`, `PaymentGatewayContract`, `SeatEventPublisherContract`):
  - a sequential `Ids`
  - a recording `PaymentGateway`, which builds the checkout URL from a base and records refunds
  - a recording `SeatEvents`; or `FakeLive` implements both ports, as `LiveSeatHub` does

  Expose them on `SliceKit` as `ids()`, `gateway()` and `events()`. `k.pipeline().idempotent()` and `k.pipeline().holdLimit()` already exist.
- **Ticket view.** `SliceKit` serves the views on the class path, so `res.render("ticket", ...)` already works in slice tests. Change nothing unless a test proves otherwise.

### Lane: booking (`feat/slice-booking`)

**Owns** (new files only), under `src/main/java/dev/thiru/bms/features/booking/`:

- **`BookingRoutes`**, a `RouteModule` for these routes:
  - `POST /api/holds` (the hold limit, then idempotent)
  - `DELETE /api/holds/:id`
  - `POST /api/bookings` (idempotent)
  - `GET /api/bookings` and `GET /api/bookings/:id`
  - `POST /api/bookings/:id/checkout` (idempotent)
  - `POST /api/bookings/:id/cancel` (idempotent)
  - `GET /api/bookings/:id/ticket` (the `ticket` view)

  The constructor takes the ports it uses: `BookingRoutes(Db db, SeatEvents events, Ids ids, Clock clock, BookingPolicy policy, PaymentGateway gateway, Middleware idempotent, Middleware holdLimit)`. It may instead take use case classes built from those ports.
- **Use cases**, taken from `CreateHold`, `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries` and `ExpireSweep`. The slice also keeps its own copies of:
  - `Outcome`, which commits and then fails, for lazy expiry
  - `BookingOps.apply`, `expire` and `ownedBooking`
- **`BookingSql`** (split it if it grows large). It writes and reads `seat_hold`, `booking`, `booking_seat`, `payment` (insert on checkout, latest by booking) and `seat_lock`, from `SqlBookings.Holds/Bookings/Payments` and `SqlSeatLocks`.
  - A `seat_lock` UNIQUE clash is `Db.ConstraintViolation`, and must give `SEAT_UNAVAILABLE` exactly as `SqlSeatLocks.lock` does.
  - A `booking` update is `WHERE id = ? AND version = ?`. On a miss it re-reads and retries once, then gives `VERSION_CONFLICT` (SPEC 5.4).
  - A booking's show info (movie title, cinema, screen, and the local date and time in the city's zone) is read in one join, as `CatalogSql` reads a show.
- **Seat events** go through `Tx.afterCommit(() -> events.publish(...))`, never before the commit. A failing publish is logged and swallowed, as `EventBuffer.publishTo` does today.
- **Local records**: `HoldRequest`, `BookingRequest`, `HoldDto`, `ShowInfoDto`, `BookingSeatDto`, `BookingDto` and `CheckoutDto`. Copy each component for component from `web.BookingModule` and `web.Dtos`.

**Tests**, under `src/test/java/dev/thiru/bms/features/booking/**`, on `TestDb` and `SliceKit` with the V2 seed (take every date from `kit.seedDate()`):

- Port `CreateHoldTest`, `CreateBookingTest`, `CheckoutTest`, `CancelBookingTest`, `ExpireSweepTest` and `BookingApiTest`.
- Port the two `BookingQueries` cases of `AdminReportsAndQueriesTest`: `strangersGetNotFound...` and `ticketIsOwnerOnly...`.
- Move `adapters.db.ConcurrentBuyersTest` onto the slice. Keep the same races, run against the slice's use cases on `TestDb`: many buyers, overlapping seats, one winner per seat, and the sweep running alongside.

**Preserve exactly:**

- Every status, problem `code` and message.
- `Idempotency-Key` replay (`Idempotency-Replayed: true`) and the per-user hold limit. Both are pipeline middleware, passed in.
- `no-store` on booking reads.
- NOT_FOUND, never 403, for another user's booking. Admins may still read any booking through `GET /api/bookings/:id`.
- Lazy expiry: commit the expiry, then answer 410.
- The checkout cap (`MAX_CHECKOUTS`, 3).
- Event order and reasons: `seat-held`, `seat-released` (with `EXPIRED`, `CANCELLED` or `RELEASED`) and `seat-booked`. The live stream and `EndToEndTest` observe them.

**Hands back** (step 3):

- `WebModules.routes`: replace the `BookingModule` block and the `BookingQueries bookings` line with one `pipeline.module(new BookingRoutes(...))`.
- `Main`: schedule the slice's `ExpireSweep` on `sliceDb`, with `live` as the events port, instead of `application.ExpireSweep` on `uow`.
- **Delete:**
  - `web.BookingModule`
  - `application.CreateHold`, `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries`, `ExpireSweep` and `Outcome`
  - the ported tests, and the two `BookingQueries` cases
  - the `Dtos` records `HoldDto`, `ShowInfoDto`, `BookingSeatDto`, `BookingDto` and `CheckoutDto`
- **Keep:**
  - `EventBuffer` and `BookingOps`: `ProcessPaymentWebhook` and `CancelShow` use them until waves 4 and 5.
  - the `SqlBookings` and `SqlSeatLocks` repositories: payments and admin use them until wave 6.
- `WEB_RECORDS`: point `Hold`, `ShowInfo`, `BookingSeat`, `Booking`, `Checkout`, `HoldRequest` and `BookingRequest` at the slice records (replace).
- **Swap proof:** with the swap applied and the legacy classes still in place, these stay green:
  - `BookingApiTest` and `RbacTest`
  - `OwnerApiTest`
  - `AdminApiTest` (cancelling a show refunds bookings the slice wrote)
  - both `EndToEndTest` journeys

**Must stay green:**

- `ConcurrentBuyersTest` until it moves, and `BookingApiTest` until step 3
- `RbacTest`, `RbacMatrixTest`
- `ApiSchemaTest` (no schema edit), `ApiCoverageTest`, `RecordGeneratorTest`
- `EndToEndTest` (both journeys)
- `ProcessPaymentWebhookTest`, `CancelShowTest`
- `TargetArchitectureTest`, `ArchitectureTest`
- `DbContract`, `LiveSeatsContract`, and the contracts named in step 0

No `Permissions` or `RBAC.md` change.

## Wave 4 brief: payments

**Done**, see [Wave 4 record](#wave-4-record). Kept for reference.

One lane, **payments** (`feat/slice-payments`), branched from `integration` after wave 3 has merged, following a short **step 0** by the integration lane. Read [How to build a slice](#how-to-build-a-slice) first. Wave 5 (admin) may run in parallel. It owns `CancelShow`, `CancelShowTest` and `AdminReportsAndQueriesTest`, so this lane edits none of them.

### Step 0: integration lane

- **`ports.GatewayCallback`.** It replaces `MockGatewayPayments.Sender`. Ports never import a slice, so the event record moves into the port: `GatewayCallback.Event(String eventId, String type, String paymentId, String gatewayRef, long amount, String currency, Instant occurredAt)`, where `type` is the wire string `payment.succeeded` or `payment.failed`. The method is `int send(Event event) throws Exception`.
- `adapters.payment.MockGatewaySender` implements `GatewayCallback` and maps the event onto `MockGatewayClient.Event` as it does today.
- `WebDeps.mockSender` becomes a `GatewayCallback`.
- Until step 3 deletes `MockGatewayPayments`, keep a one-line bridge to its `Sender`, so the legacy module keeps compiling.
- **`GatewayCallbackContract`.** A 2xx status comes back as is, and a failed delivery throws. Hold `MockGatewaySender` to it against a `JoltTest` or a local stub, and add a recording fake under `testing`, exposed as `SliceKit.callbacks()` and held to the same contract.
- Settle the two open items in the [Wave 3 record](#wave-3-record) here or in the lane, and record the outcome in this file.

### Lane: payments (`feat/slice-payments`)

**Owns** (new files only), under `src/main/java/dev/thiru/bms/features/payments/`:

- **`PaymentRoutes`**, a `RouteModule` with these routes:
  - `POST /api/payments/webhook`, behind the signature middleware passed in. That is today's `Webhooks.hmacSha256(...)` chain, which `WebModules` keeps building with `WEBHOOK_TOLERANCE`.
  - `GET /mock-gateway/:id` (the `mock-gateway` view) and `POST /mock-gateway/:id/complete` (form or JSON `outcome`, then a 303 to `/bookings/<id>`). These are registered only when the mock gateway is on, as `PaymentModule` does when given a null mock.

  The constructor takes its ports: `PaymentRoutes(Db db, PaymentGateway gateway, SeatEvents events, Ids ids, Clock clock, Middleware signature, GatewayCallback mockSender /* null when MOCK_GATEWAY is off */, JsonMapper mapper)`.
- **`ProcessWebhook`**, ported from `ProcessPaymentWebhook`, keeping its kinds (`PROCESSED`, `DUPLICATE`, `AMOUNT_MISMATCH`) and its JSON:
  - First, `INSERT OR IGNORE INTO payment_event`. A duplicate gives 200 `{duplicate: true}`.
  - Load the payment (404 `Payment` if missing) and the booking.
  - Re-check expiry on an open booking. If it has expired, expire it, delete its locks and queue `Released(EXPIRED)`.
  - **Mismatch or failure:** if the payment is `INITIATED`, mark it `FAILED`. A `PAYMENT_PENDING` booking returns to `HELD` (`paymentFailed`).
  - **Success:** if the payment is `INITIATED` and the booking is `PAYMENT_PENDING`, check that the booking owns all its locks (`seat_lock` rows with `booking_id = booking.id` equal its `booking_seat` seats). If so:
    - `paymentSucceeded`
    - `UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL WHERE hold_id = ?`
    - payment `SUCCEEDED`
    - queue `Booked(showId, seats in booking_seat rowid order)`

    If the booking does not own all its locks, expire it. When the booking is expired, cancelled or refunded, refund in full through the gateway and mark the payment `REFUNDED`.
- **`MockGateway`**, ported from `MockGatewayPayments`: `find(paymentId)` for the page (amount through `kernel.Money`), and `complete(paymentId, success)`. The latter builds a `GatewayCallback.Event` with a fresh id from `Ids` and sends it. A non-2xx status or an exception becomes an `IllegalStateException`, as today.
- **`PaymentSql`.** Copy what you need from `features.booking.BookingSql` and from `SqlBookings.Payments/PaymentEvents`; never import the booking slice:
  - payment by id; payment update (404 `Payment` if no row)
  - the booking with its seats (`ORDER BY rowid`)
  - the versioned booking update, `WHERE id = ? AND version = ?`, re-read and retried once, then `VERSION_CONFLICT`
  - `seat_lock` by hold, mark booked, and delete by hold (seats sorted)
- **Local copies** of `BookingOps.apply` and `expire`, and the records `WebhookBody` and `CompleteRequest`, copied component for component from `PaymentModule`.
- **Seat events** only through `Tx.afterCommit`.

**Tests**, under `src/test/java/dev/thiru/bms/features/payments/**`, on `TestDb` and `SliceKit` (V2 seed, dates from `kit.seedDate()`, fakes from `kit.ids()`, `kit.gateway()`, `kit.events()`):

- Port `ProcessPaymentWebhookTest`. Reach `PAYMENT_PENDING` with SQL, or by mounting `features.booking.BookingRoutes` beside `PaymentRoutes` in the test kit. Tests may combine slices; main code may not.
- Move `BookingApiTest`'s payment cases:
  - `webhookRejectsBadSignaturesAndMismatchedAmounts`
  - `mockGatewayPageAndCompletion`
  - `mockGatewayIsAbsentWhenDisabled`
  - the webhook steps of `fullFlowFromHoldToTicketAndCancel`: success, duplicate replay, then the ticket
  - the payment-failed loop of `expiredHoldGives410AndTooManyCheckoutsGive429`

  Copy `WebKit.sign` and `signedWebhook` for the signed calls. The booking halves of these cases are already in `features.booking.BookingRoutesTest`.
- Copy `AdminReportsAndQueriesTest.mockGatewayDeliversAnEventAndReturnsTheBooking` into the slice. Its deletion goes to the integration lane, because wave 5 owns the file.
- A race on `TestDb` with virtual threads: a success webhook against the sweep expiring the same booking. The result must be exactly one of `CONFIRMED`, or `EXPIRED` with a full refund, and never a seat booked twice.

**Preserve exactly:**

- 401 `UNAUTHENTICATED` for a forged or missing signature, a tampered body, or a timestamp more than 300 s off. A timestamp exactly 300 s off reaches validation.
- 422 with `errors.type` for an unknown type, and `VALIDATION` for an amount mismatch, with the booking left `HELD`.
- 200 `{status}` for a processed event and `{duplicate: true}` for a replay.
- The mock gateway page: HTML, `INR` amount, the form action and a CSP header.
- Completion: the 303 `Location`, a 422 for a bad outcome, a 403 for a cross-site cookie post (pipeline guards), and a 404 for an unknown payment.
- The mock routes return 404 when `MOCK_GATEWAY` is off.
- Events: `seat-booked` after `CONFIRMED`; `seat-released` (`EXPIRED`) when a late webhook finds the booking expired. `EndToEndTest` waits for `event: seat-booked`.

**Hands back** (step 3):

- In `WebModules.routes`, replace the `MockGatewayPayments mock = ...` statement and the `PaymentModule` block with one `pipeline.module(new PaymentRoutes(...))`. `deps.mockSender()` is passed only when `settings.mockGateway()` is on, otherwise `null`.
- **Delete:**
  - `web.PaymentModule`, `application.ProcessPaymentWebhook`, `application.MockGatewayPayments` (and the step 0 bridge)
  - `ProcessPaymentWebhookTest`
  - `web.BookingApiTest`, whole: its booking cases live in `BookingRoutesTest`, its payment cases in the slice
  - the case `AdminReportsAndQueriesTest.mockGatewayDeliversAnEventAndReturnsTheBooking`
- **`WEB_RECORDS`:** point `WebhookEvent` and `CompleteRequest` at the slice records (replace).
- **Fixture classes:** wave 4 can delete none of the wave 3 fixtures.
  - `CreateBooking` and `Checkout` lose their `ProcessPaymentWebhookTest` user, but `CancelShowTest` and `AdminReportsAndQueriesTest` still build state with them.
  - `CancelBooking`, `BookingQueries` and `Outcome` stay for `AdminReportsAndQueriesTest`.
  - `EventBuffer` and `BookingOps` stay for `CancelShow`.
  - Wave 5 deletes all seven once `CancelShow` and those two tests move. Step 3 updates the fixture notes on `CreateBooking` and `Checkout` to name only wave 5.
- **Swap proof:** with the swap applied and the legacy classes still in place, these stay green: both `EndToEndTest` journeys (the payment journey goes over a real socket through `MockGatewaySender`), `RbacTest`, `ApiSchemaTest`, `MainTest`, `AdminApiTest` and `OwnerApiTest`. Also run a legacy-vs-slice equivalence script for the webhook and mock gateway routes, as wave 3 did.

**Must stay green:** `RbacTest`, `RbacMatrixTest`, `ApiSchemaTest` (no schema edit), `ApiCoverageTest`, `RecordGeneratorTest`, `EndToEndTest`, `MainTest`, `CancelShowTest`, `AdminReportsAndQueriesTest`, `TargetArchitectureTest`, `ArchitectureTest`, `DbContract`, `PaymentGatewayContract`, `SeatEventPublisherContract` and `GatewayCallbackContract`. No `Permissions` or `RBAC.md` change.

## Wave 6 brief: retire the legacy layer

After wave 4, every route runs on a slice. What is left of the old layering is held up only by tests and by the composition root. Wave 6 deletes it, moves codegen into the main build, and re-measures.

The work splits into **two parallel lanes with no file overlap**, then **one integration step**. Both lanes branch from `integration` after wave 4 has merged. Neither edits the other's files. Each keeps `mvn -B verify` green with the guards below, and hands back a short note, as earlier waves did.

### Lane L: legacy removal (`feat/wave6-legacy`)

**Owns:** `Main`, `web.WebDeps`, `web.WebModules`, `domain.port`, `application`, `adapters.db` except `SchemaRunner`/`Sqlite`/`SqliteDb`/`SqlIdempotency`/`SqlRefreshTokens`/`Json`/`Sqls`, `testing.TestDb`, every test under `web`, `adapters.db`, `application`, `contract.RepositoriesContract`, `ArchitectureTest`, `TargetArchitectureTest`, `docs/SPEC.md` section 10, `AGENTS.md` rule 3.

Do these in order. Each step is green on its own commit.

1. **Move the remaining web tests off `WebKit` and `FakeWorld`.**
   - The tests: `RbacTest` (379 lines), `RbacMatrixTest` (102), `HttpPipelineTest` (82), `OpsApiTest` (101) and `CatalogCacheTest` (124).
   - Rewrite each on `SliceKit` with the real slice route modules mounted, as `WebModules.routes` mounts them (or call `WebModules.routes` with the kit's ports). Use the V2 seed, with dates from `kit.seedDate()`, never `FakeWorld.T0`.
   - Then delete `web.WebKit` (344).
2. **Drop the legacy unit of work from the composition root.**
   - Remove `WebDeps.uow` and the `SqliteUnitOfWork uow` line in `Main`.
   - `WebDeps` holds only ports and settings after this. Either replace it with a `WebModules.Ports` record, or pass the ports straight to `WebModules.routes`. `MainTest` and `EndToEndTest` must stay green.
   - Delete `WebDeps` (80) if replaced.
3. **Delete the repository layer.**
   - **Main:**
     - `domain.port`, the whole package (7 files, 386 lines): `Repositories`, `UnitOfWork`, `CatalogRepositories`, `BookingRepositories`, `AdminRepositories`, `SeatLockRepository`, `package-info`
     - `adapters.db.SqlRepositories` (93), `SqlCatalog` (478), `SqlBookings` (494), `SqlAdmin` (490), `SqlSeatLocks` (190) and `SqliteUnitOfWork` (26)
   - **Tests:**
     - `AdminRepositoriesTest`, `BookingRepositoriesTest`, `CatalogRepositoriesTest`, `SeatLockRepositoryTest`, `SqliteRepositoriesContractTest` and `SqliteUnitOfWorkTest`
     - `contract.RepositoriesContract` (819)
     - `application.fakes.FakeWorld` (1 040) and its four contract tests. `RecordingGateway`, `RecordingEvents`, `SequentialIds` and `RecordingCallbacks` already cover the ports.
   - **Rework, do not delete:**
     - `DbTest`, which `SqlIdempotencyTest` and `SqlRefreshTokensTest` still extend: drop its `uow`.
     - `SqliteTest`: drop its `SqliteUnitOfWork` case, or move it onto `SqliteDb`.
     - `TestDb.legacyUnitOfWork()`.
   - **Keep:** `Sqls` and `Json`, because `SqliteDb`, `SqlIdempotency` and `SqlRefreshTokens` use them. Check this with a trial deletion.
4. **Retire the transitional architecture guards.**
   - Delete `ArchitectureTest` (102) and `application/package-info.java` together.
   - Fold any rule still worth keeping into `TargetArchitectureTest`, for example web never imports adapters. Its real-tree assertions must then also say that `domain` and `application` no longer exist.
   - Delete the `feature-imports-legacy` fixture and rule if nothing is left to import.
5. **Docs.**
   - SPEC section 10: change "**Status: in migration**" to done, and describe only the slice layout.
   - `AGENTS.md` rule 3: drop the sentence about the legacy `domain.port` repositories until wave 6.
   - PLAN.md "Hot spots": drop the deleted classes.
6. **The flaky migration (wave 4 record).** In `SchemaRunner.apply`, keep the original exception and add the `rollback()` and `setAutoCommit(true)` failures to it as suppressed exceptions. Add a unit test with a connection that fails on commit, so the message names the real cause.

### Lane G: generated records (`feat/wave6-codegen`)

**Owns:** `pom.xml`, `src/test/java/dev/thiru/bms/api/**` (`RecordGenerator`, `OpenApi`, `RecordGeneratorTest`, `ApiCoverageTest`), any new build-time source directory, and the `*Dtos` classes and their uses in `features/*`. It does not edit `ApiSchemaTest` or `api/openapi.yaml`.

1. **Generation in `generate-sources`.**
   - The generator lives in test code, and its YAML reader is a test-scope dependency (pom, "test scope only"). Today it runs only in the opt-in `api-codegen` profile, at `process-test-classes`.
   - Move `RecordGenerator` and `OpenApi` to a build-time source set, for example `src/build/java`, compiled by an extra `maven-compiler-plugin` execution in `initialize`. Make the YAML library a dependency of that execution or of `exec-maven-plugin`, never a runtime dependency.
   - Run it in `generate-sources` into `target/generated-sources/openapi`, and add that directory with `build-helper-maven-plugin:add-source`.
   - Remove the profile. `mvn -B verify` must not get noticeably slower: record the wall time.
2. **Switch the slices to `dev.thiru.bms.api.generated`.**
   - Replace each local `*Dtos` record with its generated record only where the two are component for component identical (names, order, types, boxing). Otherwise the JSON changes, and `ApiSchemaTest` must stay green with **no schema edit**.
   - Keep a local record wherever they differ, for example a boxed `Long` read for validation, such as `PaymentDtos.WebhookBody.amount`, or a nested shape the generator does not emit. List each one, with the reason, in the hand-back.
   - `RecordGeneratorTest.WEB_RECORDS` then shrinks to the records kept local. Replace the per-schema mapping with a check that no slice declares a record a generated type already provides.
   - Slices stay independent: generated records are a shared main package, like `kernel`. Add `dev.thiru.bms.api.generated` to `TargetArchitectureTest`'s allowed imports for features. Lane L owns that file, so give the one line to the integration step.

### Integration step (`feat/restructure-wave6`)

1. Merge lane L, then lane G. Apply lane G's `TargetArchitectureTest` line.
2. Run `mvn -B verify`, then `npm run build`, then the packaged smoke run, as in the wave 4 record.
3. **Re-measure against `BASELINE.md`.** Use the same commands and machine notes:
   - files and lines, per package, main and test
   - tests run and `@Test` count
   - `mvn -B verify` wall time
   - "what a feature costs": files and directories per slice commit, for waves 2 to 6
   Add a "Restructure result" section to `BASELINE.md`, and close PLAN.md's waves table.

**Done when:**

- `src/main/java/dev/thiru/bms` holds only the root, `kernel`, `ports`, `adapters`, `web` and `features` (plus generated sources under `target`).
- No class names `UnitOfWork`, `Repositories` or `FakeWorld`.
- SPEC 10 says done.
- `ApiSchemaTest` and both `EndToEndTest` journeys are green with no schema edit.

**Expected size.** About −2 240 main lines and about −3 600 test lines, from lane L:

| What | Lines |
|---|---:|
| `domain.port` | 386 |
| the five `Sql*` repositories and `SqliteUnitOfWork` | 1 771 |
| `WebDeps` | 80 |
| `application` | 5 |
| test side: `FakeWorld`, `RepositoriesContract`, the repository tests and `WebKit` | about 3 500, minus whatever the web tests regain on `SliceKit` |

## Instructions for a slice lane

1. Branch from the latest `integration` after the previous wave merged. Work only in `features/<slice>/` and `src/test/java/dev/thiru/bms/features/<slice>/`.
2. Write the slice's tests first, on SQLite (`testing.TestDb` for SQL and use cases, `testing.SliceKit` for routes, see [How to build a slice](#how-to-build-a-slice)) and port fakes; port behaviour you rely on that no contract covers goes into the contract suite (ask the integration lane).
3. Bring the code over: routes, request/response records (or `api.generated`), use case or transaction script, SQL. Keep JSON identical: `ApiSchemaTest` must stay green with no schema edit. A needed schema change is a separate, reviewed PR.
4. Do not import another slice. If two slices need the same rule, it belongs in `kernel` (ask the integration lane); the same SQL may be duplicated.
5. Leave the old classes in place. Hand the integration lane the list of registrations to swap and classes to delete; it applies them in one commit.
6. `mvn -B verify` green, `mvn spotless:apply`, atomic Conventional Commits.
