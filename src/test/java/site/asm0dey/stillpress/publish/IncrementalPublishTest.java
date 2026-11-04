package site.asm0dey.stillpress.publish;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.Csrf.csrf;
import static site.asm0dey.stillpress.db.Tables.AUTHOR;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import static site.asm0dey.stillpress.db.Tables.ITEM_TERM;
import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import static site.asm0dey.stillpress.db.Tables.TERM;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.ReusableBlockService;
import site.asm0dey.stillpress.content.TermService;
import site.asm0dey.stillpress.db.tables.records.ItemRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.SessionRepository;

@QuarkusTest
class IncrementalPublishTest {
    private static final String POST = "2023/01/14/месяц-ноябрь-год-2022/";
    @Inject
    SitePublisher publisher;
    @Inject
    PublishQueue queue;
    @Inject
    FragmentRepository fragments;
    @Inject
    TestStore store;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    @Inject
    TermService terms;
    @Inject
    PublishLock lock;
    @Inject
    ItemService items;
    @Inject
    ReusableBlockService blockService;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    Jobs jobs;

    @BeforeEach
    void publishedSample() throws Exception {
        store.reset();
        store.importSample();
        publisher.publishAll();
    }

    @Test
    void incrementalEqualsFullAfterTitleEdit() throws Exception {
        edit(5994, r -> r.setTitle("New title"));
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        assertThat(inc).containsKey(POST + "index.html");
    }

    @Test
    void incrementalEqualsFullAfterUnpublish() throws Exception {
        perPage(2);
        edit(5994, r -> r.setStatus("draft"));
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        // three posts made two home pages; with two left the second page is gone
        assertThat(inc).doesNotContainKeys(POST + "index.html", POST + "feed/index.html", "page/2/index.html");
    }

    @Test
    void incrementalEqualsFullAfterPermalinkChange() throws Exception {
        edit(5994, r -> {
            r.setSlug("renamed");
            r.setPermalink("/2023/01/14/renamed/");
        });
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        assertThat(inc).doesNotContainKeys(POST + "index.html", POST + "feed/index.html");
        assertThat(inc).containsKeys("2023/01/14/renamed/index.html", "2023/01/14/renamed/feed/index.html");
    }

    @Test
    void incrementalEqualsFullAfterNewPostShiftsPagination() throws Exception {
        var travel = db.select(TERM.ID).from(TERM).where(TERM.SLUG.eq("travel")).fetchOne(TERM.ID);
        db.insertInto(ITEM_TERM, ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID).values(itemId(103), travel).execute();
        perPage(2);
        var post = db.selectFrom(ITEM).where(ITEM.SOURCE_ID.eq(103L)).fetchOne().copy();
        post.setSourceId(7000L);
        post.setTitle("Newest");
        post.setSlug("newest");
        post.setPermalink("/2024/03/03/newest/");
        post.setGuid("https://blog.example.ru/?p=7000");
        post.setPublishedAt(LocalDateTime.of(2024, 3, 3, 9, 0));
        post.setPublishedLocal(LocalDateTime.of(2024, 3, 3, 12, 0));
        post.store();
        db.insertInto(ITEM_TERM, ITEM_TERM.ITEM_ID, ITEM_TERM.TERM_ID).values(post.getId(), travel).execute();
        queue.item(post.getId());
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        assertThat(inc).containsKeys(
                "page/2/index.html",
                "category/travel/page/2/index.html",
                "2024/03/03/newest/index.html"
        );
    }

    @Test
    void incrementalEqualsFullAfterReusableBlockEdit() throws Exception {
        db
            .update(REUSABLE_BLOCK)
            .set(REUSABLE_BLOCK.CONTENT, "<p>Changed block</p>")
            .where(REUSABLE_BLOCK.SOURCE_ID.eq(6001L))
            .execute();
        var refs = db.select(ITEM.ID).from(ITEM).where(ITEM.BODY.contains("\"ref\":6001")).fetch(ITEM.ID);
        assertThat(refs).hasSize(1);
        fragments.delete(refs);
        refs.forEach(queue::item);
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        assertThat(dataDir.current().resolve(POST + "index.html")).content().contains("Changed block");
    }

    @Test
    void incrementalEqualsFullAfterTermSlugChange() throws Exception {
        var travel = db.selectFrom(TERM).where(TERM.SLUG.eq("travel")).fetchOne();
        // held so the publish the save asks for cannot take the queue before this test reads it
        var trees = lock.holding(() -> {
            terms.updateTerm(
                    travel.getId(),
                    new TermService.Change("Trips", "trips", null, null),
                    new ItemService.Actor(0, Role.ADMINISTRATOR)
            );
            var batch = queue.snapshot().orElseThrow();
            var inc = tree(publisher.publish(batch).release());
            queue.done(batch);
            return List.of(inc, tree(publisher.publishAll().release()));
        });
        assertThat(trees.getFirst()).isEqualTo(trees.getLast());
        assertThat(trees.getFirst()).containsKey("category/trips/europe/index.html");
        assertThat(trees.getFirst().keySet()).noneMatch(k -> k.startsWith("category/travel/"));
    }

