package dev.warasugi.warp.command;

import dev.warasugi.warp.auth.TotpManager;
import dev.warasugi.warp.web.handlers.AuthHandler;
import dev.warasugi.warp.metrics.MetricsCollector;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class WarpCommand implements CommandExecutor {
    private final Plugin plugin;
    private final AuthHandler auth;
    private final MetricsCollector metrics;
    // TotpManager を外から受け取る (setup でセットする場合もあるため)
    private TotpManager totpManagerRef; // mutable — setup時に設定

    public WarpCommand(Plugin plugin, AuthHandler auth, MetricsCollector metrics) {
        this.plugin = plugin;
        this.auth = auth;
        this.metrics = metrics;
    }

    public void setTotpManager(TotpManager totp) {
        this.totpManagerRef = totp;
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
        this.totpManagerRef = newTotp;
        // config.yml に保存 (WarpPlugin 経由で行う想定だが、ここでは通知のみ)
        sender.sendMessage("§6[WARP] §aNew TOTP secret generated!");
        sender.sendMessage("§7Secret: §e" + secret);
        sender.sendMessage("§7QR URI: §e" + newTotp.getQrUri("WARP"));
        sender.sendMessage("§7Scan the QR URI with Google Authenticator / Authy");
        sender.sendMessage("§c§lIMPORTANT: §7Add 'totp-secret: " + secret + "' to plugins/WARP/config.yml and reload!");
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
        sender.sendMessage("§6[WARP] §aconfig.yml reloaded.");
    }

    private void handleToken(CommandSender sender) {
        String token = auth.issueOneTimeToken();
        sender.sendMessage("§6[WARP] §aOne-time token (5min): §e" + token);
    }
}
