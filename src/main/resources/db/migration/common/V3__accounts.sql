-- users are author rows: imported authors get 'author' and no password until they set one
ALTER TABLE author ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'author';
ALTER TABLE author ADD COLUMN password_hash VARCHAR(255);

-- id: SHA-256 hex of the raw cookie value
CREATE TABLE session (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES author (id),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- id: SHA-256 hex of the raw token
CREATE TABLE user_token (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES author (id),
    purpose VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
