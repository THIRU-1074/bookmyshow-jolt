# BookMyShow on Jolt: specification v1

The contract every lane builds against. A change to this file is a PR of its own. Keywords MUST, MUST NOT and MAY are normative.

## 1. Scope

Single-node movie ticketing: browse a city's movies and shows, hold seats, pay through a mock gateway, get a ticket, cancel with a time-based refund. Theatre owners run the screens, shows and reports of the cinemas an admin links them to; admins manage the whole catalog, the owners and every report. Out of scope for v1: real payments, multi-node deployment, Postgres, offers and coupons, food and beverages, email or SMS.

## 2. Conventions

- **Time:** stored as `INTEGER` epoch milliseconds UTC. JSON uses ISO-8601 UTC (`2026-11-20T13:30:00Z`). Local dates and times are derived with the city's IANA zone.
- **Money:** `INTEGER` minor units (paise), currency always `INR`. JSON: `{"amount": 45000, "currency": "INR"}`. No floating point anywhere.
- **IDs:** catalog and users use `INTEGER` ids; cities use a slug (`bengaluru`). Holds, bookings and payments use random UUIDv4 strings, so their ids cannot be guessed.
- **Seat id:** row letter plus number, `C7`. Row `A` is nearest the screen. Rows run `A`..`Z`, seats `1`..`50`.
- **Clock:** domain and application code take a `java.time.Clock`; tests use a fixed or stepping clock.

## 3. Domain model

| Entity | Fields | Invariants |
|---|---|---|
| City | id (slug), name, timeZone | timeZone is a valid IANA zone; id matches `[a-z0-9-]{2,40}` |
| Cinema | id, cityId, name, address | name unique within city |
| Screen | id, cinemaId, name, layout | name unique within cinema; layout valid (below); capacity 1..1000 |
| Movie | id, title, languages[], genres[], certificate, durationMinutes, releaseDate, posterUrl, description | title 1..120 chars; languages and genres non-empty, no duplicates; certificate in `U, UA, A, S`; duration 1..400; posterUrl https or a site-relative path; description at most 2000 chars |
| Show | id, movieId, screenId, startsAt, endsAt, language, format, prices{category: amount}, status | language is one of the movie's; format in `2D, 3D, IMAX`; a price (100..1000000 paise) for every layout category and no others; status `SCHEDULED` or `CANCELLED`; endsAt = startsAt + movie duration + 15 min, so the cleaning buffer is included; shows on one screen MUST NOT overlap, where a show occupies `[startsAt, endsAt)` |
| SeatHold | id, showId, userId, seats[], createdAt, expiresAt, status | 1..`MAX_SEATS` distinct seats that exist and are not blocked (an empty list or more than `MAX_SEATS` is 422 `VALIDATION` on the `seats` field); expiresAt = createdAt + `HOLD_TTL`; all-or-nothing; status `ACTIVE, CONSUMED, RELEASED, EXPIRED` |
| Booking | id, code, userId, showId, holdId, seats[], amount, currency, status, expiresAt, refundAmount, checkoutCount, version, createdAt, updatedAt | each booking seat carries (seat, category, price) captured at hold time; amount = sum of seat prices; checkoutCount counts checkouts and is capped at 3; code `BK` + 8 Crockford base32 chars, unique; version starts at 0 and increments on every write |
| Payment | id, bookingId, amount, currency, status, gatewayRef, refundedAmount, createdAt, updatedAt | amount = booking amount; status `INITIATED, SUCCEEDED, FAILED, REFUNDED`; refundedAmount <= amount |
| User | id, email, passwordHash, name, roles, createdAt | email unique, stored lowercase; passwordHash from `PasswordHasher`; password 10..128 chars; roles subset of `AUDIENCE, THEATRE_OWNER, ADMIN`, never empty; every account holds `AUDIENCE` and it cannot be removed |
| CinemaOwner | cinemaId, userId | the pair is unique; a user may own many cinemas and a cinema may have many owners; only a user holding `THEATRE_OWNER` has links |

**Seat layout** (JSON, validated by the domain):

```json
{"categories": [{"code": "CLASSIC", "name": "Classic"}, {"code": "PRIME", "name": "Prime"}],
 "rows": [{"row": "A", "seats": 14, "category": "CLASSIC", "aisleAfter": [4, 10], "blocked": [1]}]}
```

Rules: rows unique and contiguous from `A`; 1..26 rows; seats 1..50 per row; category codes `[A-Z_]{2,16}`, unique, each used by at least one row; `aisleAfter` and `blocked` values within 1..seats. `blocked` MUST NOT contain duplicates (rejected as 422); every layout MUST have at least one bookable seat. Capacity = sum of seats minus blocked.

**Roles and cinema ownership.** There are exactly three roles. Every account holds `AUDIENCE`; `THEATRE_OWNER` and `ADMIN` are added on top of it.

| Role | Granted by | May |
|---|---|---|
| `AUDIENCE` | every account; `POST /api/auth/register` always creates `AUDIENCE` only and ignores any `roles` in the body | browse, hold, book, pay, cancel and see tickets; only their own holds and bookings |
| `THEATRE_OWNER` | an admin, by promoting an existing account and linking it to cinemas; there is no self-signup and no application flow | everything `AUDIENCE` may, plus screens, seat layouts, shows (create, cancel with refunds) and sales and occupancy reports, only for the cinemas linked to them |
| `ADMIN` | the `ADMIN_EMAIL` account at startup, or another admin | everything: cities, movies, cinemas, every screen, show and report, owner links and roles |

