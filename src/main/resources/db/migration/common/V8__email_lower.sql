-- the lower-cased email, maintained by the application: H2 has no expression indexes, so uniqueness lives on this column
ALTER TABLE author ADD COLUMN email_lower VARCHAR(100);
-- only rows whose lowered email no other row shares; existing duplicates keep NULL so this never fails on old data
UPDATE author SET email_lower = LOWER(email) WHERE email IS NOT NULL AND email <> '' AND NOT EXISTS (SELECT 1 FROM author o WHERE o.id <> author.id AND LOWER(o.email) = LOWER(author.email));
CREATE UNIQUE INDEX uq_author_email_lower ON author (email_lower);
