package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.escape;
import static org.jooq.impl.DSL.when;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.PASSKEY;
import static site.asm0dey.stillpress.db.Tables.TOTP_FACTOR;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.importer.Nicename;

/**
 * Authors double as users: an {@code author} row with a role and an optional password hash.
 */
@ApplicationScoped
public class AuthorRepository {
    private final DSLContext db;

    public Optional<Author> findById(long id) {
        return db.selectFrom(AUTHOR).where(AUTHOR.ID.eq(id)).fetchOptionalInto(Author.class);
    }

    public Optional<Author> findByLogin(String login) {
        return db.selectFrom(AUTHOR).where(AUTHOR.LOGIN.eq(login)).fetchOptionalInto(Author.class);
    }

    public Set<String> slugs() {
        return new HashSet<>(db.select(AUTHOR.SLUG).from(AUTHOR).fetch(AUTHOR.SLUG));
    }

    public List<Author> all() {
        return db.selectFrom(AUTHOR).fetchInto(Author.class);
    }

    /**
     * A user and whether they have a passkey or a confirmed authenticator.
     */
    public record WithTwoFactor(Author author, boolean twoFactor) {}

    public List<WithTwoFactor> allWithTwoFactor() {
        return db
            .select(AUTHOR.fields())
            .select(TWO_FACTOR)
            .from(AUTHOR)
            .fetch(r -> new WithTwoFactor(r.into(AUTHOR).into(Author.class), r.get(TWO_FACTOR)));
    }

    private static final Field<Boolean> TWO_FACTOR = DSL
        .field(DSL
            .exists(DSL.selectOne().from(PASSKEY).where(PASSKEY.USER_ID.eq(AUTHOR.ID)))
            .or(DSL.exists(DSL
                .selectOne()
                .from(TOTP_FACTOR)
                .where(TOTP_FACTOR.USER_ID.eq(AUTHOR.ID), TOTP_FACTOR.CONFIRMED.isTrue())
            ))
        )
        .as("two_factor");

    AuthorRepository(DSLContext db) {
        this.db = db;
    }

    /**
     * Administrators and editors with an email: who reviews what contributors submit.
     */
    public List<Author> reviewers() {
        return db
            .selectFrom(AUTHOR)
            .where(AUTHOR.ROLE.in(Role.ADMINISTRATOR.id(), Role.EDITOR.id()))
            .and(AUTHOR.EMAIL.isNotNull())
            .and(AUTHOR.EMAIL.ne(""))
            .fetchInto(Author.class);
    }

