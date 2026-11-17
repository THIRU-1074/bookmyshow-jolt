-- Demo catalog. All names are fictional. No users are seeded (the admin comes from the environment).

INSERT INTO city (id, name, time_zone) VALUES
  ('bengaluru', 'Bengaluru', 'Asia/Kolkata'),
  ('chennai', 'Chennai', 'Asia/Kolkata'),
  ('mumbai', 'Mumbai', 'Asia/Kolkata');

INSERT INTO cinema (id, city_id, name, address) VALUES
  (1, 'bengaluru', 'Koramangala Cineplex', '80 Feet Road, Koramangala, Bengaluru'),
  (2, 'bengaluru', 'Whitefield Galaxy', 'Main Road, Whitefield, Bengaluru'),
  (3, 'chennai', 'Marina Mall Cinemas', 'Kamarajar Salai, Chennai'),
  (4, 'chennai', 'T Nagar Picture House', 'Usman Road, T Nagar, Chennai'),
  (5, 'mumbai', 'Bandra Silver Screens', 'Linking Road, Bandra West, Mumbai'),
  (6, 'mumbai', 'Andheri Starplex', 'Link Road, Andheri West, Mumbai');

-- Three reusable layouts. Capacity is derived from the JSON so the two cannot disagree:
--   SMALL  96 seats - 2 blocked = 94, CLASSIC and PRIME
--   MEDIUM 208 seats - 3 blocked = 205, CLASSIC, PRIME and RECLINER
--   LARGE  200 seats - 2 blocked = 198, CLASSIC and PRIME
WITH layout (k, json) AS (VALUES
  ('SMALL', '{"categories":[{"code":"CLASSIC","name":"Classic"},{"code":"PRIME","name":"Prime"}],"rows":['
    || '{"row":"A","seats":12,"category":"CLASSIC","aisleAfter":[4,8],"blocked":[1,12]},'
    || '{"row":"B","seats":12,"category":"CLASSIC","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"C","seats":12,"category":"CLASSIC","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"D","seats":12,"category":"CLASSIC","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"E","seats":12,"category":"PRIME","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"F","seats":12,"category":"PRIME","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"G","seats":12,"category":"PRIME","aisleAfter":[4,8],"blocked":[]},'
    || '{"row":"H","seats":12,"category":"PRIME","aisleAfter":[4,8],"blocked":[]}]}'),
  ('MEDIUM', '{"categories":[{"code":"CLASSIC","name":"Classic"},{"code":"PRIME","name":"Prime"},{"code":"RECLINER","name":"Recliner"}],"rows":['
    || '{"row":"A","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[1,16]},'
    || '{"row":"B","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"C","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"D","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"E","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"F","seats":16,"category":"CLASSIC","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"G","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"H","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"I","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"J","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"K","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"L","seats":16,"category":"PRIME","aisleAfter":[4,12],"blocked":[]},'
    || '{"row":"M","seats":8,"category":"RECLINER","aisleAfter":[4],"blocked":[]},'
    || '{"row":"N","seats":8,"category":"RECLINER","aisleAfter":[4],"blocked":[1]}]}'),
  ('LARGE', '{"categories":[{"code":"CLASSIC","name":"Classic"},{"code":"PRIME","name":"Prime"}],"rows":['
    || '{"row":"A","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[1]},'
    || '{"row":"B","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"C","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"D","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"E","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"F","seats":20,"category":"CLASSIC","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"G","seats":20,"category":"PRIME","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"H","seats":20,"category":"PRIME","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"I","seats":20,"category":"PRIME","aisleAfter":[5,15],"blocked":[]},'
    || '{"row":"J","seats":20,"category":"PRIME","aisleAfter":[5,15],"blocked":[20]}]}')),
