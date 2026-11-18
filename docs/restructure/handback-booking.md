# Wave 3 hand-back: booking

Branch `feat/slice-booking`, from `integration` at `229abe5`.

**What the branch adds:**

- `src/main/java/dev/thiru/bms/features/booking/`
- `src/test/java/dev/thiru/bms/features/booking/`
- three port fakes under `src/test/java/dev/thiru/bms/testing/`, each with a contract test (see below)

No hot spot is edited. No kernel or port file is added, and `SliceKit` is unchanged.

**The swap is written against `origin/feat/restructure-wave2`.** The swap lines below are for wave 2's `WebModules` and `Main`. They are proven on a scratch merge of this branch into it (section 4).

**Scope, as in PLAN.md's wave 3 brief:**

- The slice covers all eight hold and booking routes, checkout included, and `ExpireSweep`.
- The payment webhook and the mock gateway are left alone for wave 4.

| File | Role |
|---|---|
| `BookingRoutes` | the `RouteModule`, built as `BookingRoutes(Db, SeatEvents, Ids, Clock, BookingPolicy, PaymentGateway, Middleware idempotent, Middleware holdLimit)`. Routes: `POST /api/holds` (hold limit, then idempotent), `DELETE /api/holds/:id`, `POST /api/bookings` (idempotent), `GET /api/bookings`, `GET /api/bookings/:id`, `POST .../checkout` and `.../cancel` (idempotent), `GET .../ticket` (the `ticket` view) |
| `CreateHold`, `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries` | the use cases, ported line for line onto `ports.Db` |
| `ExpireSweep` (public) | the sweep `Main` schedules |
| `BookingOps`, `Outcome` | local copies: `apply` (version check, one retry, then `VERSION_CONFLICT`), `expire`, `owned`, after-commit publishing; commit-then-fail for lazy expiry |
| `BookingSql` | SQL from `SqlBookings.Holds/Bookings/Payments` and `SqlSeatLocks`. A `seat_lock` clash (`Db.ConstraintViolation`) gives `SEAT_UNAVAILABLE` with the conflicting seats. The show info comes from one join |
| `BookingDtos` | copies of `HoldRequest`, `BookingRequest`, `HoldDto`, `ShowInfoDto`, `BookingSeatDto`, `BookingDto` and `CheckoutDto` |

**Test fakes added under `testing`**, each held to its existing contract suite:

- `SequentialIds` (`IdGeneratorContract`)
- `RecordingGateway` (`PaymentGatewayContract`; checkout URL from a base, records refunds, can fail refunds)
- `RecordingEvents` (`SeatEventPublisherContract`)

**Suggested `SliceKit` wiring, not done here.** Add `ids()`, `gateway()` and `events()` fields that return one instance of each fake. Today `BookingRoutesTest` builds its own instances as test fields and passes them into the routes, so it needs no `SliceKit` change.

## 1. Swap lines (against `origin/feat/restructure-wave2`)

**`WebModules.routes`.** Replace the whole `pipeline.module(new BookingModule(...));` statement with:

```java
pipeline.module(
        new BookingRoutes(
                deps.db(),
                deps.publisher(),
                deps.ids(),
                deps.clock(),
                deps.policy(),
                deps.gateway(),
                idempotent,
                pipeline.holdLimit()));
```

Then:

- Delete the line `BookingQueries bookings = new BookingQueries(deps.uow());` and the blank line after it.
- Delete the imports of `application.BookingQueries`, `CancelBooking`, `Checkout`, `CreateBooking` and `CreateHold`.
- Add `import dev.thiru.bms.features.booking.BookingRoutes;`.

No `WebDeps` change is needed.

**`Main`:**

```java
scheduleSweep(app, config, clock, uow, live, refreshTokens, idempotency);
// becomes
scheduleSweep(app, config, clock, sliceDb, live, refreshTokens, idempotency);
```

Also in `Main`:

- In `scheduleSweep`, change the parameter `SqliteUnitOfWork uow` to `SqliteDb sliceDb`, and change its first line to `ExpireSweep sweep = new ExpireSweep(sliceDb, live, clock);`.
- Replace `import dev.thiru.bms.application.ExpireSweep;` with `import dev.thiru.bms.features.booking.ExpireSweep;`.

`uow` stays, because the remaining legacy modules use it.

## 2. Deletions

**The brief's list (step 3):**

- Main: `web.BookingModule`; `application.CreateHold`, `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries`, `ExpireSweep`, `Outcome`.
- Tests: `application.CreateHoldTest`, `CreateBookingTest`, `CheckoutTest`, `CancelBookingTest`, `ExpireSweepTest`, `adapters.db.ConcurrentBuyersTest`, and the two `BookingQueries` cases of `AdminReportsAndQueriesTest` (`strangersGetNotFoundButAdminsAndOwnersGetTheBooking`, `ticketIsOwnerOnlyAndConfirmedOnly`). All of them are ported to `features.booking`.
- `Dtos` records: `HoldDto`, `ShowInfoDto`, `BookingSeatDto`, `BookingDto`, `CheckoutDto` (the whole `// ---- bookings ----` block). After that, `Dtos` no longer imports `application.BookingQueries` or `kernel.booking.Booking`.

