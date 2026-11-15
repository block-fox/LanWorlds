import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lanworlds.config.LanWorldsConfig;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.room.LanRoomRepository;
import dev.lanworlds.room.RoomKey;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ObjectShare;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.LanServer;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Tests the independent LAN data and the optional integration without starting a client. */
public final class LanWorldsRegressionCheck {
    public static void main(String[] args) throws Exception {
        String minecraftVersion = args.length == 0 ? "unknown" : args[0];
        checkConfigurationMigration(minecraftVersion);
        checkOptionalIntegration();
        checkRoomMatching();
        checkRepositoryShutdown();
        System.out.println("LANWORLDS_REGRESSION_OK Minecraft=" + minecraftVersion);
    }

    private static void checkConfigurationMigration(String minecraftVersion) throws Exception {
        // Set the loader's directory before LanWorldsConfig resolves its static paths.
        Path work = Path.of(System.getProperty("lanworldsRegression.workDir")).toAbsolutePath().normalize();
        Path game = Files.createTempDirectory(work, "lanworlds-" + minecraftVersion + "-");
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, game);
        Path legacyFile = loader.getConfigDir().resolve("radminlan.json");
        Path ownFile = loader.getConfigDir().resolve("lanworlds.json");
        require(ownFile.startsWith(work), "configuration fixture remains inside the temporary directory");
        Files.createDirectories(legacyFile.getParent());

        String legacyJson = """
                {
                  "queryLanStatus": false,
                  "queryTimeoutMs": 1700,
                  "defaultBlockType": "MOTD",
                  "blocked": [
                    {"type":"IP_PORT","value":"26.0.0.4:25565","enabled":true,"createdAt":123},
                    {"type":"MOTD","value":"Old room","enabled":false,"createdAt":456}
                  ],
                  "radminExecutable": "C:/Radmin/RvRvpnGui.exe",
                  "pipeName": "legacy-private-pipe",
                  "blockedPeers": [{"nodeId":24,"alias":"Peer"}],
                  "refreshIntervalSeconds": 17,
                  "disabledNetworks": ["private-network-guid"]
                }
                """;
        Files.writeString(legacyFile, legacyJson);

        LanWorldsConfig migrated = LanWorldsConfig.load();
        require(!migrated.queryLanStatus && migrated.queryTimeoutMs == 1700,
                "LAN query settings migrate from the previous combined mod");
        require(migrated.defaultBlockType == LanWorldsConfig.BlockMatchType.MOTD,
                "the selected LAN block rule type migrates");
        require(migrated.blocked.size() == 2 && migrated.blocked.getFirst().createdAt == 123,
                "LAN block rules retain their creation time and enabled state");
        require(migrated.isBlocked("26.0.0.4", 25565, "Unrelated room"),
                "an enabled migrated endpoint remains blocked");
        require(!migrated.isBlocked("26.0.0.5", 25565, "Old room"),
                "a disabled migrated rule stays disabled");
        equal(Files.readString(legacyFile), legacyJson, "migration preserves the Radmin configuration byte for byte");
        require(Files.exists(ownFile), "migration writes the independent LanWorlds configuration");

        JsonObject own = JsonParser.parseString(Files.readString(ownFile)).getAsJsonObject();
        for (String field : List.of("radminExecutable", "pipeName", "blockedPeers", "refreshIntervalSeconds", "disabledNetworks")) {
            require(!own.has(field), "Radmin-only configuration is not retained: " + field);
        }

        // Once LanWorlds has its own file, later changes in the old mod must not override it.
        migrated.queryLanStatus = true;
        migrated.queryTimeoutMs = 2300;
        migrated.defaultBlockType = LanWorldsConfig.BlockMatchType.IP;
        migrated.blocked.clear();
        migrated.block("192.168.1.8", 25565, "Local room");
        Files.writeString(legacyFile, "{\"queryLanStatus\":false,\"queryTimeoutMs\":9000}");
        String changedLegacy = Files.readString(legacyFile);
        LanWorldsConfig reloaded = LanWorldsConfig.load();
        require(reloaded.queryLanStatus && reloaded.queryTimeoutMs == 2300,
                "the independent file takes precedence on subsequent launches");
        require(reloaded.isBlocked("192.168.1.8", 12345, "Different room"),
                "independent LAN rules persist without the Radmin mod");
        equal(Files.readString(legacyFile), changedLegacy, "later saves also preserve the Radmin file");

        Files.writeString(ownFile, "{\"queryTimeoutMs\":-10,\"defaultBlockType\":null,\"blocked\":[null,{},"
                + "{\"type\":\"IP\",\"value\":\"192.168.1.9\"}]}" );
        LanWorldsConfig normalized = LanWorldsConfig.load();
        require(normalized.queryTimeoutMs == 100 && normalized.defaultBlockType == LanWorldsConfig.BlockMatchType.IP_PORT,
                "invalid persisted values use safe query bounds and block defaults");
        require(normalized.blocked.size() == 1 && normalized.isBlocked("192.168.1.9", 1, ""),
                "invalid rules are removed while valid rules remain usable");
        Files.writeString(ownFile, "{\"queryTimeoutMs\":2147483647,\"blocked\":null}");
        normalized = LanWorldsConfig.load();
        require(normalized.queryTimeoutMs == 10_000 && normalized.blocked.isEmpty(),
                "large query timeouts and missing rules normalize safely");

