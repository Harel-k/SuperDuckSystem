package com.qducks.superduckclientguard;

import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ScanSession {
    private final UUID playerId;
    private final List<List<HackDefinition>> batches;
    private final String reason;
    private final boolean confirmation;
    private final Map<String, ScanResult> results = new LinkedHashMap<>();
    private int batchIndex;

    private Location signLocation;
    private BlockState originalState;
    private boolean barrierPlaced;
    private Location barrierLocation;
    private BukkitTask timeoutTask;

    ScanSession(UUID playerId, List<List<HackDefinition>> batches, String reason, boolean confirmation) {
        this.playerId = playerId;
        this.batches = batches;
        this.reason = reason;
        this.confirmation = confirmation;
    }

    UUID playerId() { return playerId; }
    List<List<HackDefinition>> batches() { return batches; }
    String reason() { return reason; }
    boolean confirmation() { return confirmation; }
    Map<String, ScanResult> results() { return results; }
    int batchIndex() { return batchIndex; }
    void nextBatch() { batchIndex++; }
    boolean hasMoreBatches() { return batchIndex < batches.size(); }
    List<HackDefinition> currentBatch() { return batches.get(batchIndex); }

    Location signLocation() { return signLocation; }
    void signLocation(Location value) { signLocation = value; }
    BlockState originalState() { return originalState; }
    void originalState(BlockState value) { originalState = value; }
    boolean barrierPlaced() { return barrierPlaced; }
    void barrierPlaced(boolean value) { barrierPlaced = value; }
    Location barrierLocation() { return barrierLocation; }
    void barrierLocation(Location value) { barrierLocation = value; }
    BukkitTask timeoutTask() { return timeoutTask; }
    void timeoutTask(BukkitTask value) { timeoutTask = value; }
}
