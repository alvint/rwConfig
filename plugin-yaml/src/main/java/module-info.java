module net.rabbitware.config.plugin.yaml {
	requires java.base;
	requires org.slf4j;
    requires org.snakeyaml.engine;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with net.rabbitware.config.plugin.yaml.YamlPlugin;
}
