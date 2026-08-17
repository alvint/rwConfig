module net.rabbitware.config.plugin.hocon {
	requires java.base;
	requires org.slf4j;
    requires typesafe.config;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with net.rabbitware.config.plugin.hocon.HoconPlugin;
}
