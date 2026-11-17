# BookMyShow on Jolt

A movie ticketing app (cities, movies, shows, seat holds, payments, tickets) built on [Jolt](https://github.com/THIRU-1074/custom-Http-Server), an Express-style Java HTTP framework. It is Jolt's reference app: every framework gap it hits becomes a Jolt issue, gets fixed upstream, and is listed in [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md).

Status: integrated on Jolt v0.4.1. All lanes are merged and wired in `Main`; an end-to-end test books a seat through the mock gateway on a real socket. The contract is [docs/SPEC.md](docs/SPEC.md). Every Jolt gap from #52 to #77 is fixed upstream and the app now uses the Jolt API for it; [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md) says how each was replaced and lists what is still worked around.

## Run

Requires JDK 21+ and Maven. Jolt resolves from [JitPack](https://jitpack.io).

```bash
mvn -B verify          # tests, formatting check, coverage report in target/site/jacoco
JWT_SECRET=$(openssl rand -hex 32) WEBHOOK_SECRET=$(openssl rand -hex 32) \
  ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=change-me-please-123 \
  mvn -q compile exec:java
curl http://127.0.0.1:8080/api/health
```

`JWT_SECRET` and `WEBHOOK_SECRET` (at least 32 bytes each) are required; startup fails fast without them. The server binds to loopback on `PORT` (default 8080). Set `JOLT_BIND_ADDRESS=0.0.0.0` to expose it. The full environment variable table is in [SPEC section 9](docs/SPEC.md#9-non-functional); settings are read with Jolt's `Config.load()`, so an `application.properties` in the working directory or on the class path can supply them too (the environment wins). The UI and the server-rendered views are served from the class path, so the app runs from any directory; set `STATIC_DIR` or `VIEWS_DIR` to serve a directory instead.

With `METRICS_TOKEN` set, `/_jolt/health` reports the database check and `/_jolt/metrics` (behind `Authorization: Bearer <METRICS_TOKEN>`) adds live SSE streams, catalog cache hits and the expiry sweep runs to Jolt's own series.

### Docker

```bash
docker build -t bookmyshow-jolt .
docker run -p 8080:8080 -v bms-data:/data \
  -e JWT_SECRET=... -e WEBHOOK_SECRET=... bookmyshow-jolt
```

The image runs as a non-root user, keeps the database in the `/data` volume, binds `0.0.0.0` and has a `HEALTHCHECK` on `/api/health`.

### Render

`render.yaml` deploys the image as one free web service. Render generates `JWT_SECRET` and `WEBHOOK_SECRET`, and `PUBLIC_BASE_URL` falls back to the `RENDER_EXTERNAL_URL` Render sets, so checkout links and the mock gateway webhook use the public address. Set `ADMIN_EMAIL` and `ADMIN_PASSWORD` in the dashboard to get an admin. The free plan's disk is ephemeral: the database resets on every deploy or restart.

## Layout

```
src/main/java/dev/thiru/bms/
  domain/            entities, rules, ports (JDK only)
  application/       use cases
  adapters/db/       SQLite repositories, refresh-token and idempotency stores on jolt-db
  adapters/auth/     PBKDF2 password hashing on jolt-auth
  adapters/ids/      random UUIDv4 ids
  adapters/payment/  mock gateway and its webhook signer (Jolt verifies)
  adapters/live/     seat event fan-out for SSE on Jolt's Hub
  web/               route modules, HTTP records, error mappers
  AppConfig.java     environment settings (SPEC section 9)
  Main.java          composition root
src/main/resources/
  db/migrations/     SQL schema and seed
  static/            vanilla JS web UI (no build step)
  views/             server-rendered pages (ticket, mock gateway)
```

`ArchitectureTest` enforces the layer rules. Rules for contributors and agents are in [AGENTS.md](AGENTS.md).

## Security and RBAC

Roles (`AUDIENCE`, `THEATRE_OWNER`, `ADMIN`), token handling, rate limits and the webhook trust model are described in [docs/RBAC.md](docs/RBAC.md).

## License

MIT
