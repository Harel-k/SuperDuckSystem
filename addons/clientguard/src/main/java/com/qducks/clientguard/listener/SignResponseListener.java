package com.qducks.clientguard.listener;

import com.qducks.clientguard.detect.ClientScanner;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

public final class SignResponseListener implements Listener {
    private final ClientScanner scanner;

    public SignResponseListener(ClientScanner scanner) {
        this.scanner = scanner;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        if (!scanner.isChecking(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < lines.length; i++) {
            var component = event.line(i);
            lines[i] = component == null
                    ? ""
                    : PlainTextComponentSerializer.plainText().serialize(component);
        }
        scanner.handleResponse(event.getPlayer(), lines);
    }
}
