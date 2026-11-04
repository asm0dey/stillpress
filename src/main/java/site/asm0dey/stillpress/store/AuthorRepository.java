package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.escape;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.when;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.importer.Nicename;

/**
 * Authors double as users: an {@code author} row with a role and an optional password hash.
 */
@ApplicationScoped
public class AuthorRepository {
    @Inject
    DSLContext db;

    public Optional<AuthorRecord> findById(long id) {
        return db.selectFrom(AUTHOR).where(AUTHOR.ID.eq(id)).fetchOptional();
    }

    public Optional<AuthorRecord> findByLogin(String login) {
        return db.selectFrom(AUTHOR).where(AUTHOR.LOGIN.eq(login)).fetchOptional();
    }

    public Set<String> slugs() {
        return new HashSet<>(db.select(AUTHOR.SLUG).from(AUTHOR).fetch(AUTHOR.SLUG));
    }

    public List<AuthorRecord> all() {
        return db.selectFrom(AUTHOR).fetch();
    }

    /**
     * The exact login, else the email (case-insensitive) when exactly one user has it.
     */
    public Optional<AuthorRecord> findByLoginOrEmail(String s) {
        // the login match (at most one: login is unique) sorts first, so the limit never cuts it off
        var rows = db
            .selectFrom(AUTHOR)
            .where(AUTHOR.LOGIN.eq(s).or(lower(AUTHOR.EMAIL).eq(s.toLowerCase(Locale.ROOT))))
            .orderBy(when(AUTHOR.LOGIN.eq(s), 0).otherwise(1))
            .limit(3)
            .fetch();
        if (!rows.isEmpty() && rows.getFirst().getLogin().equals(s)) {
            return Optional.of(rows.getFirst());
        }
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    public AuthorRecord create(String login, String email, String displayName, Role role) {
        var r = db.newRecord(AUTHOR);
        r.setLogin(login);
        r.setEmail(email);
        r.setDisplayName(displayName);
        r.setRole(role.id());
        r.setSlug(uniqueSlug(login));
        r.store();
        return r;
    }

    /**
     * Whether a user other than {@code exceptId} has {@code email}, case-insensitively.
     */
    public boolean emailTaken(String email, long exceptId) {
        return db.fetchExists(AUTHOR, lower(AUTHOR.EMAIL).eq(email.toLowerCase(Locale.ROOT)), AUTHOR.ID.ne(exceptId));
    }

    public void setProfile(long id, String displayName, String email) {
        db.update(AUTHOR).set(AUTHOR.DISPLAY_NAME, displayName).set(AUTHOR.EMAIL, email).where(AUTHOR.ID.eq(id)).execute();
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
}
