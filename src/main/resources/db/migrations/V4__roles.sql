-- Three-role model (SPEC 3). The role USER is now called AUDIENCE, and a theatre owner is linked to
-- the cinemas they manage. The composite primary key keeps each pair unique.
CREATE TABLE cinema_owner (cinema_id INTEGER NOT NULL REFERENCES cinema(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  PRIMARY KEY (cinema_id, user_id)) STRICT;
-- The primary key already serves lookups by cinema. Owner scope checks look up by user.
CREATE INDEX cinema_owner_user ON cinema_owner (user_id);
-- Roles are stored as a comma list, so the token is matched with commas on both sides.
UPDATE app_user SET roles = trim(replace(',' || roles || ',', ',USER,', ',AUDIENCE,'), ',')
  WHERE ',' || roles || ',' LIKE '%,USER,%';
