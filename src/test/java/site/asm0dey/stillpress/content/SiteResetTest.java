package site.asm0dey.stillpress.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static site.asm0dey.stillpress.db.Tables.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.account.Passwords;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.DefaultSchema;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.importer.ImportRunningException;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishLock;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.publish.SitePublisher;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;
import site.asm0dey.stillpress.store.RevisionRepository;
import site.asm0dey.stillpress.store.SessionRepository;
import site.asm0dey.stillpress.store.SettingRepository;
import site.asm0dey.stillpress.store.TokenRepository;

@QuarkusTest
class SiteResetTest {
    @Inject
    SiteReset reset;
    @Inject
    TestStore store;
    @Inject
    DSLContext db;
    @Inject
    DataDir dataDir;
    @Inject
    ImportGuard guard;
    @Inject
    AuthorRepository users;
    @Inject
    SessionRepository sessions;
    @Inject
    SettingRepository settings;
    @Inject
    TokenRepository tokens;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    RevisionRepository revisions;
    @Inject
    PublishQueue queue;
    @Inject
    PublishLock lock;
    @Inject
    SitePublisher publisher;
    long adminId;
    String hash;

    @BeforeEach
    void clean() {
        store.reset();
        hash = Passwords.hash("correct horse battery");
        adminId = users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR).getId();
        users.setPassword(adminId, hash);
    }

    /**
     * Every table of the schema gets a row, so a table nobody fills here fails {@link #fillsEveryTable} first.
     */
    void fill() throws Exception {
        guard.begin();
        store.importSample();
        guard.done();
        settings.put("title", "Experiment");
        settings.put("language", "en-GB");
        settings.put("home_url", "https://blog.example.org");
        settings.put("timezone", "Europe/Berlin");
        settings.put("posts_per_page", "3");
        settings.put("permalink_structure", "/%postname%/");
        var item = db.select(DSL.min(ITEM.ID)).from(ITEM).fetchOne(DSL.min(ITEM.ID));
        db.update(AUTHOR).set(AUTHOR.SOURCE_ID, 7L).where(AUTHOR.ID.eq(adminId)).execute();
        blocks.create("Block", "<p>x</p>", adminId, LocalDateTime.now());
        revisions.insert("item", item, "t", "b", "blocks", "", adminId, false);
        tokens.create(adminId, TokenRepository.SET_PASSWORD, Duration.ofHours(1));
        var editor = users.create("editor", "e@example.org", "Editor", Role.EDITOR).getId();
        sessions.create(editor);
        sessions.create(adminId);
        db
            .insertInto(
                    SCHEDULED_TASKS,
                    SCHEDULED_TASKS.TASK_NAME,
                    SCHEDULED_TASKS.TASK_INSTANCE,
                    SCHEDULED_TASKS.EXECUTION_TIME,
                    SCHEDULED_TASKS.PICKED,
                    SCHEDULED_TASKS.VERSION
            )
            .values(Jobs.GO_LIVE, item + "@x", OffsetDateTime.now().plusYears(1), false, 1L)
            .execute();
        lock.holding(() -> publisher.publishAll());
        lock.holding(() -> publisher.publishAll());
        queue.item(item);
        Files.writeString(Files.createDirectories(dataDir.root().resolve("import")).resolve("left.xml"), "x");
    }

    @Test
    void fillsEveryTable() throws Exception {
        fill();
        assertThat(tablesWithRows())
            .containsExactlyInAnyOrderElementsOf(DefaultSchema.DEFAULT_SCHEMA
                .getTables()
                .stream()
                .map(t -> t.getName())
                .filter(n -> !n.equals("scheduled_tasks"))
                .toList()
            );
        assertThat(children(dataDir.media())).isNotEmpty();
        assertThat(children(dataDir.releases())).hasSizeGreaterThan(1);
    }

    @Test
    void resetLeavesOnlyTheAdministratorAndWizardSettings() throws Exception {
        fill();
        assertThat(reset.reset(adminId, null)).isTrue();
        assertThat(tablesWithRows()).containsExactlyInAnyOrder("author", "setting");
        assertThat(db.select(SCHEDULED_TASKS.TASK_NAME).from(SCHEDULED_TASKS).fetch(SCHEDULED_TASKS.TASK_NAME))
            .containsOnly(Jobs.PUBLISH_SWEEP);
        var admin = users.all();
        assertThat(admin)
            .extracting(r -> r.getId())
            .containsExactly(adminId);
        assertThat(admin.getFirst().getPasswordHash()).isEqualTo(hash);
        assertThat(admin.getFirst().getSourceId()).isNull();
        assertThat(settingKeys())
            .containsExactlyInAnyOrder(
                    "title",
                    "language",
                    "home_url",
                    "timezone",
                    "publish_lock",
                    // written by the publish of the empty site that follows
                    "publish_state",
                    "posts_per_page",
                    "posts_per_rss",
                    "category_base",
                    "tag_base"
            );
        assertThat(settings.get("title")).hasValue("Experiment");
        assertThat(settings.get("posts_per_page")).hasValue("10");
        assertThat(settings.get("category_base")).hasValue("category");
        assertThat(children(dataDir.media())).isEmpty();
        assertThat(children(dataDir.root().resolve("import"))).isEmpty();
        assertThat(children(dataDir.releases())).hasSize(1);
        assertThat(dataDir.current().toRealPath()).isEqualTo(children(dataDir.releases()).getFirst().toRealPath());
        assertThat(dataDir.current().resolve("index.html")).content().contains("Experiment").doesNotContain("<article");
    }

    @Test
    void callersSessionSurvives() throws Exception {
        fill();
        var raw = sessions.create(adminId);
        reset.reset(adminId, raw);
        assertThat(sessions.find(raw))
            .map(r -> r.getId())
            .hasValue(adminId);
        assertThat(db.fetchCount(SESSION)).isEqualTo(1);
    }

    @Test
    void importWorksAfterReset() throws Exception {
        fill();
        reset.reset(adminId, null);
        guard.begin();
        var report = store.importSample();
        guard.done();
        assertThat(report.items()).isEqualTo(7);
    }

    @Test
    void refusedWhileImportRuns() throws Exception {
        guard.begin();
        assertThatThrownBy(() -> reset.reset(adminId, null)).isInstanceOf(ImportRunningException.class);
        assertThat(guard.state()).hasValue("running");
        assertThat(users.all()).hasSize(1);
    }

    @Test
    void wipeLeavesNoUsersAndOnlyTheLockSetting() throws Exception {
        fill();
        assertThat(reset.wipe()).isTrue();
        assertThat(tablesWithRows()).containsExactly("setting");
        assertThat(settingKeys()).containsExactlyInAnyOrder("publish_lock", "publish_state");
        assertThat(children(dataDir.media())).isEmpty();
        assertThat(children(dataDir.releases())).hasSize(1);
    }

    private List<String> tablesWithRows() {
        return DefaultSchema.DEFAULT_SCHEMA
            .getTables()
            .stream()
            .filter(t -> !t.getName().equals("scheduled_tasks") && db.fetchExists(t))
            .map(t -> t.getName())
            .toList();
    }

    private List<String> settingKeys() {
        return db.select(SETTING.KEY).from(SETTING).fetch(SETTING.KEY);
    }

    private static List<Path> children(Path dir) throws IOException {
        try (var list = Files.list(dir)) {
            return list.toList();
        }
    }
}