**Keep:** `EventBuffer` and `BookingOps` (`ProcessPaymentWebhook`, `CancelShow`); `SqlBookings` and `SqlSeatLocks` (payments, admin, until wave 6).

**Blocker the integration lane must decide on.** I applied the brief's full deletion list to the scratch merge with wave 2 and ran `test-compile`:

- Main code compiles.
- Three tests that must stay green no longer compile, because they use the legacy use cases as fixtures:
  - `AdminReportsAndQueriesTest`: 24 errors, from `CreateBooking`, `Checkout` and `CancelBooking` in its setup and its mock-gateway case.
  - `CancelShowTest`: 16 errors, from `CreateBooking` and `Checkout`.
  - `ProcessPaymentWebhookTest`: 6 errors, from `CreateBooking` and `Checkout`.

These are FakeWorld tests of wave 4 and wave 5 code, so this lane cannot rewrite them. There are two options:

- **(a)** At step 3, delete only `BookingModule`, `CreateHold`, `ExpireSweep`, their tests, `ConcurrentBuyersTest` and the `Dtos` block. Keep `CreateBooking`, `Checkout`, `CancelBooking`, `BookingQueries` and `Outcome` as fixtures until waves 4 and 5 move those three tests. This variant was proven green on `integration` (section 4).
- **(b)** Rewrite the fixtures in those three tests at step 3 to reach the same states with FakeWorld's repositories (`putHold`, then insert a booking and payment directly).

## 3. `WEB_RECORDS` (all *replace*)

| Schema | Record |
|---|---|
| Hold | `dev.thiru.bms.features.booking.BookingDtos$HoldDto` |
| ShowInfo | `dev.thiru.bms.features.booking.BookingDtos$ShowInfoDto` |
| BookingSeat | `dev.thiru.bms.features.booking.BookingDtos$BookingSeatDto` |
| Booking | `dev.thiru.bms.features.booking.BookingDtos$BookingDto` |
| Checkout | `dev.thiru.bms.features.booking.BookingDtos$CheckoutDto` |
| HoldRequest | `dev.thiru.bms.features.booking.BookingDtos$HoldRequest` (was `BookingModule$HoldRequest`) |
| BookingRequest | `dev.thiru.bms.features.booking.BookingDtos$BookingRequest` (was `BookingModule$BookingRequest`) |

In `RecordGeneratorTest`, remove the five rows from the `dtos` array and the two `BookingModule$...` lines, and add the seven entries above. `BookingPage` stays on `in.jolt.Page$Result`.

## 4. Verify totals

**Swap proven on wave 2: BUILD SUCCESS, 629 tests, 0 failures, 0 errors, 0 skipped**, with `spotless:check` and jacoco passing. This ran on a scratch branch off `origin/feat/restructure-wave2` with `feat/slice-booking` merged in. The working tree had the swap lines from section 1 and the `WEB_RECORDS` changes from section 3, with every legacy class still in place. The scratch branch and its worktree were then deleted.

The brief's must-stay-green suites all passed against the swapped routes:

- `BookingApiTest` 12, `RbacTest` 13, `RbacMatrixTest` 3, `OwnerApiTest` 5
- `AdminApiTest` 6 (show cancel refunds bookings the slice wrote)
- `EndToEndTest` 2 (both journeys, with the sweep scheduled from `features.booking.ExpireSweep`), `MainTest` 12
- `ApiSchemaTest` with no schema edit, `ApiCoverageTest` 5, `RecordGeneratorTest` 4
- `ProcessPaymentWebhookTest` 9, `CancelShowTest` 3
- `ArchitectureTest` 5, `TargetArchitectureTest` 13
- every contract suite, including `SqliteDbContractTest` and the three new fake contract tests

**Earlier run on `integration`: 569 tests, BUILD SUCCESS.** That run had the swap, the `WEB_RECORDS` changes and the option (a) deletions, and it ran before the constructor was reordered to the brief's signature. Its result is 583 then, minus the 14 deleted tests.

**This branch, without the swap: 585 tests.** That is 523 on `integration` plus 62 slice and fake tests.

**Equivalence check** (scratch, not committed, on `integration`). A test mounted `BookingModule` on the legacy use cases and `BookingRoutes` on two `SliceKit`s built from the same V2 template. Both had the same seeded ids, random source, gateway fake and clock moves. The same 121-request script ran against both. It covered:

- every hold validation path, plus a missing key, an oversized key, no token and a cancelled show
- replay and body mismatch
- conflicts
- release by owner, stranger and admin
- booking validation and replay
- get by owner, stranger, admin and anonymous
- ticket before and after payment, by every role
- checkout by a stranger and an admin, replay, a second checkout and the limit of three
- cancel when held, pending, confirmed with full, half and no refund, already terminal and expired
- 12 list queries
- lazy expiry on booking, checkout and cancel
- a hold after the show starts
- the per-user hold limit

