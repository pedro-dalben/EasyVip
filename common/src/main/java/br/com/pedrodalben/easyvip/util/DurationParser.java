package br.com.pedrodalben.easyvip.util;

public final class DurationParser {

    private DurationParser() {
    }

    public static long parseDurationMillis(String durationStr) {
        if (durationStr == null || durationStr.equalsIgnoreCase("permanent")) {
            return -1;
        }
        durationStr = durationStr.toLowerCase().trim();
        long millis = 0;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < durationStr.length(); i++) {
            char c = durationStr.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                if (num.length() == 0) continue;
                long val = Long.parseLong(num.toString());
                num.setLength(0);
                switch (c) {
                    case 's': millis += val * 1000L; break;
                    case 'm': millis += val * 60L * 1000L; break;
                    case 'h': millis += val * 60L * 60L * 1000L; break;
                    case 'd': millis += val * 24L * 60L * 60L * 1000L; break;
                    case 'w': millis += val * 7L * 24L * 60L * 60L * 1000L; break;
                    case 'M': millis += val * 30L * 24L * 60L * 60L * 1000L; break;
                    case 'y': millis += val * 365L * 24L * 60L * 60L * 1000L; break;
                }
            }
        }
        if (num.length() > 0) {
            millis += Long.parseLong(num.toString()) * 24L * 60L * 60L * 1000L;
        }
        return millis;
    }
}