Ownership rules: a theatre owner's scope is the set of cinemas linked to them in `cinema_owner`, read from the database inside each use case's transaction, so an unlink takes effect at once. A screen, show or report of a cinema outside that scope is 404 `NOT_FOUND`, never 403, so other cinemas' ids cannot be probed. Linking a user to a cinema grants `THEATRE_OWNER`; revoking `THEATRE_OWNER` removes every link of that user; unlinking the last cinema keeps the role. Deleting a cinema deletes its links. An admin MUST NOT remove their own `ADMIN` role (409 `CONFLICT`). Owners MUST NOT create or change cities, movies or cinemas, link owners or change roles.

## 4. Booking lifecycle

```
          create from hold           checkout             webhook success
 (hold) ------------------> HELD ------------> PAYMENT_PENDING --------------> CONFIRMED
                             |  ^                 |      |                       |
               expiry/cancel |  | webhook failure |      | expiry                | user cancel / show cancel
                             v  +-----------------+      v                       v
                      EXPIRED | CANCELLED              EXPIRED        CANCELLED (refund 0) | REFUNDED (refund > 0)
```

| From | To | Trigger | Who | Rule |
|---|---|---|---|---|
| (hold ACTIVE) | HELD | `POST /api/bookings` | owner | hold ACTIVE and unexpired; hold becomes CONSUMED; booking.expiresAt = hold.expiresAt |
| HELD | PAYMENT_PENDING | `POST /api/bookings/{id}/checkout` | owner | unexpired; at most 3 checkouts per booking; expiresAt = max(expiresAt, now + `PAYMENT_WINDOW`) |
| PAYMENT_PENDING | CONFIRMED | webhook `payment.succeeded` | gateway | unexpired, every seat lock still owned by this booking, amount matches; locks become BOOKED |
| PAYMENT_PENDING | HELD | webhook `payment.failed` | gateway | unexpired; payment FAILED; the user may check out again. A success webhook whose amount or currency does not match also marks the payment FAILED and returns the booking to HELD (422 to the caller) |
| HELD, PAYMENT_PENDING | EXPIRED | sweeper or lazy check | system | now >= expiresAt; locks deleted; a success webhook arriving after expiry or cancellation marks the payment SUCCEEDED then REFUNDED in full (refundedAmount = amount) |
| HELD | CANCELLED | `POST /api/bookings/{id}/cancel` | owner | locks deleted; no payment exists. Cancelling a booking whose expiresAt has passed but which the sweeper has not yet marked EXPIRED is 409 `BOOKING_STATE` |
| PAYMENT_PENDING | (none) | cancel | owner | 409 `PAYMENT_IN_PROGRESS` |
| CONFIRMED | REFUNDED or CANCELLED | cancel | owner | before show start only (below); locks deleted |
| CONFIRMED, HELD, PAYMENT_PENDING | REFUNDED or CANCELLED | `POST /api/admin/shows/{id}/cancel` or `POST /api/owner/shows/{id}/cancel` | admin, or a theatre owner of the show's cinema | CONFIRMED gets a full refund; others become CANCELLED |

**Refund rule** (t = startsAt - now at cancellation): t > 24 h: 100 %; 4 h <= t <= 24 h: 50 %, rounded down to the paisa; 0 < t < 4 h: 0 % (status CANCELLED, seats released for resale); t <= 0: 409 `SHOW_STARTED`. The mock gateway refunds synchronously. A partial refund leaves the payment REFUNDED with `refundedAmount` set to the refunded paise (less than `amount`); payment status is never left SUCCEEDED once any refund is made.

Terminal states: EXPIRED, CANCELLED, REFUNDED. Sales for a show close at startsAt, and startsAt == now counts as started: holds then give 409 `SHOW_STARTED`. A hold on a CANCELLED show gives 409 `BOOKING_STATE`.

## 5. Concurrency and correctness

1. **Seat locks.** Table `seat_lock` has primary key `(show_id, seat_id)`; a row exists only while the seat is held or booked. The primary key is the single guarantee against double booking; application checks are only for friendly errors. No partial index is needed because released seats have no row.
2. **IMMEDIATE transactions.** Create hold, create booking, checkout, webhook processing, cancel, expiry and show cancel each run in one SQLite `BEGIN IMMEDIATE` transaction (`Db.inTransaction(TxMode.IMMEDIATE, ...)`), with WAL mode and `busy_timeout=5000`.
3. **Create hold**, in one transaction: check the show is SCHEDULED and not started; validate the seats; release the caller's other ACTIVE hold for the same show; delete expired HELD locks for the requested seats (lazy expiry); insert one lock per seat. A primary-key violation rolls back the whole hold and gives 409 `SEAT_UNAVAILABLE` with `conflicts: [seatIds]`.
4. **Optimistic version.** Every booking write is `UPDATE booking SET ..., version = version + 1 WHERE id = ? AND version = ?` expecting 1 row (`tx.updateExpecting`). A miss re-reads the booking and re-applies the transition rules once, then gives 409 `VERSION_CONFLICT`.
5. **Expiry.** A sweeper runs every `SWEEP_INTERVAL` (30 s) and expires holds and bookings with expiresAt <= now in batches of 500. Correctness never depends on the sweeper: every transition re-checks expiresAt inside its transaction.
6. **Limits.** `HOLD_TTL` 7 min and `MAX_SEATS` 10 (both configurable). One ACTIVE hold per user per show.
7. **Idempotency.** `Idempotency-Key` (1..128 printable ASCII) is REQUIRED on `POST /api/holds`, `POST /api/bookings`, `POST /api/bookings/{id}/checkout`, `POST /api/bookings/{id}/cancel` and every admin and owner `POST`; missing gives 400. Scope is the authenticated user. A replay returns the stored response with `Idempotency-Replayed: true`; the same key with a different body gives 422; a request still running gives 409.
8. **Webhook deduplication.** `payment_event.event_id` is unique; a duplicate delivery returns 200 `{"duplicate": true}` without side effects.
9. **Money.** Amounts are computed server side from show prices; client-sent amounts are ignored.

