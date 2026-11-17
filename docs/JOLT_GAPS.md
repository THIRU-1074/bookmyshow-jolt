# Jolt gaps

Every Jolt limitation the app hits. Each interim workaround carries `TODO(jolt#N)` at its location and is deleted when the fix ships in a Jolt release; then the row is marked done with the closing PR and the release.

Status: `open` (no fix released), `in progress` (fix branch exists), `released vX` (bump `jolt.version` and remove the workaround), `done` (workaround removed).

| Gap | Jolt issue | Workaround location | Status |
|---|---|---|---|
| Repeated query params (`req.queryAll`) | [#52](https://github.com/THIRU-1074/custom-Http-Server/issues/52) | `web.Query` (comma lists only) | in progress |
| App lifecycle hooks (`onStart`, `onStop`) | [#53](https://github.com/THIRU-1074/custom-Http-Server/issues/53) | `Main` (sweeper start, shutdown hook) | in progress |
| Idempotency-Key middleware | [#54](https://github.com/THIRU-1074/custom-Http-Server/issues/54) | `web.IdempotencyFilter` over the `IdempotencyStore` port | in progress |
| Schema migrations | [#55](https://github.com/THIRU-1074/custom-Http-Server/issues/55) | `adapters.db.SchemaRunner` | in progress |
| IMMEDIATE tx, generated keys, batch, optimistic update | [#56](https://github.com/THIRU-1074/custom-Http-Server/issues/56) | `adapters.db` (JDBC URL `transaction_mode=IMMEDIATE`, row-count checks) | in progress |
| Pagination helper | [#57](https://github.com/THIRU-1074/custom-Http-Server/issues/57) | `web.PageRequest`, `web.PageResult` | open |
| Scheduled background tasks | [#58](https://github.com/THIRU-1074/custom-Http-Server/issues/58) | hold expiry sweeper in `Main` | open |
| Webhook signature verification | [#59](https://github.com/THIRU-1074/custom-Http-Server/issues/59) | `adapters.payment.HmacVerifier` | open |
| Configuration loader | [#60](https://github.com/THIRU-1074/custom-Http-Server/issues/60) | `AppConfig.fromEnv()` | open |
| Nested and collection validation | [#61](https://github.com/THIRU-1074/custom-Http-Server/issues/61) | nested paths built by `domain.SeatLayout` and `domain.SeatHold`; required-field checks in `web.BookingModule`, `web.AdminModule`, `web.PaymentModule` | open |
| Pub/sub hub for SSE | [#62](https://github.com/THIRU-1074/custom-Http-Server/issues/62) | `adapters.live.SeatEvents` | open |
| Static files and views from the classpath | [#68](https://github.com/THIRU-1074/custom-Http-Server/issues/68) | `Main` (`Static.serve("/", STATIC_DIR).spaFallback("index.html")`, `VIEWS_DIR`), `Dockerfile` | in progress |
| `JoltTest` support for SSE and streams | [#69](https://github.com/THIRU-1074/custom-Http-Server/issues/69) | `web.LiveSseTest` over a loopback socket | open |
| SSE event ids and `Last-Event-ID` | [#70](https://github.com/THIRU-1074/custom-Http-Server/issues/70) | `web.LiveModule` (`snapshot` event on every connect) | open |
| App-defined metrics and health checks | [#71](https://github.com/THIRU-1074/custom-Http-Server/issues/71) | `GET /api/health` DB check in `web.WebModules`, `GET /api/admin/stats` in `web.AdminModule` | open |
| Stock middleware refusals (`JwtAuth.middleware`, `Auth.requireRole`, `RateLimit`) bypass `problemJson` and `onError` and write `text/plain` | none yet (to be filed) | `web.Guards` re-raises the refusal as an exception so it leaves as problem+json | open |
| `onError` mappers cannot see the request, so problem bodies cannot carry `requestId` | none yet (to be filed) | `web.Problems.middleware` catches handler failures and writes the problem itself | open |

Not needed for v1: [#63](https://github.com/THIRU-1074/custom-Http-Server/issues/63) TTL cache (ETag covers the catalog).
