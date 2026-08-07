package net.rabbitware.config;

public class Test {
    public static void main(String[] args) {
        Config config = ConfigFactory.create(args);

        Config.PropertyType type = config.getType("foo");
        IO.println("type of `foo`: " + type.name);
        IO.println("value of `foo`: " + config.getInt("foo"));

        IO.println("type of `dbPassword`: " + config.getType("dbPassword").name);
        IO.println("value of `dbPassword`: " + config.getString("dbPassword"));

        IO.println("type of `ports`: " + config.getType("ports").name);
        IO.println("value of `ports`: " + config.getIntList("ports"));

        IO.println("type of `firstAndLastItemsAreEmpty`: " + config.getType("firstAndLastItemsAreEmpty").name);
        IO.println("value of `firstAndLastItemsAreEmpty`: " + config.getStringList("firstAndLastItemsAreEmpty"));

        IO.println("value of `noSpace`: " + config.getString("noSpace"));

        IO.println("value of `emptyList`: " + config.getStringList("emptyList"));

        IO.println("value of `emptyIntList`: " + config.getIntList("emptyIntList"));
    }
}