## 6. SQLite schema

`V1__schema.sql` (in `src/main/resources/db/migrations`). Every table is `STRICT`; `PRAGMA foreign_keys = ON` per connection.

```sql
CREATE TABLE city (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, time_zone TEXT NOT NULL) STRICT;
CREATE TABLE cinema (id INTEGER PRIMARY KEY, city_id TEXT NOT NULL REFERENCES city(id),
  name TEXT NOT NULL, address TEXT NOT NULL, UNIQUE (city_id, name)) STRICT;
CREATE TABLE screen (id INTEGER PRIMARY KEY, cinema_id INTEGER NOT NULL REFERENCES cinema(id),
  name TEXT NOT NULL, layout_json TEXT NOT NULL, capacity INTEGER NOT NULL CHECK (capacity BETWEEN 1 AND 1000),
  UNIQUE (cinema_id, name)) STRICT;
CREATE TABLE movie (id INTEGER PRIMARY KEY, title TEXT NOT NULL, certificate TEXT NOT NULL
  CHECK (certificate IN ('U','UA','A','S')), duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 1 AND 400),
  release_date TEXT NOT NULL, poster_url TEXT NOT NULL, description TEXT NOT NULL) STRICT;
CREATE TABLE movie_language (movie_id INTEGER NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
  language TEXT NOT NULL, PRIMARY KEY (movie_id, language)) STRICT;
CREATE TABLE movie_genre (movie_id INTEGER NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
  genre TEXT NOT NULL, PRIMARY KEY (movie_id, genre)) STRICT;
CREATE TABLE show (id INTEGER PRIMARY KEY, movie_id INTEGER NOT NULL REFERENCES movie(id),
  screen_id INTEGER NOT NULL REFERENCES screen(id), starts_at INTEGER NOT NULL, ends_at INTEGER NOT NULL,
  language TEXT NOT NULL, format TEXT NOT NULL CHECK (format IN ('2D','3D','IMAX')),
  prices_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','CANCELLED'))) STRICT;
CREATE INDEX show_screen_time ON show (screen_id, starts_at);
CREATE INDEX show_movie_time ON show (movie_id, starts_at);
CREATE TABLE app_user (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
  name TEXT NOT NULL, roles TEXT NOT NULL, created_at INTEGER NOT NULL) STRICT;
CREATE TABLE refresh_token (hash TEXT PRIMARY KEY, subject TEXT NOT NULL, family TEXT NOT NULL,
  expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0) STRICT;
CREATE INDEX refresh_token_subject ON refresh_token (subject);
CREATE TABLE seat_hold (id TEXT PRIMARY KEY, show_id INTEGER NOT NULL REFERENCES show(id),
  user_id INTEGER NOT NULL REFERENCES app_user(id), seats_json TEXT NOT NULL, created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL, status TEXT NOT NULL CHECK (status IN ('ACTIVE','CONSUMED','RELEASED','EXPIRED'))) STRICT;
CREATE INDEX seat_hold_active ON seat_hold (expires_at) WHERE status = 'ACTIVE';
CREATE TABLE booking (id TEXT PRIMARY KEY, code TEXT NOT NULL UNIQUE, user_id INTEGER NOT NULL REFERENCES app_user(id),
  show_id INTEGER NOT NULL REFERENCES show(id), hold_id TEXT NOT NULL UNIQUE REFERENCES seat_hold(id),
  amount INTEGER NOT NULL CHECK (amount > 0), currency TEXT NOT NULL DEFAULT 'INR',
  status TEXT NOT NULL CHECK (status IN ('HELD','PAYMENT_PENDING','CONFIRMED','EXPIRED','CANCELLED','REFUNDED')),
  expires_at INTEGER NOT NULL, refund_amount INTEGER NOT NULL DEFAULT 0, checkout_count INTEGER NOT NULL DEFAULT 0,
  version INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL) STRICT;
CREATE INDEX booking_user ON booking (user_id, created_at DESC);
CREATE INDEX booking_open ON booking (expires_at) WHERE status IN ('HELD','PAYMENT_PENDING');
CREATE TABLE booking_seat (booking_id TEXT NOT NULL REFERENCES booking(id), seat_id TEXT NOT NULL,
  category TEXT NOT NULL, price INTEGER NOT NULL, PRIMARY KEY (booking_id, seat_id)) STRICT;
CREATE TABLE seat_lock (show_id INTEGER NOT NULL REFERENCES show(id), seat_id TEXT NOT NULL,
  hold_id TEXT NOT NULL REFERENCES seat_hold(id), booking_id TEXT REFERENCES booking(id),
  state TEXT NOT NULL CHECK (state IN ('HELD','BOOKED')), expires_at INTEGER,
  PRIMARY KEY (show_id, seat_id), CHECK ((state = 'HELD') = (expires_at IS NOT NULL))) STRICT;
CREATE INDEX seat_lock_hold ON seat_lock (hold_id);
CREATE TABLE payment (id TEXT PRIMARY KEY, booking_id TEXT NOT NULL REFERENCES booking(id),
  amount INTEGER NOT NULL, currency TEXT NOT NULL DEFAULT 'INR',
  status TEXT NOT NULL CHECK (status IN ('INITIATED','SUCCEEDED','FAILED','REFUNDED')),
  gateway_ref TEXT UNIQUE, refunded_amount INTEGER NOT NULL DEFAULT 0 CHECK (refunded_amount <= amount),
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL) STRICT;
CREATE INDEX payment_booking ON payment (booking_id);
CREATE TABLE payment_event (event_id TEXT PRIMARY KEY, payment_id TEXT NOT NULL, type TEXT NOT NULL,
  received_at INTEGER NOT NULL) STRICT;
```

