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
import net.minecraft.client.server.LanServerDetection;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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
        RoomSelectionRegression.check();
        BlockedRulesRegression.check();
        checkOptionalIntegration();
        checkRoomMatching();
        checkRepositoryShutdown();
        checkEmbeddedRoomStatus();
        checkLegacyStatus();
        checkVanillaDiscoveryRestart();
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

    private static void checkEmbeddedRoomStatus() throws Exception {
        var share = FabricLoader.getInstance().getObjectShare();
        var name = "radminlan:lan-rooms-v1";
        var route = "radminlan:status-route-v1";
        var closed = new CountDownLatch(1);
        var config = new LanWorldsConfig();
        config.queryTimeoutMs = 1000;
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var rooms = new LanRoomRepository(config)) {
            share.put(name, (java.util.function.Supplier<List<Map<String, Object>>>) () -> List.of(
                    Map.of("motd", "VPN world", "host", "26.11.12.13", "port", 25565)));
            share.put(route, (Function<java.net.InetSocketAddress, Map.Entry<java.net.InetSocketAddress, AutoCloseable>>) target -> {
                require(target.isUnresolved() && target.getHostString().equals("26.11.12.13"), "VPN host preserved for routing");
                return Map.entry((java.net.InetSocketAddress) listener.getLocalSocketAddress(), closed::countDown);
            });
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var in = new java.io.DataInputStream(socket.getInputStream());
                    var out = new java.io.DataOutputStream(socket.getOutputStream());
                    in.skipNBytes(readVarInt(in));
                    in.skipNBytes(readVarInt(in));
                    byte[] json = "{\"version\":{\"name\":\"Test VPN server\",\"protocol\":777},\"players\":{\"online\":2,\"max\":12},\"description\":\"Hello\"}".getBytes(StandardCharsets.UTF_8);
                    var frame = new java.io.ByteArrayOutputStream();
                    frame.write(0);
                    writeVarInt(frame, json.length);
                    frame.write(json);
                    writeVarInt(out, frame.size());
                    frame.writeTo(out);
                    out.flush();
                    equal(readVarInt(in), 9, "ping size");
                    equal(in.readUnsignedByte(), 1, "ping packet id");
                    out.writeByte(9);
                    out.writeByte(1);
                    out.writeLong(in.readLong());
                    out.flush();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            rooms.syncVanillaServers(RadminIntegration.rooms());
            require(closed.await(4, TimeUnit.SECONDS), "status route lease closed");
            served.get(4, TimeUnit.SECONDS);
            var key = RoomKey.fromAddress("26.11.12.13:25565");
            var room = rooms.get(key);
            require(room != null && room.protocol() == 777 && room.players() == 2 && room.latencyMs() >= 0,
                    "the routed status exchange includes version, count and ping");
            share.remove(name);
            rooms.syncVanillaServers(RadminIntegration.rooms());
            require(rooms.get(key) == null, "expired embedded advertisements leave the room list");
        } finally {
            share.remove(name);
            share.remove(route);
        }
        System.out.println("EMBEDDED_ROOM_STATUS_REGRESSION_OK");
    }

    private static void checkLegacyStatus() throws Exception {
        try (var listener = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
             var rooms = new LanRoomRepository(new LanWorldsConfig())) {
            listener.setSoTimeout(3000);
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    try (var modern = listener.accept()) {
                        modern.setSoTimeout(3000);
                        var in = new java.io.DataInputStream(modern.getInputStream());
                        in.skipNBytes(readVarInt(in));
                        in.skipNBytes(readVarInt(in));
                    }
                    try (var legacy = listener.accept()) {
                        legacy.setSoTimeout(3000);
                        var in = new java.io.DataInputStream(legacy.getInputStream());
                        var out = new java.io.DataOutputStream(legacy.getOutputStream());
                        equal(in.readUnsignedByte(), 0xFE, "legacy query id");
                        equal(in.readUnsignedByte(), 1, "legacy query version");
                        equal(in.readUnsignedByte(), 0xFA, "legacy plugin message");
                        in.skipNBytes(in.readUnsignedShort() * 2L);
                        in.skipNBytes(in.readUnsignedShort());
                        String reply = String.join(String.valueOf((char) 0), "§1", "78", "1.6.4", "Classic LAN", "3", "16");
                        out.writeByte(0xFF);
                        out.writeShort(reply.length());
                        out.writeChars(reply);
                        out.flush();
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String address = "127.0.0.1:" + listener.getLocalPort();
            rooms.syncVanillaServers(List.of(new LanServer("Legacy", address)));
            served.get(4, TimeUnit.SECONDS);
            var key = RoomKey.fromAddress(address);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (rooms.get(key).state() == dev.lanworlds.room.LanRoomSnapshot.State.QUERYING
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var room = rooms.get(key);
            require(room.protocol() == 78 && room.versionName().equals("1.6.4") && room.players() == 3,
                    "legacy response reported the version and player count");
        }
        System.out.println("LEGACY_ROOM_STATUS_REGRESSION_OK");
    }

    private static void writeVarInt(java.io.OutputStream out, int value) throws java.io.IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(java.io.DataInputStream in) throws java.io.IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new java.io.IOException("Invalid status frame size");
    }

    private static void checkVanillaDiscoveryRestart() throws Exception {
        LanServerDetection.LanServerList cache = new LanServerDetection.LanServerList();
        var socketField = LanServerDetection.LanServerDetector.class.getDeclaredField("socket");
        socketField.setAccessible(true);
        int advertisedPort = 25565;
        for (String motd : List.of("Before leaving multiplayer", "After returning to multiplayer")) {
            // Exercise the actual Minecraft detector with the same cache across
            // removal and recreation. Only send to this computer's loopback.
            LanServerDetection.LanServerDetector detector = new LanServerDetection.LanServerDetector(cache);
            MulticastSocket socket = (MulticastSocket) socketField.get(detector);
            try {
                detector.start();
                sendLocalAdvertisement(motd, advertisedPort++);
                boolean found = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!found && System.nanoTime() < deadline) {
                    List<LanServer> dirty = cache.takeDirtyServers();
                    found = dirty != null && dirty.stream().anyMatch(server -> motd.equals(server.getMotd()));
                    if (!found) Thread.sleep(10);
                }
                require(found && detector.isAlive(), "the running detector accepts new local advertisements: " + motd);
                detector.interrupt();
                // Wake receive() so this test does not need the five-second
                // vanilla timeout before it can inspect the stopped socket.
                sendLocalAdvertisement("Stopping regression detector", 25567);
                detector.join(2000);
                require(!detector.isAlive() && socket.isClosed(),
                        "vanilla removal stops its detector and releases its socket");
            } finally {
                detector.interrupt();
                socket.close();
                detector.join(1000);
            }
        }
        System.out.println("VANILLA_DISCOVERY_RESTART_REGRESSION_OK sharedCache=retained newAdvertisements=received");
    }

    private static void sendLocalAdvertisement(String motd, int port) throws Exception {
        byte[] data = ("[MOTD]" + motd + "[/MOTD][AD]" + port + "[/AD]").getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sender = new DatagramSocket()) {
            sender.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), 4445));
        }
    }

    private static void equal(Object actual, Object expected, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
