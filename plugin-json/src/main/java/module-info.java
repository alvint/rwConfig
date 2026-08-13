module net.rabbitware.config.plugin.json {
	requires java.base;
	requires org.slf4j;
    requires org.json;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with net.rabbitware.config.plugin.json.JsonPlugin;
}
