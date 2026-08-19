package net.rabbitware.config.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for checking Java sources against an `rwconfig` file. */
class RwconfigAnalyzerTest {

    @TempDir
    private Path tempDir;

    private static final String DECLARATIONS = """
        rwc.sources = args
        rwc.args.type = commandLineArguments
        int port = 8000
        string dbPassword
        duration timeout = 30s
        stringList hosts = a, b
        """;

    /** Analyze one Java source against the declarations above. */
    private List<Finding> analyze(String java) throws IOException {
        return analyze(DECLARATIONS, java);
    }

    private List<Finding> analyze(String declarations, String... java) throws IOException {
        Path config = tempDir.resolve("rwconfig");
        Files.writeString(config, declarations);
        Path sources = Files.createDirectories(tempDir.resolve("src"));
        for (int i = 0; i < java.length; i++) {
            Files.writeString(sources.resolve(i == 0 ? "App.java" : "Other" + i + ".java"), java[i]);
        }
        return new RwconfigAnalyzer("file:" + config, "rwconfig")
            .analyze(RwconfigAnalyzer.javaSourcesIn(List.of(sources)));
    }

    private List<Finding> of(List<Finding> findings, Finding.Rule rule) {
        return findings.stream().filter(f -> f.rule() == rule).toList();
    }

    @Test
    @DisplayName("a correct read is not reported")
    void correctReadsAreQuiet() throws IOException {
        List<Finding> findings = analyze("""
            class App {
                void m(net.rabbitware.config.Config c) {
                    c.getInt("port");
                    c.getString("dbPassword");
                    c.getLong("timeout");
                    c.getStringList("hosts");
                }
            }
            """);
        assertEquals(List.of(), of(findings, Finding.Rule.UNKNOWN_PROPERTY));
        assertEquals(List.of(), of(findings, Finding.Rule.WRONG_TYPE));
    }

