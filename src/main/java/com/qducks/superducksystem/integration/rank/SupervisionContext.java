package com.qducks.superducksystem.integration.rank;

import com.qducks.superducksystem.SuperDuckSystem;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * LuckPerms context "qducks:supervised" = true while at least one OTHER online player has
 * qducks.supervisor. Trial staff permissions can be granted only in that context, e.g.
 * {@code lp group trialduck permission set moderationplusplus.warn true qducks:supervised=true},
 * so they switch on and off automatically as supervisors join and leave.
 */
public final class SupervisionContext implements ContextCalculator<Player>, Listener {
    public static final String KEY = "qducks:supervised";
    public static final String SUPERVISOR_PERMISSION = "qducks.supervisor";

    private final SuperDuckSystem plugin;
    private final LuckPerms luckPerms;
    private volatile Set<UUID> supervisors = Set.of();
    private BukkitTask task;

    public SupervisionContext(SuperDuckSystem plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    public void register() {
        luckPerms.getContextManager().registerCalculator(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Also refresh periodically so rank changes (lp user ... parent add) are picked up.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 200L);
    }

    public void unregister() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
        luckPerms.getContextManager().unregisterCalculator(this);
    }

    @Override
    public void calculate(@NotNull Player target, @NotNull ContextConsumer consumer) {
        UUID self = target.getUniqueId();
        boolean supervised = supervisors.stream().anyMatch(uuid -> !uuid.equals(self));
        consumer.accept(KEY, Boolean.toString(supervised));
    }

    @Override
    public @NotNull ContextSet estimatePotentialContexts() {
        return ImmutableContextSet.builder().add(KEY, "true").add(KEY, "false").build();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Run after the player has actually left the online list.
        Bukkit.getScheduler().runTask(plugin, this::refresh);
    }

    private void refresh() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            // Non-contextual check: avoids recursing into context calculation for other players.
            if (user != null && user.getCachedData().getPermissionData(QueryOptions.nonContextual())
                    .checkPermission(SUPERVISOR_PERMISSION).asBoolean()) {
                online.add(player.getUniqueId());
            }
        }
        if (online.equals(supervisors)) return;
        supervisors = Set.copyOf(online);
        for (Player player : Bukkit.getOnlinePlayers()) {
            luckPerms.getContextManager().signalContextUpdate(player);
        }
    }
}
