package dev.lanworlds.room;

import java.util.Locale;

public record RoomKey(String ip, int port) {
    public RoomKey {
        ip = ip == null ? "" : ip.trim();
        if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
        ip = ip.toLowerCase(Locale.ROOT);
    }

    public static RoomKey fromAddress(String address) {
        if (address == null || address.isBlank()) return null;
        String value = address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String host = value.substring(0, separator).trim();
        if ((host.contains("[") || host.contains("]")) && !(host.startsWith("[") && host.endsWith("]"))) return null;
        try {
            RoomKey key = new RoomKey(host, Integer.parseInt(value.substring(separator + 1).trim()));
            return key.ip().isBlank() || key.ip().contains("§") || key.port() < 1 || key.port() > 65535 ? null : key;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    @Override public String toString() { return ip + ":" + port; }
}
