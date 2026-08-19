package net.rabbitware.config.analyzer;

/**
 * One thing the analyzer noticed, at a place in a file.
 *
 * @param severity  whether this stops a build
 * @param rule      which check produced it, for suppression and documentation
 * @param file      the file it was found in, or the `rwconfig` file itself
 * @param line      1-indexed, or 0 when it is about the file as a whole
 * @param column    1-indexed, or 0 when unknown
 * @param length    how much to underline from `column`, or 0 when there is no
 *                  particular span - the name in a declaration, or the quoted
 *                  name in a call, rather than a single character
 * @param message   what is wrong, phrased for someone who has to fix it
 */
public record Finding(
    Severity severity, Rule rule, String file, long line, long column, long length, String message
) {
    public enum Severity { ERROR, WARNING, INFO }

    /**
     * The checks. Each names what it looks for rather than what it forbids, so
     * that a suppression in a build file reads sensibly.
     */
    public enum Rule {
        UNKNOWN_PROPERTY,
        WRONG_TYPE,
        INSTANCE_GET_WITHOUT_SET,
        COMMAND_LINE_SOURCE_WITHOUT_ARGS,
        UNREAD_PROPERTY,
        SOURCE_WITHOUT_TYPE,
        SOURCE_MISSING_SETTING;

        /** The name used in configuration, e.g. `unknown-property`. */
        public String id() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    @Override
    public String toString() {
        String where = line > 0 ? file + ":" + line + (column > 0 ? ":" + column : "") : file;
        return severity + " [" + rule.id() + "] " + where + " - " + message;
    }
}
