# Wave 2, lane A hand-back: catalog + live

Branch `feat/slice-catalog-live`, from `integration` at `229abe5`. New files only: `features/catalog`, `features/live`, `kernel/catalog/SeatMap.java` and their tests. No hot spot is edited.

## 1. Swap lines

In `WebModules.routes`:

```java
pipeline.module(new CatalogModule(catalog, pipeline.catalogCache()));
// becomes
pipeline.module(
        new dev.thiru.bms.features.catalog.CatalogRoutes(
                deps.db(), deps.clock(), deps.policy(), pipeline.catalogCache()));
```

```java
pipeline.module(
        new LiveModule(
                catalog, deps.live(), deps.clock(), settings.sseHeartbeat(), mapper));
// becomes
pipeline.module(
        new dev.thiru.bms.features.live.LiveRoutes(
                deps.db(),
                deps.live(),
                deps.clock(),
                deps.policy(),
                settings.sseHeartbeat(),
                mapper));
```

Then delete `CatalogQueries catalog = new CatalogQueries(deps.uow(), deps.clock(), deps.policy());` and the `application.CatalogQueries` import. Imports of `CatalogRoutes` and `LiveRoutes` can replace the qualified names.

**Deviation from the brief:** `LiveRoutes` also takes `BookingPolicy`. The snapshot carries `holdTtlSeconds`, which `LiveModule` got through `CatalogQueries`' policy, so the brief's five-argument constructor could not keep the snapshot identical.

No `Main` change.

## 2. Deletions

- Main: `web.CatalogModule`, `web.LiveModule`, `application.CatalogQueries`.
- Tests: `application.CatalogQueriesTest`, `web.CatalogApiTest`, `web.LiveSseTest` (their cases are in `features.catalog.CatalogSqlTest`, `CatalogRoutesTest`, `features.live.LiveRoutesTest` and `LiveSqlTest`, on the V2 seed). `web.CatalogCacheTest` stays and passes against the swapped routes unchanged.
- `Dtos` records: `MovieSummary`, `ScreenSummary`, `CinemaDetailDto`, `SlotDto`, `CinemaShowsDto`, `ShowTimesDto`, `MovieRef`, `NamedRef`, `PriceDto`, `ShowDetailDto`, `SeatDto`, `SeatRowDto`, `SeatMapDto`. Keep `CityDto`, `CinemaDto` and `MovieDetail` (used by `AdminModule`). `Dtos` then no longer imports `CatalogQueries`. With the swap applied, a grep found no other users of the deleted records.

## 3. `WEB_RECORDS`

Replace (the `Dtos` record is deleted):

| Schema | Record |
|---|---|
| MovieSummary | `dev.thiru.bms.features.catalog.CatalogDtos$MovieSummary` |
| ScreenSummary | `dev.thiru.bms.features.catalog.CatalogDtos$ScreenSummary` |
| CinemaDetail | `dev.thiru.bms.features.catalog.CatalogDtos$CinemaDetailDto` |
| Slot | `dev.thiru.bms.features.catalog.CatalogDtos$SlotDto` |
| CinemaShows | `dev.thiru.bms.features.catalog.CatalogDtos$CinemaShowsDto` |
| ShowTimes | `dev.thiru.bms.features.catalog.CatalogDtos$ShowTimesDto` |
| MovieRef | `dev.thiru.bms.features.catalog.CatalogDtos$MovieRef` |
| NamedRef | `dev.thiru.bms.features.catalog.CatalogDtos$NamedRef` |
| ShowDetail | `dev.thiru.bms.features.catalog.CatalogDtos$ShowDetailDto` |
| Price | `dev.thiru.bms.features.catalog.CatalogDtos$PriceDto` and `dev.thiru.bms.features.live.LiveDtos$PriceDto` |
| Seat | `dev.thiru.bms.features.catalog.CatalogDtos$SeatDto` and `dev.thiru.bms.features.live.LiveDtos$SeatDto` |
| SeatRow | `dev.thiru.bms.features.catalog.CatalogDtos$SeatRowDto` and `dev.thiru.bms.features.live.LiveDtos$SeatRowDto` |
| SeatMap | `dev.thiru.bms.features.catalog.CatalogDtos$SeatMapDto` and `dev.thiru.bms.features.live.LiveDtos$SeatMapDto` |

Add alongside (the `Dtos` record stays for `AdminModule`):

| Schema | Record |
|---|---|
| City | `dev.thiru.bms.features.catalog.CatalogDtos$CityDto` |
| Cinema | `dev.thiru.bms.features.catalog.CatalogDtos$CinemaDto` |
| MovieDetail | `dev.thiru.bms.features.catalog.CatalogDtos$MovieDetail` |

