# Unit Tests Without Quarkus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every test that does not need the Quarkus container off `@QuarkusTest`: plain JUnit + Mockito where the class under test only needs its constructor arguments, `@QuarkusComponentTest` where it needs CDI features (`Instance<>`, `@ConfigProperty`), and `@QuarkusTest` only for what needs the real app.

**Architecture:** Since b902806 every bean takes its dependencies through one constructor, and since d2599b9 only `store/` touches the database (ArchUnit-enforced); repositories return jOOQ-generated POJOs (`site.asm0dey.stillpress.db.tables.pojos`). So services, resources and commands can be built with `new` and mocked repositories. Each migrated test class keeps its test names and the behaviour it asserts; what only the container can prove (SQL, constraints, transactions, security annotations, filters, CSRF, routing, Qute, picocli/CDI wiring) stays in `@QuarkusTest` classes, trimmed to those cases.

**Tech Stack:** Java 25, Quarkus 3.40.1.2, JUnit 5, Mockito (`org.mockito:mockito-junit-jupiter`, version from the Quarkus BOM), `io.quarkus:quarkus-junit-component` (BOM-managed), AssertJ, ArchUnit 1.5.1, jOOQ 3.21.

**Spec:** none separate. Decisions this plan implements, recorded as precedents: "Only the store layer touches the database, enforced by ArchUnit"; "Repositories return jOOQ-generated Java-record POJOs". User rule (2026-11-15): "the most important goal is to not start quarkus context unless strictly necessary". Mocking library: Mockito, with its agent set in Surefire's `argLine`.

## Global Constraints