plan (id, cinema_id, name, k) AS (VALUES
  (1, 1, 'Audi 1', 'LARGE'), (2, 1, 'Audi 2', 'SMALL'), (3, 1, 'IMAX', 'MEDIUM'),
  (4, 2, 'Screen 1', 'SMALL'), (5, 2, 'Screen 2', 'LARGE'),
  (6, 3, 'Screen 1', 'LARGE'), (7, 3, 'Screen 2', 'SMALL'), (8, 3, 'IMAX', 'MEDIUM'),
  (9, 4, 'Hall A', 'SMALL'), (10, 4, 'Hall B', 'LARGE'),
  (11, 5, 'Screen 1', 'MEDIUM'), (12, 5, 'Screen 2', 'SMALL'),
  (13, 6, 'Audi 1', 'LARGE'), (14, 6, 'Audi 2', 'MEDIUM'))
INSERT INTO screen (id, cinema_id, name, layout_json, capacity)
SELECT p.id, p.cinema_id, p.name, l.json,
  (SELECT sum(json_extract(r.value, '$.seats') - json_array_length(r.value, '$.blocked'))
     FROM json_each(l.json, '$.rows') r)
FROM plan p JOIN layout l ON l.k = p.k;

-- Runtimes stay at or under 150 minutes so a movie plus the 15 minute buffer fits the shortest
-- gap between slots (165 minutes, 20:30 to 23:15).
INSERT INTO movie (id, title, certificate, duration_minutes, release_date, poster_url, description) VALUES
  (1, 'The Monsoon Cipher', 'UA', 138, '2026-10-02', '/img/posters/the-monsoon-cipher.jpg',
   'A cryptographer in Kochi decodes rain-soaked letters that point to a heist nobody has planned yet.'),
  (2, 'Kaveri Nights', 'U', 126, '2026-10-09', '/img/posters/kaveri-nights.jpg',
   'Two ferry operators on the Kaveri trade stories and slowly fall for each other over one monsoon.'),
  (3, 'Last Train to Hampi', 'UA', 142, '2026-10-16', '/img/posters/last-train-to-hampi.jpg',
   'A night train, a missing manuscript and five strangers who each need it to stay lost.'),
  (4, 'Paper Lanterns', 'U', 98, '2026-10-23', '/img/posters/paper-lanterns.jpg',
   'A girl and her lantern-making grandmother race a storm to light up the town festival.'),
  (5, 'Chai and Chaos', 'U', 112, '2026-10-30', '/img/posters/chai-and-chaos.jpg',
   'One tea stall, three rival families and a wedding that must go perfectly by Sunday.'),
  (6, 'Neon Delta', 'UA', 147, '2026-11-06', '/img/posters/neon-delta.jpg',
   'In a flooded future city a river pilot smuggles the last working archive upstream.'),
  (7, 'The Salt Road', 'UA', 133, '2026-11-06', '/img/posters/the-salt-road.jpg',
   'A widow walks a forgotten trade route to settle her late husband''s oldest debt.'),
  (8, 'Midnight at Mylapore', 'A', 118, '2026-11-13', '/img/posters/midnight-at-mylapore.jpg',
   'A night-shift nurse hears the same temple bell every night, an hour after it was removed.'),
  (9, 'Sundarbans Run', 'UA', 129, '2026-11-13', '/img/posters/sundarbans-run.jpg',
   'A forest ranger and a wildlife photographer cross the delta ahead of a cyclone.'),
  (10, 'Little Tigers of Lalbagh', 'U', 92, '2026-11-20', '/img/posters/little-tigers-of-lalbagh.jpg',
   'Three cubs escape the garden zoo for one unforgettable Bengaluru afternoon.'),
  (11, 'Orbit of Seven Lakes', 'UA', 149, '2026-11-20', '/img/posters/orbit-of-seven-lakes.jpg',
   'A station crew above a drowned Earth must choose which of seven lakes to save.'),
  (12, 'The Wedding Ledger', 'UA', 124, '2026-11-27', '/img/posters/the-wedding-ledger.jpg',
   'An accountant hired to audit a big fat wedding finds the bride has been paying for it herself.');

