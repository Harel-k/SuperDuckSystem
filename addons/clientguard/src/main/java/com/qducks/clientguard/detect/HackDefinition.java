package com.qducks.clientguard.detect;

import java.util.Locale;

public record HackDefinition(String id, String displayName, String key, DetectionMode mode) {
    public String fallback() {
        return "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace("-", "_") + "\u27e7";
    }

    public String lowerKey() {
        return key.toLowerCase(Locale.ROOT);
    }

    public String lowerFallback() {
        return fallback().toLowerCase(Locale.ROOT);
    }
}
