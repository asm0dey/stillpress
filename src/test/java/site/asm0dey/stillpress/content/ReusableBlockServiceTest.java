package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.db.tables.pojos.ReusableBlock;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository.Use;

@ExtendWith(MockitoExtension.class)
class ReusableBlockServiceTest {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 12, 0);
    @Mock
    ReusableBlockRepository blocks;
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
    ReusableBlockService service;
    ItemService.Actor editor = new ItemService.Actor(1, Role.EDITOR);
    ItemService.Actor author1 = new ItemService.Actor(2, Role.AUTHOR);
    ItemService.Actor author2 = new ItemService.Actor(3, Role.AUTHOR);
    ItemService.Actor contributor = new ItemService.Actor(4, Role.CONTRIBUTOR);

    @BeforeEach
    void setUp() {
        service = new ReusableBlockService(blocks, revisions, queue, jobs, guard, html);
    }

    private static ReusableBlock block(long sourceId, String content, Long authorId, LocalDateTime modifiedAt) {
        return new ReusableBlock(sourceId + 100, sourceId, "T", content, modifiedAt, authorId);
    }

    private void updates() {
        when(blocks.update(anyLong(), anyString(), anyString(), any()))
            .thenAnswer(i -> new ReusableBlock(101L, 1L, i.getArgument(1), i.getArgument(2), i.getArgument(3), 2L));
    }

    @Test
    void editEnqueuesEveryItemUsingItIncludingNested() {
        var b = block(2, "<!-- wp:paragraph --><p>b</p><!-- /wp:paragraph -->", null, T0);
        var a = block(3, "<!-- wp:block {\"ref\":2} /-->", null, T0);
        when(blocks.lock(2)).thenReturn(Optional.of(b));
        when(blocks.all()).thenReturn(List.of(b, a));
        updates();
        // x shows A (which shows B), y shows B; the draft is not republished
        when(blocks.itemsUsing(Set.of(2L, 3L)))
            .thenReturn(List.of(
                    new Use(10, "publish", Set.of(3L)),
                    new Use(11, "publish", Set.of(2L)),
                    new Use(12, "draft", Set.of(2L))
            ));
        var saved = service.update(2, "B", "changed", T0, editor);
        assertThat(saved.republished()).isEqualTo(2);
        verify(queue).items(List.of(10L, 11L));
        verify(jobs).requestPublish();
    }

    @Test
    void authorEditsOwnBlockButNotOthers() {
        var mine = block(5, "one", author1.userId(), T0);
        when(blocks.create(eq("Mine"), eq("one"), eq(author1.userId()), any())).thenReturn(mine);
        assertThat(service.save(null, "Mine", "one", null, author1).authorId()).isEqualTo(author1.userId());
        when(blocks.lock(5)).thenReturn(Optional.of(mine));
        updates();
        assertThat(service.save(5L, "Mine", "two", T0, author1).content()).isEqualTo("two");
        assertThatThrownBy(() -> service.save(5L, "Taken", "three", T0, author2))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        verify(blocks, times(1)).update(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void contributorCannotCreate() {
        assertThatThrownBy(() -> service.save(null, "No", "x", null, contributor))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        verifyNoInteractions(blocks);
    }

    @Test
    void staleExpectedModifiedAtIs409() {
        when(blocks.lock(5)).thenReturn(Optional.of(block(5, "two", author1.userId(), T0.plusDays(1))));
        assertThatThrownBy(() -> service.save(5L, "T", "three", T0, author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(409));
        assertThatThrownBy(() -> service.save(5L, "T", "four", null, author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(409));
        verify(blocks, never()).update(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void ownerlessImportedBlockOnlyForEditors() {
        when(blocks.lock(500)).thenReturn(Optional.of(block(500, "imported", null, null)));
        assertThatThrownBy(() -> service.save(500L, "T", "by author", null, author1))
            .isInstanceOfSatisfying(ItemService.Refused.class, e -> assertThat(e.status).isEqualTo(403));
        verify(blocks, never()).update(anyLong(), anyString(), anyString(), any());
        updates();
        assertThat(service.save(500L, "T", "by editor", null, editor).content()).isEqualTo("by editor");
    }
}
