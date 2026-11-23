# BookMyShow on Jolt

A movie ticketing app (cities, movies, shows, seat holds, payments, tickets) built on [Jolt](https://github.com/THIRU-1074/custom-Http-Server), an Express-style Java HTTP framework. It is Jolt's reference app: every framework gap it hits becomes a Jolt issue, gets fixed upstream, and is listed in [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md).

Status: **1.0.0**, built on Jolt v0.4.2. Live at https://bookmyshow-jolt.onrender.com (free tier, so the first request after idle is slow and the database resets on each deploy). What is in the release, and its known limits, are in [docs/RELEASE-1.0.0.md](docs/RELEASE-1.0.0.md). The contract is [docs/SPEC.md](docs/SPEC.md). Every Jolt gap from #52 to #77 is fixed upstream and the app uses the Jolt API for it; [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md) says how each was replaced and lists what is still worked around.

## Run

Requires JDK 21+ and Maven. Jolt resolves from [JitPack](https://jitpack.io). The web UI also needs Node 20+ to build; the Java build and tests do not.

```bash
(cd web && npm ci && npm run build)   # React UI to web/dist, which the server serves
mvn -B verify          # tests, formatting check, coverage report in target/site/jacoco
JWT_SECRET=$(openssl rand -hex 32) WEBHOOK_SECRET=$(openssl rand -hex 32) \
  ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=change-me-please-123 \
  mvn -q compile exec:java
curl http://127.0.0.1:8080/api/health
```

`JWT_SECRET` and `WEBHOOK_SECRET` (at least 32 bytes each) are required; startup fails fast without them. The server binds to loopback on `PORT` (default 8080). Set `JOLT_BIND_ADDRESS=0.0.0.0` to expose it. The full environment variable table is in [SPEC section 9](docs/SPEC.md#9-non-functional); settings are read with Jolt's `Config.load()`, so an `application.properties` in the working directory or on the class path can supply them too (the environment wins). `STATIC_DIR` defaults to `web/dist` relative to the working directory, so build the UI and run from the repository root (startup fails fast if it is missing). The ticket and mock-gateway views are read from the class path unless `VIEWS_DIR` names a directory; they link `/css/app.css`, which the UI build ships from `web/public`.

With `METRICS_TOKEN` set, `/_jolt/health` reports the database check and `/_jolt/metrics` (behind `Authorization: Bearer <METRICS_TOKEN>`) adds live SSE streams, catalog cache hits and the expiry sweep runs to Jolt's own series.

### Web UI

The UI is a React 18 single-page app (Vite, strict TypeScript) in `web/`, branded Houselights. It is built to static files and served by the Java server on the same origin, so there is no CORS and no Node process in production.

```bash
cd web
npm ci
npm run dev     # http://localhost:5173, proxies /api and /mock-gateway to http://localhost:8080
npm run lint    # ESLint and tsc
npm test        # Vitest and Testing Library
npm run build   # web/dist
```

For `npm run dev`, start the Java server on port 8080 first (see above). The dev server proxies the backend paths, so the refresh cookie, CSRF checks and the mock gateway behave as in production. Hashed files under `/assets/` are cached for a year; `index.html` is revalidated on every load.

### Docker

```bash
docker build -t bookmyshow-jolt .
docker run -p 8080:8080 -v bms-data:/data \
  -e JWT_SECRET=... -e WEBHOOK_SECRET=... bookmyshow-jolt
```

The image builds the UI in a `node:20-alpine` stage, runs as a non-root user, keeps the database in the `/data` volume, binds `0.0.0.0` and has a `HEALTHCHECK` on `/api/health`.

### Render

`render.yaml` deploys the image as one free web service. Render generates `JWT_SECRET` and `WEBHOOK_SECRET`, and `PUBLIC_BASE_URL` falls back to the `RENDER_EXTERNAL_URL` Render sets, so checkout links and the mock gateway webhook use the public address. Set `ADMIN_EMAIL` and `ADMIN_PASSWORD` in the dashboard to get an admin. The free plan's disk is ephemeral: the database resets on every deploy or restart.

## Layout

```
src/main/java/dev/thiru/bms/
  kernel/            pure shared rules and value objects (JDK only)
  ports/             the real I/O seams: Db, PaymentGateway, SeatEvents, LiveSeats, ...
  features/          one vertical slice each: catalog, live, accounts, booking, payments, admin
  adapters/db/       SQLite on jolt-db: ports.Db, refresh-token and idempotency stores, migrations
  adapters/auth/     PBKDF2 password hashing on jolt-auth
  adapters/ids/      random UUIDv4 ids
  adapters/payment/  mock gateway and its webhook signer (Jolt verifies)
  adapters/live/     seat event fan-out for SSE on Jolt's Hub
  web/               shared HTTP kit: pipeline, RBAC gate, problems, paging, the route table
  AppConfig.java     environment settings (SPEC section 9)
  Main.java          composition root
src/main/resources/
  db/migrations/     SQL schema and seed
  views/             server-rendered pages (ticket, mock gateway)
web/
  src/api/           typed API client (token in memory, refresh, idempotency, problem+json)
  src/pages/         audience pages, admin area, theatre owner dashboard
  src/components/    seat map, posters, ticket, layout editor, reports
  public/            favicon and /css/app.css for the server-rendered views
```

`TargetArchitectureTest` enforces the layering (SPEC section 10). Rules for contributors and agents are in [AGENTS.md](AGENTS.md).

## Documentation

The hard parts of a ticketing system, solved and proven: two buyers racing for the last seat, holds that expire, a payment that lands after its hold died, duplicate webhooks, live seat maps for many viewers. Each is written up with the failing interleaving, the fix, where it lives and the test that proves it, and the document lists honestly what is not solved.

- [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md): how the classic system design problems are solved, with proofs, limits, what is not solved, and how it would scale past one node. Machine-checked by `DesignDocPointersTest`, so a renamed class or test fails the build.
- [docs/SPEC.md](docs/SPEC.md): the contract every lane builds against (domain, lifecycle, concurrency rules, REST API, schema).
- [docs/RBAC.md](docs/RBAC.md): roles, the permission matrix, token handling and the webhook trust model.
- [docs/ERD.md](docs/ERD.md): the entity-relationship diagram of the SQLite schema.
- [docs/restructure/](docs/restructure/): the vertical-slice restructure plan, its decision records (for example ADR 0002 on gateway calls inside transactions) and the per-lane hand-backs.
- [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md): where the app still works around the framework.

## Security and RBAC

Roles (`AUDIENCE`, `THEATRE_OWNER`, `ADMIN`), token handling, rate limits and the webhook trust model are described in [docs/RBAC.md](docs/RBAC.md).

## License

MIT
