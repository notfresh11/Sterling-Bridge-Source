package net.sterling.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.sterling.bridge.command.SterlingCommand;
import net.sterling.bridge.config.DiscoverConfig;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.http.EmbeddedHttpServer;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.service.DiscoverClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class SterlingBridgeMod implements ModInitializer {
    public static final String MOD_ID = "sterling-bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SterlingConfig config;
    private DiscoverConfig discoverConfig;
    private EmbeddedHttpServer httpServer;
    private ManifestGenerator manifestGenerator;
    private DiscoverClient discoverClient;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Sterling Bridge (MC 26.3 / Fabric / Java 25)...");

        Path configPath = getConfigPath();
        Path configDir = configPath.getParent();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path modsDir = gameDir.resolve("mods");
        Path manifestPath = configDir.resolve("manifest.json");

        // Load and validate both server configs.
        config = SterlingConfig.load(configPath);
        config.validate();
        try {
            discoverConfig = DiscoverConfig.load(getDiscoverConfigPath());
            discoverConfig.validate();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not load Sterling Discover configuration.", exception);
        }

        // Build the manifest from the running server versions.
        String minecraftVersion = SharedConstants.getCurrentVersion().name();
        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .orElseThrow(() -> new IllegalStateException("Fabric Loader metadata is unavailable."))
                .getMetadata().getVersion().getFriendlyString();
        manifestGenerator = new ManifestGenerator(
                modsDir, manifestPath, config, minecraftVersion, loaderVersion);
        manifestGenerator.generateAndSaveManifest();

        // Serve the manifest and published mod files.
        httpServer = new EmbeddedHttpServer(config.getHttpPort(), manifestPath, modsDir);
        httpServer.reconfigure(config.isEnableHttpServer(), config.getHttpPort());
        try {
            discoverClient = new DiscoverClient(
                    config,
                    discoverConfig,
                    configDir.resolve("discover-identity.json"),
                    LOGGER);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not load Sterling Discover identity.", exception);
        }
        // Keep Discover and HTTP services tied to the server lifecycle.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (discoverConfig.isEnabled() && server.getPort() != discoverConfig.getPublicPort()) {
                LOGGER.warn(
                        "Sterling Discover publicPort is {}, but this server is listening on {}. "
                                + "Set publicPort to the PebbleHost-assigned player port if they should match.",
                        discoverConfig.getPublicPort(),
                        server.getPort());
            }
            discoverClient.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            discoverClient.close();
            if (httpServer != null) {
                httpServer.stop();
            }
        });

        // Register the /sterling commands.
        SterlingCommand command = new SterlingCommand(
                config, discoverConfig, manifestGenerator, discoverClient, httpServer,
                configPath, getDiscoverConfigPath());
        command.register();

        LOGGER.info("Sterling Bridge successfully initialized!");
    }

    static Path getConfigPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("config")
                .resolve("sterling")
                .resolve("sterling.yml");
    }

    static Path getDiscoverConfigPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("config")
                .resolve("sterling")
                .resolve("discover.yml");
    }
}
