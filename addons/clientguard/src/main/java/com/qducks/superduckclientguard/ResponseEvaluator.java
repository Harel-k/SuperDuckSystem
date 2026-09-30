package com.qducks.superduckclientguard;

final class ResponseEvaluator {
    private ResponseEvaluator() {}

    static ScanResult evaluate(HackDefinition hack, String response, boolean exploitPreventer) {
        if (response == null || response.isEmpty()) return ScanResult.NOT_DETECTED;

        String lowerKey = hack.lowerKey();
        int keyLength = lowerKey.length();
        if (response.length() == keyLength + 1
                && response.regionMatches(true, 0, lowerKey, 0, keyLength)
                && Character.isLetter(response.charAt(keyLength))) {
            return ScanResult.NOT_DETECTED;
        }

        return switch (hack.mode()) {
            case METEOR -> {
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.DETECTED;
                if (startsWithIgnoreCase(response, hack.lowerFallback())) yield ScanResult.NOT_DETECTED;
                yield ScanResult.DETECTED;
            }
            case TRANSLATE -> {
                if (startsWithIgnoreCase(response, hack.lowerFallback())) yield ScanResult.NOT_DETECTED;
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.PROTECTED;
                yield ScanResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && response.equalsIgnoreCase(hack.key())) yield ScanResult.PROTECTED;
                if (response.equalsIgnoreCase(hack.key())) yield ScanResult.NOT_DETECTED;
                yield ScanResult.DETECTED;
            }
        };
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix.length() <= value.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
