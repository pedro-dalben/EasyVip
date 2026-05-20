package br.com.pedrodalben.easyvip.util;

import java.util.List;

public final class CommandAllowlist {

    private CommandAllowlist() {
    }

    public static boolean isAllowed(String command, boolean enabled, List<String> allowedPrefixes) {
        if (!enabled) {
            return true;
        }
        if (command == null) {
            return false;
        }
        for (String prefix : allowedPrefixes) {
            if (command.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
