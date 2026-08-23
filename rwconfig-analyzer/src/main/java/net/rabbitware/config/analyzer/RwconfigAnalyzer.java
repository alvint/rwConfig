package net.rabbitware.config.analyzer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.lang.model.element.VariableElement;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import net.rabbitware.config.Config.PropertyType;
import net.rabbitware.config.ConfigFactory;

/**
 * Checks Java sources against the property declarations in an {@code rwconfig}
 * file, so that the mistakes the library would throw on at startup can be
 * reported while the code is still being written.
 *
 * <p>Sources are read with the JDK's own parser rather than a regex or a
 * third-party library: it gets strings, comments and line numbers right, and
 * costs no dependency. Only the parse tree is used - no attribution, no
 * classpath - so this works on a file that does not compile yet, which is the
 * state a developer is usually in. The cost of that choice is that a call is
 * matched on its shape, so the analyzer tracks which local variables hold a
 * {@code Config} and ignores getters called on anything else.
 */
public final class RwconfigAnalyzer {

    /** The getters, and the declared type each one reads. */
    private static final Map<String, PropertyType> GETTERS = Map.ofEntries(
        Map.entry("getBoolean", PropertyType.BOOLEAN),     Map.entry("getb", PropertyType.BOOLEAN),
        Map.entry("getInt", PropertyType.INT),             Map.entry("geti", PropertyType.INT),
        Map.entry("getLong", PropertyType.LONG),           Map.entry("getl", PropertyType.LONG),
        Map.entry("getDouble", PropertyType.DOUBLE),       Map.entry("getd", PropertyType.DOUBLE),
        Map.entry("getString", PropertyType.STRING),       Map.entry("gets", PropertyType.STRING),
        Map.entry("getBooleanList", PropertyType.BOOLEAN_LIST), Map.entry("getbl", PropertyType.BOOLEAN_LIST),
        Map.entry("getIntList", PropertyType.INT_LIST),    Map.entry("getil", PropertyType.INT_LIST),
        Map.entry("getLongList", PropertyType.LONG_LIST),  Map.entry("getll", PropertyType.LONG_LIST),
        Map.entry("getDoubleList", PropertyType.DOUBLE_LIST), Map.entry("getdl", PropertyType.DOUBLE_LIST),
        Map.entry("getStringList", PropertyType.STRING_LIST), Map.entry("getsl", PropertyType.STRING_LIST)
    );

    /** Getters that take a name but are not type-specific. */
    private static final Set<String> NAME_ONLY = Set.of("has", "getType");

    private final Map<String, PropertyType> declared;
    private final Settings settings;

    /** Where the file is. A finding has to point somewhere openable. */
    private final String configFilePath;

    /** What to call it in a message, where a full path is noise. */
    private final String configFileName;

    /**
     * @param rwconfigLocation
     * where the `rwconfig` file is, in the form `ConfigFactory` accepts
     * @param configFileName
     * what to call it in messages - the two are deliberately separate, since a
     * message reads better with a bare name while a finding has to carry a path
     * an editor can open
     */
    public RwconfigAnalyzer(String rwconfigLocation, String configFileName) {
        this.declared = ConfigFactory.declaredTypes(rwconfigLocation);
        this.settings = readSettings(rwconfigLocation);
        this.configFilePath = pathOf(rwconfigLocation);
        this.configFileName = configFileName;
    }

    private static String pathOf(String location) {
        String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
        return Path.of(path).toAbsolutePath().toString();
    }

