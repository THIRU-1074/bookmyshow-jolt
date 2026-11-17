package dev.thiru.bms.web;

import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.IdempotencyStore;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.PasswordHasher;
import dev.thiru.bms.domain.port.RefreshTokenStore;
import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.test.JoltTest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The whole web layer on in-memory fakes: no socket, no database. */
final class WebKit {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
    static final String PASSWORD = "correct-horse-battery";
    static final String GOOD_SIGNATURE = "v1=good";

    final FakeWorld world = FakeWorld.seeded();
    final MemoryRefreshTokens refreshTokens = new MemoryRefreshTokens();
    final MemoryIdempotency idempotency = new MemoryIdempotency();
    final FakeLive live = new FakeLive(5);
    final List<ProcessPaymentWebhook.Event> sentWebhooks = new CopyOnWriteArrayList<>();
    final AtomicBoolean dbUp = new AtomicBoolean(true);
    final App app;
    final JoltTest http;
    private final AtomicInteger addresses = new AtomicInteger();

    WebKit() {
        this(true, Duration.ofSeconds(15));
    }

    WebKit(boolean mockGateway, Duration heartbeat) {
        app = Jolt.create(JoltConfig.defaults().problemJson(true));
        WebDeps deps =
                new WebDeps(
                        world,
                        world.clock,
                        world.policy,
                        world,
                        new PlainHasher(),
                        refreshTokens,
                        idempotency,
                        world,
                        world,
                        (ts, sig, body) -> GOOD_SIGNATURE.equals(sig),
                        event -> {
                            sentWebhooks.add(event);
                            return 200;
                        },
                        live,
                        dbUp::get,
                        new WebDeps.Settings(
                                SECRET,
                                mockGateway,
                                Path.of("src/main/resources/views"),
                                heartbeat));
        WebModules.install(app, deps);
        http = JoltTest.of(app);
    }

    /** A distinct client address per call keeps the per-IP auth limits out of unrelated tests. */
    String nextAddress() {
        return "10.0.0." + (addresses.incrementAndGet() % 250 + 1);
    }

    /** Registers a USER and returns its access token. */
    String user(String email) {
        return (String)
                http.post("/api/auth/register")
                        .remoteAddress(nextAddress())
                        .json(Map.of("email", email, "password", PASSWORD, "name", "Test"))
                        .expect(201)
                        .jsonField("accessToken");
    }

    /** Creates an ADMIN directly in the store (registration never grants roles) and logs in. */
    String admin() {
        world.inTransaction(
                r ->
                        r.users()
                                .insert(
                                        new User(
                                                0,
                                                "admin@bms.test",
                                                PlainHasher.PREFIX + PASSWORD,
                                                "Admin",
                                                Set.of(Role.ADMIN, Role.USER),
                                                FakeWorld.T0)));
        return login("admin@bms.test");
    }