- SonarCloud quality gate stays green on `main` (new-code coverage ≥ 80 %, ratings A, duplication ≤ 3 %). Check `curl https://sonarcloud.io/api/qualitygates/project_status?projectKey=asm0dey_stillpress&branch=main` after the final push.
- Work on `main`, one commit per task; push only at the end, after the user says so.
- `mvn -B -o verify` passes after every task, from fresh classes: delete `target/classes` and `target/test-classes` first. Never `mvn clean`: it deletes the generated jOOQ sources, which only `-Pcodegen` (Docker) regenerates.
- No test is deleted without its behaviour being asserted elsewhere: a migrated case either stays in the unit test or moves to a repository/HTTP test. Each task's commit body lists any test that changed class.
- No reflection in tests (no `setAccessible`, no reflective factories).
- Plain unit tests never start Quarkus: no `@QuarkusTest`, `@QuarkusComponentTest`, `@Inject` or `Arc` in them.
- JDK imports are JEP 511 module imports (`import module java.base;`). Prince of Space via `mvn -q spotless:apply` before each commit.
- Mockito stubs only what a real repository can return; a stubbed exception is one the repository declares or a repository test shows it throws.
- JaCoCo: plain unit tests must still land in `target/jacoco-report/jacoco.xml` (the `jacoco-maven-plugin` `prepare-agent` already sets `argLine`; extend it, don't replace it).

## Review Focus

1. **Authorization lost in the move.** A resource method called directly skips `@Authenticated`/`@RolesAllowed`. Every endpoint keeps one HTTP test for anonymous → 401 and one for the wrong role → 403. Owner: Tasks 4 and 5, step "authorization matrix".
2. **Database-only behaviour.** Cases like "duplicate email is 409" used to be proven by a real unique index. The unit test proves `AuthorRepository.Taken` maps to 409, and a repository test proves the index throws `Taken`. Owner: Task 5, `AuthorRepositoryTest#duplicateEmailDifferentCaseThrowsTaken`.
3. **Transactions.** Rollback cases ("invite mail failure keeps the user", "refused demotion leaves import open") depend on `@Transactional`/`QuarkusTransaction` and stay `@QuarkusTest`. Owner: Task 5, keep-list.
4. **Optimistic concurrency.** `ItemRepository.update` compares `modifiedAt` under a row lock. The unit test proves the 409 mapping; an `ItemRepositoryTest` case proves a stale `expectedModifiedAt` is refused. Owner: Task 4, `ItemRepositoryTest#staleExpectedModifiedAtIsRefused`.
5. **Coverage accounting.** If the Mockito agent replaces JaCoCo's `argLine`, unit tests vanish from coverage and the Sonar gate drops. Owner: Task 1, step "coverage check".

---

### Task 1: Test infrastructure and pilot (`RevisionsTest`)

**Files:**
- Modify: `pom.xml` (test dependencies; Surefire `argLine`; `maven-dependency-plugin` `properties` goal)
- Create: `src/test/java/site/asm0dey/stillpress/TestIdentities.java`
- Modify: `src/test/java/site/asm0dey/stillpress/content/RevisionsTest.java`

**Interfaces:**
- Produces: `TestIdentities.user(long id, String login, Role role) -> SecurityIdentity` and `TestIdentities.anonymous() -> SecurityIdentity`, built with `QuarkusSecurityIdentity.builder()` exactly as `SessionIdentityProvider` does (principal `QuarkusPrincipal(login)`, role `role.name`, attribute `SessionIdentityProvider.USER_ID` = id, `ENROLL_REQUIRED` = false). No mocks.
- Produces: the Mockito setup every later task uses. Annotate the class with `@ExtendWith(MockitoExtension.class)`, put `@Mock` on fields, and build the class under test with `new` in `@BeforeEach`.

- [ ] **Step 1: Add dependencies.** In `pom.xml`, add test-scoped `org.mockito:mockito-junit-jupiter` and `io.quarkus:quarkus-junit-component`, both without a version (the BOM manages them). Check with `mvn -B dependency:tree -Dincludes=org.mockito,io.quarkus:quarkus-junit-component`: both resolve, and there is one `mockito-core` version.
- [ ] **Step 2: Mockito agent.** Add a `maven-dependency-plugin` execution of goal `properties` (phase `initialize`). Set Surefire `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>`.
- [ ] **Step 3: Rewrite `RevisionsTest` as a plain Mockito test.** Keep its 3 test names. Mock `RevisionRepository` and whatever else `Revisions`' constructor takes. Each test stubs the revisions the old test created in the database, as `Revision` POJOs, and asserts the same results. Delete `@QuarkusTest`, `@Inject` and `TestStore`.
- [ ] **Step 4: Run.** `mvn -B -o test -Dtest=RevisionsTest`. Expected: 3 pass; no `Quarkus` startup line and no `A Java agent has been loaded dynamically` warning in the output.
- [ ] **Step 5: Coverage check.** Delete `target/classes` and `target/test-classes`, then run `mvn -B -o verify`. Expected: all tests pass, and `target/jacoco-report/jacoco.xml` reports `site/asm0dey/stillpress/content/Revisions` with covered lines > 0.
- [ ] **Step 6: Commit.** `test: Mockito and component tests; RevisionsTest without Quarkus`.

### Task 2: Services and helpers to plain Mockito

**Files:** `src/test/java/site/asm0dey/stillpress/` → `mail/ReviewNotificationsTest`, `content/ReusableBlockServiceTest`, `content/MediaServiceTest`, `account/TotpTest`, `account/CaptchaTest`, `account/SecretsTest`, `admin/CsrfSignatureTest`, `mail/SiteMailTest`.

**Interfaces:**
- Consumes: Task 1's Mockito setup.
- Use real objects for: `DataDir`, pointed at a JUnit `@TempDir` (it has a plain constructor; read its signature). Use `Secrets` constructed from a test keyset where it is pure crypto (`Keys`); otherwise mock it.
- `SiteMail`: mock `io.quarkus.mailer.Mailer` / `Event<Mail>` as its constructor takes them.

- [ ] **Step 1: Migrate the tests, one class at a time.** Keep the test names. Each class: `@ExtendWith(MockitoExtension.class)`, constructor with mocks, stubs that match what the repository really returns. A case whose point is a database constraint or SQL moves into the matching `store/*RepositoryTest` with the same assertion; list those cases in the commit body.
- [ ] **Step 2: Mixed classes.** For any class here whose cases need the container (e.g. `SiteMailTest` cases that read the real `MockMailbox` delivery, or `ConcurrentCreatesTest` concurrency), move those cases to a `@QuarkusTest` class `<Name>QuarkusTest`; the plain cases keep `<Name>Test`. Note the split in the commit body.
- [ ] **Step 3: Run.** `mvn -B -o test -Dtest='ReviewNotificationsTest,ReusableBlockService*Test,MediaService*Test,Totp*Test,Captcha*Test,Secrets*Test,CsrfSignature*Test,SiteMail*Test'`. Expected: the same number of test cases as before the task, all passing.
- [ ] **Step 4: Verify and commit.** Do a fresh-classes `verify` (see Global Constraints), then commit: `test: services tested with Mockito, without Quarkus`.

### Task 3: Component tests for CDI-dependent beans

**Files:** `blocks/BlockRendererTest` (`Instance<DynamicBlockRenderer>`), `config/DataDirTest` (`@ConfigProperty`), `publish/views/ViewFactorySanitizeTest` (only if `ViewFactory` doesn't need Qute; otherwise leave it).

**Interfaces:** `@QuarkusComponentTest` (from `io.quarkus:quarkus-junit-component`); collaborators are mocked with `@InjectMock`; configuration with `@TestConfigProperty(key, value)`. Read the API from the jar's annotations, since the project's docs search doesn't cover it.

- [ ] **Step 1: Migrate.** Keep the test names. List the real beans the test needs in `@QuarkusComponentTest(value = {...})`. Everything else gets auto-mocked or `@InjectMock`.
- [ ] **Step 2: Qute stays.** If the class under test needs the Qute `Engine`, ArC alone can't provide it, so leave that class on `@QuarkusTest` and say so in the commit body. `TemplatesTest` and `HtmlPolicyPropertyTest` stay.
- [ ] **Step 3: Run, verify and commit.** Run `mvn -B -o test -Dtest='BlockRendererTest,DataDirTest,ViewFactorySanitizeTest'`, then the fresh-classes `verify`. Commit: `test: component tests where CDI is needed`.

### Task 4: Content resources: logic to Mockito, HTTP kept thin

**Files:** `admin/ItemsResourceTest` (22 tests), `ReusableBlocksResourceTest` (12), `TermsResourceTest` (8), `RedirectsResourceTest` (6), `MediaResourceTest` (12), `PreviewResourceTest` (3); `store/ItemRepositoryTest` (create it if missing).

**Interfaces:**
- Consumes: `TestIdentities.user(...)` and `anonymous()` from Task 1.
- Naming: `<Resource>Test` becomes the plain Mockito test of the resource class, built with mocks and a `TestIdentities` identity. `<Resource>QuarkusTest` is the `@QuarkusTest` remainder.

- [ ] **Step 1: Unit tests.** For each resource, move every case that asserts validation, a status code, a response body or what the resource calls into `<Resource>Test`. These tests call the resource method directly with a `TestIdentities` identity. Status codes come from the returned `Response`, or from the `@ServerExceptionMapper` methods, which are called directly on the thrown exception.
- [ ] **Step 2: Authorization matrix.** In each `<Resource>QuarkusTest`, every endpoint gets an anonymous → 401 case and a wrong-role → 403 case, plus one happy-path round trip per endpoint that checks JSON serialization. CSRF-protected writes get one missing-token → 400 case per resource.
- [ ] **Step 3: Repository cases.** Optimistic-concurrency refusal goes in `ItemRepositoryTest#staleExpectedModifiedAtIsRefused` and in the matching reusable-block repository test. Permalink and slug uniqueness go in `ItemRepositoryTest#slugTakenSeesOtherItemsOnly`. These cases run against the database.
- [ ] **Step 4: Run.** `mvn -B -o test -Dtest='*ResourceTest,*ResourceQuarkusTest,*RepositoryTest'`. Expected: all pass. The commit body gives the before and after case counts for each resource.
- [ ] **Step 5: Verify and commit.** Do the fresh-classes `verify`, then commit: `test: content resources tested without Quarkus; HTTP tests keep auth and wiring`.

### Task 5: Account and site resources: same split

**Files:** `admin/UsersResourceTest` (29), `SettingsResourceTest` (13), `SetupResourceTest` (23), `CaptchaResourceTest` (9), `MailResourceTest` (9), `TwoFactorResourceTest` (6), `ResetResourceTest` (7), `PublishResourceTest` (3), `ImportResourceTest` (11); `store/AuthorRepositoryTest`.

**Interfaces:** same as Task 4.

- [ ] **Step 1: Unit tests.** Apply Task 4's Step 1 to these resources.
- [ ] **Step 2: Authorization matrix.** Apply Task 4's Step 2 to these resources.
- [ ] **Step 3: Keep-list.** These cases stay in `<Resource>QuarkusTest` because they depend on transactions or the container:
  - `UsersResourceTest#inviteMailFailureKeepsTheUser`, `#refusedDemotionLeavesImportOpen`, `#invalidProfileFieldsAre400AndLeaveImportOpen`, `#concurrentSameLoginGivesOne201AndOtherwise409`, `#concurrentCreatesSameEmailDifferentCase`.
  - The `SetupResourceTest` cases that check the setup token printed at startup.
  - `ResetResourceTest` cases that check files under `DataDir` after a real reset.
- [ ] **Step 4: Constraint case.** Add `AuthorRepositoryTest#duplicateEmailDifferentCaseThrowsTaken`, which runs against the database. The unit test in `UsersResourceTest` stubs `Taken` and asserts 409 `email_taken`.
- [ ] **Step 5: Run, verify and commit.** Run as in Task 4, then the fresh-classes `verify`. Commit: `test: account and site resources tested without Quarkus`.

### Task 6: Commands

**Files:** `account/UserCommandTest` (6), `account/SecretsCommandTest` (3), `ResetCommandTest` (3), `importer/ImportCommandTest` (8), `account/CaptchaCommandTest` (2).

**Interfaces:**
- Leaf commands: `new CommandLine(new XCommand(mocks...))`, with output captured through `setOut`/`setErr` on a `StringWriter`.
- Commands with subcommands: register each subcommand instance explicitly with `commandLine.addSubcommand(name, new Sub(mocks...))`. Use no factory.

- [ ] **Step 1: Migrate.** Move every case that asserts parsing, exit codes, output text or the calls made into the plain test. Cases that need the real keyset guard or `Main` wiring stay `@QuarkusTest`. `KeysetGuardTest` and `StillpressCommandTest` stay whole.
- [ ] **Step 2: Run, verify and commit.** Run `mvn -B -o test -Dtest='*CommandTest'`, then the fresh-classes `verify`. Commit: `test: commands tested without Quarkus`.

### Task 7: Measure and record

**Files:** `docs/superpowers/plans/2026-11-15-stillpress-unit-tests.md` (results section), memory `no-quarkus-context-in-tests`.

- [ ] **Step 1: Count.** Run `grep -rlE "@QuarkusTest|@QuarkusMainTest" src/test/java | wc -l` and `grep -rl "@QuarkusComponentTest" src/test/java | wc -l`. The baseline before Task 1 is 71 Quarkus-annotated classes and 0 component tests. Also note the plain test class count, and the surefire total time from `verify`, before and after.
- [ ] **Step 2: Write the results** into this file's Results section. The table gives each remaining `@QuarkusTest` class and the reason it stays: SQL, transactions, security, routing, Qute, or wiring.
- [ ] **Step 3: Verify, commit, then ask the user to push.** Do the fresh-classes `verify`, commit with `docs: unit-test migration results`, then ask the user before pushing. After the push, check the Sonar gate.

## Stays on `@QuarkusTest` (no task touches these)

- **Database:** `store/*RepositoryTest`, `pg/Postgres*`, `SchemaTest`, `PublishQueueTest`, `PublishLockTest`, `ImportGuardTest`, `ConcurrentCreatesTest`.
- **Publishing and import, end to end:** `SitePublisherTest`, `IncrementalPublishTest`, `PublishJobsTest`, `JobsTest`, `PublishCostBench`, `WordPressImporterTest`, `SiteResetTest`, `FromScratchBlogTest`, `HtmlCompositionTest`.
- **Security, filters and routing:** `SessionAuthTest`, `AuthResourceTest`, `TwoStepLoginTest`, `PasskeyLoginTest`, `PasskeyRegistrationTest`, `PasswordResetTest`, `MandatoryTwoFactorTest`, `CaptchaEnforcementTest`, `AdminBodyLimitTest`, `AdminShellTest`, `StaticSiteRouteTest`, `NginxModeTwoTest`, `ImportClosedTest`, `ImportMediaLimitTest`.
- **Qute:** `TemplatesTest`, `HtmlPolicyPropertyTest`.
- **Wiring:** `KeysetGuardTest`, `StillpressCommandTest`.

## Results

Measured on `main` after Task 6 (baseline `396dc1c`).

| | Before (`396dc1c`) | After |
|---|---|---|
| Classes with `@QuarkusTest` / `@QuarkusMainTest` | 71 | 67 |
| Classes with `@QuarkusComponentTest` | 0 | 0 |
| Plain JUnit/Mockito test classes (has a test annotation, no Quarkus annotation) | 29 | 63 |
| Test files under `src/test/java` | 110 | 144 |
| Surefire tests run | not measured | 955 (0 failures, 0 errors, 0 skipped) |
| Surefire time, sum of per-class "Time elapsed" | not measured (no CI run found for the commit) | 132 s |
| `mvn -B -o verify` wall time, fresh classes | not measured | 3:05 min |

No component tests were written: plain JUnit sufficed, so the `quarkus-junit-component` dependency in `pom.xml` is unused. It was left in place.

Most migrated classes keep their HTTP, authorization and database cases on Quarkus; the logic cases moved to new plain tests.

### Classes that stay on Quarkus

| Class | Reason |
|---|---|
| `AdminBodyLimitTest` | security |
| `AdminShellTest` | routing |
| `AuthResourceTest` | security |
| `AuthorRepositoryTest` | SQL |
| `CaptchaCommandQuarkusTest` | wiring |
| `CaptchaEnforcementTest` | security |
| `CaptchaQuarkusTest` | security |
| `CaptchaResourceQuarkusTest` | security |
| `ConcurrentCreatesTest` | transactions |
| `CsrfSignatureQuarkusTest` | security |
| `FromScratchBlogTest` | SQL |
| `HtmlCompositionTest` | Qute |
| `HtmlPolicyPropertyTest` | Qute |
| `ImportClosedTest` | security |
| `ImportCommandQuarkusTest` | wiring |
| `ImportGuardTest` | SQL |
| `ImportMediaLimitTest` | security |
| `ImportResourceQuarkusTest` | security |
| `IncrementalPublishTest` | SQL |
| `ItemRepositoryTest` | SQL |
| `ItemsResourceQuarkusTest` | transactions |
| `JobsTest` | transactions |
| `KeysetGuardTest` | wiring |
| `MailResourceQuarkusTest` | security |
| `MandatoryTwoFactorTest` | security |
| `MediaResourceQuarkusTest` | security |
| `MediaServiceQuarkusTest` | SQL |
| `NginxModeTwoTest` | routing |
| `PasskeyLoginTest` | security |
| `PasskeyRegistrationTest` | security |
| `PasswordResetTest` | security |
| `PostgresAccountsTest` | SQL |
| `PostgresImportGuardTest` | SQL |
| `PostgresImportPublishTest` | SQL |
| `PostgresJobsTest` | transactions |
| `PreviewResourceQuarkusTest` | routing |
| `PublishCostBench` | SQL |
| `PublishJobsTest` | transactions |
| `PublishLockTest` | transactions |
| `PublishQueueTest` | SQL |
| `PublishResourceQuarkusTest` | security |
| `RedirectsResourceQuarkusTest` | security |
| `ResetCommandQuarkusTest` | wiring |
| `ResetResourceQuarkusTest` | security |
| `ReusableBlockRepositoryTest` | SQL |
| `ReusableBlocksResourceQuarkusTest` | security |
| `ReviewNotificationsQuarkusTest` | SQL |
| `RevisionRepositoryTest` | SQL |
| `SchemaTest` | SQL |
| `SecretsCommandQuarkusTest` | wiring |
| `SessionAuthTest` | security |
| `SettingsResourceQuarkusTest` | security |
| `SetupResourceQuarkusTest` | security |
| `SiteMailQuarkusTest` | transactions |
| `SitePublisherTest` | SQL |
| `SiteResetTest` | transactions |
| `StaticSiteRouteTest` | routing |
| `StillpressCommandTest` | wiring |
| `TemplatesTest` | Qute |
| `TermsResourceQuarkusTest` | security |
| `TokenRepositoryTest` | SQL |
| `TwoFactorRepositoryTest` | SQL |
| `TwoFactorResourceQuarkusTest` | security |
| `TwoStepLoginTest` | security |
| `UserCommandQuarkusTest` | wiring |
| `UsersResourceQuarkusTest` | security |
| `WordPressImporterTest` | SQL |
