package net.rabbitware.config.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real Gradle builds, run with TestKit against small projects on disk.
 *
 * <p>Each project resolves the analyzer and the library from the local Maven
 * repository, where {@code mvn install} in the parent directory puts them - so
 * these test the plugin with the analyzer it will actually ship with.
 */
class RwconfigPluginFunctionalTest {

    private static final String VERSION = System.getProperty("rwconfig.version");

    /** Where the build found a Java 21. */
    private static final String JAVA_21 = System.getProperty("rwconfig.java21.home");

    /** The oldest Gradle this plugin supports - the first that runs on Java 21. */
    private static final String OLDEST_GRADLE = "8.5";

    @TempDir
    private Path project;

    /** A project that reads `port` correctly, with the plugin applied. */
    @BeforeEach
    void aCorrectProject() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"app\"\n");
        buildScript("");
        write("src/main/resources/rwconfig", "int port = 8080\n");
        source("App", "config.getInt(\"port\")");
    }

    // ------------------------------------------------------------------ helpers

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void buildScript(String extra) throws IOException {
        write("build.gradle.kts", """
            plugins {
                java
                id("net.rabbitware.rwconfig")
            }
            repositories {
                mavenLocal()
                mavenCentral()
            }
            dependencies {
                implementation("net.rabbitware.config:config:%s")
            }
            %s
            """.formatted(VERSION, extra));
    }

    /** A class whose one method makes the given read. */
    private void source(String name, String read) throws IOException {
        source("src/main/java", name, read);
    }

    private void source(String root, String name, String read) throws IOException {
        write(root + "/app/" + name + ".java", """
            package app;

            import net.rabbitware.config.Config;

            public class %s {
                Object read(Config config) {
                    return %s;
                }
            }
            """.formatted(name, read));
    }

    /** A build of the project - one where any deprecated use of Gradle fails it. */
    private GradleRunner runner(String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--warning-mode=fail");
        return GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments(all);
    }

    private BuildResult succeeds(String... arguments) {
        return runner(arguments).build();
    }

    private BuildResult fails(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    private static TaskOutcome outcome(BuildResult result, String task) {
        return result.task(task) == null ? null : result.task(task).getOutcome();
    }

    // -------------------------------------------------------------- the check

    @Nested
    @DisplayName("what the check reports")
    class Reports {

        @Test
        void aCorrectProjectPasses() {
            BuildResult result = succeeds("rwconfigCheck");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
            assertFalse(result.getOutput().contains("error:"), result.getOutput());
        }

        @Test
        @DisplayName("a misspelled name fails the build, pointing at the call with the nearest declared name")
        void aMisspelledName() throws IOException {
            source("App", "config.getInt(\"prot\")");
            BuildResult result = fails("rwconfigCheck");
            assertEquals(TaskOutcome.FAILED, outcome(result, ":rwconfigCheck"));
            String output = result.getOutput();
            // line 7 of the class written above, as file:line:column: severity: message [rule]
            assertTrue(output.contains("App.java:7:"), output);
            assertTrue(output.contains(
                "error: property `prot` is not declared in rwconfig - did you mean `port`? [unknown-property]"), output);
            assertTrue(output.contains("1 problem found checking the code against"), output);
        }

        @Test
        @DisplayName("the wrong getter for the declared type fails the build, naming the one that works")
        void theWrongGetter() throws IOException {
            source("App", "config.getString(\"port\")");
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("use `getInt` instead [wrong-type]"), output);
        }

        @Test
        @DisplayName("the analyzer that ships with the plugin knows the big number types")
        void bigNumberGetters() throws IOException {
            write("src/main/resources/rwconfig", "bigDecimal price = 9.99\n");
            source("App", "config.getBigDecimal(\"price\")");
            succeeds("rwconfigCheck");
            source("App", "config.getString(\"price\")");
            assertTrue(fails("rwconfigCheck").getOutput().contains("use `getBigDecimal` instead"));
        }

        @Test
        @DisplayName("every problem is reported, not only the first")
        void everyProblem() throws IOException {
            source("App", "config.getInt(\"prot\")");
            source("Other", "config.getString(\"port\")");
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("[unknown-property]"), output);
            assertTrue(output.contains("[wrong-type]"), output);
            assertTrue(output.contains("2 problems found"), output);
        }

        @Test
        @DisplayName("an unread property is shown without being asked for, and does not fail the build")
        void unreadProperties() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            String output = succeeds("rwconfigCheck").getOutput();
            assertTrue(output.contains("info: property `unused`"), output);
            assertTrue(output.contains("[unread-property]"), output);
        }

        @Test
        @DisplayName("an rwconfig file that cannot be read fails the build, naming the line")
        void anUnreadableFile() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nint[ broken\n");
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("rwconfig"), output);
            assertTrue(output.contains("error:"), output);
            assertTrue(output.contains("int[ broken"), output);
        }

        @Test
        @DisplayName("what was reported is kept in build/reports/rwconfig/findings.txt")
        void theReportFile() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            succeeds("rwconfigCheck");
            List<String> report = Files.readAllLines(project.resolve("build/reports/rwconfig/findings.txt"));
            assertEquals(1, report.size(), report.toString());
            assertTrue(report.get(0).contains("[unread-property]"), report.toString());
        }
    }

    // ---------------------------------------------------------- the settings

    @Nested
    @DisplayName("the settings, in the rwconfig block and as Gradle properties")
    class Settings {

        @Test
        void failOnErrorFalseReportsButPasses() throws IOException {
            buildScript("rwconfig { failOnError = false }");
            source("App", "config.getInt(\"prot\")");
            BuildResult result = succeeds("rwconfigCheck");
            assertTrue(result.getOutput().contains("[unknown-property]"), result.getOutput());
        }

        @Test
        void failOnErrorAsAGradleProperty() throws IOException {
            source("App", "config.getInt(\"prot\")");
            succeeds("rwconfigCheck", "-Prwconfig.failOnError=false");
        }

        @Test
        void reportUnreadFalse() throws IOException {
            buildScript("rwconfig { reportUnread = false }");
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            assertFalse(succeeds("rwconfigCheck").getOutput().contains("[unread-property]"));
        }

        @Test
        void reportUnreadAsAGradleProperty() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            assertFalse(succeeds("rwconfigCheck", "-Prwconfig.reportUnread=false").getOutput()
                .contains("[unread-property]"));
        }

        @Test
        void skipRules() throws IOException {
            buildScript("rwconfig { skipRules.add(\"unknown-property\") }");
            source("App", "config.getInt(\"prot\")");
            assertFalse(succeeds("rwconfigCheck").getOutput().contains("[unknown-property]"));
        }

        @Test
        @DisplayName("skipRules as a Gradle property takes a comma-separated list")
        void skipRulesAsAGradleProperty() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            source("App", "config.getInt(\"prot\")");
            String output = succeeds("rwconfigCheck", "-Prwconfig.skipRules=unknown-property, unread-property")
                .getOutput();
            assertFalse(output.contains("[unknown-property]"), output);
            assertFalse(output.contains("[unread-property]"), output);
        }

        @Test
        void skip() throws IOException {
            buildScript("rwconfig { skip = true }");
            source("App", "config.getInt(\"prot\")");
            assertEquals(TaskOutcome.SKIPPED, outcome(succeeds("rwconfigCheck"), ":rwconfigCheck"));
        }

        @Test
        void skipAsAGradleProperty() throws IOException {
            source("App", "config.getInt(\"prot\")");
            assertEquals(TaskOutcome.SKIPPED,
                outcome(succeeds("rwconfigCheck", "-Prwconfig.skip=true"), ":rwconfigCheck"));
        }

        @Test
        @DisplayName("the rwconfig block overrides a Gradle property")
        void theBlockWins() throws IOException {
            buildScript("rwconfig { failOnError = true }");
            source("App", "config.getInt(\"prot\")");
            fails("rwconfigCheck", "-Prwconfig.failOnError=false");
        }
    }

    // ------------------------------------------------------ finding the file

    @Nested
    @DisplayName("finding the rwconfig file")
    class Location {

        @Test
        @DisplayName("a project with no rwconfig file is left alone")
        void noFile() throws IOException {
            Files.delete(project.resolve("src/main/resources/rwconfig"));
            source("App", "config.getInt(\"anything\")");
            BuildResult result = succeeds("rwconfigCheck");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
            assertFalse(result.getOutput().contains("error:"), result.getOutput());
        }

        @Test
        @DisplayName("an rwconfig file in the project directory is found")
        void inTheProjectDirectory() throws IOException {
            Files.delete(project.resolve("src/main/resources/rwconfig"));
            write("rwconfig", "int port = 8080\n");
            succeeds("rwconfigCheck");
            source("App", "config.getInt(\"prot\")");
            fails("rwconfigCheck");
        }

        @Test
        @DisplayName("src/main/resources/rwconfig comes before one in the project directory")
        void resourcesFirst() throws IOException {
            write("rwconfig", "int other = 1\n");
            // reads `port`, which only the resources file declares
            BuildResult result = succeeds("rwconfigCheck");
            assertFalse(result.getOutput().contains("[unknown-property]"), result.getOutput());
        }

        @Test
        @DisplayName("a file can be named, and is then the only one looked for")
        void named() throws IOException {
            buildScript("rwconfig { file = layout.projectDirectory.file(\"config/app.rwconfig\") }");
            write("config/app.rwconfig", "int prot = 1\n");
            // `port` is declared only in the default file, which is now ignored
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("not declared in app.rwconfig - did you mean `prot`?"), output);
        }

        @Test
        @DisplayName("an rwconfig file added after a check is noticed")
        void addedLater() throws IOException {
            Files.delete(project.resolve("src/main/resources/rwconfig"));
            source("App", "config.getInt(\"prot\")");
            succeeds("rwconfigCheck");
            write("src/main/resources/rwconfig", "int port = 8080\n");
            assertEquals(TaskOutcome.FAILED, outcome(fails("rwconfigCheck"), ":rwconfigCheck"));
        }
    }

    // ------------------------------------------------- where it fits in a build

    @Nested
    @DisplayName("where the check runs")
    class Wiring {

        @Test
        @DisplayName("before compileJava - a misread property stops the compile")
        void beforeCompileJava() throws IOException {
            BuildResult result = succeeds("compileJava");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
            assertTrue(result.getTasks().indexOf(result.task(":rwconfigCheck"))
                < result.getTasks().indexOf(result.task(":compileJava")));

            source("App", "config.getInt(\"prot\")");
            BuildResult failed = fails("compileJava");
            assertEquals(TaskOutcome.FAILED, outcome(failed, ":rwconfigCheck"));
            assertNull(failed.task(":compileJava"), "compileJava should not have run");
        }

        @Test
        @DisplayName("as part of check, even with compileJava left out")
        void asPartOfCheck() {
            // check reaches compileJava through test, so leave it out to see
            // that check asks for rwconfigCheck itself
            BuildResult result = succeeds("check", "-x", "compileJava");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
            assertNull(result.task(":compileJava"));
        }

        @Test
        @DisplayName("sources in an extra directory are checked too")
        void extraSourceDirectory() throws IOException {
            buildScript("sourceSets.main { java.srcDir(\"src/extra/java\") }");
            source("src/extra/java", "Extra", "config.getInt(\"prot\")");
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("Extra.java:7:"), output);
        }

        @Test
        @DisplayName("a project without the java plugin gets no task, and nothing breaks")
        void withoutTheJavaPlugin() throws IOException {
            write("build.gradle.kts", "plugins { id(\"net.rabbitware.rwconfig\") }\n");
            BuildResult result = succeeds("tasks", "--all");
            assertFalse(result.getOutput().contains("rwconfigCheck"), result.getOutput());
        }

        @Test
        @DisplayName("the java plugin applied after this one is still picked up")
        void javaAppliedAfter() throws IOException {
            write("build.gradle.kts", """
                plugins { id("net.rabbitware.rwconfig") }
                apply(plugin = "java")
                repositories { mavenLocal(); mavenCentral() }
                dependencies { "implementation"("net.rabbitware.config:config:%s") }
                """.formatted(VERSION));
            assertEquals(TaskOutcome.SUCCESS, outcome(succeeds("rwconfigCheck"), ":rwconfigCheck"));
        }

        @Test
        @DisplayName("each project in a multi-project build is checked against its own file")
        void multiProject() throws IOException {
            write("settings.gradle.kts", "rootProject.name = \"root\"\ninclude(\"good\", \"bad\")\n");
            write("build.gradle.kts", "");
            for (String sub : List.of("good", "bad")) {
                write(sub + "/build.gradle.kts", """
                    plugins { java; id("net.rabbitware.rwconfig") }
                    repositories { mavenLocal(); mavenCentral() }
                    dependencies { implementation("net.rabbitware.config:config:%s") }
                    """.formatted(VERSION));
                write(sub + "/src/main/resources/rwconfig", "int port = 8080\n");
            }
            source("good/src/main/java", "App", "config.getInt(\"port\")");
            source("bad/src/main/java", "App", "config.getInt(\"prot\")");
            BuildResult result = fails("check", "--continue");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":good:rwconfigCheck"));
            assertEquals(TaskOutcome.FAILED, outcome(result, ":bad:rwconfigCheck"));
        }

        @Test
        @DisplayName("a Groovy build script works the same")
        void groovyDsl() throws IOException {
            Files.delete(project.resolve("build.gradle.kts"));
            write("build.gradle", """
                plugins {
                    id 'java'
                    id 'net.rabbitware.rwconfig'
                }
                repositories { mavenLocal(); mavenCentral() }
                dependencies { implementation 'net.rabbitware.config:config:%s' }
                rwconfig { skipRules = ['unread-property'] }
                """.formatted(VERSION));
            succeeds("rwconfigCheck");
            source("App", "config.getInt(\"prot\")");
            fails("rwconfigCheck");
        }

        @Test
        @DisplayName("the analyzer can be replaced through the rwconfig configuration")
        void replacingTheAnalyzer() throws IOException {
            buildScript("dependencies { rwconfig(\"net.rabbitware.config:rwconfig-analyzer:9.9.9\") }");
            // a version that does not exist proves the default was replaced
            String output = fails("rwconfigCheck").getOutput();
            assertTrue(output.contains("rwconfig-analyzer:9.9.9"), output);
        }
    }

    // ---------------------------------------------------- incremental builds

    @Nested
    @DisplayName("Gradle's incremental build")
    class Incremental {

        @Test
        @DisplayName("an unchanged project is up to date, and a changed source or rwconfig runs it again")
        void upToDate() throws IOException {
            assertEquals(TaskOutcome.SUCCESS, outcome(succeeds("rwconfigCheck"), ":rwconfigCheck"));
            assertEquals(TaskOutcome.UP_TO_DATE, outcome(succeeds("rwconfigCheck"), ":rwconfigCheck"));

            source("App", "config.getInt(\"prot\")");
            assertEquals(TaskOutcome.FAILED, outcome(fails("rwconfigCheck"), ":rwconfigCheck"));

            write("src/main/resources/rwconfig", "int prot = 8080\n");
            assertEquals(TaskOutcome.SUCCESS, outcome(succeeds("rwconfigCheck"), ":rwconfigCheck"));
        }

        @Test
        @DisplayName("a failed check is never up to date - it fails again until fixed")
        void aFailureIsNotRemembered() throws IOException {
            source("App", "config.getInt(\"prot\")");
            fails("rwconfigCheck");
            assertEquals(TaskOutcome.FAILED, outcome(fails("rwconfigCheck"), ":rwconfigCheck"));
        }

        @Test
        @DisplayName("a changed setting runs it again")
        void changedSetting() throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            succeeds("rwconfigCheck");
            BuildResult result = succeeds("rwconfigCheck", "-Prwconfig.reportUnread=false");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
        }

        @Test
        @DisplayName("works with the configuration cache, and still sees a change once the cache is reused")
        void configurationCache() throws IOException {
            succeeds("rwconfigCheck", "--configuration-cache");
            BuildResult reused = succeeds("rwconfigCheck", "--configuration-cache");
            assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused.getOutput());

            source("App", "config.getInt(\"prot\")");
            BuildResult failed = fails("rwconfigCheck", "--configuration-cache");
            assertTrue(failed.getOutput().contains("Reusing configuration cache."), failed.getOutput());
            assertTrue(failed.getOutput().contains("[unknown-property]"), failed.getOutput());
        }
    }

    // ---------------------------------------------------------- environment

    @Nested
    @DisplayName("the JVM and the Gradle version")
    class Environment {

        @Test
        @DisplayName("the analyzer runs on the project's Java toolchain")
        void toolchain() throws IOException {
            buildScript("java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }");
            BuildResult result = succeeds("rwconfigCheck", "--info");
            assertEquals(TaskOutcome.SUCCESS, outcome(result, ":rwconfigCheck"));
            String command = result.getOutput().lines()
                .filter(line -> line.contains(RwconfigCheck.ANALYZER_MAIN) && line.contains("Starting process"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no analyzer command in: " + result.getOutput()));
            assertTrue(command.contains(JAVA_21), "expected the Java 21 in " + JAVA_21 + " in: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {OLDEST_GRADLE, "current"})
        @DisplayName("works on the oldest supported Gradle and the newest")
        void gradleVersions(String version) throws IOException {
            write("src/main/resources/rwconfig", "int port = 8080\nstring unused = x\n");
            // every setting, so each kind of value goes through the configuration cache
            buildScript("rwconfig { reportUnread = true; failOnError = true }");
            GradleRunner runner = runner("rwconfigCheck", "--configuration-cache",
                "-Prwconfig.skipRules=unread-property");
            if (!"current".equals(version)) {
                // Gradle 8.5 runs on nothing newer than Java 21
                write("gradle.properties", "org.gradle.java.home=" + JAVA_21.replace("\\", "\\\\") + "\n");
                runner = runner.withGradleVersion(version);
            }
            BuildResult passed = runner.build();
            assertEquals(TaskOutcome.SUCCESS, outcome(passed, ":rwconfigCheck"), passed.getOutput());
            assertFalse(passed.getOutput().contains("[unread-property]"), passed.getOutput());

            source("App", "config.getInt(\"prot\")");
            BuildResult failed = runner.buildAndFail();
            assertEquals(TaskOutcome.FAILED, outcome(failed, ":rwconfigCheck"));
            assertTrue(failed.getOutput().contains("did you mean `port`?"), failed.getOutput());
        }
    }
}
