package br.com.pedrodalben.easyvip.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

public final class UniqueCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UniqueCodeGenerator() {
    }

    public static String generate(String charset, int length, String prefix, Predicate<String> isAvailable, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = generateCandidate(charset, length, prefix);
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique key code after " + maxAttempts + " attempts");
    }

    public static String generateCandidate(String charset, int length, String prefix) {
        StringBuilder sb = new StringBuilder(prefix != null ? prefix : "");
        int effectiveLength = Math.max(0, length);
        for (int i = 0; i < effectiveLength; i++) {
            sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
        }
        return sb.toString();
    }
}
