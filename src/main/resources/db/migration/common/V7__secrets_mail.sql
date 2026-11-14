-- values encrypted under the Tink AEAD keyset (Secrets); ciphertext is base64, the name is the associated data
CREATE TABLE secret (
    name VARCHAR(100) PRIMARY KEY,
    ciphertext TEXT NOT NULL
);
-- when a token was issued: the reset-mail throttle counts recent ones
ALTER TABLE user_token ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
