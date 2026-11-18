# BookMyShow on Jolt 1.0.0

First release. A complete movie ticketing app on [Jolt](https://github.com/THIRU-1074/custom-Http-Server) v0.4.1: browse a city's films, pick seats on a live seat map, hold them, pay through a mock gateway, get a ticket, cancel for a time-based refund. Theatre owners run their own cinemas; admins run the platform. It is also Jolt's reference app: every framework gap it hit became a Jolt issue, was fixed upstream and is listed in [docs/JOLT_GAPS.md](JOLT_GAPS.md).

Live demo: https://bookmyshow-jolt.onrender.com (free tier: the first request after idle takes 30 to 50 seconds, and the database resets on each deploy).

## What is in it

**For audiences:** city picker, now-showing carousel, film pages with a date strip and show times, an interactive seat map that updates live for every viewer, a hold countdown, checkout through the mock gateway, a printable ticket, my bookings with cancel and refund preview.

**For theatre owners:** a dashboard limited to the cinemas an admin links them to: screens and seat layouts, scheduling and cancelling shows (with refunds), sales and occupancy reports.

**For admins:** the whole catalog (cities, cinemas, screens, movies, shows), every report, and user management (grant or revoke the owner role, link owners to cinemas).

**Roles:** `AUDIENCE`, `THEATRE_OWNER`, `ADMIN`, enforced on the server, deny by default. See [docs/RBAC.md](RBAC.md).

## Engineering

- **The hard problems are solved and proven** ([docs/SYSTEM_DESIGN.md](SYSTEM_DESIGN.md)): two buyers racing for the last seat, holds that expire, a payment that lands after its hold died (refunded), a late success for an attempt already failed (refunded), duplicate and forged webhooks, idempotent retries, a crashed request that would wedge an idempotency key, live seat maps for many viewers, catalog caching without stampedes. Each entry has the failing interleaving, the fix, where it lives and the tests that prove it.
- **Vertical slices around a thin hexagon:** `kernel` (pure rules), `ports` (real I/O seams only), `features/{catalog,live,accounts,booking,payments,admin}`, `adapters`. One architecture guard enforces the layering ([docs/restructure/](restructure/PLAN.md)).
- **Contract first:** [api/openapi.yaml](../api/openapi.yaml) describes every route. The Java wire records are generated from it on every build, the React types are generated from it and checked in CI, and a test calls every route and validates the real responses.
- **Docs that cannot rot:** [docs/ERD.md](ERD.md) is checked against the live schema by `ErdDriftTest`, and `DesignDocPointersTest` fails if the design doc names code or tests that no longer exist. [AGENTS.md](../AGENTS.md) rules 10 and 11 require updating them with every schema or design change.
- **Tests:** 563 tests run in about 90 seconds, including 200 concurrent buyers racing for twelve seats, both end-to-end journeys over a real socket, a permission-matrix drift test and port contract suites run against both the fakes and the real adapters.
- **UI:** React 18 and strict TypeScript, no UI kit, cinematic dark theme with a light mode, Lighthouse accessibility, best practices and SEO all scored 100 on the home and seat-map pages when the UI was built.
- **Security:** short-lived JWT access tokens, rotating refresh cookies with reuse detection, CSRF checks, per-IP and per-user rate limits, HMAC-signed webhooks with a timestamp window, a strict CSP.

## Deploying

`render.yaml` deploys the Docker image as one free Render service; the `Dockerfile` builds the UI and the generated types in the image. Required: `JWT_SECRET` and `WEBHOOK_SECRET` (32+ bytes each). Optional: `ADMIN_EMAIL` and `ADMIN_PASSWORD` (12+ characters) to seed an admin. All settings are in [SPEC section 9](SPEC.md#9-non-functional).

## Known limits

Stated plainly, and detailed in [docs/SYSTEM_DESIGN.md](SYSTEM_DESIGN.md):

- **Single node.** SQLite is the only database, and the live-update hub, rate limiter, catalog cache and expiry sweep are per process. Scaling out needs Postgres, Redis and an outbox; the design doc maps each mechanism.
- **Mock payments only.** Gateway calls run inside the write transaction, which is safe for the mock but not for a real gateway; the outbox design is in [ADR 0002](restructure/ADR-0002-gateway-calls-in-transactions.md) and is not built.
- **Free-tier hosting:** the database is ephemeral and the service sleeps when idle.
- **Not built:** a virtual waiting room for hot releases, search beyond a title substring match, email or SMS notifications, account recovery and verification, and reconciliation with a payment provider.
- **Optimistic version checks** cannot conflict under SQLite's single writer, so the retry path is proven by injection rather than a real race. `Last-Event-ID` resume is not implemented; every reconnect gets a fresh snapshot instead.
- **Cookie `Secure` flag on Render:** the refresh cookie is `HttpOnly` and `SameSite=Strict` but not yet marked `Secure` behind Render's proxy; the app logs what the proxy sends so the trust setting can be completed.

## Upgrading

First release; nothing to upgrade. Schema migrations V1 to V5 run at startup.
