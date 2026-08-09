package com.qducks.superducksystem.gui;

import com.qducks.superducksystem.SuperDuckSystem;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class PagedGui extends DuckGui {
    private final int[] contentSlots;
    private final List<GuiButton> content = new ArrayList<>();
    private int page;

    public PagedGui(SuperDuckSystem plugin, int rows, Component title, int... contentSlots) {
        super(plugin, rows, title);
        if (contentSlots.length == 0) {
            throw new IllegalArgumentException("Paged GUI needs at least one content slot");
        }
        this.contentSlots = contentSlots.clone();
    }

    public PagedGui content(List<GuiButton> buttons) {
        content.clear();
        content.addAll(buttons);
        page = Math.min(page, lastPage());
        renderPage();
        return this;
    }

    public PagedGui page(int newPage) {
        page = Math.max(0, Math.min(newPage, lastPage()));
        renderPage();
        return this;
    }

    public int page() {
        return page;
    }

    public int pages() {
        return lastPage() + 1;
    }

    public PagedGui nextPage() {
        return page(page + 1);
    }

    public PagedGui previousPage() {
        return page(page - 1);
    }

    public boolean hasNextPage() {
        return page < lastPage();
    }

    public boolean hasPreviousPage() {
        return page > 0;
    }

    public void fillEmptyContent(ItemStack filler) {
        for (int slot : contentSlots) {
            if (getInventory().getItem(slot) == null) {
                setDisplay(slot, filler);
            }
        }
    }

    protected void renderPage() {
        for (int slot : contentSlots) {
            clear(slot);
        }
        int offset = page * contentSlots.length;
        for (int index = 0; index < contentSlots.length; index++) {
            int contentIndex = offset + index;
            if (contentIndex >= content.size()) {
                break;
            }
            set(contentSlots[index], content.get(contentIndex));
        }
    }

    private int lastPage() {
        return Math.max(0, (content.size() - 1) / contentSlots.length);
    }
}
