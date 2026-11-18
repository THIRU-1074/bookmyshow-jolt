# Wave 5 hand-back: admin (admin and theatre owner)

Branch `feat/slice-admin`, from `integration` at `170dc99` (wave 2 live).

**What the branch adds:**

- `src/main/java/dev/thiru/bms/features/admin/` (one slice, two route classes, as PLAN.md's class map says)
- `src/test/java/dev/thiru/bms/features/admin/`
- `kernel.account.CinemaScope` and its test (the class map moves the rule there; the link lookup stays in the slice)
- `testing.RecordingGateway` and `testing.RecordingEvents` with their contract tests: byte-identical copies of the booking lane's fakes (`feat/slice-booking`, `121a066`). Git merges identical additions cleanly, so wave 3 and wave 5 can land in either order.

No hot spot is edited. `SliceKit`, `Permissions`, `RBAC.md`, the schema and the migrations are unchanged.

| File | Role |
|---|---|
| `AdminRoutes` | `RouteModule` for the 22 `/api/admin` routes: `AdminRoutes(Db, Clock, PaymentGateway, SeatEvents, LiveSeats, Middleware idempotent)` |
| `OwnerRoutes` | `RouteModule` for the 7 `/api/owner` routes: `OwnerRoutes(Db, Clock, PaymentGateway, SeatEvents, Middleware idempotent)`. Each handler builds `CinemaScope.forCaller(userId, isAdmin)` from `web.Principal` and calls the same use cases as the admin routes |
| `AdminCatalog` | cities, cinemas, screens, movies and shows: existence, name uniqueness, "still in use", the screen-layout-with-future-shows 409, the show overlap rule (`kernel.catalog.Show.schedule`), the "locked seats" and "cancelled show" 409s, and cinema scope |
| `CancelShow` | releases active holds, refunds confirmed bookings in full through `PaymentGateway`, cancels open ones, version check with one retry, seat events through `Tx.afterCommit` |
| `AdminReports` | show reports (filtered in SQL by city, window and owner link), per-category detail, stats |
| `UserAdmin` | user list and search, grant, revoke (AUDIENCE 422, self ADMIN 409, THEATRE_OWNER clears links), link and unlink |
| `AdminSql` | shared reads, the `cinema_owner` lookup behind `CinemaScope`, and the `layout_json` and `prices_json` writers, byte for byte as `adapters.db.Json` writes them |
| `AdminDtos` | copies of the `AdminModule` request records and the admin `Dtos` records, `CityDto`, `CinemaDto` and `MovieDetail` included |

**Catalog cache.** As in legacy, the slice never touches the cache: `HttpPipeline` clears it after every successful non-GET under `/api/admin` and `/api/owner`. `AdminRoutesTest.successfulWritesClearThePublicCatalogCacheAndFailedOnesDoNot` proves it against the slice through `pipeline.catalogCache()`.

## 1. Swap lines (against integration's `WebModules`, `170dc99`)

In `WebModules.routes`, replace these five statements:

```java
AdminCatalog adminCatalog = new AdminCatalog(deps.uow(), deps.clock());
AdminReports reports = new AdminReports(deps.uow(), deps.clock());
CancelShow cancelShow =
        new CancelShow(deps.uow(), deps.gateway(), deps.publisher(), deps.clock());
pipeline.module(new AdminModule(adminCatalog, reports, cancelShow, new UserAdmin(deps.uow()), deps.live(), idempotent));
pipeline.module(new OwnerModule(adminCatalog, reports, cancelShow, idempotent));
```

with:

```java
pipeline.module(
        new AdminRoutes(
                deps.db(),
                deps.clock(),
                deps.gateway(),
                deps.publisher(),
                deps.live(),
                idempotent));
pipeline.module(
        new OwnerRoutes(deps.db(), deps.clock(), deps.gateway(), deps.publisher(), idempotent));
```

Then delete the imports of `application.AdminCatalog`, `AdminReports`, `CancelShow` and `UserAdmin`, and add `features.admin.AdminRoutes` and `features.admin.OwnerRoutes`. Run `mvn spotless:apply` to sort the imports. No `Main` or `WebDeps` change is needed.

These lines do not overlap wave 3's `BookingModule` block, so the two swaps apply in either order.

## 2. Deletions

**Main code:**

- `web.AdminModule` (414 lines) and `web.OwnerModule` (138)
- `application.AdminCatalog` (287), `AdminReports` (113), `UserAdmin` (117) and `CinemaScope` (34)
- `application.CancelShow` (80). Wave 4 blocks it, see section 6
- In `web.Principal`, delete `cinemaScope()` and the `application.CinemaScope` import. Nothing else calls it once `OwnerModule` is gone
- **`Dtos` records:** the whole `// ---- catalog ----` block (`CityDto`, `MovieDetail` and `CinemaDto`, which only `AdminModule` still uses since wave 2), and everything from `// ---- admin ----` to the end:
  - `ScreenDto`, `ShowDto`, `ShowReportDto`, `CategoryReportDto`, `ShowReportDetailDto`, `StatsDto`, `AccountDto` and `OwnedCinemaDto`
  - `CategoryRequest`, `RowRequest` and `LayoutRequest`

  That is about 212 lines. `Dtos` then drops the imports of `application.AdminCatalog`, `AdminReports` and `UserAdmin`, `domain.port.AdminRepositories`, `kernel.account.User`, `kernel.catalog.{Cinema, City, Movie, Screen, SeatLayout, Show}`, `LocalDate` and `Map`. After wave 3 deletes the booking block too, `Dtos` is empty and can go.

**Keep until wave 6:** `domain.port.AdminRepositories`, `adapters.db.SqlAdmin`, `AdminRepositoriesTest`, the admin parts of `Repositories`, `SqlRepositories`, `FakeWorld` and `RepositoriesContract`. They are repository ceremony that wave 6 deletes as a whole. No main code uses them after the swap.

**Tests, ported to the slice:**

| Legacy test | Ported to |
|---|---|
| `web.AdminApiTest` (6) | `AdminRoutesTest` |
| `web.AdminUsersApiTest` (4) | `AdminRoutesTest` (users, roles and links) |
| `web.OwnerApiTest` (5) | `OwnerRoutesTest` |
| `application.AdminCatalogTest` (6) | `AdminCatalogTest` |
| `application.CinemaScopeTest` (6) | `AdminCatalogTest`, `CancelShowTest`, `AdminReportsTest` and `kernel.account.CinemaScopeTest` |
| `application.UserAdminTest` (5) | `UserAdminTest` |
| `application.CancelShowTest` (3) | `CancelShowTest` |
| the two report cases of `AdminReportsAndQueriesTest` | `AdminReportsTest` |

`CatalogCacheTest.anAdminCatalogWriteClearsTheCache` stays in `web`. It passes against the swapped routes unchanged.

## 3. `WEB_RECORDS` (all *replace*)

| Schema | Record |
|---|---|
| City | `dev.thiru.bms.features.admin.AdminDtos$CityDto`, replacing `web.Dtos$CityDto` (catalog's entry stays) |
| Cinema | `...AdminDtos$CinemaDto`, replacing `Dtos$CinemaDto` (catalog's entry stays) |
| MovieDetail | `...AdminDtos$MovieDetail`, replacing `Dtos$MovieDetail` (catalog's entry stays) |
| LayoutCategory, LayoutRow, Layout | `...AdminDtos$CategoryRequest`, `$RowRequest`, `$LayoutRequest` |
| Screen, Show | `...AdminDtos$ScreenDto`, `$ShowDto` |
| ShowReport, CategoryReport, ShowReportDetail | `...AdminDtos$ShowReportDto`, `$CategoryReportDto`, `$ShowReportDetailDto` |
| Stats, Account, OwnedCinema | `...AdminDtos$StatsDto`, `$AccountDto`, `$OwnedCinemaDto` |
| CityRequest, RenameRequest, CinemaRequest, ScreenRequest, MovieRequest, ShowRequest, CancelShowResult | `...AdminDtos$<same name>`, replacing `web.AdminModule$<same name>` |

In `RecordGeneratorTest.webRecords()`, remove those 14 rows from the `dtos` array. Add an `adminDtos` array with the same 14 schema-to-record pairs, prefixed with `dev.thiru.bms.features.admin.AdminDtos$`. Change the `AdminModule$` loop to use that prefix. `ShowReportPage` and `AccountPage` stay on `in.jolt.Page$Result`. The `dtos` rows sit next to booking's, so after wave 3 lands, apply this edit by hand rather than as a patch.

## 4. Verify totals

**With the swap applied: BUILD SUCCESS, 622 tests, 0 failures, 0 errors, 0 skipped.** `spotless:check` and jacoco also passed. The working tree had the section 1 swap and the section 3 `WEB_RECORDS` changes, with every legacy class still in place. It was reverted afterwards (`git checkout` of the two files). All of these ran against the slice's routes:

- `ApiSchemaTest` with no schema edit, `ApiCoverageTest` and `RecordGeneratorTest` (4)
- both `EndToEndTest` journeys (2), the owner journey included
- `RbacTest` (13) and `RbacMatrixTest` (3)
- `AdminApiTest` (6), `AdminUsersApiTest` (4) and `OwnerApiTest` (5)
- `CatalogCacheTest` (5)
- `ArchitectureTest` (5) and `TargetArchitectureTest` (13)
- every contract suite, including the two ported fake contracts

`CancelShowTest` (3) and `AdminReportsAndQueriesTest` (5) are FakeWorld tests of the legacy classes, so the swap does not change them. They stayed green.

**This branch, without the swap: 622 tests**, BUILD SUCCESS. That is 567 on `integration` plus 55 new: 46 slice tests, 3 for `kernel.account.CinemaScope`, and 6 fake contract checks.

**Equivalence check** (scratch, not committed). A JUnit test mounted the legacy `AdminModule` and `OwnerModule` on the legacy use cases over `legacyUnitOfWork()` on one `SliceKit`. It mounted `AdminRoutes` and `OwnerRoutes` on a second `SliceKit` from the same V2 template. Both kits had the same clock and identical fixtures:

- users: an admin, an owner of cinema 2, an owner of cinema 1, an audience member
- on a cinema 2 show: confirmed, payment-pending and held bookings, plus an active hold
- confirmed bookings on a cinema 1 show and on another cinema 2 show

The same 132-request script ran against both. It covered all 29 routes:

- creates, replays (`Idempotency-Replayed`), a missing key, a missing body, and every 409, 404 and 422 path for cities, cinemas, screens, movies and shows
- nested layout errors, overlap, a start in the past, bad prices, bad format, unknown movie or screen
- the layout-change-with-future-shows 409, the edit of a show with locked seats or a cancelled show, every delete with and without dependants
- reports with city, date, instant and bad bounds and paging, report detail, and stats with real bookings, before and after refunds
- user list, search (including a literal `%`) and paging; grant, repeat grant, a bad role, the AUDIENCE 422, the self-demote 409 (both cases), unknown user and cinema; link, unlink, revoke-clears-links, grant and revoke ADMIN
- 401 and 403 for each role on both prefixes
- every owner route in scope and out of scope (404 for another owner's cinema, screen, show and report)
- an admin through the owner routes
- show cancel by the wrong owner (404), by the right owner and by an admin, with a replay, a repeat and an unknown show
- unlink taking effect at once with the old token

**Result: 0 differences.** The comparison covered status, body (only `requestId` normalised) and the `Cache-Control`, `Content-Type`, `Idempotency-Replayed`, `Location`, `ETag` and `Vary` headers. These were also identical:

- the 6 seat events, in the same order
- the 3 gateway refunds
- the final rows of `city`, `cinema`, `screen`, `movie`, `movie_language`, `movie_genre`, `show`, `app_user` (roles), `cinema_owner`, `seat_hold`, `seat_lock`, `booking`, `booking_seat` and `payment` (1 026 dump lines). `layout_json` and `prices_json` matched byte for byte

Status mix: 42 × 200, 12 × 201, 3 × 204, 4 × 400, 2 × 401, 3 × 403, 31 × 404, 15 × 409, 20 × 422.

## 5. Lines of code

| | Legacy | Slice (new) |
|---|---|---|
| Routes | `AdminModule` 414, `OwnerModule` 138 (552) | `AdminRoutes` 407, `OwnerRoutes` 112 (519) |
| Use cases | `AdminCatalog` 287, `AdminReports` 113, `CancelShow` 80, `UserAdmin` 117, `CinemaScope` 34 (631) | 422, 194, 202, 161 (979, SQL included), plus `kernel.account.CinemaScope` 49 |
| SQL | `SqlAdmin` 490, `AdminRepositories` 110, plus parts of `SqlCatalog`, `SqlBookings` and `SqlSeatLocks`; all stay until wave 6 | `AdminSql` 326 (shared reads, scope lookup, JSON writers) |
| Records | about 212 lines of `Dtos` | `AdminDtos` 264 |
| **Main total** | **about 1 395 without SQL**, about 2 000 with `SqlAdmin` and `AdminRepositories` | **2 137** (2 088 slice + 49 kernel) |
| Tests | 1 071 (`AdminApiTest`, `OwnerApiTest`, `AdminUsersApiTest`, `AdminCatalogTest`, `CinemaScopeTest`, `UserAdminTest`, `CancelShowTest`), plus `AdminRepositoriesTest` 366 in wave 6 | 1 902 (1 847 slice, with the 252-line `AdminFixtures`, plus 55 kernel). The ported fakes add 95 |

The use cases grew because each one now holds its own SQL. Across use cases and SQL, the slice is about 1 300 lines against legacy's about 1 230, before the shared `SqlCatalog`, `SqlBookings` and `SqlSeatLocks` code that admin also used.

## 6. Legacy tests and fixtures that block deletion

- **`application.ProcessPaymentWebhookTest`** (wave 4) calls `new CancelShow(w, w, w, w.clock)` in `successOnACancelledBookingRefundsInFull` (line 122). Deleting `application.CancelShow` breaks its compilation. There are three options:
  - keep `CancelShow` until wave 4 moves that test
  - replace the call with FakeWorld writes: cancel the show and move the booking to CANCELLED
  - move the case into the payments slice
- **`application.AdminReportsAndQueriesTest`** builds its fixture with `CreateBooking`, `Checkout` and `ProcessPaymentWebhook`, and mixes three slices' cases:
  - 2 `AdminReports` cases, ported here
  - 2 `BookingQueries` cases, which wave 3 ported
  - 1 `MockGatewayPayments` case, for wave 4

  Deleting `AdminReports` stops the whole class compiling. At step 3, delete the two report cases and the `AdminReports` import, and keep the class until waves 3 and 4 finish.
- **`web.Principal.cinemaScope()`** imports `application.CinemaScope`. Delete both in the same commit (section 2).
- **`docs/RBAC.md`** items 5 and the "Owner routes" paragraph name `Principal.cinemaScope()`, `CinemaScope.require` and `AdminCatalog`, `CancelShow` and `AdminReports`. Behaviour is unchanged, so these are wording changes only, for the integration lane: the rule is now `kernel.account.CinemaScope`, and the lookup is `features.admin.AdminSql`. `contract.PaymentGatewayContract`'s Javadoc mentions `CancelShow` and is still accurate.
- **No `WebKit` or `FakeWorld` change is needed.** The legacy web tests run on SQLite and passed against the slice.

I did not run a trial deletion and `test-compile` on the working tree, because the lane brief forbids deleting legacy classes. The blockers above come from searching every reference to the classes listed in section 2.

## 7. Behaviour notes and questions

- **Live streams on show cancel.** Legacy `CancelShow` does not close streams itself. It saves CANCELLED and publishes `seat-released` with reason `CANCELLED`. `LiveRoutes` ends each stream on its next heartbeat, when it re-reads the status (or at once for a new subscriber). The slice does exactly the same, as the brief's "exactly like legacy" requires. If the main session wants an immediate close, that needs a `LiveSeats` or `SeatEvents` capability (a "show ended" event or `close(showId)`): a port change for the integration lane.
- **The gateway is called inside the write transaction**, as in legacy and in the booking slice. A refund failure rolls everything back and publishes nothing (`CancelShowTest.aFailingRefundLeavesEverythingUntouchedAndPublishesNothing`). A slow real gateway would hold the SQLite writer lock.
- **Logging only.** A failing seat-event subscriber is now logged by `SqliteDb` (`bms.db`) instead of `EventBuffer` (`bms.events`). The commit stands either way.
- **A missing JSON body is a 400 from Jolt**, not the slice's 422 `body: is required`. That is the same as legacy, which the equivalence check confirmed. The 422 path is reachable only with a literal `null` body.
- **A non-numeric path id is 404**, through `web.Query.id`, the same as legacy.
- **Hold release in show cancel** is `UPDATE seat_hold SET status = 'RELEASED'` on rows selected as ACTIVE. That is `SeatHold.release()`'s only effect, so the slice skips mapping `seats_json`.
- **No contract gap found** beyond the one the booking lane noted: no contract says a gateway refund failure throws, though cancel-rollback relies on it. `MockPaymentGateway` never fails, so only the fake shows it.
- **No RBAC question.** Every scope rule in RBAC.md is covered by a slice test: out of scope is 404, the self-demote is 409, AUDIENCE cannot be revoked (422), revoking THEATRE_OWNER clears the links, and links are read on every request (unlink and revoke with an old token).
