package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.COMMENT;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_FRAGMENT;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.MEDIA;
import static site.asm0dey.stillpress.db.Tables.PASSKEY;
import static site.asm0dey.stillpress.db.Tables.PUBLISHED_ITEM;
import static site.asm0dey.stillpress.db.Tables.PUBLISH_QUEUE;
import static site.asm0dey.stillpress.db.Tables.REDIRECT;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import static site.asm0dey.stillpress.db.Tables.REVISION;
import static site.asm0dey.stillpress.db.Tables.SCHEDULED_TASKS;
import static site.asm0dey.stillpress.db.Tables.SECRET;
import static site.asm0dey.stillpress.db.Tables.SESSION;
import static site.asm0dey.stillpress.db.Tables.SETTING;
import static site.asm0dey.stillpress.db.Tables.TERM;
import static site.asm0dey.stillpress.db.Tables.TOTP_FACTOR;
import static site.asm0dey.stillpress.db.Tables.USER_TOKEN;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The rows a site reset or wipe deletes, across every table. Call inside a transaction.
 */
@ApplicationScoped
public class SiteWipe {
    private final DSLContext db;

    SiteWipe(DSLContext db) {
        this.db = db;
    }

    /**
     * Keeps one administrator (password kept, {@code source_id} cleared), the session {@code keepSession} (a raw
     * token, or {@code null} for none) and the settings {@code kept}; then writes {@code defaults}.
     */
    public void reset(
            long keepAdministratorId,
            String keepSession,
            Set<String> kept,
            Map<String, String> defaults,
            String keepTask
    ) {
        clearContent(keepTask);
        db
            .deleteFrom(SESSION)
            .where(keepSession == null ? DSL.noCondition() : SESSION.ID.ne(SessionRepository.hash(keepSession)))
            .execute();
        // factors reference the users; the kept administrator keeps theirs
        db.deleteFrom(TOTP_FACTOR).where(TOTP_FACTOR.USER_ID.ne(keepAdministratorId)).execute();
        db.deleteFrom(PASSKEY).where(PASSKEY.USER_ID.ne(keepAdministratorId)).execute();
        db.deleteFrom(AUTHOR).where(AUTHOR.ID.ne(keepAdministratorId)).execute();
        db.update(AUTHOR).setNull(AUTHOR.SOURCE_ID).execute();
        db.deleteFrom(SETTING).where(SETTING.KEY.notIn(kept)).execute();
        // the SMTP and CAPTCHA secrets go with the settings that use them
        db.deleteFrom(SECRET).execute();
        var insert = db.insertInto(SETTING, SETTING.KEY, SETTING.VALUE);
        for (var e : defaults.entrySet()) {
            insert = insert.values(e.getKey(), e.getValue());
        }
        insert.execute();
    }

    /**
     * Every row goes, users and settings included, except the settings {@code kept}.
     */
    public void wipe(Set<String> kept, String keepTask) {
        clearContent(keepTask);
        db.deleteFrom(TOTP_FACTOR).execute();
        db.deleteFrom(PASSKEY).execute();
        db.deleteFrom(SESSION).execute();
        db.deleteFrom(AUTHOR).execute();
        db.deleteFrom(SETTING).where(SETTING.KEY.notIn(kept)).execute();
        db.deleteFrom(SECRET).execute();
    }

    /**
     * Content and everything hanging off it, in foreign-key order. Leaves authors, sessions and settings to the caller.
     */
    private void clearContent(String keepTask) {
        // self-references first, so whole-table deletes never trip an FK on any database
        db.update(COMMENT).setNull(COMMENT.PARENT_ID).execute();
        db.update(ITEM).setNull(ITEM.PARENT_ID).execute();
        db.update(TERM).setNull(TERM.PARENT_ID).execute();
        db.deleteFrom(ITEM_TERM).execute();
        db.deleteFrom(ITEM_FRAGMENT).execute();
        db.deleteFrom(PUBLISHED_ITEM).execute();
        db.deleteFrom(PUBLISH_QUEUE).execute();
        // the recurring sweep stays; a picked publish is waiting for the lock this holds, and finds an empty queue
        db
            .deleteFrom(SCHEDULED_TASKS)
            .where(SCHEDULED_TASKS.TASK_NAME.ne(keepTask), SCHEDULED_TASKS.PICKED.isFalse())
            .execute();
        db.deleteFrom(REVISION).execute();
        db.deleteFrom(COMMENT).execute();
        db.deleteFrom(MEDIA).execute();
        db.deleteFrom(ITEM).execute();
        db.deleteFrom(TERM).execute();
        db.deleteFrom(USER_TOKEN).execute();
        db.deleteFrom(REUSABLE_BLOCK).execute();
        db.deleteFrom(REDIRECT).execute();
    }
}
