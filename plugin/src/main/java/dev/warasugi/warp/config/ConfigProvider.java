package dev.warasugi.warp.config;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigProvider {
    private volatile PanelConfig config;

    public ConfigProvider(PanelConfig initial) {
        this.config = initial;
    }

    public PanelConfig get() {
        return config;
    }

    public void reload(FileConfiguration cfg) {
        this.config = new PanelConfig(cfg);
    }
}