`V3__idempotency.sql` adds the `idempotency_key` table (section 12). `V4__roles.sql` rewrites the stored role `USER` to `AUDIENCE` in `app_user.roles` and adds the owner link:

```sql
CREATE TABLE cinema_owner (cinema_id INTEGER NOT NULL REFERENCES cinema(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  PRIMARY KEY (cinema_id, user_id)) STRICT;
CREATE INDEX cinema_owner_user ON cinema_owner (user_id);
```

`V2__seed.sql`: cities `bengaluru`, `chennai`, `mumbai` (all `Asia/Kolkata`); 6 cinemas (2 per city); 2-3 screens each with layouts of 80-250 seats in two or three categories (`CLASSIC`, `PRIME`, `RECLINER`) and at least one blocked seat and aisles; 12 fictional movies (for example "The Monsoon Cipher", "Kaveri Nights", "Last Train to Hampi") in `en, hi, ta, te, kn` with genres from `Action, Comedy, Drama, Thriller, Romance, Animation, Sci-Fi, Horror, Family`; shows 4-5 per screen per day at 10:00, 13:30, 17:00, 20:30 and 23:15 local, non-overlapping. Shows are generated relative to the migration date with a recursive CTE for 14 days; no users are seeded (admin comes from env).

## 7. REST API

Base path `/api`. JSON in and out, UTF-8. Auth is `Authorization: Bearer <access JWT>`; claims `sub` (user id) and `roles` (read from the stored user on every issue and refresh). Roles: `-` public, `U` any signed-in user, `O` THEATRE_OWNER scoped to their cinemas (an ADMIN passes with every cinema in scope), `A` ADMIN. The per-route matrix is `docs/RBAC.md`.

**Errors** are `application/problem+json`: `{"type":"about:blank","title":"Conflict","status":409,"detail":"Seat C7 is no longer available","code":"SEAT_UNAVAILABLE","conflicts":["C7"]}`. Validation gives 422 with `errors: {"seats[2]": "unknown seat"}`. Stable `code` values: `VALIDATION`, `NOT_FOUND`, `SEAT_UNAVAILABLE`, `HOLD_EXPIRED`, `BOOKING_STATE`, `PAYMENT_IN_PROGRESS`, `SHOW_STARTED`, `LIMIT_EXCEEDED`, `VERSION_CONFLICT`, `IDEMPOTENCY`, `RATE_LIMITED`, `UNAUTHENTICATED`, `FORBIDDEN`.

**Pagination:** `?page=1&size=20` (page from 1, size 1..100). Envelope `{"items":[...],"page":1,"size":20,"total":57,"totalPages":3}`.

**Filters:** multi-value filters accept repeated parameters (`?genre=Action&genre=Drama`) and comma lists (`?genre=Action,Drama`); values are ORed within a filter and ANDed across filters. `q` matches title words case-insensitively (min 2 chars).

### Auth

| Method, path | Role | Request | Response |
|---|---|---|---|
| POST /api/auth/register | - | `{email, password, name}` | 201 `{user, accessToken, expiresIn}` + refresh cookie; 409 email taken |
| POST /api/auth/login | - | `{email, password}` | 200 as register; 401 on any mismatch (same message) |
| POST /api/auth/refresh | cookie | - | 200 `{accessToken, expiresIn}`, rotated cookie; 401 reused or expired |
| POST /api/auth/logout | cookie | - | 204, cookie cleared, family revoked |
| GET /api/me | U | - | 200 `{id, email, name, roles}`; roles drawn from `AUDIENCE, THEATRE_OWNER, ADMIN` |

Access tokens live 15 min. The refresh token (30 days) is only in cookie `bms_refresh` (HttpOnly, Secure when HTTPS, SameSite=Strict, Path=/api/auth); `/api/auth` routes are behind `Csrf.protect()`.

### Catalog (public, `ETag` and `Cache-Control: public, max-age=60`)

The server also keeps `GET /api/cities`, `GET /api/movies` and `GET /api/movies/{id}/shows` in a 60 s in-memory cache keyed by path and query (`TtlCache`), so they are at most as stale as `max-age` already allows. Any successful admin or owner write clears it. The seat map is never cached.

