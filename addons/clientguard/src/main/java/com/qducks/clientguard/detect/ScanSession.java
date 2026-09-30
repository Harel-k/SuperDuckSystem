package com.qducks.clientguard.detect;

import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ScanSession {
    final UUID playerId;
    final List<List<HackDefinition>> batches;
    final Map<String, HackResult> results = new LinkedHashMap<>();
    final boolean confirmation;
    int batchIndex;
    Location signLocation;
    BlockState originalState;
    Location barrierLocation;
    boolean barrierPlaced;
    BukkitTask timeoutTask;

    ScanSession(UUID playerId, List<List<HackDefinition>> batches, boolean confirmation) {
        this.playerId = playerId;
        this.batches = batches;
        this.confirmation = confirmation;
    }

    List<HackDefinition> currentBatch() {
        return batches.get(batchIndex);
    }

    boolean hasMoreBatches() {
        return batchIndex < batches.size();
    }
}
