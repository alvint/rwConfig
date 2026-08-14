package net.rabbitware.config.example;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.rabbitware.config.Config;
import net.rabbitware.config.ConfigFactory;

public class Test {
    private static final Logger logger = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) throws Exception {
        createWebServer();
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


    private static void createWebServer() {
        HttpServer server = null;
        try {
            server = SimpleFileServer.createFileServer(
                new InetSocketAddress(1520),
                Path.of("web-test").toAbsolutePath(),
                SimpleFileServer.OutputLevel.INFO
            );
            // The server is started from a daemon thread so that the dispatcher
            // thread it creates inherits daemon status. A new thread is a daemon
            // thread if and only if the thread that created it is one, and the
            // server creates its dispatcher thread on whichever thread calls
            // `start`. Without this, that dispatcher thread would keep the JVM
            // alive after `main` returns, and nothing here ever stops the
            // server. Any failure is handed back so that it is still reported
            // by the `catch` below rather than being lost on the other thread.
            HttpServer serverToStart = server;
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread starter = new Thread(() -> {
                try {
                    serverToStart.start();
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            starter.setDaemon(true);
            starter.start();
            starter.join();
            if (failure.get() != null) {
                throw failure.get();
            }
            logger.info("web server started on port 1520");
            // if a future JDK stops creating the dispatcher thread on the
            // calling thread, the trick above silently stops working and this
            // JVM goes back to hanging on exit. say so rather than leaving
            // someone to work it out from a hung process
            if (
                Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> !t.isDaemon() && t.getName().startsWith("HTTP-Dispatcher"))
            ) {
                logger.warn(
                    "the web server's dispatcher thread is not a daemon thread, so this JVM will not exit"
                    + " on its own"
                );
            }
        } catch (Exception e) {
            logger.error("failed to start web server", e);
            if (server != null) {
                server.stop(0);
            }
        }
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
