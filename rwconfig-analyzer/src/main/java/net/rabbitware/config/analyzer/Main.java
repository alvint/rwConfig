package net.rabbitware.config.analyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.rabbitware.config.Config.ConfigException;
import net.rabbitware.config.ConfigFactory;

/**
 * A command line front end, for the editor extension to call.
 *
 * <p>Findings are written to standard output as JSON - one object per line, so
 * a caller can read them as they arrive and does not need a JSON library to
 * split them up. Anything the tool itself has to say goes to standard error, so
 * the two never mix.
 *
 * <pre>
 *   check        --rwconfig &lt;file&gt; [--source &lt;dir&gt;]...
 *   test-sources --rwconfig &lt;file&gt;
 * </pre>
 *
 * <p>{@code check} reads only the declarations and the Java sources, and so
 * never opens a socket or a database connection. {@code test-sources} does the
 * opposite - it loads every source exactly as the application would - and is
 * meant to be run when a person asks for it, never on a timer or a keystroke.
 */
public final class Main {

    public static void main(String[] args) {
        String rwconfig = null;
        List<Path> sourceRoots = new ArrayList<>();
        String command = args.length > 0 ? args[0] : "";
        for (int i = 1; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--rwconfig" -> rwconfig = args[++i];
                case "--source" -> sourceRoots.add(Path.of(args[++i]));
                default -> { }
            }
        }
        if (rwconfig == null) {
            System.err.println("usage: check|test-sources --rwconfig <file> [--source <dir>]...");
            System.exit(2);
        }
        switch (command) {
            case "check" -> check(rwconfig, sourceRoots);
            case "test-sources" -> testSources(rwconfig);
            default -> {
                System.err.println("unknown command: " + command);
                System.exit(2);
            }
        }
    }

    private static void check(String rwconfig, List<Path> sourceRoots) {
        Path config = Path.of(rwconfig);
        List<Finding> findings;
        try {
            // the file name, not the path: the message is read next to the
            // code, where a full path is noise
            RwconfigAnalyzer analyzer = new RwconfigAnalyzer(
                "file:" + config.toAbsolutePath(), config.getFileName().toString());
            findings = analyzer.analyze(RwconfigAnalyzer.javaSourcesIn(sourceRoots));
        } catch (ConfigException e) {
            // the file itself is not valid - report it where it went wrong
            findings = List.of(fileFinding(config, e));
        }
        findings.forEach(finding -> System.out.println(toJson(finding)));
    }

    /**
     * Turn a parse failure into a finding on the offending line. The library's
     * messages end with the text of the line that upset it, so the line can be
     * found by looking for it.
     */
    private static Finding fileFinding(Path config, ConfigException e) {
        String message = e.getMessage() == null ? "invalid config file" : e.getMessage();
        long line = 0;
        int colon = message.lastIndexOf(": ");
        if (colon != -1) {
            String offending = message.substring(colon + 2).trim();
            try {
                List<String> lines = Files.readAllLines(config);
                for (int i = 0; i < lines.size(); i++) {
                    if (!offending.isEmpty() && lines.get(i).trim().equals(offending)) {
                        line = i + 1;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // the line number is a nicety; the message is the point
            }
        }
        return new Finding(
            Finding.Severity.ERROR, Finding.Rule.UNKNOWN_PROPERTY, config.toString(), line, 0, 0,
            message.replace('\n', ' ')
        );
    }

    private static void testSources(String rwconfig) {
        Path config = Path.of(rwconfig);
        try {
            var configuration = ConfigFactory.create(new String[] {
                ConfigFactory.CONFIG_FILE_PATH_PROPERTY + "=file:" + config.toAbsolutePath()
            });
            System.out.println("{\"ok\":true,\"properties\":" + configuration.getPropertyNames().size() + "}");
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("{\"ok\":false,\"message\":" + quote(message) + "}");
        }
    }

    private static String toJson(Finding finding) {
        return "{"
            + "\"severity\":" + quote(finding.severity().name()) + ","
            + "\"rule\":" + quote(finding.rule().id()) + ","
            + "\"file\":" + quote(finding.file()) + ","
            + "\"line\":" + finding.line() + ","
            + "\"column\":" + finding.column() + ","
            + "\"length\":" + finding.length() + ","
            + "\"message\":" + quote(finding.message())
            + "}";
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private Main() { }
}
