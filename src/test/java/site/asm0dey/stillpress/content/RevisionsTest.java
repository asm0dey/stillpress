package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.db.tables.pojos.Revision;
import site.asm0dey.stillpress.store.RevisionRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@ExtendWith(MockitoExtension.class)
class RevisionsTest {
    private static final long USER = 7;
    @Mock
    RevisionRepository repo;
    @Mock
    SettingRepository settings;
    Revisions revisions;

    @BeforeEach
    void setUp() {
        revisions = new Revisions(repo, settings);
    }

    private static Revision revision(long id, String title, boolean autosave) {
        return new Revision(id, "item", 42L, title, "body", null, "", USER, LocalDateTime.now(), autosave);
    }

    @Test
    void keepsNewest25() {
        when(settings.get("revisions_keep")).thenReturn(Optional.empty());
        for (int i = 1; i <= 30; i++) {
            revisions.saved("item", 42, "save " + i, "body", "blocks", "", USER);
        }
        // every save prunes to the default 25, and the repository lists the survivors newest first
        verify(repo, times(30)).prune("item", 42, 25);
        when(repo.list("item", 42)).thenReturn(List.of(revision(30, "save 30", false), revision(6, "save 6", false)));
        assertThat(revisions.list("item", 42)).extracting(Revision::title).containsExactly("save 30", "save 6");
    }

    @Test
    void autosaveIsReplacedNotAccumulatedAndDeletedOnSave() {
        revisions.autosave("item", 5, USER, "one", "b1", "");
        revisions.autosave("item", 5, USER, "two", "b2", "");
        // each autosave first drops the previous one, then inserts the new one flagged as autosave
        var order = inOrder(repo);
        order.verify(repo).deleteAutosave("item", 5, USER);
        order.verify(repo).insert("item", 5, "one", "b1", null, "", USER, true);
        order.verify(repo).deleteAutosave("item", 5, USER);
        order.verify(repo).insert("item", 5, "two", "b2", null, "", USER, true);
        verifyNoMoreInteractions(repo);

        revisions.deleteAutosave("item", 5, USER);
        verify(repo, times(3)).deleteAutosave("item", 5, USER);
        when(repo.autosave("item", 5, USER)).thenReturn(Optional.empty());
        assertThat(revisions.autosaveOf("item", 5, USER)).isEmpty();
    }

    @Test
    void autosaveIsNotARevision() {
        revisions.autosave("item", 42, USER, "draft", "b", "");
        verify(repo, never()).prune("item", 42, 25);
        // the repository lists revisions without autosaves, so the autosave is only reachable through autosaveOf
        when(repo.list("item", 42)).thenReturn(List.of());
        when(repo.autosave("item", 42, USER)).thenReturn(Optional.of(revision(1, "draft", true)));
        assertThat(revisions.list("item", 42)).isEmpty();
        assertThat(revisions.autosaveOf("item", 42, USER).map(Revision::title)).contains("draft");
    }
}
