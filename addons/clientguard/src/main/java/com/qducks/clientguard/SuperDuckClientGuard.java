package com.qducks.clientguard;

import com.qducks.clientguard.command.ClientGuardCommand;
import com.qducks.clientguard.detect.ClientScanner;
import com.qducks.clientguard.listener.JoinScanListener;
import com.qducks.clientguard.listener.SanctionLockListener;
import com.qducks.clientguard.listener.SignResponseListener;
import com.qducks.clientguard.policy.PolicyEngine;
import com.qducks.clientguard.policy.StrikeStore;
import com.qducks.clientguard.sanction.DuckyPvpAdapter;
import com.qducks.clientguard.sanction.HomeStateAdapter;
import com.qducks.clientguard.sanction.ProgressSnapshotService;
import com.qducks.clientguard.sanction.ProgressWipeService;
import com.qducks.clientguard.sanction.SanctionLock;
import com.qducks.clientguard.sanction.SanctionService;
import com.qducks.superducksystem.SuperDuckSystem;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperDuckClientGuard extends JavaPlugin {
    private SuperDuckSystem superDuckSystem;
    private ClientScanner scanner;
    private PolicyEngine policy;
    private SanctionService sanctions;
    private SanctionLock sanctionLock;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin dependency = getServer().getPluginManager().getPlugin("SuperDuckSystem");
        if (!(dependency instanceof SuperDuckSystem sds) || !dependency.isEnabled()) {
            getLogger().severe("SuperDuckSystem is required and must be enabled first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.superDuckSystem = sds;

        StrikeStore strikeStore = new StrikeStore(this);
        this.sanctionLock = new SanctionLock();
        ProgressSnapshotService snapshotService = new ProgressSnapshotService(this);
        ProgressWipeService wipeService = new ProgressWipeService(this);
        DuckyPvpAdapter duckyPvpAdapter = new DuckyPvpAdapter(this);
        HomeStateAdapter homeStateAdapter = new HomeStateAdapter(this);
        this.sanctions = new SanctionService(
                this, snapshotService, wipeService, duckyPvpAdapter, homeStateAdapter, sanctionLock);
        this.policy = new PolicyEngine(this, strikeStore, sanctions);
        this.scanner = new ClientScanner(this, policy);

        getServer().getPluginManager().registerEvents(new SignResponseListener(scanner), this);
        getServer().getPluginManager().registerEvents(new JoinScanListener(this, scanner, policy), this);
        getServer().getPluginManager().registerEvents(new SanctionLockListener(sanctionLock), this);

        PluginCommand command = getCommand("clientguard");
        if (command == null) {
            throw new IllegalStateException("/clientguard is missing from plugin.yml");
        }
        ClientGuardCommand handler = new ClientGuardCommand(this, scanner, policy, sanctions);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info("SuperDuckClientGuard " + getPluginMeta().getVersion()
                + " enabled in " + (getConfig().getBoolean("enforcement.enabled", false)
                ? "enforcement" : "safe monitor") + " mode.");
    }

    @Override
    public void onDisable() {
        if (scanner != null) scanner.shutdown();
        if (sanctionLock != null) sanctionLock.unlockAll();
    }

    public SuperDuckSystem superDuckSystem() {
        return superDuckSystem;
    }

    public ClientScanner scanner() {
        return scanner;
    }

    public PolicyEngine policy() {
        return policy;
    }

    public SanctionService sanctions() {
        return sanctions;
    }
}
