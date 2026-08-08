package net.rabbitware.config;

public class Test {
    public static void main(String[] args) {
        Config config = ConfigFactory.create(args);

        Config.PropertyType type = config.getType("foo");
        System.out.println("type of `foo`: " + type.name);
        System.out.println("value of `foo`: " + config.getInt("foo"));

        System.out.println("type of `dbPassword`: " + config.getType("dbPassword").name);
        System.out.println("value of `dbPassword`: " + config.getString("dbPassword"));

        System.out.println("type of `ports`: " + config.getType("ports").name);
        System.out.println("value of `ports`: " + config.getIntList("ports"));

        System.out.println("type of `firstAndLastItemsAreEmpty`: " + config.getType("firstAndLastItemsAreEmpty").name);
        System.out.println("value of `firstAndLastItemsAreEmpty`: " + config.getStringList("firstAndLastItemsAreEmpty"));

        System.out.println("value of `noSpace`: " + config.getString("noSpace"));

        System.out.println("value of `emptyList`: " + config.getStringList("emptyList"));

        System.out.println("value of `emptyIntList`: " + config.getIntList("emptyIntList"));
    }
}
