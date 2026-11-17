# Role-based access control

Who may call which route, and how the server enforces it. The matrix below is checked against the code by `RbacMatrixTest`: the build fails if a route is registered without a rule, if a rule has no route, or if this table and `web.Permissions` disagree.

## Roles

There are exactly three roles (SPEC 3). Every account holds `AUDIENCE`; `THEATRE_OWNER` and `ADMIN` are added on top of it.

| Role | How it is granted | Notes |
|---|---|---|
| Anonymous | no `Authorization` header | catalog, seat map, live seats, health |
| `AUDIENCE` | every account; `POST /api/auth/register` always creates `AUDIENCE` only | the request body can never choose roles; extra fields such as `roles` are ignored. `AUDIENCE` cannot be revoked |
| `THEATRE_OWNER` | only an admin: `PUT /api/admin/users/{id}/roles/THEATRE_OWNER`, or `PUT /api/admin/cinemas/{id}/owners/{userId}`, which links the account to a cinema and grants the role | no self-signup and no application flow. The owner manages screens, layouts, shows and reports of the linked cinemas only. Revoking the role removes every link |
| `ADMIN` | the account named by `ADMIN_EMAIL`/`ADMIN_PASSWORD` at startup, or another admin through `PUT /api/admin/users/{id}/roles/ADMIN` | everything. An admin cannot remove their own `ADMIN` (409 `CONFLICT`) |

Roles travel in the access token's `roles` claim (HS256, `JWT_SECRET`, 15 minutes). They are read from the stored user each time a token is issued or refreshed, so a role change takes effect at the next refresh, at most 15 minutes later. Cinema links are not in the token: they are read from `cinema_owner` on every owner request, so an unlink (or a revoke, which removes the links) takes effect at once even while the old token still says `THEATRE_OWNER`. Access tokens minted before the `USER` to `AUDIENCE` rename still say `USER`; `web.Principal` reads that as `AUDIENCE` until they expire. Refresh tokens live only in the `bms_refresh` cookie (HttpOnly, SameSite=Strict, Path=/api/auth); reusing a rotated refresh token revokes its whole family.

## Owner routes: a separate prefix

Theatre owner routes live under `/api/owner` instead of reusing the admin catalog routes with an ownership check. This keeps each prefix to one rule: everything under `/api/admin` is `ADMIN` only, behind a prefix-wide `requireRole("ADMIN")`, so no ownership bug can open an admin route to an owner; everything under `/api/owner` is `THEATRE_OWNER` (or `ADMIN`), behind a prefix-wide `requireRole("THEATRE_OWNER", "ADMIN")`, and always scoped. The owner handlers call the same use cases as the admin ones (`AdminCatalog`, `CancelShow`, `AdminReports`) with a `CinemaScope`, so validation, the overlap rule and refunds behave identically. There are no owner routes for cities, movies, cinemas, owner links or roles.

## Enforcement