| Method, path | Query | Response |
|---|---|---|
| GET /api/cities | - | `[{id, name, timeZone}]` |
| GET /api/movies | `city, date, q, genre*, language*, certificate*, format*, sort=release\|title, page, size` | page of `{id, title, languages, genres, certificate, durationMinutes, releaseDate, posterUrl}`; with `city` (and optional `date`, default today in the city) only movies with SCHEDULED future shows there |
| GET /api/movies/{id} | - | movie with `description`; 404 |
| GET /api/movies/{id}/shows | `city` (required), `date`, `language*`, `format*` | `{date, cinemas:[{id, name, address, shows:[{id, startsAt, localTime, language, format, minPrice, availability}]}]}`; availability `AVAILABLE`, `FILLING_FAST` (< 20 % free), `SOLD_OUT` |
| GET /api/cinemas | `city, page, size` | page of `{id, cityId, name, address}` |
| GET /api/cinemas/{id} | - | cinema with `screens:[{id, name, capacity}]` |
| GET /api/shows/{id} | - | `{id, movie:{id,title,certificate,durationMinutes}, cinema:{id,name}, screen:{id,name}, startsAt, localTime, language, format, prices:[{category, name, amount}], status}` |

### Seats, holds, bookings

| Method, path | Role | Request | Response |
|---|---|---|---|
| GET /api/shows/{id}/seats | - | - | 200 `{showId, version, categories:[{code,name,amount}], rows:[{row, seats:[{id, number, category, status}], aisleAfter}], holdTtlSeconds}`; `version` is a content hash of the seat states (equal maps give equal versions; it is not a counter); status `AVAILABLE, HELD, BOOKED, BLOCKED`; `ETag` + `Cache-Control: no-cache` |
| GET /api/shows/{id}/events | - | - | SSE stream (below) |
| POST /api/holds | U, key | `{showId, seats:["C7","C8"]}` | 201 `{id, showId, seats, amount, currency, expiresAt}`; 409 `SEAT_UNAVAILABLE`/`SHOW_STARTED`; 422 |
| DELETE /api/holds/{id} | owner | - | 204; 404 if not owner or not ACTIVE |
| POST /api/bookings | U, key | `{holdId}` | 201 booking; 410 `HOLD_EXPIRED`; 409 hold not ACTIVE |
| GET /api/bookings | U | `status*, page, size` | page of the caller's bookings, newest first |
| GET /api/bookings/{id} | owner or A | - | booking (below); 404 for others |
| POST /api/bookings/{id}/checkout | owner, key | - | 201 `{paymentId, checkoutUrl, amount, currency, expiresAt}`; 409 `BOOKING_STATE`, 410 expired, 429 `LIMIT_EXCEEDED` after 3 |
| POST /api/bookings/{id}/cancel | owner, key | - | 200 booking with `refundAmount`; 409 `SHOW_STARTED`/`PAYMENT_IN_PROGRESS`/`BOOKING_STATE` |
| GET /api/bookings/{id}/ticket | owner | - | `text/html` ticket from `views/ticket.html` (code, movie, cinema, screen, local time, seats, amount); CONFIRMED only, else 409 |

Booking JSON: `{id, code, status, show:{id, movieTitle, cinemaName, screenName, startsAt, localTime}, seats:[{id, category, price}], amount, currency, refundAmount, expiresAt, version, createdAt}`.

### Payments

- `GET /mock-gateway/{paymentId}`: server-rendered HTML page (Views) with amount and Pay/Fail buttons. Enabled only when `MOCK_GATEWAY=true`.
- `POST /mock-gateway/{paymentId}/complete` with `{outcome: "SUCCESS"|"FAILURE"}` (same-origin form, CSRF-protected): the mock gateway adapter builds the event, signs it and POSTs it to `PUBLIC_BASE_URL/api/payments/webhook` over real HTTP, then redirects to `/bookings/{bookingId}`.
- `POST /api/payments/webhook` (public, verified by signature):
  - headers `Bms-Timestamp: <unix seconds>`, `Bms-Signature: v1=<hex HMAC-SHA256(WEBHOOK_SECRET, timestamp + "." + rawBody)>`;
  - body `{eventId, type: "payment.succeeded"|"payment.failed", paymentId, gatewayRef, amount, currency, occurredAt}`;
  - 401 on a missing or bad signature (constant-time compare) or a timestamp more than 300 s from now; 200 `{"duplicate":true}` for a seen `eventId`; 422 on amount or currency mismatch (payment marked FAILED and the booking returned to HELD); 200 `{"status": "<booking status>"}` otherwise.

### Live seat updates: `GET /api/shows/{id}/events`

`text/event-stream`, public, at most `SSE_MAX_STREAMS` (default 1000) open streams; over that 503. On every connect, including a reconnect with `Last-Event-ID`, the server sends `retry: 3000` and a `snapshot` event, then the seat events below. Seat events carry an `id:` that increases in publish order; `snapshot` and `heartbeat` carry none. A reconnect always gets a fresh snapshot rather than a replay, since the snapshot already holds the full state:

| Event | Data |
|---|---|
| `snapshot` | full seat map, same JSON as GET seats |
| `seat-held` | `{"showId":7,"seats":["C7","C8"],"until":"2026-11-20T13:07:00Z"}` |
| `seat-released` | `{"showId":7,"seats":["C7"],"reason":"EXPIRED"\|"RELEASED"\|"CANCELLED"}` |
| `seat-booked` | `{"showId":7,"seats":["C7","C8"]}` |
| `heartbeat` | `{"ts":"2026-11-20T13:00:15Z"}` every 15 s |

