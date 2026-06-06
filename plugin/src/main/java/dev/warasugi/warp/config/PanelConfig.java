package dev.warasugi.warp.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

public class PanelConfig {
    private final String host;
    private final int port;
    private final List<String> corsOrigins;
    private final String totpIssuer;
    private final String totpSecret;
    private final int loginMaxAttempts;
    private final long loginLockoutMs;
    private final int sessionHours;
    private final int logsMaxRows;
    private final int chatMaxRows;
    private final int historyMaxRows;
    private final List<String> commandBlocklist;

    public PanelConfig(FileConfiguration cfg) {
        host = cfg.getString("server.host", "127.0.0.1");
        port = cfg.getInt("server.port", 8080);
        corsOrigins = cfg.getStringList("server.cors-origins");
        totpIssuer = cfg.getString("auth.totp-issuer", "WARP");
        totpSecret = cfg.getString("auth.totp-secret", "");
        loginMaxAttempts = cfg.getInt("auth.login-max-attempts", 5);
        loginLockoutMs = cfg.getLong("auth.login-lockout-minutes", 10) * 60_000L;
        sessionHours = cfg.getInt("auth.session-hours", 8);
        logsMaxRows = cfg.getInt("storage.logs-max-rows", 100_000);
        chatMaxRows = cfg.getInt("storage.chat-max-rows", 50_000);
        historyMaxRows = cfg.getInt("storage.history-max-rows", 50_000);
        commandBlocklist = cfg.getStringList("console.command-blocklist");
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public String getTotpIssuer() { return totpIssuer; }
    public String getTotpSecret() { return totpSecret; }
    public int getLoginMaxAttempts() { return loginMaxAttempts; }
    public long getLoginLockoutMs() { return loginLockoutMs; }
    public int getSessionHours() { return sessionHours; }
    public int getLogsMaxRows() { return logsMaxRows; }
    public int getChatMaxRows() { return chatMaxRows; }
    public int getHistoryMaxRows() { return historyMaxRows; }
    public List<String> getCommandBlocklist() { return commandBlocklist; }
}
