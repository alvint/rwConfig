module net.rabbitware.config {
	requires java.base;
	requires org.slf4j;
	requires net.rabbitware.config.plugin.api;

	uses net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

	exports net.rabbitware.config;
}
