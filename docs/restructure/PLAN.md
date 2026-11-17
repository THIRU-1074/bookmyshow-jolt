# ADR 0001: vertical feature slices around a thin hexagon

- **Status:** accepted (user-approved), **in migration**. Wave 0 (guardrails) is merged onto `integration` after the Jolt v0.4.1 upgrade (`feat/restructure-wave1`); no production class has moved yet.
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
- **Wire types come from `api/openapi.yaml`.** A slice that moves switches its responses to `api.generated` records (or keeps local records that `RecordGeneratorTest` checks). Shared shapes such as `SeatMap` (catalog and live) are shared through `api.generated`, never through another slice.
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
| `WebModules` | split: middleware chain stays in `web` as the HTTP pipeline; route installation moves to `Main` | wiring slices together is composition |
| `WebDeps` | deleted | ceremony: `Main` passes each slice only the ports it uses |
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

- `Main`, `AppConfig`, `WebModules`, `WebDeps`, `web.CatalogCache`
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
| 0 | foundation (this branch) | baseline, target rules, port contracts, schema, codegen, this plan | merged |
| 1 | integration, alone | `domain` to `kernel` (package moves only); surviving port interfaces to `ports`; add `ports.Db` + `Tx.afterCommit` and `adapters.db.SqliteDb` with a `Db` contract test; replace `ArchitectureTest`'s `domain` rule with the kernel rule; `web.Money` to `kernel.Money` | no behaviour change; all tests green; `TargetArchitectureTest` now checks real `kernel` and `ports` |
| 2 | catalog+live, accounts (parallel) | `features.catalog` (public catalog, seat map; `kernel.catalog.SeatMap`), `features.live` (SSE), `features.accounts` (auth, me, `JoltRefreshStore`) | slices on SQLite tests; responses via `api.generated` or checked records; integration lane swaps registration and deletes `CatalogModule`, `LiveModule`, `AuthModule`, `CatalogQueries`, `Accounts` |
| 3 | booking, alone | `features.booking`: holds, bookings, checkout, cancel, ticket, `ExpireSweep`, `Outcome`, owned-booking SQL; events via `afterCommit` | `ConcurrentBuyersTest` moved and green; integration lane deletes `BookingModule`, the booking use cases, `EventBuffer` |
| 4 | payments | `features.payments`: webhook, mock gateway, their booking/payment transition SQL | E2E payment journey green; `PaymentModule`, `ProcessPaymentWebhook`, `MockGatewayPayments` deleted |
| 5 | admin | `features.admin`: catalog writes, reports, stats, users and roles, owner links, show cancel, `CinemaScope` lookup | `OwnerApiTest`, `AdminApiTest`, `AdminUsersApiTest` moved and green; `AdminModule`, `OwnerModule`, admin use cases deleted |
| 6 | integration | delete `Repositories`, `UnitOfWork`, repository interfaces, `SqlRepositories`, `SqlCatalog/Bookings/Admin/SeatLocks`, `FakeWorld`, `WebDeps`, the rest of `Dtos`, `ArchitectureTest`; generation moves to `generate-sources`; SPEC 10 drops "in migration"; re-measure BASELINE | `src/main/java` has only root, `kernel`, `ports`, `adapters`, `web`, `features` |

Wave order reasoning: read-only slices first (no shared writes, lowest risk, proves the pattern and `ports.Db`); booking before payments because payments reuses booking's transitions; admin last because it touches the most tables and depends on every earlier slice's SQL being settled.

## Instructions for a slice lane

1. Branch from the latest `integration` after the previous wave merged. Work only in `features/<slice>/` and `src/test/java/dev/thiru/bms/features/<slice>/`.
2. Write the slice's tests first, on SQLite (copy the `DbTest` pattern) and port fakes; port behaviour you rely on that no contract covers goes into the contract suite (ask the integration lane).
3. Bring the code over: routes, request/response records (or `api.generated`), use case or transaction script, SQL. Keep JSON identical: `ApiSchemaTest` must stay green with no schema edit. A needed schema change is a separate, reviewed PR.
4. Do not import another slice. If two slices need the same rule, it belongs in `kernel` (ask the integration lane); the same SQL may be duplicated.
5. Leave the old classes in place. Hand the integration lane the list of registrations to swap and classes to delete; it applies them in one commit.
6. `mvn -B verify` green, `mvn spotless:apply`, atomic Conventional Commits.
