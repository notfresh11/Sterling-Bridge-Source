package net.sterling.bridge.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SterlingConfig {

    private String serverName = "Sterling Modded Server";
    private String serverIp = "localhost";
    private int serverPort = 25565;
    private boolean enableHttpServer = true;
    private int httpPort = 8080;
    private String publicBaseUrl = "http://localhost:8080";
    private String launcherDownloadUrl = "https://sterling.net/download";
    private String kickMessage = "&c[Sterling Bridge]\n&7You need Sterling Launcher to join this modded server!\n&eClick to sync mods: &b{uri_protocol}\n&eDirect download: &a{download_url}";
    private String uriProtocol = "sterling://connect?ip={server_ip}&port={server_port}&manifest={manifest_url}";
    private List<String> excludedMods = new ArrayList<>();
    private boolean modpack;
    private String modpackDownloadLink = "";

    public static SterlingConfig load(Path configPath) {
        SterlingConfig config = new SterlingConfig();
        try {
            if (Files.notExists(configPath)) {
                if (configPath.getParent() != null) {
                    Files.createDirectories(configPath.getParent());
                }
                config.save(configPath);
                return config;
            }

            Yaml yaml = new Yaml();
            try (InputStream is = Files.newInputStream(configPath)) {
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    Object value = get(data, "serverName", "server-name");
                    if (value != null) config.setServerName(String.valueOf(value));
                    value = get(data, "serverIp", "server-ip");
                    if (value != null) config.setServerIp(String.valueOf(value));
                    value = get(data, "serverPort", "server-port");
                    if (value instanceof Number number) config.setServerPort(number.intValue());
                    value = get(data, "enableHttpServer", "enable-http-server");
                    if (value instanceof Boolean enabled) config.setEnableHttpServer(enabled);
                    value = get(data, "httpPort", "http-port");
                    if (value instanceof Number number) config.setHttpPort(number.intValue());
                    value = get(data, "publicBaseUrl", "custom-manifest-url");
                    if (value != null && !String.valueOf(value).isBlank()) config.setPublicBaseUrl(String.valueOf(value));
                    value = get(data, "launcherDownloadUrl", "launcher-download-url");
                    if (value != null && !String.valueOf(value).isBlank()) config.setLauncherDownloadUrl(String.valueOf(value));
                    value = get(data, "kickMessage", "kick-message");
                    if (value != null) config.setKickMessage(String.valueOf(value));
                    value = get(data, "uriProtocol", "uri-protocol");
                    if (value != null) config.setUriProtocol(String.valueOf(value));
                    value = get(data, "excludedMods", "excluded-mods");
                    if (value instanceof List<?> list) {
                        config.setExcludedMods(list.stream().map(String::valueOf).toList());
                    }
                    value = get(data, "modpack", "modpack-enabled");
                    if (value instanceof Boolean enabled) config.setModpack(enabled);
                    value = get(data, "modpack-download-link", "modpackDownloadLink");
                    if (value != null) config.setModpackDownloadLink(String.valueOf(value));
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load Sterling configuration from " + configPath + ".",
                    exception);
        }
        return config;
    }

    public void save(Path configPath) {
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml yaml = new Yaml(options);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serverName", serverName);
            data.put("serverIp", serverIp);
            data.put("serverPort", serverPort);
            data.put("enableHttpServer", enableHttpServer);
            data.put("httpPort", httpPort);
            data.put("publicBaseUrl", publicBaseUrl);
            data.put("launcherDownloadUrl", launcherDownloadUrl);
            data.put("kickMessage", kickMessage);
            data.put("uriProtocol", uriProtocol);
            data.put("excludedMods", excludedMods);
            data.put("modpack", modpack);
            data.put("modpack-download-link", modpackDownloadLink);

            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                writer.write("""
                        # Sterling Bridge server settings.
                        # This file is created once. Startup and /sterling reload do not overwrite existing settings.
                        # Change values, not setting names. Keep a backup before making changes.
                        #
                        # serverName: Name shown to players and in the manifest.
                        # serverIp/serverPort: Game address used in the Sterling join link; defaults to localhost for local testing.
                        #   For remote players, set serverIp to the public hostname and serverPort to the public game port.
                        # enableHttpServer/httpPort: Serve the manifest and mod files from Bridge. Open this port to players.
                        # publicBaseUrl: URL clients use to reach the manifest and versioned files; defaults to localhost for local testing.
                        #   For remote players, set a public URL/CDN that serves the published snapshot paths.
                        # launcherDownloadUrl: URL used by the {download_url} kick-message placeholder.
                        # kickMessage: Text shown when Bridge detects the supported registry-sync incompatibility.
                        # uriProtocol: Template used to build AutoMod's server link.
                        # excludedMods: JAR filenames or filename stems to leave out of the client download list.
                        #   Add server-only mods here; do not enter paths.
                        # modpack: Set true to publish a direct Modrinth .mrpack link in the signed manifest.
                        # modpack-download-link: Direct HTTPS URL ending in .mrpack; used only when modpack is true.
                        #
                        # Do not delete or share manifest-signing-identity.json. It contains the server's private signing key.
                        """);
                StringWriter yamlWriter = new StringWriter();
                yaml.dump(data, yamlWriter);
                String yamlText = yamlWriter.toString()
                        .replace("kickMessage:",
                                "# Keep {uri_protocol} in this message. AutoMod reads it to open this server's setup screen.\n"
                                        + "kickMessage:")
                        .replace("uriProtocol:",
                                "# Keep the ip, port, and manifest parameters in this link template.\n"
                                        + "uriProtocol:");
                writer.write(yamlText);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not save Sterling configuration to " + configPath + ".",
                    exception);
        }
    }

    private static Object get(Map<String, Object> data, String currentKey, String legacyKey) {
        return data.containsKey(currentKey) ? data.get(currentKey) : data.get(legacyKey);
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getServerIp() { return serverIp; }
    public void setServerIp(String serverIp) { this.serverIp = serverIp; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public boolean isEnableHttpServer() { return enableHttpServer; }
    public void setEnableHttpServer(boolean enableHttpServer) { this.enableHttpServer = enableHttpServer; }

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public String getLauncherDownloadUrl() { return launcherDownloadUrl; }
    public void setLauncherDownloadUrl(String launcherDownloadUrl) { this.launcherDownloadUrl = launcherDownloadUrl; }

    public String getKickMessage() { return kickMessage; }
    public void setKickMessage(String kickMessage) { this.kickMessage = kickMessage; }

    public String getUriProtocol() { return uriProtocol; }
    public void setUriProtocol(String uriProtocol) { this.uriProtocol = uriProtocol; }

    public List<String> getExcludedMods() { return List.copyOf(excludedMods); }
    public void setExcludedMods(List<String> excludedMods) {
        this.excludedMods = excludedMods == null ? new ArrayList<>() : new ArrayList<>(excludedMods);
    }

    public boolean isModpack() { return modpack; }
    public void setModpack(boolean modpack) { this.modpack = modpack; }

    public String getModpackDownloadLink() { return modpackDownloadLink; }
    public void setModpackDownloadLink(String modpackDownloadLink) {
        this.modpackDownloadLink = modpackDownloadLink == null ? "" : modpackDownloadLink.strip();
    }

    public void validate() {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName must not be blank.");
        }
        if (serverIp == null || serverIp.isBlank()) {
            throw new IllegalArgumentException("serverIp must not be blank.");
        }
        validatePort(serverPort, "serverPort");
        validatePort(httpPort, "httpPort");
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalArgumentException("publicBaseUrl must be a reachable HTTP(S) base URL.");
        }
        URI baseUri = parseUri(publicBaseUrl, "publicBaseUrl");
        if (baseUri.getHost() == null || baseUri.getUserInfo() != null
                || (!"http".equalsIgnoreCase(baseUri.getScheme())
                && !"https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("publicBaseUrl must be an HTTP(S) URL without credentials, query, or fragment.");
        }
        for (String excludedMod : excludedMods) {
            if (excludedMod == null || excludedMod.isBlank()
                    || excludedMod.contains("/") || excludedMod.contains("\\")
                    || excludedMod.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "excludedMods entries must be JAR filenames or filename stems, not paths.");
            }
        }
        if (modpack) {
            validateModpackLink();
        }
    }

    private void validateModpackLink() {
        URI link = parseUri(modpackDownloadLink, "modpack-download-link");
        String host = link.getHost();
        String path = link.getPath();
        if (host != null && (host.toLowerCase(java.util.Locale.ROOT).contains("curseforge")
                || host.toLowerCase(java.util.Locale.ROOT).contains("forgecdn"))) {
            throw new IllegalArgumentException(
                    "CurseForge modpacks are not supported yet. Use a direct Modrinth .mrpack link.");
        }
        if (!"https".equalsIgnoreCase(link.getScheme()) || host == null || link.getUserInfo() != null
                || path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".mrpack")) {
            throw new IllegalArgumentException(
                    "modpack-download-link must be a direct HTTPS link to a Modrinth .mrpack file.");
        }
    }

    private static URI parseUri(String value, String settingName) {
        URI link;
        try {
            link = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(settingName + " is not a valid URL.", exception);
        }
        return link;
    }

    private static void validatePort(int port, String settingName) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(settingName + " must be between 1 and 65535.");
        }
    }
}
