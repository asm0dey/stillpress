package site.asm0dey.stillpress.importer;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import site.asm0dey.stillpress.TestStore;
import site.asm0dey.stillpress.store.SettingRepository;

@QuarkusTest
class ImportCommandQuarkusTest {
    @Inject
    CommandLine.IFactory factory;
    @Inject
    TestStore store;
    @Inject
    SettingRepository settings;
    @TempDir
    Path tmp;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    @Test
    void importWritesDoneAndASecondImportExits1() throws Exception {
        store.reset();
        var wxr = sampleFile();
        assertThat(run("--wxr", wxr.toString())).isZero();
        assertThat(out.toString()).startsWith("Imported 9 items");
        assertThat(settings.get(ImportGuard.STATE)).contains("done");
        assertThat(run("--wxr", wxr.toString())).isOne();
        assertThat(err.toString()).contains("an import is already done");
    }

    @Test
    void runningImportElsewhereExits1() throws Exception {
        store.reset();
        settings.put(ImportGuard.STATE, "running");
        assertThat(run("--wxr", sampleFile().toString())).isOne();
        assertThat(err.toString()).contains("an import is already running");
        assertThat(settings.get(ImportGuard.STATE)).as("left alone").contains("running");
    }

    private Path sampleFile() throws IOException {
        var wxr = tmp.resolve("export.xml");
        try (var in = TestStore.sample()) {
            Files.copy(in, wxr, StandardCopyOption.REPLACE_EXISTING);
        }
        return wxr;
    }

    private int run(String... args) {
        var cmd = new CommandLine(ImportCommand.class, factory);
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }
}
