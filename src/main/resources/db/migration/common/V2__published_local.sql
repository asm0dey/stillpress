-- wp:post_date: the site-local publish time WordPress builds date archives from
ALTER TABLE item ADD COLUMN published_local TIMESTAMP;
