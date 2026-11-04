package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.account.Role.ADMINISTRATOR;
import static site.asm0dey.stillpress.account.Role.AUTHOR;
import static site.asm0dey.stillpress.account.Role.CONTRIBUTOR;
import static site.asm0dey.stillpress.account.Role.EDITOR;
import module java.base;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.db.tables.records.ReusableBlockRecord;

class CapabilitiesTest {
    private final ItemRecord draftPostBy1 = item("post", "draft", 1L);
    private final ItemRecord publishedPostBy1 = item("post", "publish", 1L);
    private final ItemRecord pageBy1 = item("page", "draft", 1L);

    @Test
    void contributorCannotPublishButMaySubmitForReview() {
        assertThat(Capabilities.canSetStatus(CONTRIBUTOR, 1, draftPostBy1, "publish")).isFalse();
        assertThat(Capabilities.canSetStatus(CONTRIBUTOR, 1, draftPostBy1, "pending")).isTrue();
    }

    @Test
    void contributorCannotEditOwnPublishedPost() {
        assertThat(Capabilities.canEdit(CONTRIBUTOR, 1, publishedPostBy1)).isFalse();
    }

    @Test
    void authorCannotTouchOthersOrPages() {
        assertThat(Capabilities.canEdit(AUTHOR, 2, draftPostBy1)).isFalse();
        assertThat(Capabilities.canCreate(AUTHOR, "page")).isFalse();
    }

    @Test
    void authorPublishesOwnOnly() {
        assertThat(Capabilities.canSetStatus(AUTHOR, 1, draftPostBy1, "publish")).isTrue();
        assertThat(Capabilities.canSetStatus(AUTHOR, 2, draftPostBy1, "publish")).isFalse();
    }

    @Test
    void editorDoesEverythingButRepublish() {
        assertThat(Capabilities.canEdit(EDITOR, 2, pageBy1)).isTrue();
        assertThat(Capabilities.canRepublish(EDITOR)).isFalse();
        assertThat(Capabilities.canRepublish(ADMINISTRATOR)).isTrue();
    }

    @Test
    void blocksAndUploads() {
        var blockBy1 = new ReusableBlockRecord();
        blockBy1.setAuthorId(1L);
        assertThat(Capabilities.canCreateBlock(CONTRIBUTOR)).isFalse();
        assertThat(Capabilities.canEditBlock(AUTHOR, 1, blockBy1)).isTrue();
        assertThat(Capabilities.canEditBlock(AUTHOR, 2, blockBy1)).isFalse();
        assertThat(Capabilities.canEditBlock(EDITOR, 2, blockBy1)).isTrue();
        assertThat(Capabilities.canUpload(CONTRIBUTOR)).isFalse();
        assertThat(Capabilities.canUpload(AUTHOR)).isTrue();
    }

    @Test
    void trashFollowsTheTable() {
        assertThat(Capabilities.canTrash(CONTRIBUTOR, 1, draftPostBy1)).isTrue();
        assertThat(Capabilities.canTrash(CONTRIBUTOR, 1, publishedPostBy1)).isFalse();
        assertThat(Capabilities.canTrash(AUTHOR, 1, publishedPostBy1)).isTrue();
        assertThat(Capabilities.canTrash(AUTHOR, 2, publishedPostBy1)).isFalse();
        assertThat(Capabilities.canTrash(EDITOR, 2, publishedPostBy1)).isTrue();
    }

    private static ItemRecord item(String type, String status, Long authorId) {
        var r = new ItemRecord();
        r.setType(type);
        r.setStatus(status);
        r.setAuthorId(authorId);
        return r;
    }
}
