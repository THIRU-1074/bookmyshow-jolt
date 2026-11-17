package dev.thiru.bms.application.fakes;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingPolicy;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.BookingRepositories;
import dev.thiru.bms.domain.port.CatalogRepositories;
import dev.thiru.bms.domain.port.IdGenerator;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.SeatLock;
import dev.thiru.bms.domain.port.SeatLockRepository;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * In-memory implementation of every port with snapshot rollback, so use cases run against real
 * transaction semantics (an exception undoes all writes) without a database.
 */
public final class FakeWorld
        implements UnitOfWork, Repositories, PaymentGateway, SeatEventPublisher, IdGenerator {

    public static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    public static final long SHOW_ID = 9;
    public static final long SCREEN_ID = 5;

    /** Mutable test clock. */
    public static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Data {
        Map<String, City> cities = new LinkedHashMap<>();
        Map<Long, Cinema> cinemas = new LinkedHashMap<>();
        Map<Long, Screen> screens = new LinkedHashMap<>();
        Map<Long, Movie> movies = new LinkedHashMap<>();
        Map<Long, Show> shows = new LinkedHashMap<>();
        Map<Long, User> users = new LinkedHashMap<>();
        Map<String, SeatHold> holds = new LinkedHashMap<>();
        Map<String, SeatLock> locks = new LinkedHashMap<>();
        Map<String, Booking> bookings = new LinkedHashMap<>();
        Map<String, Payment> payments = new LinkedHashMap<>();
        Set<String> events = new HashSet<>();

        Data copy() {
            Data d = new Data();
            d.cities = new LinkedHashMap<>(cities);
            d.cinemas = new LinkedHashMap<>(cinemas);
            d.screens = new LinkedHashMap<>(screens);
            d.movies = new LinkedHashMap<>(movies);
            d.shows = new LinkedHashMap<>(shows);
            d.users = new LinkedHashMap<>(users);
            d.holds = new LinkedHashMap<>(holds);
            d.locks = new LinkedHashMap<>(locks);
            d.bookings = new LinkedHashMap<>(bookings);
            d.payments = new LinkedHashMap<>(payments);
            d.events = new HashSet<>(events);
            return d;
        }
    }

    private Data d = new Data();
    private int idCounter;
    private final Random random = new Random(1);

    public final TestClock clock = new TestClock(T0);
    public final BookingPolicy policy = BookingPolicy.defaults();
    public final List<SeatEvent> published = new ArrayList<>();
    public final List<String> refunds = new ArrayList<>();
    public int gatewaySessions;
    public boolean failRefunds;
    public boolean failPublishing;

    /** Number of upcoming booking updates to reject with a version conflict. */
    public int injectedVersionConflicts;

    /** City, cinema, screen (23 seats), movie (120 min) and show 9 three days out. */
    public static FakeWorld seeded() {
        FakeWorld w = new FakeWorld();
        City city = City.of("bengaluru", "Bengaluru", "Asia/Kolkata");
        w.d.cities.put(city.id(), city);
        w.d.cinemas.put(2L, new Cinema(2, "bengaluru", "PVR Orion", "Rajajinagar"));
        SeatLayout layout =
                new SeatLayout(
                        List.of(
                                new SeatLayout.Category("CLASSIC", "Classic"),
                                new SeatLayout.Category("PRIME", "Prime")),
                        List.of(
                                new SeatLayout.Row("A", 14, "CLASSIC", List.of(4, 10), List.of(1)),
                                new SeatLayout.Row("B", 10, "PRIME", List.of(), List.of())));
        Screen screen = new Screen(SCREEN_ID, 2, "Screen 1", layout);
        w.d.screens.put(SCREEN_ID, screen);
        Movie movie =
                new Movie(
                        1,
                        "Kaveri Nights",
                        List.of("en", "ta"),
                        List.of("Drama"),
                        Certificate.UA,
                        120,
                        LocalDate.of(2026, 11, 1),
                        "/p.png",
                        "d");
        w.d.movies.put(1L, movie);
        w.addShow(SHOW_ID, T0.plus(Duration.ofDays(3)), 1, "en", ShowFormat.TWO_D);
        return w;
    }

    public Show addShow(long id, Instant start, long movieId, String language, ShowFormat format) {
        Show show =
                Show.schedule(
                        id,
                        d.movies.get(movieId),
                        d.screens.get(SCREEN_ID),
                        start,
                        language,
                        format,
                        Map.of("CLASSIC", 15000L, "PRIME", 25000L),
                        List.of());
        d.shows.put(id, show);
        return show;
    }

    public Movie addMovie(Movie movie) {
        d.movies.put(movie.id(), movie);
        return movie;
    }

    // ---- UnitOfWork ----

    @Override
    public <T> T inTransaction(Function<Repositories, T> work) {
        Data backup = d.copy();
        try {
            return work.apply(this);
        } catch (RuntimeException | Error e) {
            d = backup;
            throw e;
        }
    }

    // ---- inspection helpers ----

    public Booking booking(String id) {
        return d.bookings.get(id);
    }

    public List<Booking> allBookings() {
        return List.copyOf(d.bookings.values());
    }

    public SeatHold hold(String id) {
        return d.holds.get(id);
    }

    public List<SeatHold> allHolds() {
        return List.copyOf(d.holds.values());
    }

    public Payment payment(String id) {
        return d.payments.get(id);
    }

    public List<Payment> allPayments() {
        return List.copyOf(d.payments.values());
    }

    public List<SeatLock> allLocks() {
        return List.copyOf(d.locks.values());
    }

    public Show show(long id) {
        return d.shows.get(id);
    }

    /** Test shortcut: a live hold for the user on the given seats, as CreateHold would leave it. */
    public SeatHold putHold(long userId, String... seats) {
        Show show = d.shows.get(SHOW_ID);
        SeatHold hold =
                SeatHold.create(
                        newId(),
                        show,
                        d.screens.get(SCREEN_ID),
                        userId,
                        List.of(seats),
                        clock.instant(),
                        policy);
        d.holds.put(hold.id(), hold);
        hold.seats().forEach(s -> putLock(show.id(), s, hold.id(), hold.expiresAt()));
        return hold;
    }

    public void putLock(long showId, SeatId seat, String holdId, Instant expiresAt) {
        d.locks.put(
                showId + ":" + seat,
                new SeatLock(showId, seat, holdId, null, SeatLock.State.HELD, expiresAt));
    }

    // ---- IdGenerator ----

    @Override
    public String newId() {
        return "id-" + (++idCounter);
    }

    @Override
    public RandomGenerator random() {
        return random;
    }

    // ---- PaymentGateway ----

    @Override
    public CheckoutSession createSession(String paymentId, long amount, String currency) {
        gatewaySessions++;
        return new CheckoutSession("gw_" + paymentId, "http://pay.test/" + paymentId);
    }

    @Override
    public void refund(String gatewayRef, long amount) {
        if (failRefunds) {
            throw new IllegalStateException("gateway down");
        }
        refunds.add(gatewayRef + ":" + amount);
    }

    // ---- SeatEventPublisher ----

    @Override
    public void publish(SeatEvent event) {
        if (failPublishing) {
            throw new IllegalStateException("subscriber exploded");
        }
        published.add(event);
    }

    // ---- Repositories ----

    @Override
    public CatalogRepositories.Cities cities() {
        return new CatalogRepositories.Cities() {
            public List<City> findAll() {
                return List.copyOf(d.cities.values());
            }

            public Optional<City> findById(String id) {
                return Optional.ofNullable(d.cities.get(id));
            }
        };
    }

    @Override
    public CatalogRepositories.Cinemas cinemas() {
        return new CatalogRepositories.Cinemas() {
            public Optional<Cinema> findById(long id) {
                return Optional.ofNullable(d.cinemas.get(id));
            }

            public Page<Cinema> page(String cityId, int page, int size) {
                List<Cinema> all =
                        d.cinemas.values().stream()
                                .filter(c -> cityId == null || c.cityId().equals(cityId))
                                .toList();
                return slice(all, page, size);
            }
        };
    }

    @Override
    public CatalogRepositories.Screens screens() {
        return new CatalogRepositories.Screens() {
            public Optional<Screen> findById(long id) {
                return Optional.ofNullable(d.screens.get(id));
            }

            public List<Screen> findByCinema(long cinemaId) {
                return d.screens.values().stream().filter(s -> s.cinemaId() == cinemaId).toList();
            }
        };
    }

    private static <T> Page<T> slice(List<T> all, int page, int size) {
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new Page<>(all.subList(from, to), page, size, all.size());
    }

    private boolean playsIn(Show s, String cityId) {
        Screen screen = d.screens.get(s.screenId());
        return d.cinemas.get(screen.cinemaId()).cityId().equals(cityId);
    }

    @Override
    public CatalogRepositories.Movies movies() {
        return new CatalogRepositories.Movies() {
            public Optional<Movie> findById(long id) {
                return Optional.ofNullable(d.movies.get(id));
            }

            public Page<Movie> search(CatalogRepositories.MovieQuery q, int page, int size) {
                List<Movie> hits =
                        d.movies.values().stream()
                                .filter(m -> matches(m, q))
                                .sorted(
                                        q.sort() == CatalogRepositories.MovieQuery.Sort.TITLE
                                                ? Comparator.comparing(Movie::title)
                                                : Comparator.comparing(Movie::releaseDate)
                                                        .reversed())
                                .toList();
                return slice(hits, page, size);
            }

            private boolean matches(Movie m, CatalogRepositories.MovieQuery q) {
                if (q.q() != null
                        && !m.title()
                                .toLowerCase(Locale.ROOT)
                                .contains(q.q().toLowerCase(Locale.ROOT))) {
                    return false;
                }
                if (!q.genres().isEmpty() && m.genres().stream().noneMatch(q.genres()::contains)) {
                    return false;
                }
                if (!q.languages().isEmpty()
                        && m.languages().stream().noneMatch(q.languages()::contains)) {
                    return false;
                }
                if (!q.certificates().isEmpty() && !q.certificates().contains(m.certificate())) {
                    return false;
                }
                if (q.cityId() == null) {
                    return true;
                }
                return d.shows.values().stream()
                        .anyMatch(
                                s ->
                                        s.movieId() == m.id()
                                                && s.status() == ShowStatus.SCHEDULED
                                                && playsIn(s, q.cityId())
                                                && !s.startsAt().isBefore(q.from())
                                                && s.startsAt().isBefore(q.to())
                                                && (q.formats().isEmpty()
                                                        || q.formats().contains(s.format())));
            }
        };
    }

    @Override
    public CatalogRepositories.Shows shows() {
        return new CatalogRepositories.Shows() {
            public Optional<Show> findById(long id) {
                return Optional.ofNullable(d.shows.get(id));
            }

            public Show save(Show show) {
                d.shows.put(show.id(), show);
                return show;
            }

            public List<CatalogRepositories.ShowListing> findListings(
                    long movieId,
                    String cityId,
                    Instant from,
                    Instant to,
                    Set<String> languages,
                    Set<ShowFormat> formats) {
                return d.shows.values().stream()
                        .filter(s -> s.movieId() == movieId && s.status() == ShowStatus.SCHEDULED)
                        .filter(s -> playsIn(s, cityId))
                        .filter(s -> !s.startsAt().isBefore(from) && s.startsAt().isBefore(to))
                        .filter(s -> languages.isEmpty() || languages.contains(s.language()))
                        .filter(s -> formats.isEmpty() || formats.contains(s.format()))
                        .sorted(Comparator.comparing(Show::startsAt))
                        .map(
                                s -> {
                                    Screen screen = d.screens.get(s.screenId());
                                    return new CatalogRepositories.ShowListing(
                                            s, d.cinemas.get(screen.cinemaId()), screen);
                                })
                        .sorted(Comparator.comparingLong(l -> l.cinema().id()))
                        .toList();
            }

            public List<Show> findByScreen(long screenId) {
                return d.shows.values().stream().filter(s -> s.screenId() == screenId).toList();
            }
        };
    }

    @Override
    public BookingRepositories.Users users() {
        return new BookingRepositories.Users() {
            public Optional<User> findById(long id) {
                return Optional.ofNullable(d.users.get(id));
            }

            public Optional<User> findByEmail(String email) {
                return d.users.values().stream().filter(u -> u.email().equals(email)).findFirst();
            }

            public User insert(User user) {
                d.users.put(user.id(), user);
                return user;
            }
        };
    }

    @Override
    public BookingRepositories.Holds holds() {
        return new BookingRepositories.Holds() {
            public void insert(SeatHold hold) {
                d.holds.put(hold.id(), hold);
            }

            public void update(SeatHold hold) {
                d.holds.put(hold.id(), hold);
            }

            public Optional<SeatHold> findById(String id) {
                return Optional.ofNullable(d.holds.get(id));
            }

            public Optional<SeatHold> findActiveByUserAndShow(long userId, long showId) {
                return d.holds.values().stream()
                        .filter(
                                h ->
                                        h.userId() == userId
                                                && h.showId() == showId
                                                && h.status() == HoldStatus.ACTIVE)
                        .findFirst();
            }

            public List<SeatHold> findActiveByShow(long showId) {
                return d.holds.values().stream()
                        .filter(h -> h.showId() == showId && h.status() == HoldStatus.ACTIVE)
                        .toList();
            }

            public List<SeatHold> findExpiredActive(Instant now, int limit) {
                return d.holds.values().stream()
                        .filter(h -> h.status() == HoldStatus.ACTIVE && h.isExpiredAt(now))
                        .limit(limit)
                        .toList();
            }

            public long countActive(Instant now) {
                return d.holds.values().stream()
                        .filter(h -> h.status() == HoldStatus.ACTIVE && !h.isExpiredAt(now))
                        .count();
            }
        };
    }

    @Override
    public SeatLockRepository locks() {
        return new SeatLockRepository() {
            public void lock(long showId, String holdId, List<SeatId> seats, Instant expiresAt) {
                List<SeatId> conflicts =
                        seats.stream().filter(s -> d.locks.containsKey(showId + ":" + s)).toList();
                if (!conflicts.isEmpty()) {
                    throw DomainException.seatUnavailable(conflicts);
                }
                seats.forEach(s -> putLock(showId, s, holdId, expiresAt));
            }

            public List<SeatId> deleteExpiredHeld(
                    long showId, Collection<SeatId> seats, Instant now) {
                List<SeatId> freed = new ArrayList<>();
                for (SeatId s : seats) {
                    SeatLock l = d.locks.get(showId + ":" + s);
                    if (l != null
                            && l.state() == SeatLock.State.HELD
                            && !l.expiresAt().isAfter(now)) {
                        d.locks.remove(showId + ":" + s);
                        freed.add(s);
                    }
                }
                return freed;
            }

            public List<SeatId> deleteByHold(String holdId) {
                List<SeatLock> mine = findByHold(holdId);
                mine.forEach(l -> d.locks.remove(l.showId() + ":" + l.seat()));
                return mine.stream().map(SeatLock::seat).sorted().toList();
            }

            public void attachBooking(String holdId, String bookingId) {
                findByHold(holdId)
                        .forEach(
                                l ->
                                        d.locks.put(
                                                l.showId() + ":" + l.seat(),
                                                new SeatLock(
                                                        l.showId(),
                                                        l.seat(),
                                                        l.holdId(),
                                                        bookingId,
                                                        l.state(),
                                                        l.expiresAt())));
            }

            public void updateExpiry(String holdId, Instant expiresAt) {
                findByHold(holdId).stream()
                        .filter(l -> l.state() == SeatLock.State.HELD)
                        .forEach(
                                l ->
                                        d.locks.put(
                                                l.showId() + ":" + l.seat(),
                                                new SeatLock(
                                                        l.showId(),
                                                        l.seat(),
                                                        l.holdId(),
                                                        l.bookingId(),
                                                        l.state(),
                                                        expiresAt)));
            }

            public void markBooked(String holdId) {
                findByHold(holdId)
                        .forEach(
                                l ->
                                        d.locks.put(
                                                l.showId() + ":" + l.seat(),
                                                new SeatLock(
                                                        l.showId(),
                                                        l.seat(),
                                                        l.holdId(),
                                                        l.bookingId(),
                                                        SeatLock.State.BOOKED,
                                                        null)));
            }

            public List<SeatLock> findByHold(String holdId) {
                return d.locks.values().stream().filter(l -> l.holdId().equals(holdId)).toList();
            }

            public List<SeatLock> findByShow(long showId) {
                return d.locks.values().stream().filter(l -> l.showId() == showId).toList();
            }

            public Map<Long, Integer> countLive(Collection<Long> showIds, Instant now) {
                Map<Long, Integer> out = new HashMap<>();
                d.locks.values().stream()
                        .filter(l -> showIds.contains(l.showId()) && l.isLiveAt(now))
                        .forEach(l -> out.merge(l.showId(), 1, Integer::sum));
                return out;
            }
        };
    }

    @Override
    public BookingRepositories.Bookings bookings() {
        return new BookingRepositories.Bookings() {
            public void insert(Booking b) {
                d.bookings.put(b.id(), b);
            }

            public Booking update(Booking b) {
                if (injectedVersionConflicts > 0) {
                    injectedVersionConflicts--;
                    throw DomainException.versionConflict();
                }
                Booking stored = d.bookings.get(b.id());
                if (stored == null || stored.version() != b.version()) {
                    throw DomainException.versionConflict();
                }
                Booking saved = b.withVersion(b.version() + 1);
                d.bookings.put(b.id(), saved);
                return saved;
            }

            public Optional<Booking> findById(String id) {
                return Optional.ofNullable(d.bookings.get(id));
            }

            public boolean existsByCode(String code) {
                return d.bookings.values().stream().anyMatch(b -> b.code().equals(code));
            }

            public Page<Booking> page(
                    long userId, Set<BookingStatus> statuses, int page, int size) {
                return slice(
                        d.bookings.values().stream()
                                .filter(b -> b.userId() == userId)
                                .filter(b -> statuses.isEmpty() || statuses.contains(b.status()))
                                .sorted(Comparator.comparing(Booking::createdAt).reversed())
                                .toList(),
                        page,
                        size);
            }

            public List<Booking> findOpenExpired(Instant now, int limit) {
                return d.bookings.values().stream()
                        .filter(b -> b.status().isOpen() && b.isExpiredAt(now))
                        .limit(limit)
                        .toList();
            }

            public List<Booking> findLiveByShow(long showId) {
                return d.bookings.values().stream()
                        .filter(
                                b ->
                                        b.showId() == showId
                                                && (b.status().isOpen()
                                                        || b.status() == BookingStatus.CONFIRMED))
                        .toList();
            }
        };
    }

    @Override
    public BookingRepositories.Payments payments() {
        return new BookingRepositories.Payments() {
            public void insert(Payment p) {
                d.payments.put(p.id(), p);
            }

            public void update(Payment p) {
                d.payments.put(p.id(), p);
            }

            public Optional<Payment> findById(String id) {
                return Optional.ofNullable(d.payments.get(id));
            }

            public Optional<Payment> findLatestByBooking(String bookingId) {
                Payment latest = null;
                for (Payment p : d.payments.values()) {
                    if (p.bookingId().equals(bookingId)) {
                        latest = p;
                    }
                }
                return Optional.ofNullable(latest);
            }
        };
    }

    @Override
    public BookingRepositories.PaymentEvents paymentEvents() {
        return (eventId, paymentId, type, receivedAt) -> d.events.add(eventId);
    }
}
