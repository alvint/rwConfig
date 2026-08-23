package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.rabbitware.config.plugin.api.LocationBasedConfigSourcePlugin;

/**
 * Tests for loading a config source from a location.
 *
 * <p>The timeout tests matter because a config source that never answers used
 * to block startup forever, with nothing logged - which is the opposite of the
 * fail-fast behavior this library is meant to have.
 */
class LoadResourceTest {

    @TempDir
    private Path tempDir;

    @Test
    void aFileLocationIsRead() throws Exception {
        Path file = tempDir.resolve("thing.properties");
        Files.writeString(file, "greeting=hello");
        assertEquals("greeting=hello", LocationBasedConfigSourcePlugin.loadResource("file:" + file));
    }

    @Test
    void aClasspathLocationIsRead() throws Exception {
        // this test class is on the test classpath, so it can find itself
        String content = LocationBasedConfigSourcePlugin.loadResource(
            "classpath:net/rabbitware/config/load-resource-test.txt"
        );
        assertEquals("loaded from the classpath", content.strip());
    }

    @Test
    void aMissingClasspathLocationIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> LocationBasedConfigSourcePlugin.loadResource("classpath:no/such/resource")
        );
    }

    @Test
    void aLocationWithNoSchemeIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> LocationBasedConfigSourcePlugin.loadResource("just-a-path")
        );
    }

    @Test
    @DisplayName("a jar: location reads an entry from inside a jar file")
    void aJarLocationIsRead() throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("inner.properties"), "greeting=from inside a jar");
        Path jar = tempDir.resolve("bundle.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry("inner.properties"));
            out.write(Files.readAllBytes(classes.resolve("inner.properties")));
            out.closeEntry();
        }
        assertEquals(
            "greeting=from inside a jar",
            LocationBasedConfigSourcePlugin.loadResource("jar:file:" + jar + "!/inner.properties")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "classpath:a.properties",
        "file:a.properties",
        "file:/tmp/a.properties",
        "jar:file:/tmp/a.jar!/a.properties",
        "http://example.com/a.properties",
        "https://example.com/a.properties",
    })
    @DisplayName("the location prefixes the documentation lists are supported")
    void supportedLocations(String location) {
        assertTrue(
            LocationBasedConfigSourcePlugin.isSupportedLocation(location),
            location + " should be a supported location"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.properties", "/tmp/a.properties", "ftp://example.com/a", "", "   "})
    @DisplayName("anything without a supported prefix is not a location")
    void unsupportedLocations(String location) {
        assertFalse(
            LocationBasedConfigSourcePlugin.isSupportedLocation(location),
            location + " should not be a supported location"
        );
    }

    @Test
    @DisplayName("a location prefix is recognized regardless of case or surrounding space")
    void locationPrefixesAreCaseInsensitive() {
        assertTrue(LocationBasedConfigSourcePlugin.isSupportedLocation("FILE:a.properties"));
        assertTrue(LocationBasedConfigSourcePlugin.isSupportedLocation("  https://example.com/a  "));
    }

    @Test
    // Without this, losing the timeouts would hang the build rather than fail
    // it. The separate thread mode is the point: the default mode only checks
    // the elapsed time once the test method returns, which never happens if the
    // read blocks forever.
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a server that accepts the connection but never answers times out rather than hanging")
    void anUnresponsiveServerTimesOut() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread blackHole = new Thread(() -> {
                try {
                    socket.accept(); // accept, then never write a response
                    accepted.countDown();
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Exception e) {
                    // the socket is closed when the test finishes
                }
            });
            blackHole.setDaemon(true);
            blackHole.start();

            String location = "http://" + socket.getInetAddress().getHostAddress()
                + ":" + socket.getLocalPort() + "/whatever.properties";
            long start = System.nanoTime();
            Exception e = assertThrows(
                Exception.class,
                () -> LocationBasedConfigSourcePlugin.loadResource(location)
            );
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(
                e instanceof SocketTimeoutException,
                "expected a socket timeout, but got: " + e
            );
            assertTrue(
                accepted.await(1, TimeUnit.SECONDS),
                "the server should have accepted the connection, so this is a read timeout"
            );
            // generous upper bound - the point is that it returns at all
            assertTrue(
                elapsedMillis < LocationBasedConfigSourcePlugin.READ_TIMEOUT_MILLIS * 3L,
                "expected the read to time out near " + LocationBasedConfigSourcePlugin.READ_TIMEOUT_MILLIS
                    + "ms, but it took " + elapsedMillis + "ms"
            );
        }
    }

    @Test
    @DisplayName("a port with nothing listening fails immediately rather than timing out")
    void aRefusedConnectionFailsFast() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        } // closed, so nothing is listening on this port now
        String location = "http://127.0.0.1:" + closedPort + "/whatever.properties";
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> LocationBasedConfigSourcePlugin.loadResource(location));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(
            elapsedMillis < LocationBasedConfigSourcePlugin.CONNECT_TIMEOUT_MILLIS,
            "a refused connection should not wait for the connect timeout, but took " + elapsedMillis + "ms"
        );
    }
}