    /**
     * The exact login, else the email (case-insensitive) when exactly one user has it.
     */
    public Optional<Author> findByLoginOrEmail(String s) {
        // the login match (at most one: login is unique) sorts first, so the limit never cuts it off
        var rows = db
            .selectFrom(AUTHOR)
            .where(AUTHOR.LOGIN.eq(s).or(AUTHOR.EMAIL_LOWER.eq(lowerEmail(s))))
            .orderBy(when(AUTHOR.LOGIN.eq(s), 0).otherwise(1))
            .limit(3)
            .fetchInto(Author.class);
        if (!rows.isEmpty() && rows.getFirst().login().equals(s)) {
            return Optional.of(rows.getFirst());
        }
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    /**
     * @throws Taken when a unique index refuses the login or the email, as a concurrent create can
     */
    public Author create(String login, String email, String displayName, Role role) {
        var r = db.newRecord(AUTHOR);
        r.setLogin(login);
        r.setEmail(email);
        r.setEmailLower(lowerEmail(email));
        r.setDisplayName(displayName);
        r.setRole(role.id());
        r.setSlug(uniqueSlug(login));
        try {
            r.store();
        } catch (DataAccessException e) {
            if (!"23505".equals(e.sqlState())) {
                throw e;
            }
            throw new Taken(isEmailConflict(e), e);
        }
        return r.into(Author.class);
    }

    /**
     * A unique index refused a second user with the same login, or the same email when {@code email}.
     */
    public static class Taken extends RuntimeException {
        public final boolean email;

        Taken(boolean email, Throwable cause) {
            super(cause);
            this.email = email;
        }
    }

    /**
     * Whether a user other than {@code exceptId} has {@code email}, case-insensitively.
     */
    public boolean emailTaken(String email, long exceptId) {
        return db.fetchExists(AUTHOR, AUTHOR.EMAIL_LOWER.eq(lowerEmail(email)), AUTHOR.ID.ne(exceptId));
    }

    /**
     * The value of {@code author.email_lower}: null for a missing or empty email, which is never a duplicate.
     */
    public static String lowerEmail(String email) {
        return email == null || email.isEmpty() ? null : email.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code e} is the unique index on {@code email_lower} refusing a second user with the same email.
     */
    public static boolean isEmailConflict(DataAccessException e) {
        return "23505".equals(e.sqlState())
                && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("uq_author_email_lower");
    }

    /**
     * @throws Taken when the unique index refuses the email, as a concurrent change can
     */
    public void setProfile(long id, String displayName, String email) {
        try {
            db
                .update(AUTHOR)
                .set(AUTHOR.DISPLAY_NAME, displayName)
                .set(AUTHOR.EMAIL, email)
                .set(AUTHOR.EMAIL_LOWER, lowerEmail(email))
                .where(AUTHOR.ID.eq(id))
                .execute();
        } catch (DataAccessException e) {
            if (!isEmailConflict(e)) {
                throw e;
            }
            throw new Taken(true, e);
        }
    }

    public void setLocale(long id, String locale) {
        db.update(AUTHOR).set(AUTHOR.LOCALE, locale).where(AUTHOR.ID.eq(id)).execute();
    }

    public void setPassword(long id, String hash) {
        db.update(AUTHOR).set(AUTHOR.PASSWORD_HASH, hash).where(AUTHOR.ID.eq(id)).execute();
    }

    public void setRole(long id, Role role) {
        db.update(AUTHOR).set(AUTHOR.ROLE, role.id()).where(AUTHOR.ID.eq(id)).execute();
    }

    /**
     * Sets the role unless that would leave no administrator with a password; false then.
     * Locks those administrators first, so two concurrent demotions cannot both pass. Call inside a transaction.
     */
    public boolean setRoleKeepingAdministrator(long id, Role role) {
        var keepers = db
            .select(AUTHOR.ID)
            .from(AUTHOR)
            .where(AUTHOR.ROLE.eq(Role.ADMINISTRATOR.id()).and(AUTHOR.PASSWORD_HASH.isNotNull()))
            .forUpdate()
            .fetch(AUTHOR.ID);
        if (role != Role.ADMINISTRATOR && keepers.equals(List.of(id))) {
            return false;
        }
        setRole(id, role);
        return true;
    }

    public boolean anyAdministratorWithPassword() {
        return db.fetchExists(AUTHOR, AUTHOR.ROLE.eq(Role.ADMINISTRATOR.id()).and(AUTHOR.PASSWORD_HASH.isNotNull()));
    }

    public int countAdministrators() {
        return db.fetchCount(AUTHOR, AUTHOR.ROLE.eq(Role.ADMINISTRATOR.id()));
    }

    /**
     * Like {@code wp_insert_user}: nicename, then {@code -2}, {@code -3}... on collision.
     */
    private String uniqueSlug(String login) {
        var base = Nicename.of(login);
        if (base.isEmpty()) {
            base = "user";
        }
        var taken = new HashSet<>(db
            .select(AUTHOR.SLUG)
            .from(AUTHOR)
            .where(AUTHOR.SLUG.eq(base).or(AUTHOR.SLUG.like(escape(base, '!') + "-%", '!')))
            .fetch(AUTHOR.SLUG)
        );
        var slug = base;
        for (int n = 2; taken.contains(slug); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    /**
     * Inserts an imported author ({@code id} null) or updates one already there; returns its id. Writes the login,
     * slug, display name, email, its unique key and source id; role, password and locale stay.
     */
    public long saveImported(Author a) {
        if (a.id() != null) {
            db
                .update(AUTHOR)
                .set(AUTHOR.DISPLAY_NAME, a.displayName())
                .set(AUTHOR.EMAIL, a.email())
                .set(AUTHOR.EMAIL_LOWER, a.emailLower())
                .set(AUTHOR.SOURCE_ID, a.sourceId())
                .where(AUTHOR.ID.eq(a.id()))
                .execute();
            return a.id();
        }
        var r = db.newRecord(AUTHOR);
        r.setLogin(a.login());
        r.setSlug(a.slug());
        r.setDisplayName(a.displayName());
        r.setEmail(a.email());
        r.setEmailLower(a.emailLower());
        r.setSourceId(a.sourceId());
        r.store();
        return r.getId();
    }
}
