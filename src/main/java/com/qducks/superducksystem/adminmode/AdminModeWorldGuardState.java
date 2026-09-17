package com.qducks.superducksystem.adminmode;

import com.qducks.superducksystem.SuperDuckSystem;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.session.Session;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

final class AdminModeWorldGuardState {
    private static final String KEY = "abuse_admin_worldguard_bypass";

    private AdminModeWorldGuardState() {}

    static void captureAdminState(SuperDuckSystem plugin, Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
            boolean bypassEnabled = !session.hasBypassDisabled();
            player.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, KEY),
                    PersistentDataType.BYTE,
                    (byte) (bypassEnabled ? 1 : 0)
            );
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not remember WorldGuard bypass state for " + player.getName() + ": " + throwable.getMessage());
        }
    }

    static void restoreWhenAdminReady(SuperDuckSystem plugin, AdminModeService service, Player player) {
        NamespacedKey key = new NamespacedKey(plugin, KEY);
        Byte saved = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (saved == null) return;

        new BukkitRunnable() {
            private int waitedTicks;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (service.isActive(player) && !service.isSwitching(player)) {
                    restore(plugin, player, saved != 0);
                    cancel();
                    return;
                }

                waitedTicks += 2;
                if (waitedTicks >= 200) cancel();
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    private static void restore(SuperDuckSystem plugin, Player player, boolean bypassEnabled) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
            session.setBypassDisabled(!bypassEnabled);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not restore WorldGuard bypass state for " + player.getName() + ": " + throwable.getMessage());
        }
    }
}
