package net.rabbitware.config.example;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

public class Test {
    private static final Logger logger = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) throws Exception {
        createDatabase();
        Config config = ConfigFactory.create(args);

        config.getPropertyNames().stream().forEach(name -> {
            Config.PropertyType type = config.getType(name);
            System.out.println("\n type of property `" + name + "`: " + type.name);
            switch (type) {
                case BOOLEAN -> {
                    System.out.println("value of property `" + name + "`: " + config.getBoolean(name));
                }
                case INT -> {
                    System.out.println("value of property `" + name + "`: " + config.getInt(name));
                }
                case LONG -> {
                    System.out.println("value of property `" + name + "`: " + config.getLong(name));
                }
                case DOUBLE -> {
                    System.out.println("value of property `" + name + "`: " + config.getDouble(name));
                }
                case STRING -> {
                    System.out.println("value of property `" + name + "`: " + config.getString(name));
                }
                case BOOLEAN_LIST -> {
                    System.out.println("value of property `" + name + "`: " + config.getBooleanList(name));
                }
                case INT_LIST -> {
                    System.out.println("value of property `" + name + "`: " + config.getIntList(name));
                }
                case LONG_LIST -> {
                    System.out.println("value of property `" + name + "`: " + config.getLongList(name));
                }
                case DOUBLE_LIST -> {
                    System.out.println("value of property `" + name + "`: " + config.getDoubleList(name));
                }
                case STRING_LIST -> {
                    System.out.println("value of property `" + name + "`: " + config.getStringList(name));
                }
            }
        });
    }

    private static void createDatabase() throws Exception {
        try (
            var connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:test-db;DB_CLOSE_DELAY=-1", "admin", "secret1234"
            )
        ) {
            var statement = connection.createStatement();
            statement.execute(
                "CREATE TABLE config_properties (property_key VARCHAR(255), property_value VARCHAR(255))"
            );
            statement.execute(
                "INSERT INTO config_properties (property_key, property_value) "
                + "VALUES ('databaseGreeting', 'hello from the database!')"
            );
        }
        logger.info("database created and populated with test data");
    }
}
