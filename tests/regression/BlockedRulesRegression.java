import dev.lanworlds.config.LanWorldsConfig;
import dev.lanworlds.config.LanWorldsConfig.BlockMatchType;
import dev.lanworlds.config.LanWorldsConfig.BlockRule;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.RoomKey;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Exercises bulk rule changes using saved rules, independently of current LAN discovery. */
public final class BlockedRulesRegression {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(System.getProperty("lanworldsRegression.workDir", ".tmp/regression-runtime")).toAbsolutePath();
        Files.createDirectories(work);
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, Files.createTempDirectory(work, "blocked-rules-"));
        check();
    }

    public static void check() throws Exception {
        checkBulkBlockTypes();
        checkRestoreSavedRules();
        checkStableKeys();
        checkEmptySelection();
        System.out.println("BLOCKED_RULES_REGRESSION_OK bulkBlock=true bulkRestore=true stableKeys=true");
    }

    private static void checkBulkBlockTypes() {
        LanRoomSnapshot first = room("192.168.1.4", 25565, "First room");
        LanRoomSnapshot second = room("192.168.1.4", 25566, "Second room");
        LanRoomSnapshot other = room("192.168.1.5", 25565, "Other room");
        LanWorldsConfig config = new LanWorldsConfig();
        config.defaultBlockType = BlockMatchType.IP_PORT;
        config.blockRooms(List.of(first, second, first, other));
        require(config.blocked.size() == 3, "repeated selected endpoints produce one rule each");
        require(config.isBlocked("192.168.1.4", 25565, "Renamed")
                        && config.isBlocked("192.168.1.4", 25566, "Renamed"),
                "bulk endpoint blocks include separate worlds on the same host");
        require(!config.isBlocked("192.168.1.4", 25567, "First room"), "unselected ports remain visible");
        config.blocked.getFirst().enabled = false;
        config.blockRooms(List.of(first));
        require(config.blocked.size() == 3 && config.isBlocked("192.168.1.4", 25565, "Renamed"),
                "blocking a selected room again re-enables its existing rule without duplication");

        config = new LanWorldsConfig();
        config.defaultBlockType = BlockMatchType.IP;
        config.blockRooms(List.of(first, second, other));
        require(config.blocked.size() == 2, "host matching deduplicates selected worlds on one host");
        require(config.isBlocked("192.168.1.4", 1234, "Unadvertised world"), "bulk host blocks apply across ports");

        config = new LanWorldsConfig();
        config.defaultBlockType = BlockMatchType.MOTD;
        config.blockRooms(List.of(room("192.168.1.4", 25565, "  Shared\n room "),
                room("192.168.1.5", 25566, "Shared  room"), other));
        require(config.blocked.size() == 2, "MOTD matching deduplicates normalized advertisement names");
        require(config.isBlocked("192.168.1.6", 1, "Shared room"), "bulk MOTD matching uses the broadcast MOTD");
        require(!config.isBlocked("192.168.1.6", 1, "Shared room extended"), "bulk MOTD matching compares complete names");

        config = new LanWorldsConfig();
        config.defaultBlockType = null;
        config.blockRooms(Arrays.asList(null, room(null, 25565, "Missing host"), first));
        require(config.blocked.size() == 1 && config.blocked.getFirst().type == BlockMatchType.IP_PORT,
                "invalid selected snapshots are ignored and a missing block type uses endpoint matching");
    }

    private static void checkRestoreSavedRules() {
        LanWorldsConfig config = new LanWorldsConfig();
        config.defaultBlockType = BlockMatchType.IP_PORT;
        config.blockRooms(List.of(room("192.168.1.4", 25565, "First"), room("192.168.1.5", 25565, "Second"),
                room("192.168.1.6", 25565, "Third")));
        List<BlockRule> previouslySelected = List.of(config.blocked.get(0), config.blocked.get(2));
        LanWorldsConfig reloaded = LanWorldsConfig.load();
        require(reloaded.blocked.getFirst() != previouslySelected.getFirst(), "reloaded rules have new object identities");
        reloaded.unblockRules(previouslySelected);
        require(reloaded.blocked.size() == 1 && reloaded.isBlocked("192.168.1.5", 25565, "Second"),
                "batch restoration uses stable saved rule keys and preserves unselected rules");
        require(!reloaded.isBlocked("192.168.1.4", 25565, "First") && !reloaded.isBlocked("192.168.1.6", 25565, "Third"),
                "selected rules can be restored without discovering their rooms again");
        LanWorldsConfig persisted = LanWorldsConfig.load();
        require(persisted.blocked.size() == 1 && persisted.blocked.getFirst().key().equals(reloaded.blocked.getFirst().key()),
                "bulk restoration persists the remaining rule");
        persisted.unblockRules(persisted.blocked);
        require(persisted.blocked.isEmpty() && LanWorldsConfig.load().blocked.isEmpty(),
                "restoring the entire saved-rule collection is safe and persists an empty list");
    }

    private static void checkStableKeys() {
        BlockRule upper = new BlockRule(BlockMatchType.IP_PORT, " FD00::AB:25565 ");
        BlockRule lower = new BlockRule(BlockMatchType.IP_PORT, "fd00::ab:25565");
        require(upper.key().equals(lower.key()), "address selection keys follow case-insensitive rule matching");
        require(!new BlockRule(BlockMatchType.IP, "192.168.1.4").key()
                        .equals(new BlockRule(BlockMatchType.MOTD, "192.168.1.4").key()),
                "different matching types remain separate selections even when their values look alike");
        require(!new BlockRule(BlockMatchType.MOTD, "World").key().equals(new BlockRule(BlockMatchType.MOTD, "world").key()),
                "MOTD keys preserve case-sensitive matching");

        LanWorldsConfig config = new LanWorldsConfig();
        config.defaultBlockType = BlockMatchType.IP;
        config.blockRooms(List.of(room("FD00::AB", 25565, "First"), room("fd00::ab", 25566, "Second")));
        require(config.blocked.size() == 1, "case variants of an address do not create duplicate host rules");
    }

    private static void checkEmptySelection() throws Exception {
        LanWorldsConfig config = new LanWorldsConfig();
        config.blockRooms(List.of(room("192.168.1.4", 25565, "Keep")));
        Path file = FabricLoader.getInstance().getConfigDir().resolve("lanworlds.json");
        String saved = Files.readString(file);
        config.blockRooms(null);
        config.blockRooms(List.of());
        config.unblockRules(null);
        config.unblockRules(List.of());
        config.unblockRules(Arrays.asList(null, new BlockRule(BlockMatchType.IP, "192.168.1.99")));
        require(config.blocked.size() == 1 && Files.readString(file).equals(saved),
                "an empty or absent selection leaves saved rules intact");
    }

    private static LanRoomSnapshot room(String ip, int port, String motd) {
        return new LanRoomSnapshot(UUID.randomUUID(), new RoomKey(ip, port), motd, "", -1, -1, -1, 1,
                LanRoomSnapshot.State.DISCOVERED);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
