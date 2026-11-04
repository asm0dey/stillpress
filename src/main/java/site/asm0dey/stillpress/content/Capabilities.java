package site.asm0dey.stillpress.content;

import static site.asm0dey.stillpress.account.Role.ADMINISTRATOR;
import static site.asm0dey.stillpress.account.Role.CONTRIBUTOR;
import static site.asm0dey.stillpress.account.Role.EDITOR;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.ReusableBlockRecord;

/**
 * WordPress's default role capabilities; the one place that decides who may do what.
 */
public final class Capabilities {
    private static final Set<String> UNPUBLISHED = Set.of("draft", "pending");
    private static final Set<String> PUBLIC_STATUSES = Set.of("publish", "future", "private");

    private Capabilities() {
    }

    private static boolean editor(Role r) {
        return r == ADMINISTRATOR || r == EDITOR;
    }

    private static boolean own(long userId, ItemRecord item) {
        return item.getAuthorId() != null && item.getAuthorId() == userId;
    }

    /**
     * Seeing an item in the admin: one's own, or anything for editors.
     */
    public static boolean canView(Role r, long userId, ItemRecord item) {
        return editor(r) || own(userId, item);
    }

    public static boolean canEdit(Role r, long userId, ItemRecord item) {
        if (editor(r)) {
            return true;
        }
        if (!"post".equals(item.getType()) || !own(userId, item)) {
            return false;
        }
        return r != CONTRIBUTOR || UNPUBLISHED.contains(item.getStatus());
    }

    public static boolean canSetStatus(Role r, long userId, ItemRecord item, String status) {
        if (!canEdit(r, userId, item)) {
            return false;
        }
        return !PUBLIC_STATUSES.contains(status) || r != CONTRIBUTOR;
    }

    /**
     * Trash and restore: editors any, authors their own, contributors their own unpublished (or trashed) ones.
     */
    public static boolean canTrash(Role r, long userId, ItemRecord item) {
        if (editor(r)) {
            return true;
        }
        if (!"post".equals(item.getType()) || !own(userId, item)) {
            return false;
        }
        return r != CONTRIBUTOR || UNPUBLISHED.contains(item.getStatus()) || "trash".equals(item.getStatus());
    }

    /**
     * Changing an item's author.
     */
    public static boolean canReassign(Role r) {
        return editor(r);
    }

    public static boolean canCreate(Role r, String type) {
        return "post".equals(type) || "page".equals(type) && editor(r);
    }

    /**
     * Categories and tags: authors and up.
     */
    public static boolean canCreateTerm(Role r) {
        return r != CONTRIBUTOR;
    }

    /**
     * Renaming, moving and deleting categories and tags: editors and administrators.
     */
    public static boolean canManageTerms(Role r) {
        return editor(r);
    }

    public static boolean canCreateBlock(Role r) {
        return r != CONTRIBUTOR;
    }

    public static boolean canEditBlock(Role r, long userId, ReusableBlockRecord block) {
        return editor(r) || r != CONTRIBUTOR && block.getAuthorId() != null && block.getAuthorId() == userId;
    }

    public static boolean canUpload(Role r) {
        return r != CONTRIBUTOR;
    }

    /**
     * WordPress's {@code unfiltered_html}: saving HTML that can run script.
     */
    public static boolean canPostUnfilteredHtml(Role r) {
        return editor(r);
    }

    public static boolean canRepublish(Role r) {
        return r == ADMINISTRATOR;
    }
}
