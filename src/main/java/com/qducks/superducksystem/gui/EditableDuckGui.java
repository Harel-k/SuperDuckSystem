package com.qducks.superducksystem.gui;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A DuckGui with explicitly whitelisted slots that players may manually place items into or take
 * items out of. Shift-clicking and inventory drags remain blocked by GuiManager, making this useful
 * for escrow-style interfaces such as /sell without opening every menu to inventory exploits.
 */
public final class EditableDuckGui extends DuckGui {
    private final Set<Integer> editableSlots = new HashSet<>();
    private Consumer<Player> editHandler = player -> { };

    public EditableDuckGui(SuperDuckSystem plugin, int rows, Component title) {
        super(plugin, rows, title);
    }

    public EditableDuckGui allowInput(int slot) {
        if (slot < 0 || slot >= getInventory().getSize()) {
            throw new IllegalArgumentException("Slot " + slot + " is outside GUI size " + getInventory().getSize());
        }
        editableSlots.add(slot);
        return this;
    }

    public EditableDuckGui allowInput(int... slots) {
        for (int slot : slots) {
            allowInput(slot);
        }
        return this;
    }

    public EditableDuckGui onEdit(Consumer<Player> handler) {
        this.editHandler = handler == null ? player -> { } : handler;
        return this;
    }

    public boolean isEditable(int slot) {
        return editableSlots.contains(slot);
    }

    public Set<Integer> editableSlots() {
        return Set.copyOf(editableSlots);
    }

    void handleEdit(Player player) {
        editHandler.accept(player);
    }
}
