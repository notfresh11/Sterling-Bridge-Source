package net.sterling.bridge.handler;

import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.util.KickMessageFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class RegistryCompatibilityHandler {

    private static final Identifier REGISTRY_SYNC_CHANNEL = Identifier.fromNamespaceAndPath("fabric", "registry/sync");
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryCompatibilityHandler.class);

    private final SterlingConfig config;

    public RegistryCompatibilityHandler(SterlingConfig config) {
        this.config = config;
    }

    public void register() {
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((listener, server) -> {
            Map<Identifier, ?> syncedRegistries = RegistrySyncManager.createAndPopulateRegistryMap();
            boolean hasRequiredRegistries = syncedRegistries != null && syncedRegistries.keySet().stream()
                    .map(BuiltInRegistries.REGISTRY::getValue)
                    .filter(registry -> registry != null)
                    .map(RegistryAttributeHolder::get)
                    .anyMatch(attributes -> !attributes.hasAttribute(RegistryAttribute.OPTIONAL));

            boolean clientSupportsRegistrySync = ServerConfigurationNetworking.canSend(
                    listener, REGISTRY_SYNC_CHANNEL);
            LOGGER.info("Sterling registry compatibility check: requiredRegistries={}, "
                            + "clientSupportsRegistrySync={}",
                    hasRequiredRegistries, clientSupportsRegistrySync);
            if (hasRequiredRegistries && !clientSupportsRegistrySync) {
                listener.disconnect(KickMessageFormatter.create(config));
            }
        });
    }
}
