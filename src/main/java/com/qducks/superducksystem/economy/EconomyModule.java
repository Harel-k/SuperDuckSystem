package com.qducks.superducksystem.economy;

import com.qducks.superducksystem.SuperDuckSystem;
import com.qducks.superducksystem.command.BalanceCommand;
import com.qducks.superducksystem.command.BalanceTopCommand;
import com.qducks.superducksystem.command.DucksCommand;
import com.qducks.superducksystem.command.EcoCommand;
import com.qducks.superducksystem.command.PayCommand;
import com.qducks.superducksystem.module.SuperDuckModule;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class EconomyModule implements SuperDuckModule {
    private final SuperDuckSystem plugin;

    public EconomyModule(SuperDuckSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void enable() {
        BalanceCommand balance = new BalanceCommand(plugin);
        DucksCommand ducks = new DucksCommand(plugin);
        PayCommand pay = new PayCommand(plugin);
        EcoCommand eco = new EcoCommand(plugin);
        register("balance", balance, balance);
        register("ducks", ducks, ducks);
        register("pay", pay, pay);
        register("eco", eco, eco);
        register("baltop", new BalanceTopCommand(plugin), null);
        plugin.getLogger().info("Economy module enabled.");
    }

    @Override
    public void disable() {
        plugin.getLogger().info("Economy module disabled.");
    }

    private void register(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command /" + name + " is missing from plugin.yml");
        }
        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
    }
}