The result: **0 differences** in status, body (with only `requestId` normalised) and the 16 headers compared, plus the presence of `Retry-After`, `RateLimit-Reset` and `X-Request-Id`. The 63 seat events were identical and in the same order, the gateway refunds were identical, and the final rows of `seat_hold`, `seat_lock`, `booking`, `booking_seat` and `payment` (67 rows) were identical.

## 5. Lines of code

| | Legacy | Slice (new) |
|---|---|---|
| Routes | `BookingModule` 181 | `BookingRoutes` 194 |
| Use cases | `CreateHold` 110, `CreateBooking` 93, `Checkout` 76, `CancelBooking` 62, `BookingQueries` 84, `ExpireSweep` 61, `Outcome` 25 (511) | 105, 92, 74, 63, 48, 66, 25 (473) |
| Shared steps | `BookingOps` 50, `EventBuffer` 39 (both stay for now) | `BookingOps` 71 (also does after-commit publishing) |
| SQL | in `SqlBookings` (about 330 of 494 lines) and `SqlSeatLocks` (about 150 of 190); both stay until wave 6 | `BookingSql` 608 |
| Records | 73 lines of `Dtos` | `BookingDtos` 87 |
| **Main total** | **854 without SQL**, about 1330 with it | **1433** |
| Tests | 1160 (5 use case tests, `ConcurrentBuyersTest`, `BookingApiTest`) | 1813 (with the 240-line `BookingWorld` and the 3 fakes with their contract tests) |

`BookingSql` reads `layout_json` and `prices_json` with SQLite's JSON functions, as lane A does, because there is no `kernel.Json` yet.

## 6. Notes for the payments slice (wave 4)

Plain notes; nothing here is shared code.

- **What checkout leaves behind.** Checkout belongs to booking, and the webhook will find this state:
  - one `payment` row: `INITIATED`, `amount` and `currency` from the booking, `gateway_ref` = `CheckoutSession.gatewayRef`, `created_at = updated_at = now`
  - the booking `PAYMENT_PENDING`, with `checkout_count + 1` and `version + 1`, and `expires_at = max(now + paymentWindow, old expiry)`
  - the hold's `seat_lock` rows still `HELD`, with `expires_at` moved to the booking's new expiry and `booking_id` set
- **Moving a booking.** Use only the kernel transitions and the version check:

  ```sql
  UPDATE booking SET status = ?, expires_at = ?, refund_amount = ?, checkout_count = ?,
    updated_at = ?, version = version + 1 WHERE id = ? AND version = ?
  ```

  Zero rows changed means re-read once and re-apply, then `VERSION_CONFLICT`.
- **Confirming.** Run `UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL WHERE hold_id = ?` only when the booking owns all its seats. Expiry deletes the hold's locks; sort the freed seats for the event.
- **Seat events.** Queue them with `tx.afterCommit(...)`: `Booked(showId, seats in booking_seat rowid order)`, and `Released(showId, sorted seats, EXPIRED)` only when something was freed.
- **Reads.** The latest payment is `ORDER BY created_at DESC, rowid DESC LIMIT 1`; booking seats are `ORDER BY rowid`; `seats_json` is a compact array (`["A2","G1"]`).
- **Fakes.** `testing.RecordingGateway`, `RecordingEvents` and `SequentialIds` are ready for the payments tests.
- **`BookingApiTest`** holds the webhook and mock gateway cases. Keep it until wave 4 moves them. With the swap applied, it is the one test that runs the slice and the legacy payment code together on one database.
- **Test helpers.** `BookingWorld.confirmed(...)` and `BookingRoutesTest.paid(...)` fake a successful webhook with three `UPDATE`s.

## 7. Behaviour and concurrency notes

- **No behaviour change found.** The race test (now with the sweep running alongside the 200 buyers), the equivalence script and the legacy web tests all passed against the slice.
- **The gateway is called while the write lock is held.** `createSession` and `refund` run inside `BEGIN IMMEDIATE`, as they did before. This preserves rollback when a refund fails, but a slow real gateway would hold the single writer lock. It needs a decision before a real gateway arrives.
- **Retried attempts.** If Jolt retries an attempt, `SqliteDb` gives it a fresh `Tx`, so events never double. Ids and gateway sessions are re-created in the retry, the same as under `SqliteUnitOfWork`.
- **Logging only.** A failing subscriber is now logged by `SqliteDb` (`bms.db`, "after-commit action failed: <class>") instead of `EventBuffer` (`bms.events`, "seat event publish failed for show N"). The commit still stands and the other events still go out.
- **One gap in the contracts.** `DbContract`, `SeatEventPublisherContract` and `PaymentGatewayContract` cover what the slice relies on, except one point: no contract says a gateway refund *failure* throws. Cancel-rollback depends on that, but `MockPaymentGateway` never fails, so only the fake shows it.
