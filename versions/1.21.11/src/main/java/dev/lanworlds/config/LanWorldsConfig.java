package dev.lanworlds.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.ui.ToolbarLayout;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** LAN status queries and room blocking belong to LanWorlds, including without Radmin. */
public final class LanWorldsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lanworlds.json");
    private static final Path LEGACY_FILE = FabricLoader.getInstance().getConfigDir().resolve("radminlan.json");

    public ToolbarLayout.Style toolbarStyle = ToolbarLayout.Style.ICON;
    public ToolbarLayout.Anchor selectionButtonAnchor = ToolbarLayout.Anchor.AUTO;
    public ToolbarLayout.Anchor lanWorldsButtonAnchor = ToolbarLayout.Anchor.AUTO;
    public ToolbarLayout.Anchor radminButtonAnchor = ToolbarLayout.Anchor.AUTO;
    public boolean dragToolbarIcons;
    public ToolbarLayout.Point selectionButtonPosition;
    public ToolbarLayout.Point lanWorldsButtonPosition;
    public ToolbarLayout.Point radminButtonPosition;

    public boolean canDragToolbar() { return toolbarStyle == ToolbarLayout.Style.ICON && dragToolbarIcons; }

    public boolean queryLanStatus = true;
    public int queryTimeoutMs = 1000;
    public BlockMatchType defaultBlockType = BlockMatchType.IP_PORT;
    public List<BlockRule> blocked = new ArrayList<>();

    public static LanWorldsConfig load() {
        // Migrate once. Never modify the old mod's configuration or overwrite an
        // existing LanWorlds file when both mods are present on later launches.
        Path source = Files.exists(FILE) ? FILE : LEGACY_FILE;
        LanWorldsConfig loaded = new LanWorldsConfig();
        if (Files.exists(source)) {
            try (Reader reader = Files.newBufferedReader(source)) {
                LanWorldsConfig parsed = GSON.fromJson(reader, LanWorldsConfig.class);
                if (parsed != null) loaded = parsed;
            } catch (Exception ignored) {
            }
        }
        loaded.normalize();
        loaded.save();
        return loaded;
    }

    private void normalize() {
        if (toolbarStyle == null) toolbarStyle = ToolbarLayout.Style.ICON;
        if (selectionButtonAnchor == null) selectionButtonAnchor = ToolbarLayout.Anchor.AUTO;
        if (lanWorldsButtonAnchor == null) lanWorldsButtonAnchor = ToolbarLayout.Anchor.AUTO;
        if (radminButtonAnchor == null) radminButtonAnchor = ToolbarLayout.Anchor.AUTO;
        if (toolbarStyle != ToolbarLayout.Style.ICON) dragToolbarIcons = false;
        queryTimeoutMs = Math.max(100, Math.min(10_000, queryTimeoutMs));
        if (defaultBlockType == null) defaultBlockType = BlockMatchType.IP_PORT;
        if (blocked == null) blocked = new ArrayList<>();
        blocked.removeIf(rule -> rule == null || rule.type == null || rule.value == null);
    }

    public void save() {
        normalize();
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isBlocked(String ip, int port, String motd) {
        return blocked.stream().anyMatch(rule -> rule != null && rule.enabled && rule.matches(ip, port, motd));
    }

    public void block(String ip, int port, String motd) {
        BlockMatchType type = defaultBlockType == null ? BlockMatchType.IP_PORT : defaultBlockType;
        String value = type.normalize(ip, port, motd);
        RuleKey key = new RuleKey(type, value);
        blocked.removeIf(rule -> rule != null && key.equals(rule.key()));
        blocked.add(new BlockRule(type, value));
        save();
    }

    /** Applies the configured match type to every selected room and persists the batch once. */
    public void blockRooms(Collection<LanRoomSnapshot> rooms) {
        if (rooms == null || rooms.isEmpty()) return;
        normalize();
        BlockMatchType type = defaultBlockType;
        var additions = new LinkedHashMap<RuleKey, BlockRule>();
        for (LanRoomSnapshot room : rooms) {
            if (room == null || room.key() == null || room.key().ip().isBlank()) continue;
            String value = type.normalize(room.key().ip(), room.key().port(), room.motd());
            additions.putIfAbsent(new RuleKey(type, value), new BlockRule(type, value));
        }
        if (additions.isEmpty()) return;
        blocked.removeIf(rule -> additions.containsKey(rule.key()));
        blocked.addAll(additions.values());
        save();
    }

    public void unblock(BlockRule rule) {
        if (rule != null) unblockRules(List.of(rule));
    }

    /** Rules can be restored even when their rooms are absent from LAN discovery. */
    public void unblockRules(Collection<BlockRule> rules) {
        if (rules == null || rules.isEmpty() || blocked == null) return;
        Set<RuleKey> keys = new HashSet<>();
        for (BlockRule rule : rules) if (rule != null) keys.add(rule.key());
        if (blocked.removeIf(rule -> rule != null && keys.contains(rule.key()))) save();
    }

    /** Stable across reloads; address keys follow the same case-insensitive matching as rules. */
    public record RuleKey(BlockMatchType type, String value) {
        public RuleKey {
            value = value == null ? "" : value;
            if (type == BlockMatchType.MOTD) value = type.normalize("", 0, value);
            else value = value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public enum BlockMatchType {
        IP {
            @Override public String normalize(String ip, int port, String motd) { return ip == null ? "" : ip.trim(); }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equalsIgnoreCase(value); }
        },
        IP_PORT {
            @Override public String normalize(String ip, int port, String motd) { return (ip == null ? "" : ip.trim()) + ":" + port; }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equalsIgnoreCase(value); }
        },
        MOTD {
            @Override public String normalize(String ip, int port, String motd) { return motd == null ? "" : motd.replaceAll("\\s+", " ").trim(); }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equals(value); }
        };

        public abstract String normalize(String ip, int port, String motd);
        public abstract boolean matches(String ip, int port, String motd, String value);
    }

    public static final class BlockRule {
        public BlockMatchType type;
        public String value;
        public boolean enabled = true;
        public long createdAt = System.currentTimeMillis();

        public BlockRule() {}

        public BlockRule(BlockMatchType type, String value) {
            this.type = type;
            this.value = value;
        }

        public boolean matches(String ip, int port, String motd) {
            return type != null && value != null && type.matches(ip, port, motd, value);
        }

        public RuleKey key() { return new RuleKey(type, value); }
    }
}
