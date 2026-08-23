package net.rabbitware.config.plugin.dotenv;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;
import net.rabbitware.config.plugin.environmentvariables.EnvironmentVariablesPlugin;

/**
 * Reads a {@code .env} file - the {@code KEY=value} format that container
 * runtimes, Docker Compose, and local development tooling all use.
 *
 * <p>A {@code .env} file stands in for the environment, so names are resolved
 * the way the {@code environmentVariables} source resolves them: a property
 * declared as {@code dbHost} is satisfied by {@code DB_HOST} in the file, and by
 * {@code dbHost} if that is what the file happens to call it. Keys the file
 * contains that no declaration claims are passed through as they are written, so
 * a typo in the file is still reported as an unknown property rather than
 * silently ignored.
 *
 * <p>The format has no specification and implementations disagree at the edges.
 * The rules here follow Docker Compose, which is the dialect most likely to have
 * produced the file:
 *
 * <ul>
 * <li>{@code KEY=value}, with space around the {@code =} and around the value
 * ignored.</li>
 * <li>An {@code export } prefix is allowed and ignored, so a file meant to be
 * {@code source}d also works.</li>
 * <li>A line whose first non-blank character is {@code #} is a comment.</li>
 * <li>An inline comment after an unquoted value must be preceded by a space -
 * {@code KEY=value # comment}. Without the space the {@code #} is part of the
 * value, which is what makes {@code KEY=pa#ssword} work.</li>
 * <li>Single quotes are literal. Double quotes process {@code \\n}, {@code \\r},
 * {@code \\t}, {@code \\\\} and escaped quotes. Either kind may span lines.</li>
 * </ul>
 *
 * <p>Variables are <em>not</em> expanded: {@code KEY=${OTHER}} is the literal
 * text {@code ${OTHER}}. Docker Compose expands them, but rwConfig layers
 * sources and has deferred values for exactly this, and a value that quietly
 * means something else depending on the environment is the thing this library
 * exists to prevent.
 */
public class DotEnvPlugin extends LocationBasedConfigSourcePlugin {

    private static final Logger logger = LoggerFactory.getLogger(DotEnvPlugin.class);

    private final Set<String> propertyNames;

    /**
     * @param propertyNames
     * the properties the {@code rwconfig} file declares, so that a declared
     * {@code dbHost} can be satisfied by a {@code DB_HOST} in the file
     */
    public DotEnvPlugin(Set<String> propertyNames) {
        this.propertyNames = propertyNames;
        logger.info("dotenv plugin instantiated");
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading dotenv source from location: {}", getLocation());
        Map<String, String> entries = parse(loadLocation());

        Map<String, String> out = new HashMap<>();
        Set<String> consumed = new java.util.HashSet<>();
        for (String declared : propertyNames) {
            String value = entries.get(declared);
            String key = declared;
            if (value == null) {
                key = EnvironmentVariablesPlugin.toEnvironmentVariableName(declared);
                value = entries.get(key);
            }
            if (value != null) {
                out.put(declared, value);
                consumed.add(key);
            }
        }
        // Whatever no declaration claimed is passed through under the name the
        // file used, so that unknown properties are still reported. A key that
        // was consumed above is left out, or it would be reported as unknown
        // under its own name while also satisfying a declaration.
        entries.forEach((key, value) -> {
            if (!consumed.contains(key)) {
                out.put(key, value);
            }
        });
        logger.info(
            "config source `{}` loaded {} entries from location: {}",
            getSourceName(), entries.size(), getLocation()
        );
        return out;
    }

    /**
     * Parse the contents of a {@code .env} file.
     *
     * @param contents
     * the file's text
     * @return
     * the entries, in the order the file declared them
     * @throws Exception
     * if a line is not a comment, blank, or an assignment, or if a quoted value
     * is never closed
     */
    static Map<String, String> parse(String contents) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        String[] lines = contents.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            int lineNumber = index + 1;
            index++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).strip();
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                throw new Exception(
                    "line " + lineNumber + " is not a comment or a `KEY=value` assignment: " + line.strip()
                );
            }
            String key = trimmed.substring(0, equals).strip();
            checkKey(key, lineNumber, line);
            String rest = trimmed.substring(equals + 1).strip();

            if (rest.startsWith("\"") || rest.startsWith("'")) {
                char quote = rest.charAt(0);
                StringBuilder quoted = new StringBuilder();
                String remainder = rest.substring(1);
                int closed = closingQuote(remainder, quote);
                // a quote left open runs on to the following lines
                while (closed < 0) {
                    quoted.append(remainder).append('\n');
                    if (index >= lines.length) {
                        throw new Exception(
                            "the value starting on line " + lineNumber + " opens with " + quote
                            + " and is never closed"
                        );
                    }
                    remainder = lines[index];
                    index++;
                    closed = closingQuote(remainder, quote);
                }
                quoted.append(remainder, 0, closed);
                String after = remainder.substring(closed + 1).strip();
                if (!after.isEmpty() && !after.startsWith("#")) {
                    throw new Exception(
                        "line " + lineNumber + " has text after the closing " + quote + ": " + after
                    );
                }
                entries.put(key, quote == '"' ? unescape(quoted.toString()) : quoted.toString());
            } else {
                entries.put(key, stripInlineComment(rest));
            }
        }
        return entries;
    }

    /**
     * The index of the quote that closes a value, or -1 if this text does not
     * contain it. A backslash escapes the quote inside a double-quoted value;
     * inside a single-quoted one there are no escapes, so the first quote wins.
     */
    private static int closingQuote(String text, char quote) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '"' && c == '\\') {
                i++; // skip whatever it escapes, including a quote
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Remove a trailing comment from an unquoted value. The {@code #} has to be
     * preceded by whitespace to count, so that a value which simply contains one
     * - a password, a colour - survives.
     */
    private static String stripInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).strip();
            }
        }
        return value.strip();
    }

    /** Process the escape sequences a double-quoted value may contain. */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                // an unknown escape keeps both characters, rather than quietly
                // eating the backslash out of something like a Windows path
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    private static void checkKey(String key, int lineNumber, String line) throws Exception {
        if (key.isEmpty()) {
            throw new Exception("line " + lineNumber + " has no name before the `=`: " + line.strip());
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) {
                throw new Exception(
                    "line " + lineNumber + " has whitespace in the name `" + key + "`"
                );
            }
        }
    }
}
