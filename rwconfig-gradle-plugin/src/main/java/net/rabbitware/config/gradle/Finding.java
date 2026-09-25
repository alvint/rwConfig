package net.rabbitware.config.gradle;

/**
 * One thing the analyzer found, as it reports it: one JSON object per line.
 *
 * <p>The analyzer runs in a JVM of its own, so its own {@code Finding} class is
 * never loaded here - this is the same record, read back from its output.
 *
 * @param severity {@code ERROR}, {@code WARNING}, or {@code INFO}
 * @param rule the rule's id, such as {@code unknown-property}
 * @param file the file the finding is in
 * @param line the 1-based line, or 0 when the finding is about the whole file
 * @param column the 1-based column, or 0 when there is none
 * @param message what is wrong
 */
record Finding(String severity, String rule, String file, long line, long column, String message) {

    boolean isError() {
        return "ERROR".equals(severity);
    }

    /**
     * {@code <file>:<line>:<column>: <severity>: <message> [<rule>]} - the shape
     * javac uses, which IDEs and CI logs turn into a link.
     */
    String describe() {
        String where = line > 0 ? file + ":" + line + ":" + column + ": " : file + ": ";
        return where + severity.toLowerCase(java.util.Locale.ROOT) + ": " + message + " [" + rule + "]";
    }

    /**
     * Read one line of the analyzer's output.
     *
     * <p>Each line is a flat object of strings and whole numbers, written by
     * the analyzer's own encoder, so this reads exactly that and nothing more.
     * Anything else is an error rather than something to skip: a line that
     * cannot be read is a finding that would otherwise go unreported.
     *
     * @throws IllegalArgumentException if the line is not such an object
     */
    static Finding parse(String line) {
        java.util.Map<String, Object> fields = new Reader(line).object();
        return new Finding(
            string(fields, "severity", line),
            string(fields, "rule", line),
            string(fields, "file", line),
            number(fields, "line", line),
            number(fields, "column", line),
            string(fields, "message", line)
        );
    }

    private static String string(java.util.Map<String, Object> fields, String name, String line) {
        if (fields.get(name) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException("no string `" + name + "` in analyzer output: " + line);
    }

    private static long number(java.util.Map<String, Object> fields, String name, String line) {
        if (fields.get(name) instanceof Long value) {
            return value;
        }
        throw new IllegalArgumentException("no number `" + name + "` in analyzer output: " + line);
    }

    /** A reader for one flat JSON object. */
    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        java.util.Map<String, Object> object() {
            java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
            space();
            expect('{');
            space();
            if (peek() == '}') {
                at++;
            } else {
                while (true) {
                    space();
                    String name = string();
                    space();
                    expect(':');
                    space();
                    fields.put(name, peek() == '"' ? string() : number());
                    space();
                    if (peek() == ',') {
                        at++;
                        continue;
                    }
                    expect('}');
                    break;
                }
            }
            space();
            if (at != text.length()) {
                throw fail("unexpected text after the object");
            }
            return fields;
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw fail("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw fail("unterminated escape");
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw fail("short unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw fail("bad unicode escape");
                        }
                        at += 4;
                    }
                    default -> throw fail("unknown escape \\" + e);
                }
            }
        }

        private Long number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (at == start || (at == start + 1 && text.charAt(start) == '-')) {
                throw fail("expected a string or a whole number");
            }
            return Long.parseLong(text.substring(start, at));
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private char peek() {
            if (at >= text.length()) {
                throw fail("unexpected end of line");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw fail("expected `" + c + "`");
            }
            at++;
        }

        private IllegalArgumentException fail(String what) {
            return new IllegalArgumentException(what + " at column " + (at + 1) + " of analyzer output: " + text);
        }
    }
}
