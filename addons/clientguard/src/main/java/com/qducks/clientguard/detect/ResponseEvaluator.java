package com.qducks.clientguard.detect;

/**
 * Response classification adapted from CheckHacks by Branduzzo (MIT).
 */
public final class ResponseEvaluator {
    private ResponseEvaluator() {}

    public static HackResult evaluate(HackDefinition hack, String response, boolean exploitPreventer) {
        String resp = response == null ? "" : response.strip();
        if (resp.isEmpty()) return HackResult.NOT_DETECTED;

        String lowerKey = hack.lowerKey();
        int keyLen = lowerKey.length();
        if (resp.length() == keyLen + 1
                && resp.regionMatches(true, 0, lowerKey, 0, keyLen)
                && Character.isLetter(resp.charAt(keyLen))) {
            return HackResult.NOT_DETECTED;
        }

        return switch (hack.mode()) {
            case METEOR -> {
                if (resp.equalsIgnoreCase(hack.key())) yield HackResult.DETECTED;
                if (startsWithIgnoreCase(resp, hack.lowerFallback())) yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case TRANSLATE -> {
                if (startsWithIgnoreCase(resp, hack.lowerFallback())) yield HackResult.NOT_DETECTED;
                if (resp.equalsIgnoreCase(hack.key())) yield HackResult.PROTECTED;
                yield HackResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && resp.equalsIgnoreCase(hack.key())) yield HackResult.PROTECTED;
                if (resp.equalsIgnoreCase(hack.key())) yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
        };
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix.length() <= value.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