    @Test
    @DisplayName("a property that is not declared is an error, with the nearest name suggested")
    void unknownProperty() throws IOException {
        List<Finding> findings = of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getInt("prot"); } }
            """), Finding.Rule.UNKNOWN_PROPERTY);
        assertEquals(1, findings.size());
        assertEquals(Finding.Severity.ERROR, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("did you mean `port`"), findings.get(0).message());
        assertEquals(1, findings.get(0).line(), "the line the call is on");
    }

    @Test
    @DisplayName("a name too far from any declaration gets no suggestion")
    void noSuggestionWhenNothingIsClose() throws IOException {
        List<Finding> findings = of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getInt("somethingElseEntirely"); } }
            """), Finding.Rule.UNKNOWN_PROPERTY);
        assertEquals(1, findings.size());
        assertTrue(!findings.get(0).message().contains("did you mean"), findings.get(0).message());
    }

    @Test
    @DisplayName("reading a property as the wrong type names the getter that would work")
    void wrongType() throws IOException {
        List<Finding> findings = of(analyze("""
            class App {
                void m(net.rabbitware.config.Config c) {
                    c.getString("port");
                    c.getInt("hosts");
                }
            }
            """), Finding.Rule.WRONG_TYPE);
        assertEquals(2, findings.size());
        assertTrue(findings.get(0).message().contains("use `getInt`"), findings.get(0).message());
        assertTrue(findings.get(1).message().contains("use `getStringList`"), findings.get(1).message());
    }

    @Test
    @DisplayName("a `duration` is read with getLong, since that is its run-time type")
    void unitTypesAreCheckedAsLongs() throws IOException {
        assertEquals(List.of(), of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getLong("timeout"); } }
            """), Finding.Rule.WRONG_TYPE));
        assertEquals(1, of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getDouble("timeout"); } }
            """), Finding.Rule.WRONG_TYPE).size());
    }

    @Test
    @DisplayName("the short aliases are checked the same way")
    void shortAliases() throws IOException {
        assertEquals(List.of(), of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.geti("port"); } }
            """), Finding.Rule.WRONG_TYPE));
        assertEquals(1, of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.gets("port"); } }
            """), Finding.Rule.WRONG_TYPE).size());
    }

    @Test
    @DisplayName("a name that is not a literal cannot be checked, and is not guessed at")
    void computedNamesAreLeftAlone() throws IOException {
        List<Finding> findings = analyze("""
            class App {
                void m(net.rabbitware.config.Config c, String name) {
                    c.getInt(name);
                    c.getInt(NAMES[0]);
                }
                static final String[] NAMES = {"port"};
            }
            """);
        assertEquals(List.of(), of(findings, Finding.Rule.UNKNOWN_PROPERTY));
    }

    @Test
    @DisplayName("a constant expression is checked, because it is a constant")
    void constantConcatenationIsChecked() throws IOException {
        // javac folds `"pre" + "fix"` to `"prefix"` while parsing, and that is
        // the name the library would look up - so it is worth checking
        List<Finding> findings = of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getInt("pre" + "fix"); } }
            """), Finding.Rule.UNKNOWN_PROPERTY);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("`prefix`"), findings.get(0).message());
    }

    @Test
    @DisplayName("a name inside a comment or a string is not a call")
    void commentsAndStringsAreNotCalls() throws IOException {
        List<Finding> findings = analyze("""
            class App {
                // c.getInt("prot") in a comment
                String s = "c.getInt(\\"prot\\")";
            }
            """);
        assertEquals(List.of(), of(findings, Finding.Rule.UNKNOWN_PROPERTY));
    }

    @Test
    @DisplayName("`Instance.get()` with no `set` anywhere is an error")
    void instanceGetWithoutSet() throws IOException {
        List<Finding> findings = of(analyze("""
            class App { void m() { net.rabbitware.config.Config.Instance.get(); } }
            """), Finding.Rule.INSTANCE_GET_WITHOUT_SET);
        assertEquals(1, findings.size());
        assertEquals(Finding.Severity.ERROR, findings.get(0).severity());
    }

    @Test
    @DisplayName("`Instance.get()` is fine when something sets it")
    void instanceGetWithSet() throws IOException {
        assertEquals(List.of(), of(analyze("""
            class App {
                void setup(net.rabbitware.config.Config c) { net.rabbitware.config.Config.Instance.set(c); }
                void use() { net.rabbitware.config.Config.Instance.get(); }
            }
            """), Finding.Rule.INSTANCE_GET_WITHOUT_SET));
    }

    @Test
    @DisplayName("a command line source with only `create()` is a guaranteed startup failure")
    void commandLineSourceWithoutArgs() throws IOException {
        List<Finding> findings = of(analyze("""
            class App { void m() { net.rabbitware.config.ConfigFactory.create(); } }
            """), Finding.Rule.COMMAND_LINE_SOURCE_WITHOUT_ARGS);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("create(args)"), findings.get(0).message());
    }

    @Test
    @DisplayName("no such finding when `create(args)` is used, or when no command line source is declared")
    void commandLineSourceSatisfied() throws IOException {
        assertEquals(List.of(), of(analyze("""
            class App { void m(String[] a) { net.rabbitware.config.ConfigFactory.create(a); } }
            """), Finding.Rule.COMMAND_LINE_SOURCE_WITHOUT_ARGS));
        assertEquals(List.of(), of(analyze(
            "rwc.sources = sys\\nrwc.sys.type = systemProperties\\nint port = 1\\n", """
            class App { void m() { net.rabbitware.config.ConfigFactory.create(); } }
            """), Finding.Rule.COMMAND_LINE_SOURCE_WITHOUT_ARGS));
    }

    @Nested
    @DisplayName("a property named by a constant")
    class Constants {

        private static final String CONFIG = """
            int port = 8000
            string host = localhost
            """;

        @Test
        @DisplayName("is resolved, so the property counts as read")
        void constantInTheSameFile() throws IOException {
            List<Finding> findings = of(analyze(CONFIG, """
                class App {
                    static final String PORT = "port";
                    void m(net.rabbitware.config.Config c) { c.getInt(PORT); }
                }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(1, findings.size(), "`host` is unread, but `port` was read through PORT");
            assertTrue(findings.get(0).message().contains("`host`"), findings.get(0).message());
        }

        @Test
        @DisplayName("is resolved through the class that holds it, and across files")
        void constantInAnotherFile() throws IOException {
            List<Finding> findings = of(analyze(CONFIG, """
                class App {
                    void m(net.rabbitware.config.Config c) {
                        c.getInt(Props.PORT);
                        c.getString(Props.HOST);
                    }
                }
                """, """
                class Props {
                    static final String PORT = "port";
                    static final String HOST = "ho" + "st";
                }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(List.of(), findings, "both were read through constants in another file");
        }

        @Test
        @DisplayName("is checked like any other name - a wrong getter is still caught")
        void wrongTypeThroughAConstant() throws IOException {
            List<Finding> findings = of(analyze(CONFIG, """
                class App {
                    static final String PORT = "port";
                    void m(net.rabbitware.config.Config c) { c.getString(PORT); }
                }
                """), Finding.Rule.WRONG_TYPE);
            assertEquals(1, findings.size());
            assertTrue(findings.get(0).message().contains("`port`"), findings.get(0).message());
        }

        @Test
        @DisplayName("that names nothing declared is reported, pointing at the constant")
        void unknownThroughAConstant() throws IOException {
            List<Finding> findings = of(analyze(CONFIG, """
                class App {
                    static final String NOPE = "nope";
                    void m(net.rabbitware.config.Config c) { c.getInt(NOPE); }
                }
                """), Finding.Rule.UNKNOWN_PROPERTY);
            assertEquals(1, findings.size());
            Finding finding = findings.get(0);
            assertTrue(finding.message().contains("`nope`"), finding.message());
            assertEquals("NOPE".length(), finding.length(), "underlines the constant as written");
        }

        @Test
        @DisplayName("that is not actually constant stays unknowable")
        void nonFinalFieldIsNotAConstant() throws IOException {
            assertEquals(List.of(), of(analyze(CONFIG, """
                class App {
                    static String name = "port";
                    void m(net.rabbitware.config.Config c) { c.getInt(name); }
                }
                """), Finding.Rule.UNREAD_PROPERTY),
                "`name` can be reassigned, so the read could be of any property");
        }
    }


    @Nested
    @DisplayName("properties nothing reads")
    class Unread {

        private static final String CONFIG = """
            int port = 8000
            string host = localhost
            """;

        @Test
        @DisplayName("are reported when every read names a property outright")
        void literalReadsOnly() throws IOException {
            List<Finding> findings = of(analyze(CONFIG, """
                class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(1, findings.size());
            assertTrue(findings.get(0).message().contains("`host`"), findings.get(0).message());
        }

        @Test
        @DisplayName("are not reported when there were no sources to read")
        void noSourcesAtAll() throws IOException {
            Path config = tempDir.resolve("rwconfig");
            Files.writeString(config, CONFIG);
            List<Finding> findings = new RwconfigAnalyzer("file:" + config, "rwconfig")
                .analyze(List.of());
            assertEquals(List.of(), of(findings, Finding.Rule.UNREAD_PROPERTY),
                "nothing was scanned, so nothing can be said about what reads what");
        }

        @Test
        @DisplayName("are not reported when a read works its name out at run time")
        void computedNameSuppresses() throws IOException {
            assertEquals(List.of(), of(analyze(CONFIG, """
                class App {
                    void m(net.rabbitware.config.Config c) {
                        for (String name : c.getPropertyNames()) { c.getString(name); }
                    }
                }
                """), Finding.Rule.UNREAD_PROPERTY),
                "`c.getString(name)` could read anything, so nothing can be shown to be unread");
        }

        @Test
        @DisplayName("stay unprovable even when the computed read is in another file")
        void computedNameInAnotherFile() throws IOException {
            assertEquals(List.of(), of(analyze(CONFIG, """
                class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
                """, """
                class Dump {
                    void m(net.rabbitware.config.Config c, String n) { c.getString(n); }
                }
                """), Finding.Rule.UNREAD_PROPERTY));
        }

        @Test
        @DisplayName("a computed name does not mask the checks that do not depend on it")
        void otherChecksSurvive() throws IOException {
            List<Finding> findings = analyze(CONFIG, """
                class App {
                    void m(net.rabbitware.config.Config c, String n) {
                        c.getString(n);
                        c.getInt("nope");
                        c.getString("port");
                    }
                }
                """);
            assertEquals(1, of(findings, Finding.Rule.UNKNOWN_PROPERTY).size(), "`nope`");
            assertEquals(1, of(findings, Finding.Rule.WRONG_TYPE).size(), "`port` read as a string");
        }
    }


    @Nested
    @DisplayName("the sources declared in `rwc.sources`")
    class Sources {

        private static final String APP = """
            class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
            """;

        @Test
        @DisplayName("a source with no `type` is an error, underlined where it is named")
        void sourceWithoutType() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = args, orphan
                rwc.args.type = commandLineArguments
                int port = 8000
                """, APP), Finding.Rule.SOURCE_WITHOUT_TYPE);
            assertEquals(1, findings.size());
            Finding finding = findings.get(0);
            assertTrue(finding.message().contains("rwc.orphan.type"), finding.message());
            assertEquals(1, finding.line(), "the `sources` line");
            // `rwc.sources = args, orphan` - `orphan` starts at column 21
            assertEquals(21, finding.column());
            assertEquals("orphan".length(), finding.length());
        }

        @Test
        @DisplayName("a built-in type that needs a setting says which one")
        void builtInRequirements() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = f, d
                rwc.f.type = properties
                rwc.d.type = directory
                int port = 8000
                """, APP), Finding.Rule.SOURCE_MISSING_SETTING);
            assertEquals(2, findings.size());
            assertTrue(findings.get(0).message().contains("rwc.f.location"), findings.get(0).message());
            assertTrue(findings.get(1).message().contains("rwc.d.path"), findings.get(1).message());
        }

        @Test
        @DisplayName("a source with everything it needs is quiet")
        void satisfiedSources() throws IOException {
            List<Finding> findings = analyze("""
                rwc.sources = args, sys, env, f
                rwc.args.type = commandLineArguments
                rwc.sys.type = systemProperties
                rwc.env.type = environmentVariables
                rwc.f.type = properties
                rwc.f.location = file:app.properties
                int port = 8000
                """, APP);
            assertEquals(List.of(), of(findings, Finding.Rule.SOURCE_WITHOUT_TYPE));
            assertEquals(List.of(), of(findings, Finding.Rule.SOURCE_MISSING_SETTING));
        }

        @Test
        @DisplayName("a plugin type is not second-guessed - only the plugin knows what it needs")
        void pluginTypesAreNotChecked() throws IOException {
            List<Finding> findings = analyze("""
                rwc.sources = p
                rwc.p.type = json.plugin
                int port = 8000
                """, APP);
            assertEquals(List.of(), of(findings, Finding.Rule.SOURCE_MISSING_SETTING),
                "the `location` a json source needs is the plugin's business, not ours");
            assertEquals(List.of(), of(findings, Finding.Rule.SOURCE_WITHOUT_TYPE));
        }

        @Test
        @DisplayName("a name on a continued `sources` line is underlined on the line it is written")
        void namesOnContinuationLines() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = args, \\
                              orphan
                rwc.args.type = commandLineArguments
                int port = 8000
                """, APP), Finding.Rule.SOURCE_WITHOUT_TYPE);
            assertEquals(1, findings.size());
            assertEquals(2, findings.get(0).line(), "the second physical line of the list");
        }

        @Test
        @DisplayName("a custom prefix is followed")
        void customPrefix() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.prefix = myapp.
                myapp.sources = orphan
                int port = 8000
                """, APP), Finding.Rule.SOURCE_WITHOUT_TYPE);
            assertEquals(1, findings.size());
            assertTrue(findings.get(0).message().contains("myapp.orphan.type"), findings.get(0).message());
        }
    }


    @Nested
    @DisplayName("finding the line a property is declared on")
    class DeclaredLines {

        @Test
        @DisplayName("the name is what sits between any allowed values and the `=`")
        void namesAreExtracted() {
            assertEquals("port", RwconfigAnalyzer.declaredNameOn("int port = 8000"));
            assertEquals("port", RwconfigAnalyzer.declaredNameOn("  int[80, 1024..65535] port = 8000  "));
            assertEquals("dbPassword", RwconfigAnalyzer.declaredNameOn("dbPassword"));
            assertEquals("dbPassword", RwconfigAnalyzer.declaredNameOn("string dbPassword"));
            assertEquals("empty", RwconfigAnalyzer.declaredNameOn("intList empty ="));
        }

        @Test
        @DisplayName("an `=` inside allowed values is not mistaken for the value separator")
        void equalsInsideAllowedValues() {
            assertEquals("mode", RwconfigAnalyzer.declaredNameOn("string[a=b, c=d] mode = a=b"));
        }

        @Test
        @DisplayName("an escaped `]` does not end the allowed values early")
        void escapedBracket() {
            assertEquals("punctuation", RwconfigAnalyzer.declaredNameOn("string[e\\]f, g] punctuation = g"));
        }

        @Test
        @DisplayName("names holding backslashes, as the hierarchical plugins produce")
        void backslashNames() {
            assertEquals("a\\b", RwconfigAnalyzer.declaredNameOn("a\\b"));
            assertEquals("a\\\\b", RwconfigAnalyzer.declaredNameOn("string a\\\\b = x"));
            assertEquals("foo\\4\\nested", RwconfigAnalyzer.declaredNameOn("int foo\\4\\nested = 1"));
        }

        @Test
        void commentsAndBlanksDeclareNothing() {
            assertEquals(null, RwconfigAnalyzer.declaredNameOn("# int port = 8000"));
            assertEquals(null, RwconfigAnalyzer.declaredNameOn("! a comment"));
            assertEquals(null, RwconfigAnalyzer.declaredNameOn("   "));
        }

        @Test
        @DisplayName("an unread property points at its own line, not the top of the file")
        void unreadFindingsCarryTheLine() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = args
                rwc.args.type = commandLineArguments
                int port = 8000
                string unreadOne
                int[0..9] unreadTwo = 5
                """, """
                class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(2, findings.size());
            assertEquals(4, findings.get(0).line(), "`unreadOne` is on line 4");
            assertEquals(5, findings.get(1).line(), "`unreadTwo` is on line 5");
        }

        @Test
        @DisplayName("the finding underlines the name itself, not the type or the whole line")
        void findingsUnderlineTheName() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = args
                rwc.args.type = commandLineArguments
                int port = 8000
                string[home,work] contactType = home
                """, """
                class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(1, findings.size());
            Finding finding = findings.get(0);
            assertEquals(4, finding.line());
            // `string[home,work] contactType = home` - the name starts at 19
            assertEquals(19, finding.column(), "the column the name starts at");
            assertEquals("contactType".length(), finding.length(), "the name's length");
        }

        @Test
        @DisplayName("a call's finding underlines the quoted name, not the whole call")
        void callFindingsUnderlineTheLiteral() throws IOException {
            List<Finding> findings = of(analyze("""
                class App { void m(net.rabbitware.config.Config c) { c.getInt("prot"); } }
                """), Finding.Rule.UNKNOWN_PROPERTY);
            assertEquals(1, findings.size());
            // the quotes are part of the literal, so the span covers `"prot"`
            assertEquals("\"prot\"".length(), findings.get(0).length());
        }

        @Test
        @DisplayName("a declaration after a continued line still reports its own line")
        void linesAfterAContinuation() throws IOException {
            List<Finding> findings = of(analyze("""
                rwc.sources = args
                rwc.args.type = commandLineArguments
                int[80, \\
                    1024..65535] port = 8000
                string unread
                """, """
                class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
                """), Finding.Rule.UNREAD_PROPERTY);
            assertEquals(1, findings.size());
            assertEquals(5, findings.get(0).line(),
                "the joined declaration takes two physical lines, so `unread` is on line 5");
        }
    }


    @Test
    @DisplayName("a declared property nothing reads is worth mentioning, but is not an error")
    void unreadProperty() throws IOException {
        List<Finding> findings = of(analyze("""
            class App { void m(net.rabbitware.config.Config c) { c.getInt("port"); } }
            """), Finding.Rule.UNREAD_PROPERTY);
        assertTrue(findings.stream().allMatch(f -> f.severity() == Finding.Severity.INFO));
        assertTrue(
            findings.stream().anyMatch(f -> f.message().contains("dbPassword")),
            "expected `dbPassword` to be listed, but got: " + findings
        );
    }

    @Test
    @DisplayName("a source that does not compile is still checked - that is when it matters most")
    void sourcesThatDoNotCompile() throws IOException {
        List<Finding> findings = of(analyze("""
            class App {
                void m(net.rabbitware.config.Config c) {
                    c.getInt("prot");
                    this.doesNotExist();
                }
            }
            """), Finding.Rule.UNKNOWN_PROPERTY);
        assertEquals(1, findings.size());
    }
}
