package com.qducks.superducksystem.system;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SystemStateService {
    private volatile boolean readOnly;
    private final Set<String> maintenance = ConcurrentHashMap.newKeySet();

    public boolean readOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean maintenance(String module) {
        return maintenance.contains(normalize(module));
    }

    public void setMaintenance(String module, boolean enabled) {
        String id = normalize(module);
        if (enabled) maintenance.add(id);
        else maintenance.remove(id);
    }

    public boolean available(String module) {
        return !maintenance(module);
    }

    public Set<String> maintenanceModules() {
        return Set.copyOf(maintenance);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