Events are published after the transaction commits and never carry user data. The stream ends when the show starts or is cancelled: on admin show cancel the live adapter closes every stream for that show.

### Admin (role A, prefix `/api/admin`)

| Method, path | Notes |
|---|---|
| POST, PUT /cities[/{id}] | create, rename |
| POST /cinemas, PUT /cinemas/{id}, DELETE /cinemas/{id} | DELETE 409 if it has shows |
| POST /cinemas/{id}/screens, PUT /screens/{id}, DELETE /screens/{id} | layout validated (nested `errors` paths like `rows[3].seats`); layout change 409 if future shows exist |
| POST /movies, PUT /movies/{id}, DELETE /movies/{id} | DELETE 409 if it has shows |
| POST /shows, PUT /shows/{id} | overlap check; PUT 409 once any seat is locked |
| POST /shows/{id}/cancel | section 4; idempotent |
| GET /reports/shows | `city, from, to, page, size`: page of `{showId, movieTitle, cinemaName, startsAt, status, capacity, booked, occupancyPercent, revenue, refunded}`; revenue = confirmed amounts minus refunds |
| GET /reports/shows/{id} | the same row plus `byCategory:[{category, capacity, booked, revenue}]` |
| GET /stats | `{activeHolds, bookingsConfirmedToday, sseStreams}` |
| GET /users | `q, page, size`: page of `{id, email, name, roles, cinemaIds}` ordered by id; `q` matches email or name (case-insensitive substring) |
| PUT /users/{id}/roles/{role} | grant `THEATRE_OWNER` or `ADMIN`; 200 the user as in GET /users; 404 unknown user; 422 unknown role; repeating is a no-op |
| DELETE /users/{id}/roles/{role} | revoke; 200 the user; revoking `THEATRE_OWNER` removes every cinema link; revoking `AUDIENCE` is 422; an admin revoking their own `ADMIN` is 409 `CONFLICT` |
| PUT /cinemas/{id}/owners/{userId} | link the user to the cinema and grant `THEATRE_OWNER`; 200 the user; 404 unknown cinema or user; repeating is a no-op |
| DELETE /cinemas/{id}/owners/{userId} | unlink; 200 the user; the role stays; 404 unknown cinema or user |

Admin list views reuse the public GET endpoints. Mutations need an `Idempotency-Key` on POST. A role change reaches the JWT `roles` claim at the next token issue or refresh (at most 15 minutes); cinema links are read per request.

### Theatre owner (role O, prefix `/api/owner`)

Owner routes live under their own prefix instead of reusing the admin routes, so every `/api/admin` route stays admin-only behind one prefix-wide role check and every `/api/owner` route is scoped. The prefix has its own `Auth.requireRole("THEATRE_OWNER", "ADMIN")`. Each use case loads the caller's linked cinemas inside its transaction; anything outside them is 404 `NOT_FOUND`. An ADMIN calling these routes has every cinema in scope. There are no owner routes for cities, movies, cinemas, owner links or roles.

| Method, path | Notes |
|---|---|
| GET /cinemas | my cinemas: `[{id, cityId, name, address, screens:[{id, cinemaId, name, capacity, layout}]}]`, ordered by name |
| POST /cinemas/{id}/screens, PUT /screens/{id} | as the admin routes (layout validated; layout change 409 if future shows exist); 404 outside scope |
| POST /shows | as the admin route, including the overlap check; 404 when the screen is outside scope |
| POST /shows/{id}/cancel | section 4, through the same use case and refunds; idempotent; 404 outside scope |
| GET /reports/shows | as the admin report, filtered to my cinemas |
| GET /reports/shows/{id} | as the admin report detail; 404 outside scope |

Owner POSTs need an `Idempotency-Key`. Movies and cities come from the public GET endpoints.

### Ops

- `GET /api/health`: 200 `{"status":"UP","db":"UP"}`, 503 when `SELECT 1` fails.
- `/_jolt/health` and `/_jolt/metrics` from `app.exposeMetrics(guard)`; the guard requires `Authorization: Bearer <METRICS_TOKEN>` on `/_jolt/metrics`; without `METRICS_TOKEN` neither is exposed. `/_jolt/health` includes the `db` check (`app.health`): 200 `{"status":"UP","checks":{"db":"UP"}}`, else 503. Besides Jolt's own series, metrics carry `bms_sse_subscribers`, `bms_sse_dropped_total`, `bms_catalog_cache_*` and `jolt_task_*{task="expiry-sweep"}`.
- `/_jolt/routes` and `/_jolt/openapi.json` (`app.exposeRoutes()`) only when `JOLT_DEV=1`.

### Rate limits (429 with `Retry-After`)

