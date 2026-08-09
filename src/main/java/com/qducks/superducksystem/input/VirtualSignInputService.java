package com.qducks.superducksystem.input;

import com.qducks.superducksystem.SuperDuckSystem;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Java players use a virtual sign; Floodgate players automatically receive a native Bedrock text form. */
@SuppressWarnings("UnstableApiUsage")
public final class VirtualSignInputService implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SuperDuckSystem plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public VirtualSignInputService(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, String initialText, Consumer<String> callback) {
        clear(player);
        String initial = initialText == null ? "" : initialText.trim();
        if (initial.length() > 64) initial = initial.substring(0, 64);

        if (plugin.integrations() != null && plugin.integrations().bedrock().isBedrock(player)) {
            boolean opened = plugin.integrations().bedrock().requestText(
                    player,
                    "QDucks Input",
                    "Type your search / amount / price",
                    "Enter text...",
                    initial,
                    input -> callback.accept(limit(input, 128))
            );
            if (opened) return;
        }

        Location location = chooseLocation(player);
        BlockData signData = Material.OAK_SIGN.createBlockData();
        BlockState state = signData.createBlockState();
        if (!(state instanceof Sign sign)) throw new IllegalStateException("OAK_SIGN did not create a Sign block state");
        sign.getSide(Side.FRONT).line(0, Component.text(initial));

        player.closeInventory();
        player.sendBlockChange(location, signData);
        player.sendBlockUpdate(location, sign);

        BlockPosition position = Position.block(location);
        long token = System.nanoTime();
        sessions.put(player.getUniqueId(), new Session(position, location, callback, token));
        player.openVirtualSign(position, Side.FRONT);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Session current = sessions.get(player.getUniqueId());
            if (current != null && current.token == token) {
                sessions.remove(player.getUniqueId());
                restore(player, current.location);
            }
        }, 20L * 60L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVirtualSignChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !samePosition(session.position, event.getEditedBlockPosition())) return;

        event.setCancelled(true);
        sessions.remove(player.getUniqueId());
        restore(player, session.location);

        List<String> pieces = new ArrayList<>();
        for (Component line : event.lines()) {
            String plain = PLAIN.serialize(line).trim();
            if (!plain.isEmpty()) pieces.add(plain);
        }
        session.callback.accept(limit(String.join(" ", pieces).trim(), 128));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public void clear(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) restore(player, session.location);
    }

    private Location chooseLocation(Player player) {
        Location base = player.getLocation().getBlock().getLocation();
        int maxY = player.getWorld().getMaxHeight() - 1;
        int y = Math.min(maxY, base.getBlockY() + 2);
        return new Location(player.getWorld(), base.getBlockX(), y, base.getBlockZ());
    }

    private boolean samePosition(BlockPosition first, BlockPosition second) {
        return first.blockX() == second.blockX() && first.blockY() == second.blockY() && first.blockZ() == second.blockZ();
    }

    private void restore(Player player, Location location) {
        if (!player.isOnline() || !player.getWorld().equals(location.getWorld())) return;
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    private String limit(String value, int max) {
        String safe = value == null ? "" : value.trim();
        return safe.length() > max ? safe.substring(0, max) : safe;
    }

    private static final class Session {
        private final BlockPosition position;
        private final Location location;
        private final Consumer<String> callback;
        private final long token;

        private Session(BlockPosition position, Location location, Consumer<String> callback, long token) {
            this.position = position;
            this.location = location;
            this.callback = callback;
            this.token = token;
        }
    }
}
