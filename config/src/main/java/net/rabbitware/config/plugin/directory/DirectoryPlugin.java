package net.rabbitware.config.plugin.directory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.rabbitware.config.Config.ConfigException;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectoryPlugin implements SimpleConfigSourcePlugin {
    private static Logger logger = LoggerFactory.getLogger(DirectoryPlugin.class);

    private String sourceName;
    private Path path;

    // change detection stuff
    private WatchService watchService;

    public DirectoryPlugin() {
        logger.info("directory plugin instantiated");
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
        return true;
    }

    @Override
    public void startChangeDetection() throws Exception {
        if (watchService != null) {
            throw new IllegalStateException("change detection is already started");
        }
        if (path == null) {
            throw new IllegalStateException("path is not set");
        }
        logger.debug("starting change detection for location: {}", path);
            watchService = FileSystems.getDefault().newWatchService();
            path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.OVERFLOW
            );
        logger.debug("change detection started");
    }

    @Override
    public void stopChangeDetection() throws Exception {
        if (watchService == null) {
            throw new IllegalStateException("change detection is not started");
        }
        logger.debug("stopping change detection for directory: {}", path);
        if (watchService != null) {
            watchService.close();
        }
        logger.debug("change detection stopped");
    }

    @Override
    public boolean isChanged() throws Exception {
        logger.trace("checking if directory `{}` has changed", path);
        if (watchService == null) {
            throw new IllegalStateException("change detection is not started");
        }
        WatchKey key = watchService.poll();
        if (key == null) { // no events to process - the directory has not changed
            logger.trace("no change events for directory `{}`", path);
            return false;
        } else { // there are events to process - consider the directory changed
            logger.debug("directory `{}` has changed", path);
            key.reset(); // reset the watch to continue receiving events
            return true;
        }
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("path");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return null;
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        path = Path.of(properties.get("path"));
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        try (Stream<Path> files = Files.list(path)) {
            Map<String, String> properties = new HashMap<>();
            files
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        String fileName = path.getFileName().toString();
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        properties.put(fileName, content);
                    } catch (IOException e) {
                        throw new ConfigException(
                            "error reading config source file from directory for `" + sourceName + "`: " + this.path, e
                        );
                    }
                });
            logger.info("config source `{}` loaded from directory: {}", sourceName, path);
            return properties;
        }
    }
}
