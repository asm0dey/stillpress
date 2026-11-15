package site.asm0dey.stillpress;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "site.asm0dey.stillpress", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    // the generated POJOs are plain Java records: what repositories hand out
    @ArchTest
    static final ArchRule onlyTheStoreTouchesTheDatabase = noClasses()
        .that()
        .resideOutsideOfPackages("site.asm0dey.stillpress.store..", "site.asm0dey.stillpress.db..")
        .should()
        .dependOnClassesThat(resideInAnyPackage(
                "org.jooq..",
                "site.asm0dey.stillpress.db..",
                "javax.sql..",
                "java.sql.."
        )
            .and(not(resideInAPackage("site.asm0dey.stillpress.db.tables.pojos..")))
        )
        .because("logic is tested against stubbed repositories without a database; only store/ is tested with one");
}
