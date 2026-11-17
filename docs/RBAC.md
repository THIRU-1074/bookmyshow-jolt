# Role-based access control

Who may call which route, and how the server enforces it. The matrix below is checked against the code by `RbacMatrixTest`: the build fails if a route is registered without a rule, if a rule has no route, or if this table and `web.Permissions` disagree.

## Roles

| Role | How it is granted | Notes |
|---|---|---|
| Anonymous | no `Authorization` header | catalog, seat map, live seats, health |
| `AUDIENCE` | every account created by `POST /api/auth/register` | the request body can never choose roles; extra fields such as `roles` are ignored |
| `ADMIN` | only the account named by `ADMIN_EMAIL`/`ADMIN_PASSWORD` at startup (it also holds `AUDIENCE`) | there is no API that grants or changes roles |

Roles travel in the access token's `roles` claim (HS256, `JWT_SECRET`, 15 minutes). They are read from the stored user each time a token is issued or refreshed, so a role change takes effect at the next refresh, at most 15 minutes later. Refresh tokens live only in the `bms_refresh` cookie (HttpOnly, SameSite=Strict, Path=/api/auth); reusing a rotated refresh token revokes its whole family.

## Enforcement

1. **Deny by default.** `web.Rbac` runs on `/api` and `/mock-gateway` before any handler and looks the request up in `web.Permissions`. A method and path with no rule is 404, whatever handlers exist.
2. **Authentication.** Every rule other than `PUBLIC` and `SELF_AUTHENTICATED` needs a valid bearer token (Jolt `JwtAuth.middleware()`); otherwise 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer`.
3. **Roles.** `ADMIN` rules need the `ADMIN` role (Jolt `Auth.requireRole("ADMIN")`), else 403 `FORBIDDEN`. `/api/admin` has a second, prefix-wide `requireRole("ADMIN")` layer, so a mistaken rule cannot open an admin route.
4. **Ownership.** Handlers never take a user id from the request; they pass the token's subject to the use case, which loads the hold or booking and compares owners inside its transaction. A stranger gets 404 `NOT_FOUND`, never 403, so hold and booking ids (random UUIDs) cannot be probed. `OWNER_OR_ADMIN` lets an administrator read any booking, but cancel, checkout and ticket stay owner-only: an admin cannot act as a customer.
5. **Self-authenticated routes.** `POST /api/payments/webhook` is authenticated by its `Bms-Signature` HMAC over the raw body and a fresh `Bms-Timestamp`, checked before parsing. `/api/auth/refresh` and `/api/auth/logout` are authenticated by the refresh cookie and protected by `Csrf.protect()`.
6. **Mock gateway.** The `/mock-gateway` pages exist only when `MOCK_GATEWAY=true`. The unguessable payment id is the capability, as at a hosted checkout page; the POST is CSRF-protected and rate limited per IP.
7. **Scoped side effects.** `Idempotency-Key` records are scoped to the authenticated user, so one user's key can never replay another user's response. The `POST /api/holds` rate limit is keyed by user id.
8. **Errors** are `application/problem+json` with a stable `code` and the `requestId`.

## Matrix

Cells: `yes` allowed; `no` refused (401 anonymous, 403 signed in); `own` only for the caller's own hold or booking (others get 404); `any` any user's resource; `proof` no JWT, authenticated by the refresh cookie or webhook signature instead.

| Method | Path | Access | Anonymous | AUDIENCE | ADMIN |
|---|---|---|---|---|---|
| POST | `/api/auth/register` | PUBLIC | yes | yes | yes |
| POST | `/api/auth/login` | PUBLIC | yes | yes | yes |
| POST | `/api/auth/refresh` | SELF_AUTHENTICATED | proof | proof | proof |
| POST | `/api/auth/logout` | SELF_AUTHENTICATED | proof | proof | proof |
| GET | `/api/me` | USER | no | yes | yes |
| GET | `/api/cities` | PUBLIC | yes | yes | yes |
| GET | `/api/movies` | PUBLIC | yes | yes | yes |
| GET | `/api/movies/:id` | PUBLIC | yes | yes | yes |
| GET | `/api/movies/:id/shows` | PUBLIC | yes | yes | yes |
| GET | `/api/cinemas` | PUBLIC | yes | yes | yes |
| GET | `/api/cinemas/:id` | PUBLIC | yes | yes | yes |
| GET | `/api/shows/:id` | PUBLIC | yes | yes | yes |
| GET | `/api/shows/:id/seats` | PUBLIC | yes | yes | yes |
| GET | `/api/shows/:id/events` | PUBLIC | yes | yes | yes |
| POST | `/api/holds` | USER | no | yes | yes |
| DELETE | `/api/holds/:id` | OWNER | no | own | own |
| POST | `/api/bookings` | USER | no | yes | yes |
| GET | `/api/bookings` | USER | no | yes | yes |
| GET | `/api/bookings/:id` | OWNER_OR_ADMIN | no | own | any |
| POST | `/api/bookings/:id/checkout` | OWNER | no | own | own |
| POST | `/api/bookings/:id/cancel` | OWNER | no | own | own |
| GET | `/api/bookings/:id/ticket` | OWNER | no | own | own |
| POST | `/api/payments/webhook` | SELF_AUTHENTICATED | proof | proof | proof |
| GET | `/mock-gateway/:id` | PUBLIC | yes | yes | yes |
| POST | `/mock-gateway/:id/complete` | PUBLIC | yes | yes | yes |
| POST | `/api/admin/cities` | ADMIN | no | no | yes |
| PUT | `/api/admin/cities/:id` | ADMIN | no | no | yes |
| POST | `/api/admin/cinemas` | ADMIN | no | no | yes |
| PUT | `/api/admin/cinemas/:id` | ADMIN | no | no | yes |
| DELETE | `/api/admin/cinemas/:id` | ADMIN | no | no | yes |
| POST | `/api/admin/cinemas/:id/screens` | ADMIN | no | no | yes |
| PUT | `/api/admin/screens/:id` | ADMIN | no | no | yes |
| DELETE | `/api/admin/screens/:id` | ADMIN | no | no | yes |
| POST | `/api/admin/movies` | ADMIN | no | no | yes |
| PUT | `/api/admin/movies/:id` | ADMIN | no | no | yes |
| DELETE | `/api/admin/movies/:id` | ADMIN | no | no | yes |
| POST | `/api/admin/shows` | ADMIN | no | no | yes |
| PUT | `/api/admin/shows/:id` | ADMIN | no | no | yes |
| POST | `/api/admin/shows/:id/cancel` | ADMIN | no | no | yes |
| GET | `/api/admin/reports/shows` | ADMIN | no | no | yes |
| GET | `/api/admin/reports/shows/:id` | ADMIN | no | no | yes |
| GET | `/api/admin/stats` | ADMIN | no | no | yes |
| GET | `/api/health` | PUBLIC | yes | yes | yes |

## Adding a route

1. Add a row to `web.Permissions.TABLE` (pick the narrowest access level).
2. Register the handler in a `RouteModule`; for owner-scoped resources pass `Principal.of(req).userId()` to the use case and let it return NOT_FOUND for strangers.
3. Add the row to the matrix above. `RbacMatrixTest` and `RbacTest` fail until all three agree and the role checks hold.
