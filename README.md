# BookMyShow on Jolt

A movie ticketing app (cities, movies, shows, seat holds, payments, tickets) built on [Jolt](https://github.com/THIRU-1074/custom-Http-Server), an Express-style Java HTTP framework. It is Jolt's reference app: every framework gap it hits becomes a Jolt issue, gets fixed upstream, and is listed in [docs/JOLT_GAPS.md](docs/JOLT_GAPS.md).

Status: in development. Domain, application, SQLite and payment adapters, live seat events and the static web UI are being built lane by lane against the contract in [docs/SPEC.md](docs/SPEC.md). Fixes for Jolt gaps #52 to #54 are closed upstream and wait for Jolt v0.4.0.

## Run

Requires JDK 21+ and Maven. Jolt resolves from [JitPack](https://jitpack.io).

```bash
mvn -B verify          # tests, formatting check, coverage report in target/site/jacoco
JWT_SECRET=$(openssl rand -hex 32) WEBHOOK_SECRET=$(openssl rand -hex 32)   ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=change-me-please-123   mvn -q compile exec:java
curl http://127.0.0.1:8080/api/health
```

`JWT_SECRET` and `WEBHOOK_SECRET` (at least 32 bytes each) are required; startup fails fast without them. The server binds to loopback on `PORT` (default 8080). Set `JOLT_BIND_ADDRESS=0.0.0.0` to expose it. The full environment variable table is in [SPEC section 9](docs/SPEC.md#9-non-functional). Until Jolt serves from the classpath (jolt#68), `STATIC_DIR` and `VIEWS_DIR` default to the source tree, so run from the repository root.

### Docker

```bash
docker build -t bookmyshow-jolt .
docker run -p 8080:8080 -v bms-data:/data   -e JWT_SECRET=... -e WEBHOOK_SECRET=... bookmyshow-jolt
```

The image runs as a non-root user, keeps the database in the `/data` volume, binds `0.0.0.0` and has a `HEALTHCHECK` on `/api/health`.

## Layout

```
src/main/java/dev/thiru/bms/
  domain/            entities, rules, ports (JDK only)
  application/       use cases
  adapters/db/       SQLite repositories on jolt-db
  adapters/payment/  mock gateway and webhook signing
  adapters/live/     seat event fan-out for SSE
  web/               route modules, HTTP records, error mappers
  Main.java          composition root
src/main/resources/
  db/migrations/     SQL schema and seed
  static/            vanilla JS web UI (no build step)
  views/             server-rendered pages (ticket, mock gateway)
```

`ArchitectureTest` enforces the layer rules. Rules for contributors and agents are in [AGENTS.md](AGENTS.md).

## Security and RBAC

Roles (`USER`, `ADMIN`), token handling, rate limits and the webhook trust model are described in [docs/RBAC.md](docs/RBAC.md).

## License

MIT