    /**
     * Each change goes through the service (or endpoint) that makes it, which queues what it changed; nothing in the
     * test deletes fragments or queues rows for it.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "user created",
            "display name",
            "default category",
            "go live",
            "trash",
            "restore",
            "block save"
    })
    void incrementalEqualsFullAfterServiceSave(String change) {
        var admin = users.create("boss", "boss@example.org", "Boss", Role.ADMINISTRATOR).getId();
        var cookie = sessions.create(admin);
        var actor = new ItemService.Actor(admin, Role.ADMINISTRATOR);
        var post = itemId(5994);
        // held throughout so the publish each save asks for cannot take the queue before this test reads it
        var trees = lock.holding(() -> {
            switch (change) {
                case "go live" -> db.update(ITEM).set(ITEM.STATUS, "future").where(ITEM.ID.eq(post)).execute();
                case "restore" -> items.trash(post, actor);
                default -> {
                }
            }
            queue.snapshot().ifPresent(queue::done);
            publisher.publishAll();
            switch (change) {
                case "user created" -> csrf()
                    .contentType(JSON)
                    .cookie("sp_session", cookie)
                    .body(Map.of("login", "newbie", "email", "n@example.org", "displayName", "Newbie", "role", "author")
                    )
                    .post("/admin/api/users")
                    .then()
                    .statusCode(201);
                case "display name" -> {
                    var author = db
                        .selectFrom(AUTHOR)
                        .where(AUTHOR.ID.eq(db.select(ITEM.AUTHOR_ID).from(ITEM).where(ITEM.ID.eq(post))))
                        .fetchOne();
                    csrf()
                        .contentType(JSON)
                        .cookie("sp_session", cookie)
                        .body(Map.of("displayName", "Renamed", "email", author.getEmail()))
                        .put("/admin/api/users/" + author.getId())
                        .then()
                        .statusCode(204);
                }
                case "default category" -> {
                    var r = items.create("post", admin);
                    items.update(
                            r.getId(),
                            new ItemService.Update(
                                    "Fresh",
                                    null,
                                    "<p>x</p>",
                                    null,
                                    null,
                                    "publish",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    r.getModifiedAt()
                            ),
                            actor
                    );
                }
                case "go live" -> jobs.goLive(post + "@0");
                case "trash" -> items.trash(post, actor);
                case "restore" -> items.restore(post, actor);
                case "block save" -> {
                    var b = blocks.find(6001L).orElseThrow();
                    blockService.save(6001L, b.getTitle(), "<p>Changed block</p>", b.getModifiedAt(), actor);
                }
                default -> throw new IllegalArgumentException(change);
            }
            var inc = queue.snapshot().map(batch -> {
                var t = tree(publisher.publish(batch).release());
                queue.done(batch);
                return t;
            }).orElseGet(() -> {
                try {
                    return tree(dataDir.current().toRealPath());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return List.of(inc, tree(publisher.publishAll().release()));
        });
        assertThat(trees.getFirst()).isEqualTo(trees.getLast());
    }

    @Test
    void untouchedFilesAreHardLinks() throws Exception {
        var live = dataDir.current().toRealPath();
        var full = publisher.publishAll();
        assertThat(full.release().toRealPath()).isNotEqualTo(live);
        edit(5994, r -> r.setTitle("New title"));
        var inc = publisher.publish(queue.snapshot().orElseThrow());
        for (var rel : List.of("2020/05/10/qa/index.html", "about/index.html", "assets/plain.css")) {
            var before = full.release().resolve(rel);
            var after = inc.release().resolve(rel);
            assertThat(Files.readAttributes(after, BasicFileAttributes.class).fileKey())
                .isEqualTo(Files.readAttributes(before, BasicFileAttributes.class).fileKey());
        }
        // the post (page + feed), its listings (home, 2 categories, tag, author, 3 dates: pages and feeds) and the
        // comments feed; nothing else is rendered
        assertThat(inc.files()).isEqualTo(2 + 2 + 2 + 2 + 2 + 2 + 3 + 1);
        assertThat(inc.files()).isLessThan(full.files());
    }

    @Test
    void noLiveReleaseFallsBackToFull() throws Exception {
        edit(5994, r -> r.setTitle("New title"));
        Files.delete(dataDir.current());
        var inc = tree(publisher.publish(queue.snapshot().orElseThrow()).release());
        var full = tree(publisher.publishAll().release());
        assertThat(inc).isEqualTo(full);
        assertThat(inc).containsKeys("robots.txt", "404.html", "about/index.html");
    }

    /**
     * What an item save does: the record, its fragment and its queue row.
     */
    private void edit(long sourceId, Consumer<ItemRecord> change) {
        var r = db.selectFrom(ITEM).where(ITEM.SOURCE_ID.eq(sourceId)).fetchOne();
        change.accept(r);
        r.store();
        fragments.delete(List.of(r.getId()));
        queue.item(r.getId());
    }

    private long itemId(long sourceId) {
        return db.select(ITEM.ID).from(ITEM).where(ITEM.SOURCE_ID.eq(sourceId)).fetchOne(ITEM.ID);
    }

    /**
     * A listing setting changes every page, so it is followed by a full publish, as the admin's would be.
     */
    private void perPage(int n) {
        db.execute("update setting set value = ? where key = 'posts_per_page'", String.valueOf(n));
        publisher.publishAll();
    }

    private static Map<String, String> tree(Path root) {
        try {
            return walk(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> walk(Path root) throws Exception {
        var out = new TreeMap<String, String>();
        try (var walk = Files.walk(root)) {
            for (var p : walk.filter(Files::isRegularFile).toList()) {
                var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
                out.put(root.relativize(p).toString().replace('\\', '/'), sha);
            }
        }
        return out;
    }
}
