package net.rabbitware.config.plugin.commandline;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.rabbitware.config.ConfigFactory;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

public class CommandLinePlugin implements SimpleConfigSourcePlugin {
    private static Logger logger = LoggerFactory.getLogger(CommandLinePlugin.class);

    /**
     * A command line argument of the form `name=value`, where `name` is a
     * legal property name. Names begin with a letter, so an application's own
     * flags are never mistaken for property assignments.
     */
    private static final Pattern COMMAND_LINE_ASSIGNMENT = Pattern.compile(
        "^\\s*([A-Za-z][\\w.\\\\-]*)\\s*=\\s*(.*)$"
    );

    private String sourceName;
    private final String[] commandLineArgs;
    private final String configPrefix;

    public CommandLinePlugin(String[] commandLineArgs, String configPrefix) {
        this.commandLineArgs = commandLineArgs;
        this.configPrefix = configPrefix;
        logger.info("command line plugin instantiated");
    }

    
    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public boolean isChangeDetectionSupported() {
        return false;
    }

    @Override
    public void startChangeDetection() throws Exception {
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public void stopChangeDetection() throws Exception {
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public boolean isChanged() throws Exception {
        throw new UnsupportedOperationException("change detection is not supported");
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return null;
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return null;
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {

    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        Map<String, String> configSourceProperties = new CommandLineProperties(commandLineArgs, configPrefix);
        logger.info("config source `{}` loaded from command line arguments", sourceName);
        return configSourceProperties;
    }


    private static final class CommandLineProperties implements Map<String, String> {
        private final Map<String, String> map = new HashMap<>();

        /**
         * Every argument that looks like a property assignment is collected,
         * not just the ones that were declared. Unlike the environment or the
         * system properties - which are full of entries that have nothing to do
         * with this application - every command line argument was typed
         * deliberately, for this program, so an argument that matches no
         * declaration is far more likely to be a typo than a coincidence.
         * Collecting them all lets the usual unknown-property check see them,
         * which means `ignoreUnknownProperties` turns this off in the same way
         * it does for every other config source.
         *
         * <p>An argument only counts as an assignment if its name is a legal
         * property name, so an application's own flags and positional arguments
         * (`--verbose`, `input.txt`, `-n=3`) are left alone. The library's own
         * arguments are skipped too.
         */
        private CommandLineProperties(String[] args, String configPrefix) {
            if (args == null) { // if no args were provided, just return an empty map
                return;
            }
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                Matcher matcher = COMMAND_LINE_ASSIGNMENT.matcher(arg);
                if (!matcher.matches()) { // not a property assignment - leave it for the application
                    continue;
                }
                String name = matcher.group(1);
                // the library's own arguments are not application properties
                if (name.equals(ConfigFactory.CONFIG_FILE_PATH_PROPERTY) || name.startsWith(configPrefix)) {
                    continue;
                }
                // first occurrence wins, matching `getCommandLineArgument`
                map.putIfAbsent(name, matcher.group(2));
            }
        }

        @Override public String get(Object key) { return key instanceof String name ? map.get(name) : null; }
        @Override public int size() { return map.size(); }
        @Override public boolean isEmpty() { return map.isEmpty(); }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Set<String> keySet() { return Set.copyOf(map.keySet()); }
        @Override public Collection<String> values() { return List.copyOf(map.values()); }
        @Override public Set<Entry<String, String>> entrySet() { return Set.copyOf(map.entrySet()); }
        // other methods are not implemented, as they are not needed
        @Override public String put(String key, String value) { throw new UnsupportedOperationException(); }
        @Override public String remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }
    }
}