| Route | Limit | Key |
|---|---|---|
| POST /api/auth/login, /register | 10 per minute | IP (`RateLimit.perIp`) |
| POST /api/auth/refresh | 30 per minute | IP |
| POST /api/holds | 20 per minute | user id (`RateLimit.perKey`) |
| POST /mock-gateway/*/complete | 30 per minute | IP |

## 8. Web UI

Vanilla JS ES modules, no build step, no framework, served from `static/` by `Static.serve("/", dir).spaFallback("index.html")`. Hash-free History API routes:

| Route | Page |
|---|---|
| `/` | city picker on first visit (remembered in `localStorage`), then the movie grid |
| `/movies` | grid with search box and filter chips (genre, language, certificate, format), date strip for the next 7 days, pagination |
| `/movies/{id}` | details, then show times grouped by cinema for the chosen city and date, availability badges |
| `/shows/{id}` | seat map with category legend and prices, live updates over SSE, selection up to 10, sticky summary, "Hold seats" |
| `/checkout/{bookingId}` | hold countdown (mm:ss from `expiresAt`, warning under 60 s, expiry message and back link), "Pay" opens the mock gateway |
| `/bookings` | my bookings with status filter and cancel (shows the refund preview from the rule) |
| `/bookings/{id}` | booking status; once CONFIRMED, the ticket |
| `/login`, `/register` | forms with inline validation from 422 `errors` |
| `/owner` | theatre owner dashboard: tabs for my cinemas with their screens and layouts (create and edit, layout JSON editor with a preview), shows (schedule, list, cancel) and reports, all limited to my cinemas |
| `/admin` | tabs for movies, cinemas and screens (layout JSON editor with a preview), shows, reports and users (search, grant or revoke `THEATRE_OWNER`, assign or unassign cinemas) |

Navigation follows the roles in `/api/me`: everyone signed in sees My bookings; `THEATRE_OWNER` also sees My cinemas (`/owner`); `ADMIN` also sees Admin. An audience member sees neither dashboard, and the route guard sends them home. The UI only hides controls; the server is the authority.

Requirements: semantic HTML; every control keyboard reachable; seats are `button` elements with `aria-label="Row C seat 7, Prime, 250 rupees, available"` and `aria-pressed`; arrow keys move within the map; status is not shown by colour alone (pattern or icon too); countdown in an `aria-live="polite"` region; focus moves to the page heading on route change; WCAG 2.2 AA contrast; light and dark themes from `prefers-color-scheme` plus a toggle; responsive down to 360 px; no inline scripts or styles (CSP). Access token in memory only; the app calls `/api/auth/refresh` on load and on 401.

## 9. Non-functional

- **Middleware order:** `RequestId.middleware()`, `Logger.requests()`, `SecurityHeaders`, `Compress.gzip()`, IP rate limits, `jwt.middleware()` on protected prefixes, per-user rate limits, `Auth.requireRole("ADMIN")` on `/api/admin`, `Auth.requireRole("THEATRE_OWNER", "ADMIN")` on `/api/owner`, `Idempotency.keys().required()` on the POST routes in section 5.
- **Security headers:** `SecurityHeaders.defaults().csp("default-src 'self'; img-src 'self' https: data:; frame-ancestors 'none'; form-action 'self'")`; HSTS only when the request is HTTPS.
- **CORS:** off (no `Cors` middleware); the UI is same-origin.
- **Request IDs:** `X-Request-Id` accepted or generated, echoed, and included in logs and in problem bodies as `requestId`.
- **Logging:** `System.Logger` only; one line per request; never log bodies, tokens, passwords, emails or signatures. Domain events (hold, confirm, expire, refund) log ids only.
- **Binding:** loopback by default; the Docker image sets `JOLT_BIND_ADDRESS=0.0.0.0`.
- **Docker:** multi-stage `Dockerfile` (Maven build, then `eclipse-temurin:21-jre`), non-root user, volume `/data` for the database, `HEALTHCHECK` on `/api/health`.
- **Startup:** fail fast on a missing or short secret, then run migrations, seed the admin, start the sweeper, then listen. Shutdown drains requests, stops the sweeper and closes the pool.

**Environment variables** (read through Jolt's `Config.load()`, so an `application.properties` file may also set them; the environment wins)

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | |
| `JOLT_BIND_ADDRESS` | `127.0.0.1` | read by `JoltConfig.fromEnv()`; also `JOLT_MAX_BODY_BYTES`, `JOLT_MAX_CONNECTIONS` |
| `DB_URL` | `jdbc:sqlite:data/bms.db` | |
| `JWT_SECRET` | required | at least 32 bytes |
| `WEBHOOK_SECRET` | required | at least 32 bytes |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | unset | both set: create or keep that admin on start; password at least 12 chars |
| `HOLD_TTL` | `PT7M` | ISO-8601 duration, or Jolt's short form such as `7m` (all durations) |
| `PAYMENT_WINDOW` | `PT5M` | |
| `MAX_SEATS` | `10` | |
| `SWEEP_INTERVAL` | `PT30S` | |
| `SSE_MAX_STREAMS` | `1000` | |
| `PUBLIC_BASE_URL` | `RENDER_EXTERNAL_URL`, else `http://localhost:${PORT}` | checkout URLs and the mock webhook target; an absolute http(s) URL, trailing slash ignored |
| `RENDER_EXTERNAL_URL` | unset | set by Render to the service's public URL; read only as the `PUBLIC_BASE_URL` fallback, resolved in `AppConfig`, never in domain code |
| `MOCK_GATEWAY` | `true` | |
| `METRICS_TOKEN` | unset | enables `/_jolt/metrics` |
| `TRUST_PROXY` | unset | comma-separated CIDRs for `JoltConfig.trustProxy` |
| `STATIC_DIR`, `VIEWS_DIR` | unset: `/static` and `/views` on the class path | a directory overrides the class path; the Docker image sets both |

## 10. Layering

`domain` (entities, rules, ports, `Clock` use) imports only the JDK. `application` (use cases, transaction boundary through a domain `UnitOfWork` port) imports `domain` only. `adapters.db` and `adapters.payment` implement ports with jolt-db and `java.net.http`. `web` holds `RouteModule`s, request/response records and `onError` mappers and imports `application` and `domain`. `Main` is the only class that names concrete adapters. `ArchitectureTest` enforces this.

## 11. Jolt features used

Jolt `v0.4.1` (`jolt.version` in `pom.xml`).

| Requirement | Jolt API |
|---|---|
| Routing, modules, typed handlers | `Jolt.create`, `app.module`, `RouteModule`, `Router`, `Reply`, `Params` |
| Error mapping, problem+json | `app.onError(Type, (e, req) -> ...)`, `JoltConfig.problemJson(true)`, `HttpException` (Jolt adds `requestId`) |
| Validation | `Validate.check`, `Validator`, `Rules` (`each`, `nested`) |
| Typed and repeated params | `req.param(name, Long.class)`, `req.query(name, type, default)`, `req.queryAll` |
| Pagination envelope | `Page`, `Page.Result` |
| Configuration | `Config.load()` |
| Auth | `JwtAuth.hs256`, `RefreshTokens` with a SQLite `RefreshStore`, `PasswordHasher`, `Auth.requireRole` |
| CSRF on cookie routes | `Csrf.protect()` |
| Hardening | `SecurityHeaders`, `RateLimit.perIp/perKey`, `RequestId`, `Logger.requests()`, `JoltConfig.fromEnv` |
| Idempotency-Key | `Idempotency.keys().store(...).required()` with a SQLite `IdempotencyStore` |
| Webhook signatures | `Webhooks.hmacSha256(secret, "Bms-Signature").prefix("v1=").timestampHeader("Bms-Timestamp")` |
| Caching and size | `res.json(x).etag()`, `res.cache(Duration)`, `res.noStore()`, `TtlCache`, `Compress.gzip()` |
| UI and ticket | `Static.classpath("/", "/static").spaFallback`, `app.viewsClasspath`, `res.render` (`Static.serve` and `app.views` when `STATIC_DIR`/`VIEWS_DIR` are set) |
| Live seats | `Hub` (`subscribe`, `publish`, `metrics`), `res.sse()`, `EventStream.event(id, name, data)`, `retry`, `config.streamTimeouts` |
| Persistence | `Db.open`, `query`, `queryOne`, `update`, `insert`, `batch`, `updateExpecting`, `inTransaction(TxMode.IMMEDIATE, ...)`, `busyTimeout` |
| Background work and lifecycle | `app.every`, `app.onStop` |
| Ops | `app.exposeMetrics(guard)`, `app.health`, `app.metrics()`, `app.exposeRoutes()`, `app.openApi()` |
| Tests | `JoltTest.of(app)`, `session()`, `jsonEquals`, `maxEvents`, `timeout`, `events()`, `app.listen(0)` for socket tests |

## 12. Known gaps

Every Jolt gap from #52 to #77 is fixed in v0.4.1 and its workaround removed; `docs/JOLT_GAPS.md` records how each was replaced. What remains is listed there with a reason:

| Requirement | Jolt | What the app still does |
|---|---|---|
| Migrations | `Migrations` (#55) keeps its own history table | `adapters.db.SchemaRunner` stays, so databases it already migrated keep working |
| Paging errors | `Page.of` answers 400 without the field and caps `size` | `web.Paging` checks `page` and `size` and answers 422 with `errors.page`/`errors.size` |
| Idempotency errors | `Idempotency` writes plain-text 400/409/422 and replays the first request's `X-Request-Id` | `web.Idempotent` re-throws them as problem+json `IDEMPOTENCY` and keeps the current request id |
| Expected errors in the log | mapped non-`HttpException` errors are logged at WARNING with a stack trace | `web.Problems.middleware` turns domain errors into `HttpException`s and logs unexpected ones by class and request id |
| SSE snapshot and shutdown | `Hub.sse` cannot send a snapshot first; nothing ends a topic's subscribers | `adapters.live.LiveSeatHub` subscribes and the route writes SSE itself; `Main` ends streams before the drain |
| Health URL | checks run only behind `/_jolt/health` | `GET /api/health` keeps its shape for Render on the same DB check |
| Static files in Docker | none | `STATIC_DIR`/`VIEWS_DIR` overrides stay while the image copies the UI outside the jar |
| Webhook signing for the mock gateway | verification only | `adapters.payment.WebhookSigner` |

## 13. Test plan (per lane)

- Domain: layout validation, overlap rule, refund boundaries (24 h, 4 h, start), state transitions table-driven.
- Repositories: one contract test per port, run against SQLite in a temp file.
- API: `JoltTest` for every endpoint, including each error `code`.
- RBAC: anonymous gets 401 on every protected route; an audience member 403 on every owner and admin route; a theatre owner 403 on every admin route and 404 on another owner's cinema, screen, show and report; an admin passes every route and cannot remove their own `ADMIN`. `RbacMatrixTest` keeps the routes, `web.Permissions` and `docs/RBAC.md` in step.
- Concurrency: 200 virtual-thread buyers on overlapping seats; booked seats never exceed capacity and no seat is booked twice; every hold ends CONSUMED, RELEASED or EXPIRED.
- Webhook: valid, tampered body, wrong secret, stale timestamp, duplicate event id, success after expiry (refund).
- End to end on a socket: register, search, hold, book, checkout, webhook, ticket, cancel, SSE events; an admin promotes a user and links a cinema, the owner schedules a show there, an audience member books it, the owner sees the sale in their report and gets 404 on another cinema.
