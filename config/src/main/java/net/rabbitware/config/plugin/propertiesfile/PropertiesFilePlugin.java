package net.rabbitware.config.plugin.propertiesfile;
import java.io.StringReader;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;

public class PropertiesFilePlugin extends LocationBasedConfigSourcePlugin{
    private static final Logger logger = LoggerFactory.getLogger(PropertiesFilePlugin.class);

    public PropertiesFilePlugin() {
        logger.info("properties plugin instantiated");
    }
 

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        logger.debug("loading properties source from location: {}", getLocation());
        String sourceContent = LocationBasedConfigSourcePlugin.loadResource(getLocation());
        Properties properties = new Properties();
        properties.load(new StringReader(sourceContent));
        Map<String, String> out = properties.stringPropertyNames().stream()
            .collect(Collectors.toMap(name -> name, name -> properties.getProperty(name)));
        logger.info(
            "config source `{}` loaded {} properties from location: {}",
            getSourceName(), out.size(), getLocation()
        );
        return out;
    }
}
