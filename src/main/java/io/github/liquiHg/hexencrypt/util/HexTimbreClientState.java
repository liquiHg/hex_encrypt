package io.github.liquiHg.hexencrypt.util;

public final class HexTimbreClientState {

    private static volatile String current = "default";

    private HexTimbreClientState() {}

    public static String get() {
        return current;
    }

    public static void set(String name) {
        current = (name == null || name.isEmpty()) ? "default" : name;
    }
}