        Files.writeString(ownFile, "{bad json");
        LanWorldsConfig recovered = LanWorldsConfig.load();
        require(recovered.queryLanStatus && recovered.queryTimeoutMs == 1000 && recovered.blocked.isEmpty(),
                "a damaged independent file recovers to independent defaults");
        require(JsonParser.parseString(Files.readString(ownFile)).isJsonObject(),
                "recovery writes valid JSON for the next launch");
        equal(Files.readString(legacyFile), changedLegacy, "error recovery leaves the other mod's settings unchanged");
        System.out.println("CONFIG_MIGRATION_REGRESSION_OK isolatedConfig=" + ownFile);
    }

    private static void checkOptionalIntegration() {
        ObjectShare share = FabricLoader.getInstance().getObjectShare();
        String key = RadminIntegration.PROVIDER_KEY;
        Object previous = share.remove(key);
        try {
            require(!RadminIntegration.available() && RadminIntegration.lookup("26.0.0.1") == null,
                    "LAN rooms remain independent when no Radmin provider is installed");
            share.put(key, "outdated-provider-format");
            require(!RadminIntegration.available() && RadminIntegration.lookup("26.0.0.1") == null,
                    "an unsupported provider format is treated as unavailable");
            share.remove(key);

            AtomicReference<String> queriedIp = new AtomicReference<>();
            AtomicBoolean opened = new AtomicBoolean();
            Consumer<Screen> open = parent -> opened.set(parent == null);
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(Map.of("label", "User properties", "open", open));
            Map<String, Object> values = new HashMap<>(Map.of(
                    "name", "Room owner", "virtualIp", "26.0.0.1", "virtualIpv6", "fd00::1",
                    "nodeId", 99L, "publicIp", "192.0.2.1", "connectionType", "Direct",
                    "version", "1.4.5", "state", "online", "latencyMs", 42L, "networks", "Friends"));
            values.put("actions", actions);
            Function<String, Map<String, Object>> provider = ip -> { queriedIp.set(ip); return values; };
            share.put(key, provider);
            require(RadminIntegration.available(), "a compatible optional provider is detected");
            RadminIntegration.RoomInfo info = RadminIntegration.lookup("26.0.0.1");
            require(info != null, "matching Radmin room data is available through the bridge");
            equal(queriedIp.get(), "26.0.0.1", "the bridge requests the room host address");
            equal(info.name(), "Room owner", "the owner's display name is preserved");
            equal(info.nodeId(), "99", "numeric identifiers are safely exposed as text");
            require(info.latencyMs() == 42 && info.state().equals("online") && info.networks().equals("Friends"),
                    "optional Radmin telemetry and memberships remain available");
            require(info.actions().size() == 1 && info.actions().getFirst().label().equals("User properties"),
                    "optional property actions retain their labels");
            info.actions().getFirst().open().accept(null);
            require(opened.get(), "property actions pass their caller's parent screen to the management mod");
            actions.clear();
            require(info.actions().size() == 1, "room action snapshots do not change with the provider's mutable list");
            try {
                info.actions().clear();
                throw new AssertionError("room action snapshots must be immutable");
            } catch (UnsupportedOperationException expected) {
            }
            require(RadminIntegration.lookup(null) == null && RadminIntegration.lookup(" ") == null,
                    "missing addresses never reach the optional provider");
            share.remove(key);

            for (Object invalidResult : new Object[] {Map.of(), "unsupported", List.of()}) {
                share.put(key, (Function<String, Object>) ip -> invalidResult);
                require(RadminIntegration.lookup("26.0.0.1") == null,
                        "unmatched or malformed provider responses leave vanilla room details available");
                share.remove(key);
            }
            share.put(key, (Function<String, Object>) ip -> null);
            require(RadminIntegration.lookup("26.0.0.1") == null, "a null provider result is safe");
            share.remove(key);
            share.put(key, (Function<String, Object>) ip -> { throw new IllegalStateException("provider unavailable"); });
            require(RadminIntegration.lookup("26.0.0.1") == null, "provider failures do not break vanilla LAN inspection");
            share.remove(key);
            share.put(key, (Function<String, Object>) ip -> { throw new NoClassDefFoundError("optional dependency"); });
            require(RadminIntegration.lookup("26.0.0.1") == null, "an unavailable optional dependency does not break LAN inspection");
            share.remove(key);

            Map<String, Object> partial = new HashMap<>();
            partial.put("name", new Object());
            partial.put("latencyMs", "not a number");
            partial.put("actions", List.of("bad entry", Map.of("label", "missing callback"),
                    Map.of("label", "", "open", open), Map.of("label", "valid action", "open", open)));
            share.put(key, (Function<String, Object>) ip -> partial);
            RadminIntegration.RoomInfo safe = RadminIntegration.lookup("26.0.0.1");
            require(safe != null && safe.name().isEmpty() && safe.latencyMs() == -1 && safe.actions().size() == 1,
                    "malformed optional fields use defaults and malformed actions are skipped");
        } finally {
            share.remove(key);
            if (previous != null) share.put(key, previous);
        }
        System.out.println("OPTIONAL_INTEGRATION_REGRESSION_OK missing=independent present=linked damaged=independent");
    }

    private static void checkRoomMatching() {
        RoomKey endpoint = new RoomKey(" 192.168.1.4 ", 25565);
        Map<RoomKey, String> rooms = Map.of(endpoint, "Local world");
        equal(rooms.get(new RoomKey("192.168.1.4", 25565)), "Local world",
                "an advertisement with whitespace resolves to the same room");
        require(!endpoint.equals(new RoomKey("192.168.1.4", 25566)),
                "different worlds on one host remain distinct endpoints");
        require(!endpoint.equals(new RoomKey("192.168.1.5", 25565)),
                "rooms on different hosts remain distinct");
        equal(new RoomKey(null, 1).ip(), "", "an absent host is normalized safely");
        equal(new RoomKey(" fd00::42 ", 25565), new RoomKey("fd00::42", 25565),
                "IPv6 room hosts retain their address while trimming whitespace");

        LanWorldsConfig config = new LanWorldsConfig();
        config.defaultBlockType = LanWorldsConfig.BlockMatchType.IP_PORT;
        config.block("192.168.1.4", 25565, "First");
        config.block("192.168.1.4", 25565, "Second");
        require(config.blocked.size() == 1 && config.isBlocked("192.168.1.4", 25565, "Renamed room"),
                "endpoint block rules deduplicate and survive a MOTD change");
        require(!config.isBlocked("192.168.1.4", 25566, "First"),
                "endpoint blocks preserve other worlds on the same host");
        config.defaultBlockType = LanWorldsConfig.BlockMatchType.IP;
        config.block("192.168.1.5", 25565, "Host");
        require(config.isBlocked("192.168.1.5", 25566, "Other world"),
                "host block rules intentionally apply across ports");
        config.defaultBlockType = LanWorldsConfig.BlockMatchType.MOTD;
        config.block("192.168.1.6", 25565, "  Shared  world\nname ");
        require(config.isBlocked("192.168.1.7", 25566, "Shared world name"),
                "MOTD block rules ignore advertisement whitespace");
        require(!config.isBlocked("192.168.1.7", 25566, "Shared world name extended"),
                "MOTD blocks compare complete names instead of substrings");
        LanWorldsConfig.BlockRule motdRule = config.blocked.getLast();
        motdRule.enabled = false;
        require(!config.isBlocked("192.168.1.7", 25566, "Shared world name"),
                "a disabled LAN rule no longer hides rooms");
        config.unblock(motdRule);
        require(config.blocked.size() == 2, "unblocking removes only the selected rule");
        System.out.println("ROOM_MATCHING_REGRESSION_OK endpoint=host+port rules=IP,IP_PORT,MOTD");
    }

    private static void checkRepositoryShutdown() throws Exception {
        SharedConstants.tryDetectVersion();
        LanWorldsConfig config = new LanWorldsConfig();
        config.queryLanStatus = true;
        config.queryTimeoutMs = 10_000;
        LanRoomRepository repository = new LanRoomRepository(config);
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread stalledServer = new Thread(() -> {
                // Receive the real status handshake but deliberately withhold a
                // reply. Shutdown must cancel Socket.read before its 10s timeout.
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    while (peer.getInputStream().read() >= 0) requestReceived.countDown();
                    disconnected.countDown();
                } catch (Exception error) {
                    serverFailure.set(error);
                }
            }, "lanworlds-regression-stalled-status");
            stalledServer.setDaemon(true);
            stalledServer.start();
            LanServer discovered = new LanServer("Closing room", "127.0.0.1:" + server.getLocalPort());
            repository.syncVanillaServers(List.of(discovered));
            require(requestReceived.await(5, TimeUnit.SECONDS), "a real LAN status request reaches the local fixture");
            repository.close();
            require(repository.queryExecutor().awaitTermination(2, TimeUnit.SECONDS),
                    "closing the repository immediately unblocks a pending status read");
            require(disconnected.await(2, TimeUnit.SECONDS) && serverFailure.get() == null,
                    "closing the repository closes its active network socket");
            require(repository.allRooms().isEmpty(), "closed repositories release room snapshots");
            repository.syncVanillaServers(List.of(discovered));
            require(repository.allRooms().isEmpty(), "late discovery does not revive a closed repository");
            repository.close();
            require(repository.queryExecutor().isShutdown(), "repeated shutdown is safe");
        } finally {
            repository.close();
        }
        System.out.println("REPOSITORY_SHUTDOWN_REGRESSION_OK pendingSocket=closed lateDiscovery=ignored");
    }

    private static void equal(Object actual, Object expected, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
