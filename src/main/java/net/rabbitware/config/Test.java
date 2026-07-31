package net.rabbitware.config;

public class Test {
    public static void main(String[] args) {
        Config config = ConfigFactory.create(args);

        IO.println("type of `foo`: " + config.getType("foo"));
        IO.println("value of `foo`: " + config.getInt("foo"));

        IO.println("type of `dbPassword`: " + config.getType("dbPassword"));
        IO.println("value of `dbPassword`: " + config.getString("dbPassword"));

        IO.println("type of `ports`: " + config.getType("ports"));
        IO.println("value of `ports`: " + config.getIntList("ports"));

        IO.println("type of `firstAndLastItemsAreEmpty`: " + config.getType("firstAndLastItemsAreEmpty"));
        IO.println("value of `firstAndLastItemsAreEmpty`: " + config.getStringList("firstAndLastItemsAreEmpty"));

        IO.println("value of `noSpace`: " + config.getString("noSpace"));
    }
}
