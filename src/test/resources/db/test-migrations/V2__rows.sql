-- semicolons in comments; and in strings must not split statements
INSERT INTO probe (id, v) VALUES (1, 'a;b');
INSERT INTO probe (id, v) VALUES (2, 'it''s');
