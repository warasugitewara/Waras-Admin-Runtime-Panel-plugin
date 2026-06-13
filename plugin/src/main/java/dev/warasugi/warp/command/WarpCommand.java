package dev.warasugi.warp.command;

import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.config.ConfigProvider;
import dev.warasugi.warp.web.handlers.AuthHandler;
import dev.warasugi.warp.metrics.MetricsCollector;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class WarpCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final AuthHandler auth;
    private final MetricsCollector metrics;
    private final ConfigProvider configProvider;

    public WarpCommand(Plugin plugin, AuthHandler auth, MetricsCollector metrics, ConfigProvider configProvider) {
        this.plugin = plugin;
        this.auth = auth;
        this.metrics = metrics;
        this.configProvider = configProvider;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("warp.admin")) {
            sender.sendMessage("§cPermission denied.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6WARP §7— usage: /warp <setup|status|reload|token>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "setup" -> handleSetup(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            case "token" -> handleToken(sender);
            default -> sender.sendMessage("§cUnknown subcommand. Use: setup|status|reload|token");
        }
        return true;
    }

    private void handleSetup(CommandSender sender) {
        String secret = TotpManager.generateSecret();
        TotpManager newTotp = new TotpManager(secret);
        auth.setTotpManager(newTotp);
        plugin.getConfig().set("auth.totp-secret", secret);
        plugin.saveConfig();
        configProvider.reload(plugin.getConfig());
        sender.sendMessage("§6[WARP] §aNew TOTP secret generated and saved!");
        sender.sendMessage("§7Secret: §e" + secret);
        sender.sendMessage("§7QR URI: §e" + newTotp.getQrUri("WARP"));
        sender.sendMessage("§7Scan the QR URI with Google Authenticator / Authy");
        sender.sendMessage("§aLogin is active immediately — no reload required.");
    }

    private void handleStatus(CommandSender sender) {
        var snap = metrics.getLatestSnapshot();
        sender.sendMessage("§6[WARP] §aServer Status:");
        sender.sendMessage("§7TPS: §e" + String.format("%.1f", snap.tps1()) +
                " §7/ §e" + String.format("%.1f", snap.tps5()) +
                " §7/ §e" + String.format("%.1f", snap.tps15()));
        sender.sendMessage("§7MSPT: §e" + String.format("%.1f", snap.mspt()) + "ms");
        sender.sendMessage("§7Players: §e" + snap.players());
        sender.sendMessage("§7Memory: §e" + snap.memoryUsedMb() + " MB");
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        configProvider.reload(plugin.getConfig());
        String newSecret = configProvider.get().getTotpSecret();
        if (newSecret != null && !newSecret.isBlank()) {
            auth.setTotpManager(new TotpManager(newSecret));
        }
        sender.sendMessage("§6[WARP] §aconfig.yml reloaded.");
        sender.sendMessage("§7反映済み: totp-secret / command-blocklist / storage 保持上限 / session-hours(Cookie寿命のみ)");
        sender.sendMessage("§7再起動が必要: server.host / server.port / server.cors-origins / auth.login-* / session-hours(JWT本体のTTL)");
    }

    private void handleToken(CommandSender sender) {
        String token = auth.issueOneTimeToken();
        sender.sendMessage("§6[WARP] §aOne-time token (5min): §e" + token);
    }

    private static final List<String> SUBCOMMANDS = List.of("setup", "status", "reload", "token");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("warp.admin")) return List.of();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(input)).toList();
        }
        return List.of();
    }
}
