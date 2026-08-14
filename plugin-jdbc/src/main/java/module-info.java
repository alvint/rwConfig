module net.rabbitware.config.plugin.jdbc {
	requires java.base;
	requires java.sql;
	requires org.slf4j;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with net.rabbitware.config.plugin.jdbc.JdbcPlugin;
}