    String login(String email) {
        return (String)
                http.post("/api/auth/login")
                        .remoteAddress(nextAddress())
                        .json(Map.of("email", email, "password", PASSWORD))
                        .expect(200)
                        .jsonField("accessToken");
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- booking flow ----

    private final AtomicInteger keys = new AtomicInteger();

    String key() {
        return "key-" + keys.incrementAndGet();
    }

    /** Holds seats on the seeded show and returns the hold id. */
    String hold(String token, String... seats) {
        return (String)
                http.post("/api/holds")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .json(Map.of("showId", FakeWorld.SHOW_ID, "seats", List.of(seats)))
                        .expect(201)
                        .jsonField("id");
    }

    String book(String token, String holdId) {
        return (String)
                http.post("/api/bookings")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .json(Map.of("holdId", holdId))
                        .expect(201)
                        .jsonField("id");
    }

    /** Checks out and returns the payment id. */
    String checkout(String token, String bookingId) {
        return (String)
                http.post("/api/bookings/" + bookingId + "/checkout")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .expect(201)
                        .jsonField("paymentId");
    }

    JoltTest.Call webhook(String eventId, String type, String paymentId, long amount) {
        return http.post("/api/payments/webhook")
                .header("Bms-Timestamp", "1")
                .header("Bms-Signature", GOOD_SIGNATURE)
                .json(
                        Map.of(
                                "eventId", eventId,
                                "type", type,
                                "paymentId", paymentId,
                                "gatewayRef", "gw_" + paymentId,
                                "amount", amount,
                                "currency", "INR",
                                "occurredAt", FakeWorld.T0.toString()));
    }

    /** A CONFIRMED booking of seats A2 and A3 (2 x 150.00) for {@code token}; returns its id. */
    String confirmedBooking(String token) {
        String booking = book(token, hold(token, "A2", "A3"));
        String payment = checkout(token, booking);
        webhook("evt-" + booking, "payment.succeeded", payment, 30000)
                .expect(200)
                .jsonEquals("status", "CONFIRMED");
        return booking;
    }

    // ---- fakes ----

    static final class PlainHasher implements PasswordHasher {
        static final String PREFIX = "plain:";

        @Override
        public String hash(String raw) {
            return PREFIX + raw;
        }

        @Override
        public boolean verify(String raw, String hash) {
            return hash.equals(PREFIX + raw);
        }
    }

    static final class MemoryRefreshTokens implements RefreshTokenStore {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        private final Set<String> revoked = ConcurrentHashMap.newKeySet();

        @Override
        public void save(Entry entry) {
            entries.put(entry.hash(), entry);
        }

        @Override
        public Optional<Entry> find(String hash) {
            return Optional.ofNullable(entries.get(hash));
        }

        @Override
        public synchronized boolean rotate(String oldHash, Entry next) {
            Entry old = entries.get(oldHash);
            if (old == null || old.used() || revoked.contains(old.family())) {
                return false;
            }
            entries.put(
                    oldHash,
                    new Entry(old.hash(), old.subject(), old.family(), old.expires(), true));
            entries.put(next.hash(), next);
            return true;
        }

        @Override
        public void revokeFamily(String family) {
            revoked.add(family);
            entries.values().removeIf(e -> e.family().equals(family));
        }

        @Override
        public Set<String> revokeSubject(String subject) {
            Set<String> families = new HashSet<>();
            entries.values().stream()
                    .filter(e -> e.subject().equals(subject))
                    .forEach(e -> families.add(e.family()));
            families.forEach(this::revokeFamily);
            return families;
        }
    }

    static final class MemoryIdempotency implements IdempotencyStore {
        private record Row(String fingerprint, Stored stored) {}

        private final Map<String, Row> rows = new ConcurrentHashMap<>();

        @Override
        public synchronized Claim begin(long userId, String key, String fingerprint, Instant now) {
            String k = userId + "/" + key;
            Row row = rows.get(k);
            if (row == null) {
                rows.put(k, new Row(fingerprint, null));
                return new Claim(Kind.FRESH, null);
            }
            if (!row.fingerprint().equals(fingerprint)) {
                return new Claim(Kind.MISMATCH, null);
            }
            return row.stored() == null
                    ? new Claim(Kind.IN_FLIGHT, null)
                    : new Claim(Kind.REPLAY, row.stored());
        }

        @Override
        public synchronized void complete(long userId, String key, Stored response) {
            String k = userId + "/" + key;
            rows.put(k, new Row(rows.get(k).fingerprint(), response));
        }

        @Override
        public synchronized void release(long userId, String key) {
            rows.remove(userId + "/" + key);
        }
    }

    static final class FakeLive implements LiveSeats {
        final List<Sub> subs = new CopyOnWriteArrayList<>();
        private final int max;

        FakeLive(int max) {
            this.max = max;
        }

        @Override
        public synchronized Optional<Subscription> subscribe(long showId) {
            if (openStreams() >= max) {
                return Optional.empty();
            }
            Sub s = new Sub(showId);
            subs.add(s);
            return Optional.of(s);
        }

        @Override
        public int openStreams() {
            return (int) subs.stream().filter(Sub::isOpen).count();
        }

        void push(long showId, String name, String data) {
            subs.stream()
                    .filter(s -> s.showId == showId)
                    .forEach(s -> s.queue.add(new Message(name, data)));
        }

        void endAll() {
            subs.forEach(Sub::close);
        }

        static final class Sub implements Subscription {
            final long showId;
            final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();
            private volatile boolean open = true;

            Sub(long showId) {
                this.showId = showId;
            }

            @Override
            public Message poll(Duration timeout) throws InterruptedException {
                return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        }
    }
}
