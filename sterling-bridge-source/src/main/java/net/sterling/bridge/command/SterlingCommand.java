package net.sterling.bridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.sterling.bridge.config.DiscoverConfig;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.http.EmbeddedHttpServer;
import net.sterling.bridge.manifest.ManifestGenerator;
import net.sterling.bridge.manifest.ManifestModel;
import net.sterling.bridge.service.DiscoverClient;

import java.nio.file.Path;
import java.util.List;

public class SterlingCommand {
    private static final int OUTPUT_LINES_PER_MESSAGE = 15;

    private final SterlingConfig config;
    private final DiscoverConfig discoverConfig;
    private final ManifestGenerator manifestGenerator;
    private final DiscoverClient discoverClient;
    private final EmbeddedHttpServer httpServer;
    private final Path configPath;
    private final Path discoverConfigPath;

    public SterlingCommand(
            SterlingConfig config,
            DiscoverConfig discoverConfig,
            ManifestGenerator manifestGenerator,
            DiscoverClient discoverClient,
            EmbeddedHttpServer httpServer,
            Path configPath,
            Path discoverConfigPath) {
        this.config = config;
        this.discoverConfig = discoverConfig;
        this.manifestGenerator = manifestGenerator;
        this.discoverClient = discoverClient;
        this.httpServer = httpServer;
        this.configPath = configPath;
        this.discoverConfigPath = discoverConfigPath;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommand(dispatcher);
        });
    }

    public void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sterling")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("reload")
                        .executes(this::executeReload))
                .then(Commands.literal("sync")
                        .executes(this::executeSync))
                .then(Commands.literal("rollback")
                        .then(Commands.argument("snapshotId", StringArgumentType.word())
                                .executes(this::executeRollback)))
                .then(Commands.literal("list")
                        .then(Commands.literal("mods").executes(this::executeListMods))
                        .then(Commands.literal("excluded_mods").executes(this::executeListExcludedMods)))
                .then(Commands.literal("status")
                        .executes(this::executeStatus))
        );
    }

    public int executeReload(CommandContext<CommandSourceStack> context) {
        SterlingConfig newConfig = SterlingConfig.load(configPath);
        newConfig.validate();
        DiscoverConfig newDiscoverConfig;
        try {
            newDiscoverConfig = DiscoverConfig.load(discoverConfigPath);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not reload Sterling Discover configuration.", exception);
        }
        newDiscoverConfig.validate();
        boolean previousHttpEnabled = config.isEnableHttpServer();
        int previousHttpPort = config.getHttpPort();
        httpServer.reconfigure(newConfig.isEnableHttpServer(), newConfig.getHttpPort());
        try {
            manifestGenerator.generateAndSaveManifest(newConfig);
        } catch (RuntimeException publishFailure) {
            try {
                httpServer.reconfigure(previousHttpEnabled, previousHttpPort);
            } catch (RuntimeException rollbackFailure) {
                publishFailure.addSuppressed(rollbackFailure);
            }
            throw publishFailure;
        }
        config.setServerName(newConfig.getServerName());
        config.setServerIp(newConfig.getServerIp());
        config.setServerPort(newConfig.getServerPort());
        config.setEnableHttpServer(newConfig.isEnableHttpServer());
        config.setHttpPort(newConfig.getHttpPort());
        config.setPublicBaseUrl(newConfig.getPublicBaseUrl());
        config.setLauncherDownloadUrl(newConfig.getLauncherDownloadUrl());
        config.setKickMessage(newConfig.getKickMessage());
        config.setUriProtocol(newConfig.getUriProtocol());
        config.setExcludedMods(newConfig.getExcludedMods());
        config.setModpack(newConfig.isModpack());
        config.setModpackDownloadLink(newConfig.getModpackDownloadLink());
        discoverConfig.copyFrom(newDiscoverConfig);
        discoverClient.reload();

        context.getSource().sendSuccess(() -> Component.literal("§a[Sterling Bridge] Configuration reloaded and manifest synced!"), true);
        return 1;
    }

    public int executeSync(CommandContext<CommandSourceStack> context) {
        manifestGenerator.generateAndSaveManifest();
        context.getSource().sendSuccess(() -> Component.literal("§a[Sterling Bridge] Manifest successfully re-generated!"), true);
        return 1;
    }

    public int executeRollback(CommandContext<CommandSourceStack> context) {
        String snapshotId = StringArgumentType.getString(context, "snapshotId");
        manifestGenerator.restoreSnapshot(snapshotId);
        context.getSource().sendSuccess(
                () -> Component.literal("§a[Sterling Bridge] Restored snapshot " + snapshotId + "!"), true);
        return 1;
    }

    public int executeStatus(CommandContext<CommandSourceStack> context) {
        ManifestModel manifest = manifestGenerator.getActiveManifest();
        String listenerStatus = httpServer != null && httpServer.isRunning()
                ? "Running on port " + httpServer.getPort() : "Stopped";
        String msg = String.format("§e[Sterling Bridge] Status:\n§7- Configuration: valid\n§7- Server: %s\n§7- IP/Port: %s:%d\n§7- HTTP server configured: %s (configured port: %d; listener: %s)\n§7- Manifest endpoint: %s/manifest.json\n§7- Manifest identity fingerprint (SHA-256): %s\n§7- Public access: not externally verified; test this URL from outside the host network\n§7- Active snapshot: %s\n§7- Snapshot generated: %s\n§7- Included mods: %d\n§7- Excluded mod entries: %d\n§7- Modpack mode: %s\n§7- Discover listing: %s\n§7- Discover state: %s\n§7- Discover address: %s:%d",
                config.getServerName(),
                config.getServerIp(),
                config.getServerPort(),
                config.isEnableHttpServer() ? "Enabled" : "Disabled",
                config.getHttpPort(),
                listenerStatus,
                config.getPublicBaseUrl(),
                manifestGenerator.getIdentityFingerprint(),
                manifest.getSnapshotId(),
                manifest.getGeneratedAt(),
                manifest.getMods() == null ? 0 : manifest.getMods().size(),
                config.getExcludedMods().size(),
                config.isModpack() ? "Enabled" : "Disabled",
                discoverConfig.isEnabled() ? "Enabled" : "Disabled",
                discoverClient.getStatus(),
                discoverConfig.getPublicAddress().isBlank() ? "not set" : discoverConfig.getPublicAddress(),
                discoverConfig.getPublicPort()
        );
        context.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    public int executeListMods(CommandContext<CommandSourceStack> context) {
        ManifestModel manifest = manifestGenerator.getActiveManifest();
        if ("modrinth_mrpack".equals(manifest.getInstallType())) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "§e[Sterling Bridge] Modpack mode publishes a pack URL, not a local mod inventory."), false);
            return 1;
        }
        List<ManifestModel.ModEntry> mods = manifest.getMods() == null ? List.of() : manifest.getMods();
        if (mods.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§e[Sterling Bridge] No client mods are included in the active manifest."),
                    false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.literal("§e[Sterling Bridge] Included mods (" + mods.size() + "):"), false);
        for (int start = 0; start < mods.size(); start += OUTPUT_LINES_PER_MESSAGE) {
            int from = start;
            int to = Math.min(start + OUTPUT_LINES_PER_MESSAGE, mods.size());
            String lines = mods.subList(from, to).stream()
                    .map(mod -> "§7- " + mod.getFileName() + " (" + mod.getSizeBytes() + " bytes)")
                    .collect(java.util.stream.Collectors.joining("\n"));
            context.getSource().sendSuccess(() -> Component.literal(lines), false);
        }
        return 1;
    }

    public int executeListExcludedMods(CommandContext<CommandSourceStack> context) {
        List<String> excludedMods = config.getExcludedMods();
        if (excludedMods.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§e[Sterling Bridge] No mods are excluded."), false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.literal("§e[Sterling Bridge] Excluded mod entries (" + excludedMods.size() + "):"),
                false);
        for (int start = 0; start < excludedMods.size(); start += OUTPUT_LINES_PER_MESSAGE) {
            int from = start;
            int to = Math.min(start + OUTPUT_LINES_PER_MESSAGE, excludedMods.size());
            String lines = excludedMods.subList(from, to).stream()
                    .map(name -> "§7- " + name)
                    .collect(java.util.stream.Collectors.joining("\n"));
            context.getSource().sendSuccess(() -> Component.literal(lines), false);
        }
        return 1;
    }
}