    /**
     * The line a property is declared on, or 0 when it cannot be found.
     *
     * <p>This reads the file again rather than asking the library, which does
     * not report positions. The duplication is deliberate and kept as small as
     * possible: all this understands is where the name sits on a line, and the
     * worst it can do when wrong is point a diagnostic at the wrong line - it
     * cannot change whether something is reported. Teaching the parser to carry
     * positions would be the tidier answer, at the cost of changing the path
     * every application's startup goes through.
     *
     * <p>A declaration split across lines with a trailing backslash is found at
     * its first line, unless the split falls inside the name itself, which
     * nothing sensible does.
     */
    private Position lineOf(String name) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(configFilePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Position.UNKNOWN;
        }
        for (int i = 0; i < lines.size(); i++) {
            String declared = declaredNameOn(lines.get(i));
            if (name.equals(declared)) {
                // the name is the last thing before the `=`, so the last
                // occurrence is the declaration rather than a type or a value
                int column = lines.get(i).lastIndexOf(declared) + 1;
                return new Position(i + 1, column, declared.length());
            }
        }
        return Position.UNKNOWN;
    }

    /** Where something is, so a diagnostic can underline it. */
    private record Position(int line, int column, int length) {
        static final Position UNKNOWN = new Position(0, 0, 0);
    }

    /**
     * The property name a line declares, or null. The name is whatever sits
     * between any allowed values and the `=`, so the type in front of it and
     * the value after it both fall away - and an `=` inside allowed values,
     * which is legal, does not fool it.
     */
    static String declaredNameOn(String line) {
        String text = line.replaceAll("^\\s*[!#].*$", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        int from = 0;
        int open = text.indexOf('[');
        if (open != -1) {
            for (int i = open + 1; i < text.length(); i++) {
                if (text.charAt(i) == '\\') {
                    i++;
                } else if (text.charAt(i) == ']') {
                    from = i + 1;
                    break;
                }
            }
        }
        String rest = text.substring(from);
        int equals = rest.indexOf('=');
        String beforeValue = (equals == -1 ? rest : rest.substring(0, equals)).trim();
        if (beforeValue.isEmpty()) {
            return null;
        }
        String[] tokens = beforeValue.split("\\s+");
        return tokens[tokens.length - 1];
    }

    /**
     * The library settings in the file: the prefix, the sources named in
     * `<prefix>sources`, and every `<prefix><source>.<setting>` value.
     *
     * <p>Read here rather than asked of the library, which resolves settings
     * while loading sources - the thing this tool exists not to do.
     */
    private record Settings(String prefix, List<String> sources, Map<String, String> values) {
        static final Settings EMPTY = new Settings("rwc.", List.of(), Map.of());

        String typeOf(String source) {
            return values.get(prefix + source + ".type");
        }

        boolean has(String source, String setting) {
            String value = values.get(prefix + source + "." + setting);
            return value != null && !value.isBlank();
        }
    }

    private static Settings readSettings(String location) {
        try {
            List<String> raw = Files.readAllLines(Path.of(pathOf(location)), StandardCharsets.UTF_8);
            // join continuations, as the library does, so a `sources` list
            // split over several lines is read whole
            List<String> joined = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String line : raw) {
                boolean continues = line.endsWith("\\");
                current.append(continues ? line.substring(0, line.length() - 1) : line);
                if (!continues) {
                    joined.add(current.toString());
                    current.setLength(0);
                }
            }
            if (current.length() > 0) {
                joined.add(current.toString());
            }

            String prefix = "rwc.";
            Map<String, String> values = new HashMap<>();
            List<String> declarations = joined.stream()
                .map(line -> line.replaceAll("^\\s*[!#].*$", ""))
                .filter(line -> !line.isBlank())
                .toList();
            if (!declarations.isEmpty()) {
                String[] first = declarations.get(0).split("=", 2);
                if (first.length == 2 && first[0].trim().equals("rwc.prefix")) {
                    prefix = first[1].trim();
                }
            }
            for (String line : declarations) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().startsWith(prefix)) {
                    values.put(parts[0].trim(), parts[1].trim());
                }
            }
            String sources = values.getOrDefault(prefix + "sources", "");
            List<String> names = sources.isBlank()
                ? List.of()
                : Stream.of(sources.split("\\s*,\\s*")).map(String::trim).filter(n -> !n.isEmpty()).toList();
            return new Settings(prefix, names, values);
        } catch (Exception e) {
            // a file the library cannot read is its own error to report
            return Settings.EMPTY;
        }
    }

    /** What each built-in source type needs, beyond its `type`. */
    private static final Map<String, Set<String>> BUILT_IN_REQUIREMENTS = Map.of(
        "commandLineArguments", Set.of(),
        "systemProperties", Set.of(),
        "environmentVariables", Set.of(),
        "properties", Set.of("location"),
        "directory", Set.of("path")
    );

    /**
     * Every source named in `sources` needs a `type`, and a built-in type needs
     * whatever that type reads. Both are startup failures; finding them here
     * means finding them before startup.
     *
     * <p>A plugin's requirements come from the plugin, which cannot be asked
     * without loading it, so a source of a plugin type is checked only for
     * having a type at all.
     */
    /** Settings that stand alone, rather than belonging to a source. */
    private static final Set<String> GLOBAL_SETTINGS =
        Set.of("prefix", "sources", "changeDetectionPollingInterval", "redactSecretsByName");

    /** Settings any source takes, whatever its type. */
    private static final Set<String> ANY_SOURCE_SETTINGS =
        Set.of("type", "ignoreUnknownProperties", "secret");

    /** What each built-in type accepts beyond {@link #ANY_SOURCE_SETTINGS}. */
    private static final Map<String, Set<String>> BUILT_IN_SETTINGS = Map.of(
        "commandLineArguments", Set.of(),
        "systemProperties", Set.of(),
        "environmentVariables", Set.of(),
        "properties", Set.of("location", "username", "password"),
        "directory", Set.of("path"),
        "dotenv", Set.of("location", "username", "password"));

    /**
     * Check the library settings themselves: that each one is a setting, that it
     * belongs to a source that exists, and that the source's type has a use for
     * it. None of these stops the library - an unrecognised setting is simply
     * ignored - which is what makes them worth reporting here.
     */
    private void checkSettings(List<Finding> findings) {
        String prefix = settings.prefix();
        for (Map.Entry<String, String> entry : settings.values().entrySet()) {
            String key = entry.getKey();
            String rest = key.substring(prefix.length());
            if (GLOBAL_SETTINGS.contains(rest)) {
                continue;
            }
            int dot = rest.indexOf('.');
            if (dot < 0) {
                Position at = lineOf(key);
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.UNKNOWN_SETTING, configFilePath,
                    at.line(), at.column(), at.length(),
                    "`" + key + "` is not a setting rwConfig knows, so it does nothing"
                        + suggestionFrom(rest, GLOBAL_SETTINGS)
                ));
                continue;
            }
            String source = rest.substring(0, dot);
            String setting = rest.substring(dot + 1);
            if (!settings.sources().contains(source)) {
                Position at = lineOf(key);
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.SETTING_FOR_UNKNOWN_SOURCE, configFilePath,
                    at.line(), at.column(), at.length(),
                    "`" + key + "` is for a config source `" + source + "` that `" + prefix
                        + "sources` does not list, so it does nothing"
                        + suggestionFrom(source, Set.copyOf(settings.sources()))
                ));
                continue;
            }
            if (ANY_SOURCE_SETTINGS.contains(setting)) {
                continue;
            }
            String type = settings.typeOf(source);
            // only a built-in type's settings are known - what a plugin accepts
            // is the plugin's business, and it would have to be loaded to ask
            Set<String> accepted = type == null ? null : BUILT_IN_SETTINGS.get(type);
            if (accepted != null && !accepted.contains(setting)) {
                Position at = lineOf(key);
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.SETTING_NOT_USED_BY_TYPE, configFilePath,
                    at.line(), at.column(), at.length(),
                    "config source `" + source + "` is of type `" + type + "`, which has no use for `"
                        + setting + "`" + suggestionFrom(setting, union(accepted, ANY_SOURCE_SETTINGS))
                ));
            }
        }
    }

    /** Levenshtein distance, for the "did you mean" suggestions. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
    }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
    }
        return previous[b.length()];
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }

    /** " - did you mean `x`?", when something close enough exists. */
    private static String suggestionFrom(String actual, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(actual.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // close enough to be a typo rather than a different word entirely
        return best != null && bestDistance <= Math.max(2, actual.length() / 3)
            ? " - did you mean `" + best + "`?"
            : "";
    }

    /** Name words that mean a value should not be written into a committed file. */
    private static final Set<String> SECRET_WORDS =
        Set.of("password", "passwd", "pwd", "secret", "token", "credential", "credentials");

    /**
     * Report a declaration that gives a secret-looking property a default. The
     * default lives in the `rwconfig` file, which is normally committed - so this
     * is a credential in version control.
     */
    private void checkSecretDefaults(List<Finding> findings) {
        for (Map.Entry<String, String> entry : declaredDefaults().entrySet()) {
            String name = entry.getKey();
            if (!nameLooksSecret(name)) {
                continue;
            }
            Position at = lineOf(name);
            findings.add(new Finding(
                Finding.Severity.WARNING, Finding.Rule.SECRET_DEFAULT_IN_FILE, configFilePath,
                at.line(), at.column(), at.length(),
                "`" + name + "` reads like a secret and has a default written here - the `rwconfig` file is"
                    + " usually committed, so give it no default and supply it from a config source instead"
            ));
        }
    }

    /** Whether a property name reads like it holds a secret - the library's own rule. */
    private static boolean nameLooksSecret(String propertyName) {
        String[] words = propertyName.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[._\\\\-]");
        for (int i = 0; i < words.length; i++) {
            String word = words[i].toLowerCase();
            if (SECRET_WORDS.contains(word) || (i > 0 && word.equals("key"))) {
                return true;
            }
        }
        return false;
    }

    /** Declared properties that carry a default value, by name. */
    private Map<String, String> declaredDefaults() {
        Map<String, String> defaults = new HashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(configFilePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return defaults;
        }
        for (String line : lines) {
            String stripped = line.replaceAll("^\\s*[!#].*$", "");
            if (stripped.isBlank() || stripped.trim().startsWith(settings.prefix())) {
                continue;
            }
            int equals = stripped.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String beforeEquals = stripped.substring(0, equals).trim();
            String value = stripped.substring(equals + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            // the property name is the last word before the `=`
            String[] words = beforeEquals.split("\\s+");
            defaults.put(words[words.length - 1], value);
        }
        return defaults;
    }

    /** Locations whose contents can change while the process runs. */
    private boolean isWatchable(String source) {
        String type = settings.typeOf(source);
        if (type == null) {
            return false;
        }
        if (type.equals("jdbc.plugin")) {
            return settings.has(source, "changeQuery");
        }
        String location = settings.values().get(settings.prefix() + source + ".location");
        if (location == null) {
            // `directory` watches a path; the fixed sources cannot change
            return type.equals("directory");
        }
        return location.startsWith("file:") || location.startsWith("jar:file:")
            || location.startsWith("http:") || location.startsWith("https:");
    }

    /**
     * Check what the `rwconfig` file says about change detection against what the
     * code actually asks for. Every mismatch here is silent at run time: a
     * listener that never fires, or a setting that nothing reads.
     */
    private void checkChangeDetection(List<Finding> findings, FileScan scan) {
        String prefix = settings.prefix();
        String intervalKey = prefix + "changeDetectionPollingInterval";
        boolean intervalSet = settings.values().containsKey(intervalKey);

        if (intervalSet && !scan.changeDetectionAsked() && !scan.computedName()) {
            Position at = lineOf(intervalKey);
            findings.add(new Finding(
                Finding.Severity.WARNING, Finding.Rule.CHANGE_DETECTION_NOT_ENABLED, configFilePath,
                at.line(), at.column(), at.length(),
                "`" + intervalKey + "` is set, but nothing calls `ConfigFactory.create` asking for change"
                    + " detection - pass `true`, or the interval does nothing"
            ));
        }
        if (scan.listenerAdded() && !scan.changeDetectionAsked()) {
            findings.add(new Finding(
                Finding.Severity.ERROR, Finding.Rule.LISTENER_WITHOUT_CHANGE_DETECTION, configFilePath, 0, 0, 0,
                "`addChangeListener` is called on a Config built without change detection, which throws at"
                    + " startup - use `ConfigFactory.create(true, ...)`"
            ));
        }
        for (String listened : scan.listenedSources()) {
            if (!settings.sources().contains(listened)) {
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.LISTENER_FOR_UNKNOWN_SOURCE, configFilePath, 0, 0, 0,
                    "a change listener is registered for config source `" + listened + "`, which `" + prefix
                        + "sources` does not list - it can never fire"
                ));
            }
        }
        if (scan.changeDetectionAsked() && !settings.sources().isEmpty()
                && settings.sources().stream().noneMatch(this::isWatchable)) {
            Position at = lineOf(prefix + "sources");
            findings.add(new Finding(
                Finding.Severity.WARNING, Finding.Rule.NOTHING_TO_WATCH, configFilePath,
                at.line(), at.column(), at.length(),
                "change detection is enabled, but no declared source can be watched - the environment,"
                    + " system properties, the command line and `classpath:` locations never change while"
                    + " the process runs"
            ));
        }
    }

    /**
     * Check credentials against the location they would be sent to. Both of these
     * are quiet at run time: credentials for something that is not a URL are
     * never used, and over plain HTTP they are sent in the clear.
     */
    private void checkCredentials(List<Finding> findings) {
        String prefix = settings.prefix();
        for (String source : settings.sources()) {
            boolean hasUser = settings.has(source, "username");
            boolean hasPassword = settings.has(source, "password");
            if (!hasUser && !hasPassword) {
                continue;
            }
            String key = prefix + source + "." + (hasUser ? "username" : "password");
            Position at = lineOf(key);
            if (hasPassword && !hasUser) {
                findings.add(new Finding(
                    Finding.Severity.ERROR, Finding.Rule.PASSWORD_WITHOUT_USERNAME, configFilePath,
                    at.line(), at.column(), at.length(),
                    "config source `" + source + "` has a `password` but no `username`, which is rejected"
                        + " at startup because it would never be sent"
                ));
                continue;
            }
            String location = settings.values().get(prefix + source + ".location");
            if (location == null) {
                continue;
            }
            if (location.startsWith("http:")) {
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.CREDENTIALS_OVER_HTTP, configFilePath,
                    at.line(), at.column(), at.length(),
                    "credentials for config source `" + source + "` would be sent unencrypted - basic"
                        + " authentication is base64, so use `https:`"
                ));
            } else if (!location.startsWith("https:")) {
                findings.add(new Finding(
                    Finding.Severity.WARNING, Finding.Rule.CREDENTIALS_UNUSED, configFilePath,
                    at.line(), at.column(), at.length(),
                    "config source `" + source + "` has credentials, but its location is not an http(s) URL"
                        + " - they are never used"
                ));
            }
        }
    }

    private void checkSources(List<Finding> findings) {
        for (String source : settings.sources()) {
            Position at = sourceNameIn(source);
            String type = settings.typeOf(source);
            if (type == null || type.isBlank()) {
                findings.add(new Finding(
                    Finding.Severity.ERROR, Finding.Rule.SOURCE_WITHOUT_TYPE, configFilePath,
                    at.line(), at.column(), at.length(),
                    "config source `" + source + "` has no type - add `"
                        + settings.prefix() + source + ".type = ...`"
                ));
                continue;
            }
            Set<String> required = BUILT_IN_REQUIREMENTS.get(type);
            if (required == null) {
                continue; // a plugin type: only the plugin knows what it needs
            }
            for (String setting : new TreeSet<>(required)) {
                if (!settings.has(source, setting)) {
                    findings.add(new Finding(
                        Finding.Severity.ERROR, Finding.Rule.SOURCE_MISSING_SETTING, configFilePath,
                        at.line(), at.column(), at.length(),
                        "config source `" + source + "` is of type `" + type + "`, which needs `"
                            + settings.prefix() + source + "." + setting + "`"
                    ));
                }
            }
        }
    }

    /**
     * Where a source is named inside the `sources` line. The list may be split
     * over several lines with a trailing backslash, so the physical lines are
     * walked from the one that opens the declaration.
     */
    private Position sourceNameIn(String source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(configFilePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Position.UNKNOWN;
        }
        boolean inSources = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inSources) {
                String name = declaredNameOn(line);
                if (!(settings.prefix() + "sources").equals(name)) {
                    continue;
                }
                inSources = true;
            }
            int column = tokenIndex(line, source);
            if (column != -1) {
                return new Position(i + 1, column + 1, source.length());
            }
            if (!line.endsWith("\\")) {
                break; // the declaration ended and the name was not in it
            }
        }
        return Position.UNKNOWN;
    }

    /** Where `token` appears as a whole item of a comma separated list. */
    private static int tokenIndex(String line, String token) {
        int from = 0;
        while (true) {
            int at = line.indexOf(token, from);
            if (at == -1) {
                return -1;
            }
            char before = at == 0 ? ',' : line.charAt(at - 1);
            int end = at + token.length();
            char after = end >= line.length() ? ',' : line.charAt(end);
            boolean delimitedBefore = before == ',' || before == '=' || Character.isWhitespace(before);
            boolean delimitedAfter = after == ',' || Character.isWhitespace(after) || after == '\\';
            if (delimitedBefore && delimitedAfter) {
                return at;
            }
            from = at + 1;
        }
    }

    /** Check every given source file, and the project as a whole. */
    public List<Finding> analyze(List<Path> sources) {
        List<Finding> findings = new ArrayList<>();
        Set<String> readProperties = new HashSet<>();
        boolean instanceGetSeen = false;
        boolean instanceSetSeen = false;
        boolean createWithArgsSeen = false;
        boolean createWithoutArgsSeen = false;
        boolean computedNameSeen = false;

        FileScan scan = scan(sources, findings, readProperties);
        instanceGetSeen = scan.instanceGet;
        instanceSetSeen = scan.instanceSet;
        createWithArgsSeen = scan.createWithArgs;
        createWithoutArgsSeen = scan.createWithoutArgs;
        computedNameSeen = scan.computedName;

        if (instanceGetSeen && !instanceSetSeen) {
            findings.add(new Finding(
                Finding.Severity.ERROR, Finding.Rule.INSTANCE_GET_WITHOUT_SET, configFilePath, 0, 0, 0,
                "`Config.Instance.get()` is used but nothing calls `Config.Instance.set(...)`, so the"
                + " instance will never have been set and every call will throw"
            ));
        }
        checkSources(findings);
        checkSettings(findings);
        checkCredentials(findings);
        checkSecretDefaults(findings);
        checkChangeDetection(findings, scan);

        List<String> commandLineSources = settings.sources().stream()
            .filter(source -> "commandLineArguments".equals(settings.typeOf(source)))
            .toList();
        if (!commandLineSources.isEmpty() && createWithoutArgsSeen && !createWithArgsSeen) {
            findings.add(new Finding(
                Finding.Severity.ERROR, Finding.Rule.COMMAND_LINE_SOURCE_WITHOUT_ARGS, configFilePath, 0, 0, 0,
                "config source " + commandLineSources + " is of type `commandLineArguments`, but the"
                + " code only calls `ConfigFactory.create()` with no arguments - that fails at startup."
                + " Call `create(args)` instead"
            ));
        }
        // Saying a property is unread is a claim about the whole program, so it can only be made
        // when the whole program was there to look at. One read through a name the code works out
        // at run time - `config.getString(name)` in a loop over `getPropertyNames()`, say - is
        // enough to make the claim unprovable for every property, and so is having no sources to
        // read at all, which is what an editor sees before it has been told where they live.
        boolean unreadIsKnowable = !sources.isEmpty() && !computedNameSeen;
        for (String name : unreadIsKnowable ? new TreeSet<>(declared.keySet()) : Set.<String>of()) {
            if (!readProperties.contains(name)) {
                Position at = lineOf(name);
                findings.add(new Finding(
                    Finding.Severity.INFO, Finding.Rule.UNREAD_PROPERTY, configFilePath,
                    at.line(), at.column(), at.length(),
                    "property `" + name + "` is declared but nothing reads it"
                ));
            }
        }
        return findings;
    }

    private record FileScan(
        boolean instanceGet, boolean instanceSet, boolean createWithArgs, boolean createWithoutArgs,
        boolean computedName, boolean changeDetectionAsked, boolean listenerAdded,
        Set<String> listenedSources
    ) {}

    private FileScan scan(List<Path> sources, List<Finding> findings, Set<String> readProperties) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no Java compiler is available - run this on a JDK, not a JRE");
        }
        if (sources.isEmpty()) {
            // javac refuses a task with nothing to compile, and a project whose sources have not
            // been found yet is a reason to check the file alone rather than to fail.
            return new FileScan(false, false, false, false, false, false, false, Set.of());
        }
        List<JavaFileObject> files = new ArrayList<>();
        Map<String, Path> pathsByUri = new HashMap<>();
        for (Path source : sources) {
            String content;
            try {
                content = Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.add(new SimpleJavaFileObject(source.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return content;
                }
            });
            pathsByUri.put(source.toUri().toString(), source);
        }
        // Every file goes through one task so that a name held in a constant can be resolved
        // even when the constant lives in a different file from the read.
        JavacTask task = (JavacTask) compiler.getTask(
            null, null, diagnostic -> { /* code that does not compile is still worth scanning */ },
            List.of("-proc:none"), null, files
        );
        List<CompilationUnitTree> units = new ArrayList<>();
        try {
            task.parse().forEach(units::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Attribution is what turns `Props.PORT` back into "port", and it costs several times
        // what parsing does - so it is only worth doing when a name that might be a constant is
        // actually there to resolve. Code that names its properties outright never pays for it.
        boolean attributed = false;
        if (units.stream().anyMatch(RwconfigAnalyzer::mayNameAConstant)) {
            try {
                task.analyze();
                attributed = true;
            } catch (Exception | StackOverflowError e) {
                // Attribution is a convenience: without it a constant simply reads as a name
                // worked out at run time, which is the safe answer rather than a wrong one.
                attributed = false;
            }
        }

        Trees trees = Trees.instance(task);
        Set<String> listenedSources = new HashSet<>();
        FileScan total =
            new FileScan(false, false, false, false, false, false, false, listenedSources);
        for (CompilationUnitTree unit : units) {
            Path source = pathsByUri.getOrDefault(
                unit.getSourceFile().toUri().toString(), Path.of(unit.getSourceFile().toUri()));
            Visitor visitor = new Visitor(
                trees, attributed, unit, source, findings, readProperties);
            visitor.scan(unit, null);
            listenedSources.addAll(visitor.listenedSources);
            total = new FileScan(
                total.instanceGet | visitor.instanceGet,
                total.instanceSet | visitor.instanceSet,
                total.createWithArgs | visitor.createWithArgs,
                total.createWithoutArgs | visitor.createWithoutArgs,
                total.computedName | visitor.computedName,
                total.changeDetectionAsked | visitor.changeDetectionAsked,
                total.listenerAdded | visitor.listenerAdded,
                listenedSources);
        }
        return total;
    }

    /**
     * Whether a file reads a property through anything that could turn out to be a constant -
     * {@code c.getInt(PORT)} or {@code c.getInt(Props.PORT)}. A literal needs no resolving and a
     * call such as {@code c.getInt(name())} can never resolve, so neither is worth attributing for.
     */
    private static boolean mayNameAConstant(CompilationUnitTree unit) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree select
                        && node.getArguments().size() == 1) {
                    String method = select.getIdentifier().toString();
                    ExpressionTree argument = node.getArguments().get(0);
                    if ((GETTERS.containsKey(method) || NAME_ONLY.contains(method))
                            && (argument instanceof IdentifierTree
                                || argument instanceof MemberSelectTree)) {
                        found[0] = true;
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(unit, null);
        return found[0];
    }

    /** Walks one file, collecting getter calls and the shape of the setup code. */
    private final class Visitor extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final boolean attributed;
        private final SourcePositions positions;
        private final CompilationUnitTree unit;
        private final Path source;
        private final List<Finding> findings;
        private final Set<String> readProperties;

        boolean instanceGet;
        boolean instanceSet;
        boolean createWithArgs;
        boolean createWithoutArgs;
        boolean computedName;
        boolean changeDetectionAsked;
        boolean listenerAdded;
        final Set<String> listenedSources = new HashSet<>();

        Visitor(Trees trees, boolean attributed, CompilationUnitTree unit, Path source,
                List<Finding> findings, Set<String> readProperties) {
            this.trees = trees;
            this.attributed = attributed;
            this.positions = trees.getSourcePositions();
            this.unit = unit;
            this.source = source;
            this.findings = findings;
            this.readProperties = readProperties;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (node.getMethodSelect() instanceof MemberSelectTree select) {
                String method = select.getIdentifier().toString();
                String receiver = select.getExpression().toString();

                if (receiver.endsWith("Config.Instance") || receiver.endsWith("Instance")) {
                    if (method.equals("get")) {
                        instanceGet = true;
                    } else if (method.equals("set") || method.equals("replace")) {
                        instanceSet = true;
                    }
                }
                if (method.equals("create") && receiver.endsWith("ConfigFactory")) {
                    if (node.getArguments().isEmpty()) {
                        createWithoutArgs = true;
                    } else {
                        createWithArgs = true;
                    }
                }
                if (GETTERS.containsKey(method) || NAME_ONLY.contains(method)) {
                    checkGetter(node, method);
                }
                if (method.equals("create") && receiver.endsWith("ConfigFactory")) {
                    // `create(true, args)` or `create("name", true, args)` - a
                    // literal `true` anywhere in the arguments is the request
                    for (ExpressionTree argument : node.getArguments()) {
                        if (argument instanceof LiteralTree literal
                                && java.lang.Boolean.TRUE.equals(literal.getValue())) {
                            changeDetectionAsked = true;
                        }
                    }
                }
                if (method.equals("addChangeListener")) {
                    listenerAdded = true;
                    // the three-argument form names a source; the two-argument
                    // form listens to all of them
                    if (node.getArguments().size() == 3
                            && node.getArguments().get(0) instanceof LiteralTree literal
                            && literal.getValue() instanceof String listened) {
                        listenedSources.add(listened);
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        /**
         * The property this expression names, or null if that cannot be known from the source.
         * A string literal names itself; anything else has to be a compile-time constant, which
         * is what {@code PORT} and {@code Props.PORT} are once the trees have been attributed.
         */
        private String nameOf(ExpressionTree argument) {
            if (argument instanceof LiteralTree literal && literal.getValue() instanceof String name) {
                return name;
            }
            if (!attributed
                    || !(argument instanceof IdentifierTree || argument instanceof MemberSelectTree)) {
                return null;
            }
            try {
                var element = trees.getElement(new TreePath(getCurrentPath(), argument));
                if (element instanceof VariableElement variable
                        && variable.getConstantValue() instanceof String name) {
                    return name;
                }
            } catch (Exception e) {
                // an expression javac could not attribute - unknowable, same as a computed name
            }
            return null;
        }

        private void checkGetter(MethodInvocationTree node, String method) {
            if (node.getArguments().size() != 1) {
                return;
            }
            ExpressionTree argument = node.getArguments().get(0);
            String name = nameOf(argument);
            if (name == null) {
                // A name the code works out at run time cannot be checked against the declared
                // type - and it also means no property can be shown to be unread, since this call
                // could be reading any of them.
                computedName = true;
                return;
            }
            readProperties.add(name);
            PropertyType actual = declared.get(name);
            if (actual == null) {
                findings.add(finding(
                    argument, Finding.Severity.ERROR, Finding.Rule.UNKNOWN_PROPERTY,
                    "property `" + name + "` is not declared in " + configFileName
                        + suggestion(name)
                ));
                return;
            }
            PropertyType expected = GETTERS.get(method);
            if (expected != null && expected != actual) {
                findings.add(finding(
                    argument, Finding.Severity.ERROR, Finding.Rule.WRONG_TYPE,
                    "property `" + name + "` is declared as `" + actual.name + "`, so `" + method
                        + "` will throw - use `" + getterFor(actual) + "` instead"
                ));
            }
        }

        /** The closest declared name, when one is close enough to be a typo. */
        private String suggestion(String name) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String candidate : declared.keySet()) {
                int distance = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best != null && bestDistance <= Math.max(2, name.length() / 3)
                ? " - did you mean `" + best + "`?"
                : "";
        }


        /** Anchored on the given tree, so the quoted name is what gets underlined. */
        private Finding finding(com.sun.source.tree.Tree at, Finding.Severity severity, Finding.Rule rule,
                                String message) {
            long start = positions.getStartPosition(unit, at);
            long end = positions.getEndPosition(unit, at);
            long line = unit.getLineMap().getLineNumber(start);
            long column = unit.getLineMap().getColumnNumber(start);
            long length = end > start ? end - start : 0;
            return new Finding(severity, rule, source.toString(), line, column, length, message);
        }
    }

    /**
     * The getter that reads a property of the given type. Every type has a
     * long name and a short alias; the long one is named here, since a message
     * telling someone to use `getsl` is not much of a hint.
     */
    static String getterFor(PropertyType type) {
        return GETTERS.entrySet().stream()
            .filter(entry -> entry.getValue() == type)
            .map(Map.Entry::getKey)
            .max(java.util.Comparator.comparingInt(String::length))
            .orElseThrow();
    }

    /** Every `.java` file under the given roots. */
    public static List<Path> javaSourcesIn(List<Path> roots) {
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                    .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }
}
