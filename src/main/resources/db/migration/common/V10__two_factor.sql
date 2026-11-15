CREATE TABLE totp_factor (
    user_id BIGINT PRIMARY KEY REFERENCES author (id),
    ciphertext TEXT NOT NULL,
    confirmed BOOLEAN NOT NULL,
    last_step BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE passkey (
    credential_id VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES author (id),
    name VARCHAR(100) NOT NULL,
    public_key TEXT NOT NULL,
    algorithm BIGINT NOT NULL,
    aaguid VARCHAR(36),
    sign_count BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);
CREATE INDEX ix_passkey_user ON passkey (user_id);
