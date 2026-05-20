package br.com.pedrodalben.easyvip.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

public final class UniqueCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UniqueCodeGenerator() {
    }

    public static String generate(String charset, int length, String prefix, Predicate<String> isAvailable, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < length; i++) {
                sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
            }
            String candidate = sb.toString();
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique key code after " + maxAttempts + " attempts");
    }
}
