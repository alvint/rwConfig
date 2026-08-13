module net.rabbitware.config.plugin.prefix {
	requires java.base;
	requires org.slf4j;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with net.rabbitware.config.plugin.prefix.PrefixPlugin;
}
