package com.qducks.superduckclientguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResponseEvaluatorTest {
    private static HackDefinition hack(DetectionMode mode) {
        return new HackDefinition(
                "test-hack",
                "Test Hack",
                "key.test.toggle",
                mode,
                DetectionPolicy.ALERT_ONLY
        );
    }

    @Test
    void emptyResponseIsClean() {
        assertEquals(ScanResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack(DetectionMode.KEYBIND), "", false));
    }

    @Test
    void keyPlusSingleLetterFalsePositiveGuardIsClean() {
        assertEquals(ScanResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack(DetectionMode.KEYBIND), "key.test.togglea", false));
    }

    @Test
    void meteorExactKeyIsDetectedAndFallbackIsClean() {
        HackDefinition hack = hack(DetectionMode.METEOR);
        assertEquals(ScanResult.DETECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), false));
        assertEquals(ScanResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack, hack.fallback(), false));
    }

    @Test
    void translateExactKeyIsProtectedAndTranslatedResponseIsDetected() {
        HackDefinition hack = hack(DetectionMode.TRANSLATE);
        assertEquals(ScanResult.PROTECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), false));
        assertEquals(ScanResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack, hack.fallback(), false));
        assertEquals(ScanResult.DETECTED,
                ResponseEvaluator.evaluate(hack, "translated mod text", false));
    }

    @Test
    void keybindUsesControlLineToAvoidExploitPreventerFalsePositive() {
        HackDefinition hack = hack(DetectionMode.KEYBIND);
        assertEquals(ScanResult.NOT_DETECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), false));
        assertEquals(ScanResult.PROTECTED,
                ResponseEvaluator.evaluate(hack, hack.key(), true));
        assertEquals(ScanResult.DETECTED,
                ResponseEvaluator.evaluate(hack, "F8", false));
    }
}
