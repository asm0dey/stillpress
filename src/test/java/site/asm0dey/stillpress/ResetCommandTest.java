package site.asm0dey.stillpress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;
import module java.base;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.content.SiteReset;
import site.asm0dey.stillpress.importer.ImportRunningException;
import site.asm0dey.stillpress.store.SchemaRepository;

@ExtendWith(MockitoExtension.class)
class ResetCommandTest {
    @Mock
    SiteReset reset;
    @Mock
    SchemaRepository schema;
    @Mock
    DataDir dataDir;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(new ResetCommand(reset, schema, dataDir));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
    }

    @Test
    void withoutYesPrintsCountsAndWipesNothing() {
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("item", 12);
        counts.put("author", 3);
        when(schema.rowCounts()).thenReturn(counts);
        when(dataDir.root()).thenReturn(Path.of("/data/stillpress"));
        assertThat(cmd.execute()).isOne();
        assertThat(out.toString()).contains("/data/stillpress").contains("item: 12").contains("author: 3").contains(
                "--yes"
        );
        verify(reset, never()).wipe(anyBoolean());
    }

    @Test
    void withYesWipesAndPointsAtTheSetupToken() {
        when(reset.wipe(false)).thenReturn(true);
        assertThat(cmd.execute("--yes")).isZero();
        assertThat(out.toString()).contains("setup token");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void forcePassesThroughToTheWipe() {
        when(reset.wipe(true)).thenReturn(true);
        assertThat(cmd.execute("--yes", "--force")).isZero();
        verify(reset).wipe(true);
    }

    @Test
    void runningImportExits1AndMentionsForce() {
        when(reset.wipe(false)).thenThrow(new ImportRunningException());
        assertThat(cmd.execute("--yes")).isOne();
        assertThat(err.toString()).contains("--force").contains("Nothing was deleted");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void failedFileCleanupExits1() {
        when(reset.wipe(false)).thenReturn(false);
        assertThat(cmd.execute("--yes")).isOne();
        assertThat(err.toString()).contains("cleaning up the files failed");
        assertThat(out.toString()).doesNotContain("setup token");
    }
}
