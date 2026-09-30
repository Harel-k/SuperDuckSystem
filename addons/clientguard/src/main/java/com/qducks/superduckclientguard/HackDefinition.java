package com.qducks.superduckclientguard;

record HackDefinition(
        String id,
        String displayName,
        String key,
        DetectionMode mode,
        DetectionPolicy policy
) {
    String fallback() {
        return "⟦NO_" + id.toUpperCase().replace("-", "_") + "⟧";
    }

    String lowerKey() {
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    String lowerFallback() {
        return fallback().toLowerCase(java.util.Locale.ROOT);
    }
}
