package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.stillpress.db.Tables.ITEM;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ResetCommandTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    ImportGuard guard;
    @Inject
    AuthorRepository users;
    @Inject
    DataDir dataDir;
    @Inject
    DSLContext db;
    @Inject
    SettingRepository settings;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @BeforeEach
    void fill() throws Exception {
        store.reset();
        users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        guard.begin();
        store.importSample();
        guard.done();
    }

    @Test
    void withoutYesPrintsCountsAndDeletesNothing() {
        var items = db.fetchCount(ITEM);
        assertThat(run()).isOne();
        assertThat(out.toString())
            .contains("item: " + items)
            .contains("author: " + users.all().size())
            .contains(dataDir.root().toString())
            .contains("--yes");
        assertThat(db.fetchCount(ITEM)).isEqualTo(items);
    }

    @Test
    void withYesEmptiesTheStore() {
        assertThat(run("--yes")).isZero();
        assertThat(db.fetchCount(ITEM)).isZero();
        assertThat(users.all()).isEmpty();
        assertThat(guard.state()).isEmpty();
        assertThat(out.toString()).contains("setup token");
    }

    @Test
    void runningImportExits1() {
        store.reset();
        users.create("admin", "a@example.org", "Admin", Role.ADMINISTRATOR);
        settings.put(ImportGuard.STATE, "running");
        assertThat(run("--yes")).isOne();
        assertThat(err.toString()).contains("An import is running");
        assertThat(users.all()).hasSize(1);
    }

    private int run(String... args) {
        var cmd = new CommandLine(ResetCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