Live's nested `PriceDto`, `SeatDto` and `SeatRowDto` are listed too, so all of live's copy is checked, not only its top record.

## 4. Swap proof

The swap lines above were applied in the working tree, along with the `WEB_RECORDS` changes from section 3, and the full `mvn -B verify` was run, legacy classes still in place. Then both files were reverted (`git checkout --`).

**Result: BUILD SUCCESS, 560 tests, 0 failures, 0 errors, 0 skipped**, with `spotless:check` and jacoco passing. That is 523 on `integration` plus the slice's 37. The suites the brief names were all green against the swapped routes:

- `CatalogApiTest` 8, `LiveSseTest` 3, `CatalogCacheTest` 5 (unchanged)
- `EndToEndTest` 2 (both journeys)
- `ApiSchemaTest`, `ApiCoverageTest` 5, `RecordGeneratorTest` 4 (with the new entries)
- `RbacMatrixTest` 3, `RbacTest` 12, `HttpPipelineTest` 3, `SliceKitTest` 5
- `ArchitectureTest` 5, `TargetArchitectureTest` 13
- every contract suite, including `LiveSeatHubContractTest`, `FakeLiveContractTest` and `SqliteDbContractTest`

With the swap reverted, the branch is also green at 560 tests.

At step 3, `CatalogApiTest` and `LiveSseTest` can be deleted outright: every case they hold is ported to the slice tests on the V2 seed.

Also checked, not committed: a scratch test mounted `CatalogModule` (on `CatalogQueries`) and `CatalogRoutes` on two kits over the same V2 template and compared 165 GETs. The URLs covered every movie, cinema and city, show listings on two dates, every 37th show and its seat map, and the 404 and 422 paths. Status, body, `Cache-Control` and `ETag` were byte-identical. Two things were left out of the comparison: the per-request `requestId` in problems, and the seat map's `version` (see section 6).

## 5. Lines of code

| | Legacy (deleted at step 3) | Slice (new) |
|---|---|---|
| Routes | `CatalogModule` 131, `LiveModule` 100 | `CatalogRoutes` 120, `LiveRoutes` 100 |
| Queries and SQL | `CatalogQueries` 309 | `CatalogSql` 603, `LiveSql` 137 |
| Assembly | (inside `CatalogQueries`) | `kernel.catalog.SeatMap` 81 |
| Records | about 160 lines of `Dtos` | `CatalogDtos` 131, `LiveDtos` 53 |
| **Main total** | **about 700** | **1225** |
| Tests | `CatalogQueriesTest` 267, `CatalogApiTest` 157, `LiveSseTest` 102 (526) | 1185 |

The slice is larger because it now owns its SQL. Today that SQL sits in `adapters.db.SqlCatalog` (read parts, about 400 of its 478 lines) and `SqlSeatLocks.findByShow/countLive`. Those classes are not deleted at step 3: admin and booking still use them until waves 3 to 5. Live repeats about 70 lines of seat-map SQL from catalog, which PLAN.md accepts. The tests grew because they now also cover the cache, field order, show start and the cancelled-show cases of the stream.

## 6. Notes and behaviour questions

- **JSON columns.** There is no `kernel.Json` yet, and slices may not import `adapters.db.Json`, so `layout_json` and `prices_json` are read with SQLite's JSON1 functions (`json_each`, `json_extract`). The V2 seed already uses these. Integer arrays come back as SQLite's text (`[4,12]`) and are split in a few lines.
- **Screen capacity** in show times and cinema detail is read from the `screen.capacity` column, not recomputed from the layout. Every writer stores `layout.capacity()` there (seed and `SqlAdmin`), so the values are the same.
- **Seat map `version`.** `version` keeps the legacy algorithm: `Arrays.hashCode` over the status enum constants. Enum hash codes are identity hashes, so the value is stable within one JVM but changes across restarts and between the old and new enum. It still changes exactly when the map does, as SPEC says. Hashing `ordinal()` or `name()` instead would make the value, and the seat map `ETag`, survive a restart. That is a small behaviour change, so it is not made here. Question: should it be?
- **Unreachable differences.** A show whose screen, cinema, city or movie row is missing gave a 500 (`NoSuchElementException`) before and gives a 404 `Show` now, because the slice reads the show in one join. Foreign keys make this unreachable.
- **No port behaviour without a contract.** The slice relies on `LiveSeats.subscribe` returning empty at the cap, `poll` returning null on timeout or end, and `isOpen` going false after `close`. `LiveSeatsContract` covers all three.