1. **Deny by default.** `web.Rbac` runs on `/api` and `/mock-gateway` before any handler and looks the request up in `web.Permissions`. A method and path with no rule is 404, whatever handlers exist.
2. **Authentication.** Every rule other than `PUBLIC` and `SELF_AUTHENTICATED` needs a valid bearer token (Jolt `JwtAuth.middleware()`); otherwise 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer`.
3. **Roles.** `SIGNED_IN`, `OWNER` and `OWNER_OR_ADMIN` rules need a token with a known role. `THEATRE_OWNER` rules need `THEATRE_OWNER` or `ADMIN` (Jolt `Auth.requireRole("THEATRE_OWNER", "ADMIN")`) and `ADMIN` rules need `ADMIN` (`Auth.requireRole("ADMIN")`), else 403 `FORBIDDEN`. `/api/admin` and `/api/owner` each have a second, prefix-wide role layer, so a mistaken rule cannot open a route under them.
4. **Booking ownership.** Handlers never take a user id from the request; they pass the token's subject to the use case, which loads the hold or booking and compares owners inside its transaction. A stranger gets 404 `NOT_FOUND`, never 403, so hold and booking ids (random UUIDs) cannot be probed. `OWNER_OR_ADMIN` lets an administrator read any booking, but cancel, checkout and ticket stay owner-only: an admin cannot act as a customer.
5. **Cinema scope.** Owner handlers pass `Principal.cinemaScope()` to the use case: every cinema for an `ADMIN`, else `CinemaScope.ownedBy(userId)`. The use case loads the screen or show, finds its cinema and checks the `cinema_owner` link inside the same transaction (`CinemaScope.require`); reports are filtered in SQL by the same link. A cinema, screen, show or report outside the scope is 404 `NOT_FOUND`, matching the booking convention, so one owner cannot discover another's ids.
6. **Self-authenticated routes.** `POST /api/payments/webhook` is authenticated by its `Bms-Signature` HMAC over the raw body and a fresh `Bms-Timestamp`, checked before parsing. `/api/auth/refresh` and `/api/auth/logout` are authenticated by the refresh cookie and protected by `Csrf.protect()`.
7. **Mock gateway.** The `/mock-gateway` pages exist only when `MOCK_GATEWAY=true`. The unguessable payment id is the capability, as at a hosted checkout page; the POST is CSRF-protected and rate limited per IP.
8. **Scoped side effects.** `Idempotency-Key` records are scoped to the authenticated user, so one user's key can never replay another user's response. The `POST /api/holds` rate limit is keyed by user id.
9. **Errors** are `application/problem+json` with a stable `code` and the `requestId`.

The UI only hides links and controls by role; every rule above is enforced by the server.

## Matrix

Columns are the role sets accounts actually hold: `AUDIENCE` alone, `AUDIENCE` + `THEATRE_OWNER`, and `AUDIENCE` + `ADMIN`. Cells: `yes` allowed; `no` refused (401 anonymous, 403 signed in); `own` only the caller's own hold or booking, or for owner routes only the caller's linked cinemas (others get 404); `any` any user's resource or any cinema; `proof` no JWT, authenticated by the refresh cookie or webhook signature instead.

| Method | Path | Access | Anonymous | AUDIENCE | THEATRE_OWNER | ADMIN |
|---|---|---|---|---|---|---|
| POST | `/api/auth/register` | PUBLIC | yes | yes | yes | yes |
| POST | `/api/auth/login` | PUBLIC | yes | yes | yes | yes |
| POST | `/api/auth/refresh` | SELF_AUTHENTICATED | proof | proof | proof | proof |
| POST | `/api/auth/logout` | SELF_AUTHENTICATED | proof | proof | proof | proof |
| GET | `/api/me` | SIGNED_IN | no | yes | yes | yes |
| GET | `/api/cities` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/movies` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/movies/:id` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/movies/:id/shows` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/cinemas` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/cinemas/:id` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/shows/:id` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/shows/:id/seats` | PUBLIC | yes | yes | yes | yes |
| GET | `/api/shows/:id/events` | PUBLIC | yes | yes | yes | yes |
| POST | `/api/holds` | SIGNED_IN | no | yes | yes | yes |
| DELETE | `/api/holds/:id` | OWNER | no | own | own | own |
| POST | `/api/bookings` | SIGNED_IN | no | yes | yes | yes |
| GET | `/api/bookings` | SIGNED_IN | no | yes | yes | yes |
| GET | `/api/bookings/:id` | OWNER_OR_ADMIN | no | own | own | any |
| POST | `/api/bookings/:id/checkout` | OWNER | no | own | own | own |
| POST | `/api/bookings/:id/cancel` | OWNER | no | own | own | own |
| GET | `/api/bookings/:id/ticket` | OWNER | no | own | own | own |
| POST | `/api/payments/webhook` | SELF_AUTHENTICATED | proof | proof | proof | proof |
| GET | `/mock-gateway/:id` | PUBLIC | yes | yes | yes | yes |
| POST | `/mock-gateway/:id/complete` | PUBLIC | yes | yes | yes | yes |
| POST | `/api/admin/cities` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/cities/:id` | ADMIN | no | no | no | yes |
| POST | `/api/admin/cinemas` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/cinemas/:id` | ADMIN | no | no | no | yes |
| DELETE | `/api/admin/cinemas/:id` | ADMIN | no | no | no | yes |
| POST | `/api/admin/cinemas/:id/screens` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/screens/:id` | ADMIN | no | no | no | yes |
| DELETE | `/api/admin/screens/:id` | ADMIN | no | no | no | yes |
| POST | `/api/admin/movies` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/movies/:id` | ADMIN | no | no | no | yes |
| DELETE | `/api/admin/movies/:id` | ADMIN | no | no | no | yes |
| POST | `/api/admin/shows` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/shows/:id` | ADMIN | no | no | no | yes |
| POST | `/api/admin/shows/:id/cancel` | ADMIN | no | no | no | yes |
| GET | `/api/admin/reports/shows` | ADMIN | no | no | no | yes |
| GET | `/api/admin/reports/shows/:id` | ADMIN | no | no | no | yes |
| GET | `/api/admin/stats` | ADMIN | no | no | no | yes |
| GET | `/api/admin/users` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/users/:id/roles/:role` | ADMIN | no | no | no | yes |
| DELETE | `/api/admin/users/:id/roles/:role` | ADMIN | no | no | no | yes |
| PUT | `/api/admin/cinemas/:id/owners/:userId` | ADMIN | no | no | no | yes |
| DELETE | `/api/admin/cinemas/:id/owners/:userId` | ADMIN | no | no | no | yes |
| GET | `/api/owner/cinemas` | THEATRE_OWNER | no | no | own | any |
| POST | `/api/owner/cinemas/:id/screens` | THEATRE_OWNER | no | no | own | any |
| PUT | `/api/owner/screens/:id` | THEATRE_OWNER | no | no | own | any |
| POST | `/api/owner/shows` | THEATRE_OWNER | no | no | own | any |
| POST | `/api/owner/shows/:id/cancel` | THEATRE_OWNER | no | no | own | any |
| GET | `/api/owner/reports/shows` | THEATRE_OWNER | no | no | own | any |
| GET | `/api/owner/reports/shows/:id` | THEATRE_OWNER | no | no | own | any |
| GET | `/api/health` | PUBLIC | yes | yes | yes | yes |

## Adding a route

1. Add a row to `web.Permissions.TABLE` (pick the narrowest access level).
2. Register the handler in a `RouteModule`; for owner-scoped resources pass `Principal.of(req).userId()` to the use case and let it return NOT_FOUND for strangers; for cinema-scoped resources pass `Principal.of(req).cinemaScope()`.
3. Add the row to the matrix above. `RbacMatrixTest` and `RbacTest` fail until all three agree and the role checks hold.
