# ADR 0001: vertical feature slices around a thin hexagon

- **Status:** accepted (user-approved), **in migration**. Wave 0 (guardrails) is merged onto `integration` after the Jolt v0.4.1 upgrade; wave 1 (kernel, ports, `ports.Db`) is merged too. Wave 2 step 0 (pipeline split, `SliceKit`, `WebKit` on SQLite, `ports.Db` wired) is done on `feat/restructure-wave2-step0`. Next: lanes A and B, see [Wave 2 briefs](#wave-2-briefs) and [How to build a slice](#how-to-build-a-slice).
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
- **Wire types come from `api/openapi.yaml`.** A slice that moves switches its responses to `api.generated` records (or keeps local records that `RecordGeneratorTest` checks). Until wave 6 moves generation to `generate-sources`, `api.generated` is not on the main class path, so wave 2 to 5 slices keep local records and the integration lane points `WEB_RECORDS` at them. Shared shapes such as `SeatMap` (catalog and live) are shared through `api.generated`, never through another slice.
- **Hand-written records stay until their slice moves.** `RecordGeneratorTest.WEB_RECORDS` maps each schema to the record serialised today; a moving slice updates its entries.

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

- `Main`, `AppConfig`, `WebModules`, `WebDeps`, `web.HttpPipeline`, `web.CatalogCache`
- the rest of the shared `web` kit (`Query`, `Paging`, `Principal`, `ApiException`, `Problems`, `Idempotent`): slices use it, never edit it
- `kernel` and `ports`, except new files a brief hands to a lane (wave 2: `kernel.catalog.SeatMap`)
- the shared test kit in `src/test/java/dev/thiru/bms/testing` (`TestDb`, `SliceKit`, `MutableClock`, `PlainHasher`, `MemoryRefreshTokens`, `FakeLive`)
- `web.Permissions`, `web.Rbac` and `docs/RBAC.md`
- `web.Dtos` (until it is fully split)
- `domain.port.Repositories`, `UnitOfWork`, `adapters.db.SqlRepositories`, `SqliteUnitOfWork`
- `application.fakes.FakeWorld`, `web.WebKit`
- `src/main/resources/db/migrations/*` (one numbering sequence)
- `pom.xml`, `api/openapi.yaml`, `docs/SPEC.md`
- `ArchitectureTest`, `TargetArchitectureTest`, `RecordGeneratorTest.WEB_RECORDS`

## Waves

Each wave starts from a green `mvn -B verify` on `integration`, and ends green with the guards above. Move code with history (`git mv` first, edit after, separate commits). Do not start wave 1 until `feat/jolt-0.4.1` and `feat/react-ui` have merged, because wave 1 renames every package they touch.

| Wave | Lane(s) | Scope | Done when |
|---|---|---|---|
| 0 | foundation | baseline, target rules, port contracts, schema, codegen, this plan | **done** (merged onto `integration` with v0.4.1) |
| 1 | integration, alone | `domain` to `kernel` (package moves only); surviving port interfaces to `ports`; add `ports.Db` + `Tx.afterCommit` and `adapters.db.SqliteDb` with a `Db` contract test; replace `ArchitectureTest`'s `domain` rule with the kernel rule; `web.Money` to `kernel.Money` | **done**, see [Wave 1 record](#wave-1-record) |
| 2 | catalog+live, accounts (parallel) | `features.catalog` (public catalog, seat map; `kernel.catalog.SeatMap`), `features.live` (SSE), `features.accounts` (auth, me, `JoltRefreshStore`) | slices on SQLite tests; responses via `api.generated` or checked records; integration lane swaps registration and deletes `CatalogModule`, `LiveModule`, `AuthModule`, `CatalogQueries`, `Accounts` |
| 3 | booking, alone | `features.booking`: holds, bookings, checkout, cancel, ticket, `ExpireSweep`, `Outcome`, owned-booking SQL; events via `afterCommit` | `ConcurrentBuyersTest` moved and green; integration lane deletes `BookingModule`, the booking use cases, `EventBuffer` |
| 4 | payments | `features.payments`: webhook, mock gateway, their booking/payment transition SQL | E2E payment journey green; `PaymentModule`, `ProcessPaymentWebhook`, `MockGatewayPayments` deleted |
| 5 | admin | `features.admin`: catalog writes, reports, stats, users and roles, owner links, show cancel, `CinemaScope` lookup | `OwnerApiTest`, `AdminApiTest`, `AdminUsersApiTest` moved and green; `AdminModule`, `OwnerModule`, admin use cases deleted |
| 6 | integration | delete `Repositories`, `UnitOfWork`, repository interfaces, `SqlRepositories`, `SqlCatalog/Bookings/Admin/SeatLocks`, `FakeWorld`, `WebDeps`, the rest of `Dtos`, `ArchitectureTest`; generation moves to `generate-sources`; SPEC 10 drops "in migration"; re-measure BASELINE | `src/main/java` has only root, `kernel`, `ports`, `adapters`, `web`, `features` |

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

## Instructions for a slice lane

1. Branch from the latest `integration` after the previous wave merged. Work only in `features/<slice>/` and `src/test/java/dev/thiru/bms/features/<slice>/`.
2. Write the slice's tests first, on SQLite (`testing.TestDb` for SQL and use cases, `testing.SliceKit` for routes, see [How to build a slice](#how-to-build-a-slice)) and port fakes; port behaviour you rely on that no contract covers goes into the contract suite (ask the integration lane).
3. Bring the code over: routes, request/response records (or `api.generated`), use case or transaction script, SQL. Keep JSON identical: `ApiSchemaTest` must stay green with no schema edit. A needed schema change is a separate, reviewed PR.
4. Do not import another slice. If two slices need the same rule, it belongs in `kernel` (ask the integration lane); the same SQL may be duplicated.
5. Leave the old classes in place. Hand the integration lane the list of registrations to swap and classes to delete; it applies them in one commit.
6. `mvn -B verify` green, `mvn spotless:apply`, atomic Conventional Commits.
