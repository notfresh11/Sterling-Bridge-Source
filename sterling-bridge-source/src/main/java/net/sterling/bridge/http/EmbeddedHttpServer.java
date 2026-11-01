package net.sterling.bridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class EmbeddedHttpServer {

    private int port;
    private final Path manifestPath;
    private final Path modsDir;
    private final Path snapshotsDir;
    private volatile HttpServer server;

    public EmbeddedHttpServer(int port, Path manifestPath, Path modsDir) {
        this.port = port;
        this.manifestPath = manifestPath.toAbsolutePath().normalize();
        this.modsDir = modsDir.toAbsolutePath().normalize();
        this.snapshotsDir = this.manifestPath.getParent().resolve("snapshots");
    }

    public synchronized void start() {
        reconfigure(true, port);
    }

    public synchronized void reconfigure(boolean enabled, int requestedPort) {
        if (!enabled) {
            stop();
            return;
        }
        if (server != null && port == requestedPort) {
            return;
        }

        HttpServer replacement;
        try {
            replacement = createServer(requestedPort);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not start Sterling Bridge HTTP server on port " + requestedPort + ".", e);
        }

        HttpServer previous = server;
        server = replacement;
        port = requestedPort;
        if (previous != null) {
            previous.stop(0);
        }
    }

    private HttpServer createServer(int requestedPort) throws IOException {
        HttpServer replacement = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        replacement.createContext("/manifest.json", new ManifestHandler());
        replacement.createContext("/mods/", new ModsHandler());
        replacement.createContext("/snapshots/", new SnapshotsHandler());
        replacement.setExecutor(null);
        try {
            replacement.start();
        } catch (RuntimeException exception) {
            replacement.stop(0);
            throw exception;
        }
        return replacement;
    }

    public synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getPort() {
        HttpServer current = server;
        return current == null ? port : current.getAddress().getPort();
    }

    private class ManifestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!Files.exists(manifestPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = Files.readAllBytes(manifestPath);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ModsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String fileName = path.substring("/mods/".length());
            Path modFile = modsDir.resolve(fileName).normalize();

            if (!modFile.startsWith(modsDir) || Files.notExists(modFile) || !Files.isRegularFile(modFile)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            long fileSize = Files.size(modFile);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, fileSize);

            try (InputStream is = Files.newInputStream(modFile);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }

    private class SnapshotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            String relativePath = requestPath.substring("/snapshots/".length());
            Path requestedFile = snapshotsDir.resolve(relativePath).normalize();
            if (!requestedFile.startsWith(snapshotsDir) || !isSafeSnapshotPath(requestedFile)
                    || !Files.isRegularFile(requestedFile)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            long fileSize = Files.size(requestedFile);
            exchange.getResponseHeaders().set("Content-Type",
                    requestedFile.getFileName().toString().endsWith(".json")
                            ? "application/json; charset=utf-8" : "application/java-archive");
            exchange.sendResponseHeaders(200, fileSize);
            try (InputStream input = Files.newInputStream(requestedFile);
                 OutputStream output = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        }

        private boolean isSafeSnapshotPath(Path requestedFile) {
            if (Files.isSymbolicLink(snapshotsDir)) {
                return false;
            }
            Path current = snapshotsDir;
            for (Path segment : snapshotsDir.relativize(requestedFile)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    return false;
                }
            }
            return true;
        }
    }
}
