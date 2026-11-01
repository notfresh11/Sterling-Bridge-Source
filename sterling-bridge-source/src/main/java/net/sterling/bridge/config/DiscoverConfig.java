package net.sterling.bridge.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DiscoverConfig {
    private static final String DEFAULT_CATALOG_URL = "https://sterling-discover.cojocarumario80.workers.dev";
    private boolean enabled;
    private String nameServer = "";
    private String publicAddress = "";
    private int publicPort = 25565;
    private String catalogUrl = DEFAULT_CATALOG_URL;

    public static DiscoverConfig load(Path configPath) throws java.io.IOException {
        DiscoverConfig config = new DiscoverConfig();
        if (Files.notExists(configPath)) {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            config.save(configPath);
            return config;
        }

        Yaml yaml = new Yaml();
        try (InputStream input = Files.newInputStream(configPath)) {
            Map<String, Object> data = yaml.load(input);
            if (data == null) {
                return config;
            }
            Object value = data.get("enabled");
            if (value instanceof Boolean setting) {
                config.setEnabled(setting);
            }
            value = data.containsKey("name-server") ? data.get("name-server") : data.get("nameServer");
            if (value != null) {
                config.setNameServer(String.valueOf(value));
            }
            value = data.get("publicAddress");
            if (value != null) {
                config.setPublicAddress(String.valueOf(value));
            }
            value = data.get("publicPort");
            if (value instanceof Number number) {
                config.setPublicPort(number.intValue());
            }
            value = data.get("catalogUrl");
            if (value != null && !String.valueOf(value).isBlank()) {
                config.setCatalogUrl(String.valueOf(value));
            }
        }
        config.validate();
        return config;
    }

    public void save(Path configPath) throws java.io.IOException {
        if (configPath.getParent() != null) {
            Files.createDirectories(configPath.getParent());
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        data.put("name-server", nameServer);
        data.put("publicAddress", publicAddress);
        data.put("publicPort", publicPort);
        data.put("catalogUrl", catalogUrl);
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            writer.write("""
                    # Sterling Discover settings. Discover is off until you opt in.
                    # This file is created once; startup and /sterling reload do not overwrite existing settings.
                    # Change values, not setting names. Keep a backup before making changes.
                    #
                    # enabled: Set true to publish this server in Sterling Discover.
                    # name-server: Optional display-name override. Leave empty to use serverName from sterling.yml.
                    # publicAddress: Public hostname or IP players use to join. Do not include a scheme or port.
                    # publicPort: Public Minecraft port from your hosting panel; it may differ from the local port.
                    # catalogUrl: HTTPS origin of the Discover catalog. Change only for another catalog deployment.
                    #
                    # Do not delete or share discover-identity.json. It stores this server's private catalog credential.
                    """);
            new Yaml(options).dump(data, writer);
        }
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (nameServer.length() > 80 || nameServer.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Discover name-server must be at most 80 printable characters.");
        }
        if (publicAddress.isBlank()) {
            throw new IllegalArgumentException(
                    "Discover is enabled, but discover.yml has no publicAddress configured.");
        }
        if (publicPort < 1 || publicPort > 65535) {
            throw new IllegalArgumentException("Discover publicPort must be between 1 and 65535.");
        }
        if (catalogUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Discover is enabled, but discover.yml has no catalogUrl configured.");
        }
        URI catalog;
        try {
            catalog = URI.create(catalogUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Discover catalogUrl must be a valid HTTPS URL.", exception);
        }
        if (!"https".equalsIgnoreCase(catalog.getScheme()) || catalog.getHost() == null
                || catalog.getUserInfo() != null || catalog.getQuery() != null || catalog.getFragment() != null
                || (catalog.getPath() != null && !catalog.getPath().isEmpty() && !"/".equals(catalog.getPath()))) {
            throw new IllegalArgumentException(
                    "Discover catalogUrl must use HTTPS without credentials, path, query, or fragment.");
        }
        if (publicAddress.length() > 253 || publicAddress.chars().anyMatch(Character::isWhitespace)
                || publicAddress.contains("/") || publicAddress.contains("\\")
                || publicAddress.contains("@") || publicAddress.contains("?") || publicAddress.contains("#")) {
            throw new IllegalArgumentException(
                    "Discover publicAddress must be a hostname or IP address without a port.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer == null ? "" : nameServer.strip();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPublicAddress() {
        return publicAddress;
    }

    public void setPublicAddress(String publicAddress) {
        this.publicAddress = publicAddress == null ? "" : publicAddress.strip();
    }

    public int getPublicPort() {
        return publicPort;
    }

    public void setPublicPort(int publicPort) {
        this.publicPort = publicPort;
    }

    public String getCatalogUrl() {
        return catalogUrl;
    }

    public void setCatalogUrl(String catalogUrl) {
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.strip();
    }

    public void copyFrom(DiscoverConfig source) {
        setEnabled(source.isEnabled());
        setNameServer(source.getNameServer());
        setPublicAddress(source.getPublicAddress());
        setPublicPort(source.getPublicPort());
        setCatalogUrl(source.getCatalogUrl());
    }
}
