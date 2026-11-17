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
| Webhook signature verification | [#59](https://github.com/THIRU-1074/custom-Http-Server/issues/59) | verification is `Webhooks.hmacSha256(WEBHOOK_SECRET, "Bms-Signature").prefix("v1=").timestampHeader("Bms-Timestamp")` on the webhook route; `adapters.payment.WebhookSigner` stays because Jolt has no signer and the mock gateway must sign | done (v0.4.1) |
| Configuration loader | [#60](https://github.com/THIRU-1074/custom-Http-Server/issues/60) | none: `AppConfig.from(Config)` reads every SPEC variable through Jolt's `Config.load()` and keeps only the app's own checks (secret length, admin pair, SQLite URL, base URL) | done (v0.4.1) |
| Nested and collection validation | [#61](https://github.com/THIRU-1074/custom-Http-Server/issues/61) | request shape in `web.BookingModule.HOLD` and `web.AdminModule.SHOW` is a Jolt `Validator` (`Rules.each`, `Rules.nested`, all errors in one 422). Kept: `domain.SeatLayout` and `domain.SeatHold` build their own `rows[3].seats` keys because the domain may not import Jolt (SPEC 10); `web.PaymentModule` has only flat fields | done (v0.4.1) |
| Pub/sub hub for SSE | [#62](https://github.com/THIRU-1074/custom-Http-Server/issues/62) | `adapters.live.LiveSeatHub` publishes to a Jolt `Hub` (one topic per show; cap, bounded queues and slow-subscriber drop are Jolt's) and keeps a small hand-off queue so the pull-based `LiveSeats` port and the snapshot-first route stay as they are; it also tracks streams to end them at shutdown (new gap C) | done (v0.4.1) |
| Static files and views from the classpath | [#68](https://github.com/THIRU-1074/custom-Http-Server/issues/68) | `Main` (`Static.serve("/", STATIC_DIR).spaFallback("index.html")`, `VIEWS_DIR`), `Dockerfile` | in progress |
| `JoltTest` support for SSE and streams | [#69](https://github.com/THIRU-1074/custom-Http-Server/issues/69) | none: `web.LiveSseTest` runs through `JoltTest` (`maxEvents`, `timeout`, `events()`); `EndToEndTest` still streams over a real socket on purpose | done (v0.4.1) |
| SSE event ids and `Last-Event-ID` | [#70](https://github.com/THIRU-1074/custom-Http-Server/issues/70) | seat events carry the hub's `id:` through `EventStream.event(id, name, data)`. Kept on purpose: a `snapshot` on every connect, including one with `Last-Event-ID`, because SPEC 7 and the UI rely on it and it already restores the full state; replaying older events after it adds nothing | done (v0.4.1) |
| App-defined metrics and health checks | [#71](https://github.com/THIRU-1074/custom-Http-Server/issues/71) | `app.health("db", ...)` feeds `/_jolt/health`, and the hub adds `bms_sse_subscribers` and `bms_sse_dropped_total` to `/_jolt/metrics`. Kept: `GET /api/health` with its `{"status","db"}` shape, which Render's health check and the Docker `HEALTHCHECK` use (new gap D), and `GET /api/admin/stats`, which is a SPEC 7 admin endpoint | done (v0.4.1) |
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
| #68 | `src/main/java/dev/thiru/bms/Main.java`, `Dockerfile` |

## New gaps found in v0.4.1 (not filed yet)

| | Gap | Effect on the app | Workaround |
|---|---|---|---|
| A | Jolt logs every mapped exception that is not an `HttpException` at WARNING with its stack trace (`Mapped exception on GET /path`). Expected outcomes (a seat conflict, a 404) flood the log, and messages may carry user data. | SPEC 9 wants one log line per request. | `web.Problems.middleware` turns domain errors into `ApiException` (an `HttpException`) and logs unexpected ones by class and request id before re-throwing a bare 500. |
| B | No hook runs before `Server.close()` drains, and stop hooks run after it. Long-lived SSE streams hold the drain for its full 5 s grace period unless the app ends them first. | Slower shutdown. | `Main.Booted.close` closes every live stream, then calls `Server.close()`; the JVM shutdown hook calls `Booted.close` instead of `closeOnShutdown()`. |
| C | `Hub` cannot end the subscribers of a topic or of the whole hub, `publish` does not return the id it assigned, and with `replay > 0` a topic is never removed, so its replay buffer outlives the show. `hub.sse` also cannot send a first event (the snapshot) before the stream, nor named events. | The app subscribes with `hub.subscribe` and writes SSE itself, tracks its own streams for shutdown, and runs with replay 0. | `adapters.live.LiveSeatHub` |
| D | Registered health checks cannot be run from app code, and `/_jolt/health` only exists when metrics are exposed. An app that must keep its own health URL and shape cannot reuse the checks. | `/api/health` calls the same `dbUp` supplier as the `db` check, so the two cannot drift. | `web.WebModules` |
