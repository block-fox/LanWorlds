package dev.lanworlds.room;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** LAN list preferences and transient presentation state. */
public final class RoomBrowser {
    public enum Group { RADMIN, IP, PORT, MOTD }
    public enum Search { ALL, IP, MOTD, PORT, USER }

    public final Set<String> collapsed = new HashSet<>();
    public Search search = Search.ALL;
    public boolean open;
    public String query = "";
    public int page;

    public void copyFrom(RoomBrowser previous) {
        collapsed.addAll(previous.collapsed);
        search = previous.search;
        open = previous.open;
        query = previous.query;
        page = previous.page;
    }

    public static Group grouping(String name) {
        try { return Group.valueOf(name); }
        catch (IllegalArgumentException | NullPointerException ignored) { return Group.RADMIN; }
    }

    public static String clean(String value) {
        return value == null ? "" : value.replaceAll("(?i)§[0-9a-fk-orx]", "").replaceAll("\\s+", " ").strip();
    }

    private static boolean includes(String text, String query) {
        return clean(text).toLowerCase(Locale.ROOT).contains(query);
    }

    public static String group(Group mode, RoomKey key, String motd, boolean radmin, String filter) {
        String needle = clean(filter).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case RADMIN -> radmin ? "screen.lanworlds.folder.radmin" : "screen.lanworlds.folder.other";
            case IP -> key.ip();
            case PORT -> Integer.toString(key.port());
            case MOTD -> needle.isBlank() ? (clean(motd).isEmpty() ? "screen.lanworlds.folder.other" : clean(motd))
                    : includes(motd, needle) ? "screen.lanworlds.folder.matches" : "screen.lanworlds.folder.unmatched";
        };
    }

    public static boolean matches(Search mode, String query, RoomKey key, String broadcast, String status,
                                  String user, boolean showAddress) {
        String needle = clean(query).toLowerCase(Locale.ROOT);
        if (needle.isBlank()) return false;
        return switch (mode) {
            case IP -> showAddress && includes(key.ip(), needle);
            case PORT -> showAddress && Integer.toString(key.port()).contains(needle);
            case MOTD -> includes(broadcast, needle) || includes(status, needle);
            case USER -> includes(user, needle);
            case ALL -> (showAddress && includes(key.toString(), needle)) || includes(broadcast, needle)
                    || includes(status, needle) || includes(user, needle);
        };
    }
}