INSERT INTO movie_language (movie_id, language) VALUES
  (1, 'en'), (1, 'hi'),
  (2, 'ta'), (2, 'kn'),
  (3, 'kn'), (3, 'en'), (3, 'hi'),
  (4, 'en'), (4, 'hi'), (4, 'ta'), (4, 'te'),
  (5, 'hi'), (5, 'en'),
  (6, 'en'), (6, 'te'), (6, 'ta'), (6, 'hi'),
  (7, 'hi'),
  (8, 'ta'), (8, 'te'),
  (9, 'en'), (9, 'hi'),
  (10, 'kn'), (10, 'en'),
  (11, 'en'), (11, 'hi'), (11, 'te'),
  (12, 'te'), (12, 'hi'), (12, 'kn');

INSERT INTO movie_genre (movie_id, genre) VALUES
  (1, 'Thriller'), (1, 'Action'),
  (2, 'Drama'), (2, 'Romance'),
  (3, 'Drama'), (3, 'Thriller'),
  (4, 'Animation'), (4, 'Family'),
  (5, 'Comedy'),
  (6, 'Sci-Fi'), (6, 'Action'),
  (7, 'Drama'),
  (8, 'Horror'), (8, 'Thriller'),
  (9, 'Action'), (9, 'Drama'),
  (10, 'Animation'), (10, 'Family'), (10, 'Comedy'),
  (11, 'Sci-Fi'), (11, 'Drama'),
  (12, 'Comedy'), (12, 'Romance');

-- Shows for 14 days from the migration date, in local time (all seeded cities are UTC+05:30
-- with no daylight saving). Slots are 10:00, 13:30, 17:00, 20:30 and 23:15; even-numbered
-- screens also run the 23:15 show. A show occupies [starts_at, ends_at) where ends_at includes
-- the 15 minute buffer, and slots are at least 165 minutes apart, so shows never overlap.
-- Prices (paise) per layout category, plus a 3D or IMAX surcharge.
WITH RECURSIVE
  day (d) AS (SELECT 0 UNION ALL SELECT d + 1 FROM day WHERE d < 13),
  slot (n, minutes) AS (VALUES (0, 600), (1, 810), (2, 1020), (3, 1230), (4, 1395)),
  origin (ms) AS (
    SELECT ((CAST(strftime('%s', 'now') AS INTEGER) + 19800) / 86400 * 86400 - 19800) * 1000),
  plan AS (
    SELECT s.id AS screen_id, s.name AS screen_name, s.layout_json,
           o.ms + d.d * 86400000 + sl.minutes * 60000 AS starts_at,
           (s.id * 3 + d.d * 2 + sl.n) % 12 + 1 AS movie_id,
           d.d + sl.n + s.id AS pick
    FROM screen s CROSS JOIN day d CROSS JOIN slot sl CROSS JOIN origin o
    WHERE sl.n < 4 OR s.id % 2 = 0),
  typed AS (
    SELECT p.*, m.duration_minutes,
           CASE WHEN p.screen_name = 'IMAX' THEN 'IMAX'
                WHEN p.movie_id IN (4, 6) THEN '3D'
                ELSE '2D' END AS format
    FROM plan p JOIN movie m ON m.id = p.movie_id)
INSERT INTO show (movie_id, screen_id, starts_at, ends_at, language, format, prices_json, status)
SELECT t.movie_id, t.screen_id, t.starts_at,
       t.starts_at + (t.duration_minutes + 15) * 60000,
       -- The (pick mod n)-th language alphabetically, so shows rotate through a movie's languages.
       (SELECT l.language FROM movie_language l WHERE l.movie_id = t.movie_id
          AND (SELECT count(*) FROM movie_language x
                 WHERE x.movie_id = l.movie_id AND x.language < l.language)
              = t.pick % (SELECT count(*) FROM movie_language c WHERE c.movie_id = t.movie_id)),
       t.format,
       (SELECT json_group_object(
                 json_extract(c.value, '$.code'),
                 CASE json_extract(c.value, '$.code')
                   WHEN 'CLASSIC' THEN 18000 WHEN 'PRIME' THEN 28000 ELSE 45000 END
                 + CASE t.format WHEN 'IMAX' THEN 15000 WHEN '3D' THEN 5000 ELSE 0 END)
          FROM json_each(t.layout_json, '$.categories') c),
       'SCHEDULED'
FROM typed t
ORDER BY t.screen_id, t.starts_at;
