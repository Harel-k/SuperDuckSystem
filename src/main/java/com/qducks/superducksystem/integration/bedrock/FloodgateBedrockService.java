package com.qducks.superducksystem.integration.bedrock;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.message.MessageService;
import com.qducks.superducksystem.settings.PlayerSetting;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

public final class FloodgateBedrockService implements BedrockService {
    private final SuperDuckSystem plugin;
    private final MessageService messages;

    public FloodgateBedrockService(SuperDuckSystem plugin) {
        this.plugin = plugin;
        this.messages = new MessageService(plugin);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean isBedrock(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean openSettings(Player player) {
        if (!isBedrock(player)) return false;

        FileConfiguration config = plugin.configs().settings();
        CustomForm.Builder form = CustomForm.builder().title(config.getString("bedrock.title", "Player Settings"));
        List<PlayerSetting> shown = new ArrayList<>();
        for (PlayerSetting setting : PlayerSetting.values()) {
            String base = "menu.items." + setting.configKey();
            if (!config.getBoolean(base + ".enabled", true)) continue;
            String label = config.getString(base + ".bedrock-label", config.getString(base + ".plain-name", setting.configKey()));
            form.toggle(label, plugin.settings().get(player.getUniqueId(), setting));
            shown.add(setting);
        }

        form.validResultHandler(response -> {
            EnumMap<PlayerSetting, Boolean> values = new EnumMap<>(PlayerSetting.class);
            for (int index = 0; index < shown.size(); index++) values.put(shown.get(index), response.asToggle(index));
            plugin.settings().setAll(player.getUniqueId(), values).whenComplete((ignored, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) messages.send(player, "settings.failed", "<red>Could not save your settings.</red>");
                        else messages.send(player, "settings.saved", "<green>Your settings were saved.</green>");
                    }));
        });
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }

    @Override
    public boolean requestText(Player player, String title, String label, String placeholder, String defaultValue, Consumer<String> callback) {
        if (!isBedrock(player)) return false;
        String safeTitle = title == null || title.isBlank() ? "Enter Text" : title;
        String safeLabel = label == null || label.isBlank() ? "Enter text" : label;
        String safePlaceholder = placeholder == null ? "" : placeholder;
        String safeDefault = defaultValue == null ? "" : defaultValue;

        CustomForm.Builder form = CustomForm.builder()
                .title(safeTitle)
                .input(safeLabel, safePlaceholder, safeDefault)
                .validResultHandler(response -> {
                    String value = response.asInput(0);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) callback.accept(value == null ? "" : value.trim());
                    });
                });
        player.closeInventory();
        return FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }
}
