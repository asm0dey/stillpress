package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.PASSKEY;
import static site.asm0dey.stillpress.db.Tables.TOTP_FACTOR;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord.RequiredPersistedData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.db.tables.records.PasskeyRecord;
import site.asm0dey.stillpress.db.tables.records.TotpFactorRecord;

/**
 * Second-factor rows: one TOTP seed (stored encrypted by {@code Secrets}) and any number of passkeys per user.
 */
@ApplicationScoped
public class TwoFactorRepository {
    @Inject
    DSLContext db;

    public Optional<TotpFactorRecord> totp(long userId) {
        return db.selectFrom(TOTP_FACTOR).where(TOTP_FACTOR.USER_ID.eq(userId)).fetchOptional();
    }

    /**
     * An unconfirmed row with {@code last_step = 0}, replacing any earlier one.
     */
    @Transactional
    public void putTotp(long userId, String ciphertext) {
        var now = SessionRepository.now();
        var updated = db
            .update(TOTP_FACTOR)
            .set(TOTP_FACTOR.CIPHERTEXT, ciphertext)
            .set(TOTP_FACTOR.CONFIRMED, false)
            .set(TOTP_FACTOR.LAST_STEP, 0L)
            .set(TOTP_FACTOR.CREATED_AT, now)
            .where(TOTP_FACTOR.USER_ID.eq(userId))
            .execute();
        if (updated == 0) {
            db
                .insertInto(TOTP_FACTOR)
                .set(TOTP_FACTOR.USER_ID, userId)
                .set(TOTP_FACTOR.CIPHERTEXT, ciphertext)
                .set(TOTP_FACTOR.CONFIRMED, false)
                .set(TOTP_FACTOR.LAST_STEP, 0L)
                .set(TOTP_FACTOR.CREATED_AT, now)
                .execute();
        }
    }

    public void confirmTotp(long userId) {
        db.update(TOTP_FACTOR).set(TOTP_FACTOR.CONFIRMED, true).where(TOTP_FACTOR.USER_ID.eq(userId)).execute();
    }

    /**
     * Records {@code step} as used; false when a confirmed factor has already accepted this step or a later one, so a
     * code works once.
     */
    public boolean advanceTotp(long userId, long step) {
        return db
            .update(TOTP_FACTOR)
            .set(TOTP_FACTOR.LAST_STEP, step)
            .where(TOTP_FACTOR.USER_ID.eq(userId), TOTP_FACTOR.CONFIRMED.isTrue(), TOTP_FACTOR.LAST_STEP.lt(step))
            .execute() == 1;
    }

    public int deleteTotp(long userId) {
        return db.deleteFrom(TOTP_FACTOR).where(TOTP_FACTOR.USER_ID.eq(userId)).execute();
    }

    public int deleteAllTotp() {
        return db.deleteFrom(TOTP_FACTOR).execute();
    }

    public List<PasskeyRecord> passkeys(long userId) {
        return db
            .selectFrom(PASSKEY)
            .where(PASSKEY.USER_ID.eq(userId))
            .orderBy(PASSKEY.CREATED_AT, PASSKEY.CREDENTIAL_ID)
            .fetch();
    }

    public Optional<PasskeyRecord> passkey(String credentialId) {
        return db.selectFrom(PASSKEY).where(PASSKEY.CREDENTIAL_ID.eq(credentialId)).fetchOptional();
    }

    public void addPasskey(long userId, String name, RequiredPersistedData data) {
        db
            .insertInto(PASSKEY)
            .set(PASSKEY.CREDENTIAL_ID, data.credentialId())
            .set(PASSKEY.USER_ID, userId)
            .set(PASSKEY.NAME, name)
            .set(PASSKEY.PUBLIC_KEY, Base64.getEncoder().encodeToString(data.publicKey()))
            .set(PASSKEY.ALGORITHM, data.publicKeyAlgorithm())
            .set(PASSKEY.AAGUID, data.aaguid().toString())
            .set(PASSKEY.SIGN_COUNT, data.counter())
            .set(PASSKEY.CREATED_AT, SessionRepository.now())
            .execute();
    }

    public void usePasskey(String credentialId, long signCount) {
        db
            .update(PASSKEY)
            .set(PASSKEY.SIGN_COUNT, signCount)
            .set(PASSKEY.LAST_USED_AT, SessionRepository.now())
            .where(PASSKEY.CREDENTIAL_ID.eq(credentialId))
            .execute();
    }

    public int deletePasskey(long userId, String credentialId) {
        return db
            .deleteFrom(PASSKEY)
            .where(PASSKEY.USER_ID.eq(userId), PASSKEY.CREDENTIAL_ID.eq(credentialId))
            .execute();
    }

    /**
     * Confirmed TOTP plus passkeys, counted in one statement.
     */
    public int factorCount(long userId) {
        var totp =
                db
            .selectCount()
            .from(TOTP_FACTOR)
            .where(TOTP_FACTOR.USER_ID.eq(userId), TOTP_FACTOR.CONFIRMED.isTrue());
        var passkeys = db.selectCount().from(PASSKEY).where(PASSKEY.USER_ID.eq(userId));
        var count = DSL.field(totp).plus(DSL.field(passkeys));
        return db.select(count).fetchOne(count);
    }

    public boolean hasFactor(long userId) {
        return factorCount(userId) > 0;
    }

    /**
     * Deletes every factor of the user and returns how many rows went.
     */
    @Transactional
    public int clear(long userId) {
        return deleteTotp(userId) + db.deleteFrom(PASSKEY).where(PASSKEY.USER_ID.eq(userId)).execute();
    }

    /**
     * Any stored TOTP seed: enough to tell which keyset the seeds belong to.
     */
    public Optional<TotpFactorRecord> firstTotp() {
        return db.selectFrom(TOTP_FACTOR).limit(1).fetchOptional();
    }
}
