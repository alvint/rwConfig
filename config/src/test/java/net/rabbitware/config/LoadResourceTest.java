package net.rabbitware.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

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
        assertEquals("greeting=hello", SimpleConfigSourcePlugin.loadResource("file:" + file));
    }

    @Test
    void aClasspathLocationIsRead() throws Exception {
        // this test class is on the test classpath, so it can find itself
        String content = SimpleConfigSourcePlugin.loadResource(
            "classpath:net/rabbitware/config/load-resource-test.txt"
        );
        assertEquals("loaded from the classpath", content.strip());
    }

    @Test
    void aMissingClasspathLocationIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SimpleConfigSourcePlugin.loadResource("classpath:no/such/resource")
        );
    }

    @Test
    void aLocationWithNoSchemeIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SimpleConfigSourcePlugin.loadResource("just-a-path")
        );
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
                () -> SimpleConfigSourcePlugin.loadResource(location)
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
                elapsedMillis < SimpleConfigSourcePlugin.READ_TIMEOUT_MILLIS * 3L,
                "expected the read to time out near " + SimpleConfigSourcePlugin.READ_TIMEOUT_MILLIS
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
        assertThrows(Exception.class, () -> SimpleConfigSourcePlugin.loadResource(location));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(
            elapsedMillis < SimpleConfigSourcePlugin.CONNECT_TIMEOUT_MILLIS,
            "a refused connection should not wait for the connect timeout, but took " + elapsedMillis + "ms"
        );
    }
}
