package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.Fixtures;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.Author;
import site.asm0dey.stillpress.db.tables.pojos.Item;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.mail.ReviewNotifications;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TermRepository;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {
    private static final LocalDateTime MODIFIED = Fixtures.T0;
    private static final long ITEM = 42;
    @Mock
    SettingRepository settings;
    @Mock
    ItemRepository items;
    @Mock
    TermRepository terms;
    @Mock
    AuthorRepository authors;
    @Mock
    Permalinker permalinks;
    @Mock
    FragmentRepository fragments;
    @Mock
    Revisions revisions;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;
    @Mock
    ImportGuard guard;
    @Mock
    HtmlCheck html;
    @Mock
    ReviewNotifications reviewNotifications;
    ItemService service;
    ItemService.Actor author1 = new ItemService.Actor(1, Role.AUTHOR);
    ItemService.Actor author2 = new ItemService.Actor(2, Role.AUTHOR);
    ItemService.Actor editor = new ItemService.Actor(3, Role.EDITOR);
    ItemService.Actor contributor = new ItemService.Actor(4, Role.CONTRIBUTOR);

    @BeforeEach
    void setUp() {
        service = new ItemService(
                settings,
                items,
                terms,
                authors,
                permalinks,
                fragments,
                revisions,
                queue,
                jobs,
                guard,
                html,
                reviewNotifications
        );
    }

    private static ItemService.Update change(String title, String status, LocalDateTime localDate) {
        return new ItemService.Update(
                title,
                null,
                null,
                null,
                null,
                status,
                localDate,
                null,
                null,
                null,
                null,
                null,
                null,
                MODIFIED
        );
    }

    private static ItemService.Update withAuthor(Long authorId) {
        return new ItemService.Update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                authorId,
                null,
                null,
                MODIFIED
        );
    }

    private Item stored(String type, String status, long authorId) {
        var item = Fixtures.item(ITEM, type, status, authorId, null, MODIFIED);
        when(items.lock(ITEM)).thenReturn(Optional.of(item));
        return item;
    }

    /**
     * The repository answers an update with what was written.
     */
    private void savesAsWritten(Item base) {
        when(items.update(eq(ITEM), any())).thenAnswer(i -> {
            ItemRepository.Edit e = i.getArgument(1);
            return new Item(
                    ITEM,
                    base.sourceId(),
                    base.type(),
                    e.status(),
                    false,
                    e.title(),
                    e.slug(),
                    base.permalink(),
                    e.body(),
                    e.bodyFormat(),
                    e.excerpt(),
                    e.authorId(),
                    e.parentId(),
                    e.publishedAt(),
                    e.modifiedAt(),
                    base.guid(),
                    e.commentStatus(),
                    e.menuOrder(),
                    null,
                    e.publishedLocal()
            );
        });
    }

    private static void refusedWith(Runnable call, int status, String error, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ItemService.Refused.class, e -> {
            assertThat(e.status).isEqualTo(status);
            assertThat(e.error).isEqualTo(error);
            assertThat(e.field).isEqualTo(field);
        });
    }

    private void nothingWritten() {
        verify(items, never()).update(anyLong(), any());
        verifyNoInteractions(revisions, queue, jobs, fragments);
    }

    /**
     * A refusal before the import is closed leaves the site importable; later ones roll the transaction back.
     */
    private void importStillOpen() {
        nothingWritten();
        verify(guard, never()).close();
    }

    @Test
    void createClosesImportAndStartsADraftWithTheSitesDefaults() {
        when(settings.get("home_url")).thenReturn(Optional.of("http://blog.example//"));
        when(settings.get("default_comment_status")).thenReturn(Optional.of("closed"));
        var draft = Fixtures.item(5, "post", "draft", 1L);
        when(items.create(eq("post"), eq(1L), eq("http://blog.example"), eq("closed"), any())).thenReturn(draft);
        assertThat(service.create("post", 1)).isSameAs(draft);
        verify(guard).close();
    }

    @Test
    void staleExpectedModifiedAtIs409AndChangesNothing() {
        stored("post", "draft", 1);
        var stale = new ItemService.Update(
                "B",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MODIFIED.minusSeconds(1)
        );
        refusedWith(() -> service.update(ITEM, stale, author1), 409, "conflict", null);
        importStillOpen();
    }

    @Test
    void missingItemIs404() {
        when(items.lock(9)).thenReturn(Optional.empty());
        refusedWith(() -> service.update(9, change("t", null, null), author1), 404, "not_found", null);
    }

    @Test
    void updateDeletesTheUsersAutosaveAfterRecordingTheRevision() {
        var base = stored("post", "draft", 1);
        savesAsWritten(base);
        var saved = service.update(ITEM, change("Final", null, null), author1);
        assertThat(saved.title()).isEqualTo("Final");
        // the save is the revision; the autosave it supersedes goes, and only the saver's own
        var order = inOrder(revisions);
        order.verify(revisions).saved(eq("item"), eq(ITEM), anyString(), anyString(), anyString(), anyString(), eq(1L));
        order.verify(revisions).deleteAutosave("item", ITEM, 1);
        verify(fragments).delete(List.of(ITEM));
        verify(guard).close();
    }

    @Test
    void aDraftSaveDoesNotRepublish() {
        var base = stored("post", "draft", 1);
        savesAsWritten(base);
        service.update(ITEM, change("Final", null, null), author1);
        verifyNoInteractions(queue, jobs);
    }

    @Test
    void savingAPublishedItemQueuesItAndRequestsAPublish() {
        var base = stored("post", "publish", 1);
        savesAsWritten(base);
        service.update(ITEM, change("Edited", null, null), author1);
        verify(queue).item(ITEM);
        verify(jobs).requestPublish();
    }

    @Test
    void contributorPublishIs403AndStatusIsUnchanged() {
        stored("post", "draft", 4);
        refusedWith(() -> service.update(ITEM, change("Mine", "publish", null), contributor), 403, "forbidden", null);
        nothingWritten();
    }

    @Test
    void contributorMaySubmitForReview() {
        var base = stored("post", "draft", 4);
        savesAsWritten(base);
        assertThat(service.update(ITEM, change("Mine", "pending", null), contributor).status()).isEqualTo("pending");
        verify(reviewNotifications).pendingReview(ITEM, "Mine", 4);
    }

    @Test
    void authorEditingOthersPostIs403() {
        stored("post", "draft", 1);
        refusedWith(() -> service.update(ITEM, change("Taken over", null, null), author2), 403, "forbidden", null);
        importStillOpen();
    }

    @Test
    void unsafeHtmlFromAnAuthorIs400AndChangesNothingAndKeepsImportOpen() {
        stored("post", "draft", 1);
        var refused = new ItemService.Refused(400, "unsafe_html", "body", List.of("onclick attribute on <p>"));
        doThrow(refused).when(html).item(eq(Role.AUTHOR), any(), eq("<p onclick=\"go()\">a</p>"), any(), any());
        var u = new ItemService.Update(
                "t",
                null,
                "<p onclick=\"go()\">a</p>",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MODIFIED
        );
        assertThatThrownBy(() -> service.update(ITEM, u, author1)).isSameAs(refused);
        importStillOpen();
    }

    @Test
    void anEditorsSaveIsCheckedAsAnEditor() {
        var base = stored("post", "draft", 1);
        savesAsWritten(base);
        service.update(ITEM, change("t", null, null), editor);
        verify(html).item(eq(Role.EDITOR), eq("t"), any(), any(), any());
    }

    @Test
    void futureStatusWithAPastDateIs400() {
        stored("post", "draft", 1);
        when(permalinks.zone()).thenReturn(ZoneOffset.UTC);
        var yesterday = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        refusedWith(() -> service.update(ITEM, change("Past", "future", yesterday), author1), 400, "invalid", "date");
        nothingWritten();
    }

    @Test
    void publishWithAFutureDateIsScheduledNotPublished() {
        var base = stored("post", "draft", 1);
        savesAsWritten(base);
        when(permalinks.zone()).thenReturn(ZoneOffset.UTC);
        var tomorrow = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        var saved = service.update(ITEM, change("Later", "publish", tomorrow), author1);
        assertThat(saved.status()).isEqualTo("future");
        verify(jobs).scheduleGoLive(ITEM, tomorrow.toInstant(ZoneOffset.UTC));
        verify(permalinks, never()).assignPermalink(any());
    }

    @Test
    void unknownStatusAndValuesAre400() {
        stored("post", "draft", 1);
        refusedWith(() -> service.update(ITEM, change("t", "trash", null), author1), 400, "invalid", "status");
        refusedWith(() -> service.update(ITEM, change("t", "bogus", null), author1), 400, "invalid", "status");
        var u = new ItemService.Update(
                null,
                null,
                null,
                "wiki",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MODIFIED
        );
        refusedWith(() -> service.update(ITEM, u, author1), 400, "invalid", "bodyFormat");
        var c = new ItemService.Update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "maybe",
                null,
                MODIFIED
        );
        refusedWith(() -> service.update(ITEM, c, author1), 400, "invalid", "commentStatus");
        nothingWritten();
    }

    @Test
    void anAuthorCannotReassignButAnEditorCanToAKnownAuthor() {
        var base = stored("post", "draft", 1);
        refusedWith(() -> service.update(ITEM, withAuthor(2L), author1), 403, "forbidden", null);
        nothingWritten();
        when(authors.findById(2L))
            .thenReturn(Optional.of(new Author(2L, "a2", null, null, null, null, "author", null, null, null)));
        savesAsWritten(base);
        assertThat(service.update(ITEM, withAuthor(2L), editor).authorId()).isEqualTo(2L);
    }

    @Test
    void reassigningToAnUnknownAuthorIs400() {
        stored("post", "draft", 1);
        when(authors.findById(99L)).thenReturn(Optional.empty());
        refusedWith(() -> service.update(ITEM, withAuthor(99L), editor), 400, "invalid", "authorId");
    }

    @Test
    void restoringARevisionOfAnotherItemIs404() {
        when(revisions.find(5)).thenReturn(Optional.of(Fixtures.revision(5, "item", ITEM + 1, false, MODIFIED)));
        refusedWith(() -> service.restoreRevision(ITEM, 5, author1), 404, "not_found", null);
        when(revisions.find(6)).thenReturn(Optional.of(Fixtures.revision(6, "item", ITEM, true, MODIFIED)));
        refusedWith(() -> service.restoreRevision(ITEM, 6, author1), 404, "not_found", null);
    }

    @Test
    void restoringARevisionSavesItsTitleBodyAndExcerptWithTheCurrentStatus() {
        var base = stored("post", "publish", 1);
        when(items.find(ITEM)).thenReturn(Optional.of(base));
        when(revisions.find(5)).thenReturn(Optional.of(Fixtures.revision(5, "item", ITEM, false, MODIFIED)));
        savesAsWritten(base);
        service.restoreRevision(ITEM, 5, author1);
        verify(items)
            .update(
                    eq(ITEM),
                    argThat(e -> e.title().equals("rt")
                    && e.body().equals("rb")
                    && e.excerpt().equals("re")
                    && e.status().equals("publish"))
            );
    }

    @Test
    void autosaveOfSomeoneElsesItemIs403() {
        stored("post", "draft", 1);
        refusedWith(() -> service.autosave(ITEM, author2, "T", "b", ""), 403, "forbidden", null);
        verifyNoInteractions(revisions);
    }

    @Test
    void anUnsafeAutosaveIsRefusedAndWritesNothing() {
        stored("post", "draft", 1);
        doThrow(new ItemService.Refused(400, "unsafe_html", "title", List.of("<script> element")))
            .when(html)
            .item(eq(Role.AUTHOR), eq("<script>x</script>"), any(), any(), any());
        refusedWith(() -> service.autosave(ITEM, author1, "<script>x</script>", "", ""), 400, "unsafe_html", "title");
        verifyNoInteractions(revisions);
        verify(guard, never()).close();
    }

    @Test
    void autosaveIsJudgedInTheItemsStoredFormat() {
        stored("post", "draft", 1);
        service.autosave(ITEM, author1, "T", "b", "e");
        verify(html).item(Role.AUTHOR, "T", "b", "blocks", "e");
        verify(revisions).autosave("item", ITEM, 1, "T", "b", "e");
        verify(guard).close();
    }

    @Test
    void trashOfSomeoneElsesPostIs403AndTrashingTwiceIs400() {
        stored("post", "publish", 1);
        refusedWith(() -> service.trash(ITEM, author2), 403, "forbidden", null);
        verify(items, never()).setStatus(anyLong(), anyString(), any());
        when(items.lock(7)).thenReturn(Optional.of(Fixtures.item(7, "post", "trash", 1L)));
        refusedWith(() -> service.trash(7, author1), 400, "invalid", "status");
    }

    @Test
    void trashingAPublishedPostUnpublishesIt() {
        stored("post", "publish", 1);
        service.trash(ITEM, author1);
        verify(items).setStatus(eq(ITEM), eq("trash"), any());
        verify(fragments).delete(List.of(ITEM));
        verify(queue).item(ITEM);
        verify(jobs).requestPublish();
    }

    @Test
    void restoreGivesADraftAndRechecksTheHtml() {
        stored("post", "trash", 1);
        service.restore(ITEM, author1);
        verify(html).item(eq(Role.AUTHOR), anyString(), anyString(), anyString(), anyString());
        verify(items).setStatus(eq(ITEM), eq("draft"), any());
        verifyNoInteractions(queue, jobs);
    }
}
