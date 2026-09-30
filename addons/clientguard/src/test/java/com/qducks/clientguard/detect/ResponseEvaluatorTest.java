package com.qducks.clientguard.detect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ResponseEvaluatorTest {
    @Test
    void meteorKeyMeansDetected() {
        HackDefinition hack = new HackDefinition("meteor-client", "Meteor Client",
                "key.meteor-client.open-gui", DetectionMode.METEOR);
        assertEquals(HackResult.DETECTED,
                ResponseEvaluator.evaluate(hack, "key.meteor-client.open-gui", false));
    }

    @Test
    void vanillaFallbackMeansClean() {
        HackDefinition hack = new HackDefinition("liquidbounce", "LiquidBounce",
                "liquidbounce.module.killaura.name", DetectionMode.TRANSLATE);
        assertEquals(HackResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack, hack.fallback(), false));
    }

    @Test
    void blockedTranslationIsProtectedNotDetected() {
        HackDefinition hack = new HackDefinition("liquidbounce", "LiquidBounce",
                "liquidbounce.module.killaura.name", DetectionMode.TRANSLATE);
        assertEquals(HackResult.PROTECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), false));
    }

    @Test
    void keybindControlPreventsProtectedClientFromLookingDetected() {
        HackDefinition hack = new HackDefinition("freecam", "Freecam",
                "key.freecam.toggle", DetectionMode.KEYBIND);
        assertEquals(HackResult.PROTECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), true));
    }
}
