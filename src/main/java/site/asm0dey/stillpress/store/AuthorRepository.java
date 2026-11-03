package site.asm0dey.stillpress.store;

import static org.jooq.impl.DSL.lower;
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

    public List<AuthorRecord> all() {
        return db.selectFrom(AUTHOR).fetch();
    }

    /**
     * The exact login, else the email (case-insensitive) when exactly one user has it.
     */
    public Optional<AuthorRecord> findByLoginOrEmail(String s) {
        var byLogin = db.selectFrom(AUTHOR).where(AUTHOR.LOGIN.eq(s)).fetchOptional();
        if (byLogin.isPresent()) {
            return byLogin;
        }
        var byEmail = db
            .selectFrom(AUTHOR)
            .where(lower(AUTHOR.EMAIL).eq(s.toLowerCase(Locale.ROOT)))
            .limit(2)
            .fetch();
        return byEmail.size() == 1 ? Optional.of(byEmail.getFirst()) : Optional.empty();
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

    public void setPassword(long id, String hash) {
        db.update(AUTHOR).set(AUTHOR.PASSWORD_HASH, hash).where(AUTHOR.ID.eq(id)).execute();
    }

    public void setRole(long id, Role role) {
        db.update(AUTHOR).set(AUTHOR.ROLE, role.id()).where(AUTHOR.ID.eq(id)).execute();
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
        var slug = base;
        for (int n = 2; db.fetchExists(AUTHOR, AUTHOR.SLUG.eq(slug)); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }
}
