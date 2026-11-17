package dev.thiru.bms.application.fakes;

import dev.thiru.bms.domain.port.AdminRepositories;
import dev.thiru.bms.domain.port.BookingRepositories;
import dev.thiru.bms.domain.port.CatalogRepositories;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatLockRepository;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
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
public final class FakeWorld implements UnitOfWork, Repositories, PaymentGateway, SeatEvents, Ids {

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
        // "cinemaId/userId" pairs, as in table cinema_owner.
        Set<String> owners = new java.util.LinkedHashSet<>();

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
            d.owners = new java.util.LinkedHashSet<>(owners);
            return d;
        }
    }

    private Data d = new Data();
    private int idCounter;
    private long userSeq;
    private long entitySeq = 1000;
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

    // ---- Ids ----

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
        // As MockPaymentGateway: a negative amount is a caller bug, not a gateway outage.
        if (amount < 0) {
            throw new IllegalArgumentException("refund amount must not be negative");
        }
        if (failRefunds) {
            throw new IllegalStateException("gateway down");
        }
        refunds.add(gatewayRef + ":" + amount);
    }

    // ---- SeatEvents ----

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
                // As SqlCatalog: ORDER BY name.
                return d.cities.values().stream().sorted(Comparator.comparing(City::name)).toList();
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
                // As SqlCatalog: ORDER BY name, id.
                return d.screens.values().stream()
                        .filter(s -> s.cinemaId() == cinemaId)
                        .sorted(Comparator.comparing(Screen::name).thenComparing(Screen::id))
                        .toList();
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
                // Like the SQL adapter: emails are stored lower-case and looked up lower-case.
                String wanted = email.toLowerCase(Locale.ROOT);
                return d.users.values().stream().filter(u -> u.email().equals(wanted)).findFirst();
            }

            public User insert(User user) {
                // app_user.email is UNIQUE; the SQL adapter reports the clash as VALIDATION.
                if (d.users.values().stream().anyMatch(u -> u.email().equals(user.email()))) {
                    throw DomainException.validation("email", "is already registered");
                }
                User saved =
                        user.id() != 0
                                ? user
                                : new User(
                                        ++userSeq,
                                        user.email(),
                                        user.passwordHash(),
                                        user.name(),
                                        user.roles(),
                                        user.createdAt());
                d.users.put(saved.id(), saved);
                return saved;
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
                        .sorted(
                                Comparator.comparing(SeatHold::expiresAt)
                                        .thenComparing(SeatHold::id))
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
                return freed.stream().sorted().toList();
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
                return d.locks.values().stream()
                        .filter(l -> l.holdId().equals(holdId))
                        .sorted(Comparator.comparing(SeatLock::seat))
                        .toList();
            }

            public List<SeatLock> findByShow(long showId) {
                return d.locks.values().stream()
                        .filter(l -> l.showId() == showId)
                        .sorted(Comparator.comparing(SeatLock::seat))
                        .toList();
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
                        .sorted(Comparator.comparing(Booking::expiresAt).thenComparing(Booking::id))
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

    @Override
    public AdminRepositories.CatalogWrites catalogWrites() {
        return new AdminRepositories.CatalogWrites() {
            public City saveCity(City city) {
                d.cities.put(city.id(), city);
                return city;
            }

            public Cinema saveCinema(Cinema c) {
                Cinema saved =
                        c.id() != 0
                                ? c
                                : new Cinema(++entitySeq, c.cityId(), c.name(), c.address());
                d.cinemas.put(saved.id(), saved);
                return saved;
            }

            public boolean cinemaNameTaken(String cityId, String name, long exceptId) {
                return d.cinemas.values().stream()
                        .anyMatch(
                                c ->
                                        c.id() != exceptId
                                                && c.cityId().equals(cityId)
                                                && c.name().equalsIgnoreCase(name));
            }

            public void deleteCinema(long id) {
                d.cinemas.remove(id);
                d.owners.removeIf(k -> k.startsWith(id + "/"));
            }

            public boolean cinemaHasShows(long id) {
                return d.shows.values().stream()
                        .anyMatch(s -> d.screens.get(s.screenId()).cinemaId() == id);
            }

            public Screen saveScreen(Screen s) {
                Screen saved =
                        s.id() != 0
                                ? s
                                : new Screen(++entitySeq, s.cinemaId(), s.name(), s.layout());
                d.screens.put(saved.id(), saved);
                return saved;
            }

            public boolean screenNameTaken(long cinemaId, String name, long exceptId) {
                return d.screens.values().stream()
                        .anyMatch(
                                s ->
                                        s.id() != exceptId
                                                && s.cinemaId() == cinemaId
                                                && s.name().equalsIgnoreCase(name));
            }

            public void deleteScreen(long id) {
                d.screens.remove(id);
            }

            public boolean screenHasShows(long id) {
                return d.shows.values().stream().anyMatch(s -> s.screenId() == id);
            }

            public boolean screenHasFutureShows(long id, Instant now) {
                return d.shows.values().stream()
                        .anyMatch(
                                s ->
                                        s.screenId() == id
                                                && s.status() == ShowStatus.SCHEDULED
                                                && s.startsAt().isAfter(now));
            }

            public Movie saveMovie(Movie m) {
                Movie saved =
                        m.id() != 0
                                ? m
                                : new Movie(
                                        ++entitySeq,
                                        m.title(),
                                        m.languages(),
                                        m.genres(),
                                        m.certificate(),
                                        m.durationMinutes(),
                                        m.releaseDate(),
                                        m.posterUrl(),
                                        m.description());
                d.movies.put(saved.id(), saved);
                return saved;
            }

            public void deleteMovie(long id) {
                d.movies.remove(id);
            }

            public boolean movieHasShows(long id) {
                return d.shows.values().stream().anyMatch(s -> s.movieId() == id);
            }

            public Show insertShow(Show s) {
                Show saved =
                        new Show(
                                ++entitySeq,
                                s.movieId(),
                                s.screenId(),
                                s.startsAt(),
                                s.endsAt(),
                                s.language(),
                                s.format(),
                                s.prices(),
                                s.status());
                d.shows.put(saved.id(), saved);
                return saved;
            }
        };
    }

    @Override
    public AdminRepositories.Reports reports() {
        return new AdminRepositories.Reports() {
            public Page<AdminRepositories.ShowStat> showStats(
                    String cityId, Long ownerId, Instant from, Instant to, int page, int size) {
                return slice(
                        d.shows.values().stream()
                                .filter(s -> cityId == null || playsIn(s, cityId))
                                .filter(
                                        s ->
                                                ownerId == null
                                                        || d.owners.contains(
                                                                d.screens
                                                                                .get(s.screenId())
                                                                                .cinemaId()
                                                                        + "/"
                                                                        + ownerId))
                                .filter(
                                        s ->
                                                !s.startsAt().isBefore(from)
                                                        && s.startsAt().isBefore(to))
                                .sorted(Comparator.comparing(Show::startsAt))
                                .map(s -> stat(s))
                                .toList(),
                        page,
                        size);
            }

            public Optional<AdminRepositories.ShowStat> showStat(long showId) {
                return Optional.ofNullable(d.shows.get(showId)).map(s -> stat(s));
            }

            private AdminRepositories.ShowStat stat(Show s) {
                Screen screen = d.screens.get(s.screenId());
                long paid = 0;
                long refunded = 0;
                int booked = 0;
                for (Booking b : d.bookings.values()) {
                    if (b.showId() != s.id()) {
                        continue;
                    }
                    if (b.status() == BookingStatus.CONFIRMED) {
                        booked += b.seats().size();
                    }
                    for (Payment p : d.payments.values()) {
                        if (p.bookingId().equals(b.id())
                                && (p.status() == PaymentStatus.SUCCEEDED
                                        || p.status() == PaymentStatus.REFUNDED)) {
                            paid += p.amount();
                            refunded += p.refundedAmount();
                        }
                    }
                }
                return new AdminRepositories.ShowStat(
                        s.id(),
                        d.movies.get(s.movieId()).title(),
                        d.cinemas.get(screen.cinemaId()).name(),
                        s.startsAt(),
                        s.status(),
                        screen.capacity(),
                        booked,
                        paid,
                        refunded);
            }

            public List<AdminRepositories.CategoryStat> categoryStats(long showId) {
                Show s = d.shows.get(showId);
                Screen screen = d.screens.get(s.screenId());
                List<AdminRepositories.CategoryStat> out = new ArrayList<>();
                for (SeatLayout.Category c : screen.layout().categories()) {
                    int cap =
                            screen.layout().bookableSeats().stream()
                                    .mapToInt(
                                            id ->
                                                    screen.layout().categoryOf(id).equals(c.code())
                                                            ? 1
                                                            : 0)
                                    .sum();
                    int booked = 0;
                    long revenue = 0;
                    for (Booking b : d.bookings.values()) {
                        if (b.showId() == showId && b.status() == BookingStatus.CONFIRMED) {
                            for (var bs : b.seats()) {
                                if (bs.category().equals(c.code())) {
                                    booked++;
                                    revenue += bs.price();
                                }
                            }
                        }
                    }
                    out.add(new AdminRepositories.CategoryStat(c.code(), cap, booked, revenue));
                }
                return out;
            }

            public long countConfirmedSince(Instant since) {
                return d.bookings.values().stream()
                        .filter(
                                b ->
                                        b.status() == BookingStatus.CONFIRMED
                                                && !b.updatedAt().isBefore(since))
                        .count();
            }
        };
    }

    @Override
    public AdminRepositories.CinemaOwners cinemaOwners() {
        return new AdminRepositories.CinemaOwners() {
            public List<Cinema> cinemas(Long userId) {
                return d.cinemas.values().stream()
                        .filter(c -> userId == null || d.owners.contains(c.id() + "/" + userId))
                        .sorted(Comparator.comparing(Cinema::name).thenComparing(Cinema::id))
                        .toList();
            }

            public List<Long> cinemaIdsOf(long userId) {
                return d.owners.stream()
                        .filter(k -> k.endsWith("/" + userId))
                        .map(k -> Long.parseLong(k.substring(0, k.indexOf('/'))))
                        .sorted()
                        .toList();
            }

            public boolean owns(long userId, long cinemaId) {
                return d.owners.contains(cinemaId + "/" + userId);
            }

            public void link(long cinemaId, long userId) {
                d.owners.add(cinemaId + "/" + userId);
            }

            public void unlink(long cinemaId, long userId) {
                d.owners.remove(cinemaId + "/" + userId);
            }

            public void unlinkAll(long userId) {
                d.owners.removeIf(k -> k.endsWith("/" + userId));
            }
        };
    }

    @Override
    public AdminRepositories.UserRoles userRoles() {
        return new AdminRepositories.UserRoles() {
            public Page<User> page(String q, int page, int size) {
                String needle = q == null ? null : q.strip().toLowerCase(Locale.ROOT);
                return slice(
                        d.users.values().stream()
                                .filter(
                                        u ->
                                                needle == null
                                                        || needle.isEmpty()
                                                        || u.email().contains(needle)
                                                        || u.name()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(needle))
                                .sorted(Comparator.comparingLong(User::id))
                                .toList(),
                        page,
                        size);
            }

            public void saveRoles(long userId, Set<Role> roles) {
                User u = d.users.get(userId);
                d.users.put(
                        userId,
                        new User(
                                u.id(),
                                u.email(),
                                u.passwordHash(),
                                u.name(),
                                roles,
                                u.createdAt()));
            }
        };
    }
}
