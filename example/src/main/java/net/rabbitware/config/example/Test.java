package net.rabbitware.config.example;
import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

public class Test {
    public static void main(String[] args) {
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
}
