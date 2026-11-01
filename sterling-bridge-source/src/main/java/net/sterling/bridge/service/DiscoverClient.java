package net.sterling.bridge.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.sterling.bridge.config.DiscoverConfig;
import net.sterling.bridge.config.SterlingConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscoverClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long HEARTBEAT_MINUTES = 10;
    private final SterlingConfig serverConfig;
    private final DiscoverConfig discoverConfig;
    private final DiscoverIdentityStore identityStore;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Gson gson = new Gson();
    private final Logger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sterling Discover");
        thread.setDaemon(true);
        return thread;
    });
    private DiscoverIdentityStore.Identity identity;
    private boolean started;
    private volatile String status = "Not started";

    public DiscoverClient(
            SterlingConfig serverConfig,
            DiscoverConfig discoverConfig,
            Path identityPath,
            Logger logger
    ) throws IOException {
        this.serverConfig = serverConfig;
        this.discoverConfig = discoverConfig;
        this.identityStore = new DiscoverIdentityStore(identityPath);
        this.identity = identityStore.load();
        this.logger = logger;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        status = discoverConfig.isEnabled() ? "Starting" : "Disabled";
        executor.execute(this::runSafely);
        executor.scheduleWithFixedDelay(
                this::runSafely, HEARTBEAT_MINUTES, HEARTBEAT_MINUTES, TimeUnit.MINUTES);
    }

    public void reload() {
        if (started) {
            status = discoverConfig.isEnabled() ? "Starting" : "Disabling";
            executor.execute(this::runSafely);
        }
    }

    public String getStatus() {
        return status;
    }

    private void runSafely() {
        try {
            if (!discoverConfig.isEnabled()) {
                withdraw();
                status = "Disabled";
            } else {
                discoverConfig.validate();
                sync();
                status = identity.hasServerCredential() ? "Listed"
                        : "Enabled";
            }
        } catch (Exception exception) {
            status = "Error: " + exception.getMessage();
            logger.error("Sterling Discover synchronization failed.", exception);
        }
    }

    private void sync() throws Exception {
        if (!identity.getCatalogUrl().isBlank()
                && !identity.getCatalogUrl().equals(discoverConfig.getCatalogUrl())) {
            withdraw();
            if (identity.hasServerCredential()) {
                logger.error("Cannot switch Sterling Discover catalogs until the previous listing is withdrawn.");
                return;
            }
        }
        identity.setCatalogUrl(discoverConfig.getCatalogUrl());

        if (identity.hasServerCredential()) {
            HttpResponseWrapper response = send(
                    "PUT",
                    endpoint("/v1/servers/" + identity.getServerId() + "/heartbeat"),
                    identity.getCredential(),
                    serverMetadata().toString());
            if (response.statusCode() == 401) {
                identity.clearServerCredential();
                identityStore.save(identity);
            } else if (!successful(response)) {
                throw responseError("heartbeat", response);
            } else {
                logger.info("Sterling Discover listing heartbeat sent.");
                return;
            }
        }

        registerAndHeartbeat();
    }

    private void registerAndHeartbeat() throws Exception {
        if (identity.hasPendingEnrollment()) {
            identity.clearPendingEnrollment();
            identityStore.save(identity);
        }
        HttpResponseWrapper response = send(
                "POST",
                endpoint("/v1/servers/register"),
                null,
                serverMetadata().toString());
        if (response.statusCode() != 201) {
            throw responseError("registration", response);
        }
        JsonObject payload = gson.fromJson(response.body(), JsonObject.class);
        identity.setServerId(requiredString(payload, "serverId"));
        identity.setCredential(requiredString(payload, "credential"));
        identity.clearPendingEnrollment();
        identityStore.save(identity);

        HttpResponseWrapper heartbeat = send(
                "PUT",
                endpoint("/v1/servers/" + identity.getServerId() + "/heartbeat"),
                identity.getCredential(),
                serverMetadata().toString());
        if (!successful(heartbeat)) {
            throw responseError("initial heartbeat", heartbeat);
        }
        logger.info("Sterling Discover listing is active and marked unverified.");
    }

    private void withdraw() throws Exception {
        if (identity.hasServerCredential()) {
            String catalogUrl = identity.getCatalogUrl().isBlank()
                    ? discoverConfig.getCatalogUrl() : identity.getCatalogUrl();
            HttpResponseWrapper response = send(
                    "DELETE",
                    endpoint(catalogUrl, "/v1/servers/" + identity.getServerId()),
                    identity.getCredential(),
                    null);
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                throw responseError("listing withdrawal", response);
            }
            identity.clearServerCredential();
        }
        identity.clearPendingEnrollment();
        identity.setCatalogUrl(discoverConfig.getCatalogUrl());
        identityStore.save(identity);
    }

    private JsonObject serverMetadata() {
        JsonObject metadata = new JsonObject();
        String listingName = discoverConfig.getNameServer().isBlank()
                ? serverConfig.getServerName()
                : discoverConfig.getNameServer();
        metadata.addProperty("name", listingName);
        metadata.addProperty("address", discoverConfig.getPublicAddress());
        metadata.addProperty("port", discoverConfig.getPublicPort());
        metadata.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        metadata.addProperty("loaderType", "fabric");
        metadata.addProperty("loaderVersion", FabricLoader.getInstance()
                .getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElseThrow(() -> new IllegalStateException("Fabric Loader version is unavailable.")));
        String manifestUrl = publicManifestUrl();
        if (manifestUrl != null) {
            metadata.addProperty("manifestUrl", manifestUrl);
        }
        return metadata;
    }

    private String publicManifestUrl() {
        String baseUrl = serverConfig.getPublicBaseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String configuredUrl = baseUrl + "/manifest.json";
        URI manifestUri = URI.create(configuredUrl);
        String scheme = manifestUri.getScheme();
        String host = manifestUri.getHost();
        if (host == null || manifestUri.getUserInfo() != null
                || manifestUri.getQuery() != null || manifestUri.getFragment() != null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Sterling Discover requires a valid public manifest URL.");
        }
        String path = manifestUri.getPath();
        if (serverConfig.isModpack()
                && (path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".mrpack"))) {
            throw new IllegalArgumentException("Sterling Discover modpack URL must point directly to a .mrpack file.");
        }
        if (isLocalHost(host)) {
            return null;
        }
        return manifestUri.toString();
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")
                || normalized.endsWith(".test") || normalized.endsWith(".example")
                || normalized.endsWith(".invalid")) {
            return true;
        }
        if (normalized.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            String[] parts = normalized.split("\\.");
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168) || (first == 100 && second >= 64 && second <= 127);
        }
        return normalized.equals("::1") || normalized.startsWith("fc")
                || normalized.startsWith("fd") || normalized.startsWith("fe8")
                || normalized.startsWith("fe9") || normalized.startsWith("fea")
                || normalized.startsWith("feb");
    }

    private HttpResponseWrapper send(String method, URI uri, String credential, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (credential != null) {
            builder.header("Authorization", "Bearer " + credential);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 32 * 1024) {
                    throw new IOException("Sterling Discover returned an oversized response.");
                }
                output.write(buffer, 0, read);
            }
            return new HttpResponseWrapper(response.statusCode(), output.toString(StandardCharsets.UTF_8));
        }
    }

    private URI endpoint(String path) {
        return endpoint(discoverConfig.getCatalogUrl(), path);
    }

    private static URI endpoint(String baseUrl, String path) {
        URI base = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath()))) {
            throw new IllegalArgumentException("Sterling Discover catalog URL must be a plain HTTPS origin.");
        }
        return URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path);
    }

    private static String requiredString(JsonObject payload, String property) throws IOException {
        if (payload == null || !payload.has(property) || !payload.get(property).isJsonPrimitive()) {
            throw new IOException("Sterling Discover response is missing " + property + ".");
        }
        String value = payload.get(property).getAsString();
        if (value.isBlank()) {
            throw new IOException("Sterling Discover response contains an empty " + property + ".");
        }
        return value;
    }

    private static boolean successful(HttpResponseWrapper response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static IOException responseError(String operation, HttpResponseWrapper response) {
        return new IOException("Sterling Discover " + operation + " returned HTTP " + response.statusCode() + ".");
    }

    private record HttpResponseWrapper(int statusCode, String body) {
    }

    @Override
    public void close() {
        if (!started) {
            executor.shutdownNow();
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    withdraw();
                } catch (Exception exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }).get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.warn("Could not withdraw Sterling Discover listing during shutdown: {}", exception.getMessage());
        } finally {
            executor.shutdownNow();
            status = "Stopped";
        }
    }
}
