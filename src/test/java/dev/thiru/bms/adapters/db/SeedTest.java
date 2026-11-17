package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.db.Db;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the invariants SPEC sections 3 and 6 promise about the seed data. */
class SeedTest {

    @TempDir Path dir;

    Db db;

    @BeforeEach
    void open() {
        String url = Sqlite.jdbcUrl(dir.resolve("seed.db"));
        SchemaRunner.migrate(url);
        db = Sqlite.open(url);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private int count(String sql) {
        return db.queryOne(sql, r -> r.getInt(1)).orElseThrow();
    }

    private List<String> strings(String sql) {
        return db.query(sql, r -> r.getString(1));
    }

    @Test
    void citiesAndCinemas() {
        assertEquals(
                List.of("bengaluru", "chennai", "mumbai"),
                strings("SELECT id FROM city ORDER BY id"));
        assertEquals(0, count("SELECT count(*) FROM city WHERE time_zone <> 'Asia/Kolkata'"));
        assertEquals(6, count("SELECT count(*) FROM cinema"));
        assertEquals(
                List.of(),
                strings("SELECT city_id FROM cinema GROUP BY city_id HAVING count(*) <> 2"));
    }

    @Test
    void everyCinemaHasTwoOrThreeScreens() {
        assertEquals(
                List.of(),
                strings(
                        "SELECT c.name FROM cinema c LEFT JOIN screen s ON s.cinema_id = c.id"
                                + " GROUP BY c.id HAVING count(s.id) NOT BETWEEN 2 AND 3"));
    }

    @Test
    void screenLayoutsAreValid() {
        assertEquals(0, count("SELECT count(*) FROM screen WHERE NOT json_valid(layout_json)"));
        // 80..250 seats, and the stored capacity equals seats minus blocked.
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.name FROM screen s WHERE s.capacity NOT BETWEEN 80 AND 250"
                                + " OR s.capacity <> (SELECT sum(json_extract(r.value, '$.seats')"
                                + " - json_array_length(r.value, '$.blocked'))"
                                + " FROM json_each(s.layout_json, '$.rows') r)"));
        // At least one blocked seat and one aisle per screen.
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.name FROM screen s WHERE"
                                + " (SELECT sum(json_array_length(r.value, '$.blocked'))"
                                + " FROM json_each(s.layout_json, '$.rows') r) = 0"
                                + " OR (SELECT sum(json_array_length(r.value, '$.aisleAfter'))"
                                + " FROM json_each(s.layout_json, '$.rows') r) = 0"));
        // Rows are contiguous from A, 1..26 of them, 1..50 seats each.
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.name FROM screen s, json_each(s.layout_json, '$.rows') r"
                                + " WHERE json_extract(r.value, '$.row') <> char(65 + r.key)"
                                + " OR r.key > 25"
                                + " OR json_extract(r.value, '$.seats') NOT BETWEEN 1 AND 50"));
        // Aisle and blocked values lie within the row.
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.name FROM screen s, json_each(s.layout_json, '$.rows') r,"
                                + " json_each(r.value, '$.aisleAfter') v"
                                + " WHERE v.value NOT BETWEEN 1 AND json_extract(r.value, '$.seats')"
                                + " UNION ALL SELECT s.name FROM screen s,"
                                + " json_each(s.layout_json, '$.rows') r,"
                                + " json_each(r.value, '$.blocked') v"
                                + " WHERE v.value NOT BETWEEN 1 AND json_extract(r.value, '$.seats')"));
        // 2 or 3 categories, every one used by a row, every row category declared.
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.name FROM screen s WHERE"
                                + " json_array_length(s.layout_json, '$.categories') NOT BETWEEN 2 AND 3"
                                + " OR EXISTS (SELECT 1 FROM json_each(s.layout_json, '$.categories') c"
                                + " WHERE NOT EXISTS (SELECT 1 FROM json_each(s.layout_json, '$.rows') r"
                                + " WHERE json_extract(r.value, '$.category')"
                                + " = json_extract(c.value, '$.code')))"
                                + " OR EXISTS (SELECT 1 FROM json_each(s.layout_json, '$.rows') r"
                                + " WHERE json_extract(r.value, '$.category') NOT IN"
                                + " (SELECT json_extract(c.value, '$.code')"
                                + " FROM json_each(s.layout_json, '$.categories') c))"));
        assertTrue(count("SELECT count(*) FROM screen") >= 12);
    }

    @Test
    void moviesFollowTheDomainRules() {
        assertEquals(12, count("SELECT count(*) FROM movie"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM movie WHERE length(title) NOT BETWEEN 1 AND 120"
                                + " OR length(description) > 2000"
                                + " OR NOT (poster_url LIKE 'https://%' OR poster_url LIKE '/%')"
                                // Slots are 210 min apart (165 before the last one) so every
                                // movie must fit its runtime plus the 15 min buffer in 165.
                                + " OR duration_minutes > 150"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM movie m WHERE NOT EXISTS"
                                + " (SELECT 1 FROM movie_language l WHERE l.movie_id = m.id)"
                                + " OR NOT EXISTS (SELECT 1 FROM movie_genre g WHERE g.movie_id = m.id)"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM movie_language WHERE language NOT IN ('en','hi','ta','te','kn')"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM movie_genre WHERE genre NOT IN ('Action','Comedy',"
                                + "'Drama','Thriller','Romance','Animation','Sci-Fi','Horror','Family')"));
        assertEquals(12, count("SELECT count(DISTINCT title) FROM movie"));
    }

    @Test
    void showsNeverOverlapOnAScreen() {
        assertTrue(count("SELECT count(*) FROM show") > 0);
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM show a JOIN show b ON a.screen_id = b.screen_id"
                                + " AND a.id < b.id AND a.starts_at < b.ends_at"
                                + " AND b.starts_at < a.ends_at"));
        // ends_at is start + runtime + 15 min cleaning buffer.
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM show s JOIN movie m ON m.id = s.movie_id"
                                + " WHERE s.ends_at <> s.starts_at + (m.duration_minutes + 15) * 60000"));
    }

    @Test
    void showsCoverFourteenDaysAtTheFixedLocalTimes() {
        assertEquals(
                14, count("SELECT count(DISTINCT (starts_at / 1000 + 19800) / 86400) FROM show"));
        assertEquals(
                List.of(),
                strings(
                        "SELECT screen_id FROM show GROUP BY screen_id,"
                                + " (starts_at / 1000 + 19800) / 86400 HAVING count(*) NOT BETWEEN 4 AND 5"));
        assertEquals(
                List.of("1020", "1230", "1395", "600", "810"),
                strings(
                        "SELECT DISTINCT CAST(((starts_at / 60000 + 330) % 1440) AS TEXT)"
                                + " FROM show ORDER BY 1"));
    }

    @Test
    void everyShowIsConsistentWithItsMovieAndLayout() {
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM show s WHERE NOT EXISTS (SELECT 1 FROM movie_language l"
                                + " WHERE l.movie_id = s.movie_id AND l.language = s.language)"));
        assertEquals(0, count("SELECT count(*) FROM show WHERE status <> 'SCHEDULED'"));
    }

    @Test
    void everyShowPricesExactlyTheLayoutCategories() {
        assertEquals(0, count("SELECT count(*) FROM show WHERE NOT json_valid(prices_json)"));
        assertEquals(
                List.of(),
                strings(
                        "SELECT s.id FROM show s JOIN screen sc ON sc.id = s.screen_id WHERE"
                                + " (SELECT group_concat(k, ',') FROM (SELECT key AS k"
                                + " FROM json_each(s.prices_json) ORDER BY key))"
                                + " IS NOT (SELECT group_concat(code, ',') FROM (SELECT"
                                + " json_extract(c.value, '$.code') AS code"
                                + " FROM json_each(sc.layout_json, '$.categories') c ORDER BY code))"
                                + " LIMIT 5"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM show s, json_each(s.prices_json) p"
                                + " WHERE p.type <> 'integer' OR p.value NOT BETWEEN 100 AND 1000000"));
    }

    @Test
    void noUsersAreSeeded() {
        assertEquals(0, count("SELECT count(*) FROM app_user"));
    }
}
