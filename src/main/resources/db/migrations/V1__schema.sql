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
