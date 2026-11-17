# Jolt gaps

Every Jolt limitation the app hits. Each interim workaround carries `TODO(jolt#N)` at its location and is deleted when the fix ships in a Jolt release; then the row is marked done with the closing PR and the release.

Status: `open` (no fix released), `in progress` (fix branch exists), `released vX` (bump `jolt.version` and remove the workaround), `done` (workaround removed).

| Gap | Jolt issue | Workaround location | Status |
|---|---|---|---|
| Repeated query params (`req.queryAll`) | [#52](https://github.com/THIRU-1074/custom-Http-Server/issues/52) | none: `web.Query.list` reads `queryAll` and still splits comma lists | done (v0.4.1) |
| App lifecycle hooks (`onStart`, `onStop`) | [#53](https://github.com/THIRU-1074/custom-Http-Server/issues/53) | `Main` registers `app.onStop(db::close)`; `Booted.close` still ends SSE streams before `Server.close()`, because stop hooks run only after the 5 s drain (new gap B) | done (v0.4.1) |
| Idempotency-Key middleware | [#54](https://github.com/THIRU-1074/custom-Http-Server/issues/54) | `web.IdempotencyFilter` over the `IdempotencyStore` port | closed upstream, awaiting release v0.4.0 (bump jolt.version, remove workaround) |
| Schema migrations | [#55](https://github.com/THIRU-1074/custom-Http-Server/issues/55) | `adapters.db.SchemaRunner` | in progress |
| IMMEDIATE tx, generated keys, batch, optimistic update | [#56](https://github.com/THIRU-1074/custom-Http-Server/issues/56) | `adapters.db` (JDBC URL `transaction_mode=IMMEDIATE`, row-count checks) | in progress |
| Pagination helper | [#57](https://github.com/THIRU-1074/custom-Http-Server/issues/57) | `web.Paging`: Jolt `Page` and `Page.Result` build the envelope; the app still checks `page` and `size` itself because `Page.of` answers 400 without naming the field and silently caps `size`, where SPEC 7 wants 422 `errors.page`/`errors.size` | done (v0.4.1), parameter check kept |
| Scheduled background tasks | [#58](https://github.com/THIRU-1074/custom-Http-Server/issues/58) | none: `app.every("expiry-sweep", SWEEP_INTERVAL, ...)` in `Main` | done (v0.4.1) |
| Webhook signature verification | [#59](https://github.com/THIRU-1074/custom-Http-Server/issues/59) | `adapters.payment.HmacVerifier` | open |
| Configuration loader | [#60](https://github.com/THIRU-1074/custom-Http-Server/issues/60) | `AppConfig.fromEnv()` | open |
| Nested and collection validation | [#61](https://github.com/THIRU-1074/custom-Http-Server/issues/61) | nested paths built by `domain.SeatLayout` and `domain.SeatHold`; required-field checks in `web.BookingModule`, `web.AdminModule`, `web.PaymentModule` | open |
| Pub/sub hub for SSE | [#62](https://github.com/THIRU-1074/custom-Http-Server/issues/62) | `adapters.live.SeatEvents` | open |
| Static files and views from the classpath | [#68](https://github.com/THIRU-1074/custom-Http-Server/issues/68) | `Main` (`Static.serve("/", STATIC_DIR).spaFallback("index.html")`, `VIEWS_DIR`), `Dockerfile` | in progress |
| `JoltTest` support for SSE and streams | [#69](https://github.com/THIRU-1074/custom-Http-Server/issues/69) | `web.LiveSseTest` over a loopback socket | open |
| SSE event ids and `Last-Event-ID` | [#70](https://github.com/THIRU-1074/custom-Http-Server/issues/70) | `web.LiveModule` (`snapshot` event on every connect) | open |
| App-defined metrics and health checks | [#71](https://github.com/THIRU-1074/custom-Http-Server/issues/71) | `GET /api/health` DB check in `web.WebModules`, `GET /api/admin/stats` in `web.AdminModule` | open |
| Stock middleware refusals (`JwtAuth.middleware`, `Auth.requireRole`, `RateLimit`) bypass `problemJson` and `onError` and write `text/plain` | [#76](https://github.com/THIRU-1074/custom-Http-Server/issues/76) | none: `JwtAuth.middleware`, `Auth.requireRole`, `RateLimit` and `Csrf.protect` are installed as they are | done (v0.4.1) |
| `onError` mappers cannot see the request, so problem bodies cannot carry `requestId` | [#77](https://github.com/THIRU-1074/custom-Http-Server/issues/77) | bodies now come from `app.onError((e, req) -> ...)` and Jolt adds `requestId`; `web.Problems.middleware` only re-throws domain errors as `ApiException` (an `HttpException`) and unexpected ones as a bare 500, see new gap A below | done (v0.4.1) |

Not needed for v1: [#63](https://github.com/THIRU-1074/custom-Http-Server/issues/63) TTL cache (ETag covers the catalog).

## Where each workaround lives

Found with `grep -rn "TODO(jolt#" src`. Every gap with workaround code has a marker; #63 needs none.

| Jolt issue | File holding the workaround |
|---|---|
| #54 | `src/main/java/dev/thiru/bms/web/IdempotencyFilter.java`, `adapters/db/SqlIdempotency.java`, `src/main/resources/db/migrations/V3__idempotency.sql` |
| #55 | `src/main/java/dev/thiru/bms/adapters/db/SchemaRunner.java` |
| #56 | `src/main/java/dev/thiru/bms/adapters/db/Sqlite.java` |
| #59 | `src/main/java/dev/thiru/bms/adapters/payment/HmacVerifier.java`, `HmacWebhookVerifier.java`, `MockGatewayClient.java` (signing) |
| #60 | `src/main/java/dev/thiru/bms/AppConfig.java` |
| #61 | `src/main/java/dev/thiru/bms/web/BookingModule.java`, `AdminModule.java` (required-field checks) |
| #62 | `src/main/java/dev/thiru/bms/adapters/live/SeatEvents.java` |
| #68 | `src/main/java/dev/thiru/bms/Main.java`, `Dockerfile` |
| #69 | `src/test/java/dev/thiru/bms/web/LiveSseTest.java` |
| #70 | `src/main/java/dev/thiru/bms/web/LiveModule.java` |
| #71 | `src/main/java/dev/thiru/bms/web/WebModules.java` (`/api/health` DB check) |

## New gaps found in v0.4.1 (not filed yet)

| | Gap | Effect on the app | Workaround |
|---|---|---|---|
| A | Jolt logs every mapped exception that is not an `HttpException` at WARNING with its stack trace (`Mapped exception on GET /path`). Expected outcomes (a seat conflict, a 404) flood the log, and messages may carry user data. | SPEC 9 wants one log line per request. | `web.Problems.middleware` turns domain errors into `ApiException` (an `HttpException`) and logs unexpected ones by class and request id before re-throwing a bare 500. |
| B | No hook runs before `Server.close()` drains, and stop hooks run after it. Long-lived SSE streams hold the drain for its full 5 s grace period unless the app ends them first. | Slower shutdown. | `Main.Booted.close` closes every live stream, then calls `Server.close()`; the JVM shutdown hook calls `Booted.close` instead of `closeOnShutdown()`. |
