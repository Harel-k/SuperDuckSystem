package com.qducks.superducksystem.module;

public interface SuperDuckModule {
    String id();

    void enable();

    default void reload() {
        // Most modules read their config live and need no special refresh step.
    }

    void disable();
}
