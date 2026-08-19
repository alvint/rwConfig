package net.rabbitware.config.plugin.fixture;

import java.util.Map;
import java.util.Set;

import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

/**
 * A plugin that exists only to be found.
 *
 * <p>The tests run on the class path, so this class lands in the unnamed module
 * exactly as a plugin jar on a application's class path would - which is the
 * thing worth testing. It is registered through {@code META-INF/services} in the
 * test resources, and its package name is what the source type {@code
 * fixture.plugin} resolves against.
 */
public class FixturePlugin implements SimpleConfigSourcePlugin {

    private String sourceName;
    private String greeting = "default";

    @Override
    public String getPluginVersion() {
        return "1.0.0";
    }

    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    @Override
    public boolean isChangeDetectionSupported() {
        return false;
    }

    @Override
    public void addChangeListener(SimpleConfigSourcePlugin.ChangeListener listener) {
        // not supported
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("greeting");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of();
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) {
        greeting = properties.get("greeting");
    }

    @Override
    public Map<String, String> getConfigSourceProperties() {
        return Map.of("fromPlugin", greeting, "sourceName", sourceName);
    }
}
