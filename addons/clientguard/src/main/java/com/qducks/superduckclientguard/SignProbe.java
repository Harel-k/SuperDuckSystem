package com.qducks.superduckclientguard;

/*
 * Sign probing mechanics adapted from CheckHacks by Branduzzo.
 * Original project: https://github.com/branduzzo/CheckHacks
 * Licensed under the MIT License. See THIRD_PARTY_NOTICES.md.
 */

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class SignProbe {
    private SignProbe() {}

    static void setAllowedEditor(Location loc, UUID playerId, Plugin plugin) {
        try {
            Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object blockPos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

            Method getBlockEntity = Arrays.stream(world.getClass().getMethods())
                    .filter(method -> method.getName().equals("getBlockEntity") && method.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (getBlockEntity == null) return;

            Object blockEntity = getBlockEntity.invoke(world, blockPos);
            if (blockEntity == null) return;

            for (Method method : blockEntity.getClass().getMethods()) {
                if (method.getName().equals("setAllowedPlayerEditor") && method.getParameterCount() == 1) {
                    method.invoke(blockEntity, playerId);
                    return;
                }
            }

            for (Field field : allFields(blockEntity.getClass())) {
                if (field.getType().equals(UUID.class)) {
                    field.setAccessible(true);
                    field.set(blockEntity, playerId);
                    return;
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("ClientGuard setAllowedEditor failed: " + exception.getMessage());
        }
    }

    static void sendBlockEntityPacket(Player player, Location loc, Plugin plugin) {
        try {
            Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object blockPos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

            Method getBlockEntity = Arrays.stream(world.getClass().getMethods())
                    .filter(method -> method.getName().equals("getBlockEntity") && method.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (getBlockEntity == null) return;

            Object blockEntity = getBlockEntity.invoke(world, blockPos);
            if (blockEntity == null) return;

            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
            Method create = Arrays.stream(packetClass.getMethods())
                    .filter(method -> method.getName().equals("create") && method.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (create == null) return;

            sendPacket(player, create.invoke(null, blockEntity), plugin);
        } catch (Exception exception) {
            plugin.getLogger().warning("ClientGuard block-entity packet failed: " + exception.getMessage());
        }
    }

    static void sendOpenSignPacket(Player player, Location loc, Plugin plugin) {
        try {
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
            Object blockPos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Object packet = packetClass.getConstructor(blockPosClass, boolean.class).newInstance(blockPos, true);
            sendPacket(player, packet, plugin);
        } catch (Exception exception) {
            plugin.getLogger().warning("ClientGuard open-sign packet failed: " + exception.getMessage());
        }
    }

    static Location findAirBlock(Player player) {
        Location base = player.getLocation().clone();
        for (int dy = 1; dy <= 5; dy++) {
            Location loc = base.clone().add(0, dy, 0);
            if (loc.getBlock().getType().isAir()) return loc;
        }

        int[][] offsets = {
                {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
                {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                {2,1,0},{-2,1,0},{0,1,2},{0,1,-2}
        };
        for (int[] offset : offsets) {
            Location loc = base.clone().add(offset[0], offset[1], offset[2]);
            if (loc.getBlock().getType().isAir()) return loc;
        }
        return null;
    }

    private static void sendPacket(Player player, Object packet, Plugin plugin) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = null;

            for (String fieldName : new String[]{"connection", "networkManager", "playerConnection"}) {
                try {
                    Field field;
                    try {
                        field = handle.getClass().getField(fieldName);
                    } catch (NoSuchFieldException ignored) {
                        field = handle.getClass().getDeclaredField(fieldName);
                        field.setAccessible(true);
                    }
                    Object value = field.get(handle);
                    if (value != null) {
                        connection = value;
                        break;
                    }
                } catch (Exception ignored) {
                    // Try the next known field name.
                }
            }

            if (connection == null) throw new IllegalStateException("player connection not found");

            Method send = null;
            for (Method method : connection.getClass().getMethods()) {
                if (method.getName().equals("send")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(packet.getClass())) {
                    send = method;
                    break;
                }
            }
            if (send == null) {
                for (Method method : connection.getClass().getMethods()) {
                    if (method.getName().equals("send") && method.getParameterCount() == 1) {
                        send = method;
                        break;
                    }
                }
            }

            if (send == null) throw new IllegalStateException("connection send method not found");
            send.invoke(connection, packet);
        } catch (Exception exception) {
            plugin.getLogger().warning("ClientGuard packet send failed: " + exception.getMessage());
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
