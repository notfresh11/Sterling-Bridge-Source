package net.sterling.bridge;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.sterling.bridge.config.SterlingConfig;
import net.sterling.bridge.handler.RegistryCompatibilityHandler;

public class SterlingBridgePreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        SterlingConfig config = SterlingConfig.load(SterlingBridgeMod.getConfigPath());
        config.validate();
        new RegistryCompatibilityHandler(config).register();
    }
}
