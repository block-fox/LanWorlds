package dev.lanworlds.mixin;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.ui.LanWorldsScreen;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.RoomKey;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    private static final Logger LANWORLDS$LOGGER = LoggerFactory.getLogger("LanWorlds");

    @org.spongepowered.asm.mixin.Shadow
    protected ServerSelectionList serverSelectionList;

    @org.spongepowered.asm.mixin.Shadow
    private LanServerDetection.LanServerList lanServerList;

    @org.spongepowered.asm.mixin.Shadow
    private LanServerDetection.LanServerDetector lanServerDetector;

    @org.spongepowered.asm.mixin.Shadow
    private Button editButton;

    @org.spongepowered.asm.mixin.Shadow
    private Button deleteButton;

    private String lanworlds$lastLanSignature = "";
    private int lanworlds$refreshCooldown;
    private int lanworlds$discoveryRetryCooldown;
    private Button lanworlds$roomDetailsButton;
    private Button lanworlds$roomBlockButton;

    protected MultiplayerScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void lanworlds$startDiscovery(CallbackInfo ci) {
        LanWorldsClient.MULTIPLAYER_SCREEN = (Screen)(Object)this;
        if (LanWorldsClient.ROOMS != null) {
            lanworlds$refreshLanEntries();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void lanworlds$restoreDiscovery(CallbackInfo ci) {
        if (lanServerList == null || lanServerDetector != null) return;
        if (lanworlds$discoveryRetryCooldown > 0) {
            lanworlds$discoveryRetryCooldown--;
            return;
        }
        // Returning to an initialized parent screen only repositions widgets.
        // Vanilla removed() stopped discovery, so resume it using its existing
        // detector cache without rebuilding the list or losing its selection.
        try {
            lanServerDetector = new LanServerDetection.LanServerDetector(lanServerList);
            lanServerDetector.start();
            LANWORLDS$LOGGER.info("Resumed LAN discovery on the multiplayer screen");
        } catch (IOException error) {
            lanServerDetector = null;
            lanworlds$discoveryRetryCooldown = 100;
            LANWORLDS$LOGGER.warn("Unable to resume LAN discovery; retrying in five seconds", error);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lanworlds$mergeRooms(CallbackInfo ci) {
        // LAN discovery is updated asynchronously by vanilla.  Rebuilding the
        // merged list every client tick only copied the same entries repeatedly;
        // a short cadence is enough to keep discovery responsive while leaving
        // the render thread free for the list itself.
        if (lanworlds$refreshCooldown > 0) {
            lanworlds$refreshCooldown--;
            return;
        }
        lanworlds$refreshCooldown = 4;
        lanworlds$refreshLanEntries();
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;updateNetworkServers(Ljava/util/List;)V"), index = 0)
    private List<LanServer> lanworlds$filterVanillaDiscovery(List<LanServer> detected) {
        // Vanilla replaces its rows when a new advertisement arrives, even if
        // our filtered signature did not change. Filter that update as well.
        return lanworlds$filterDetectedServers(detected);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void lanworlds$releaseLanFavicons(CallbackInfo ci) {
        lanworlds$discoveryRetryCooldown = 0;
        LanRoomFaviconCache.clear();
    }

    private void lanworlds$refreshLanEntries() {
        if (serverSelectionList == null || lanServerList == null || LanWorldsClient.ROOMS == null) return;

        List<LanServer> detected;
        synchronized (lanServerList) {
            detected = new ArrayList<>(((LanServerListAccessor) lanServerList).lanworlds$getServers());
        }
        LanWorldsClient.ROOMS.syncVanillaServers(detected);
        List<LanServer> vanilla = lanworlds$filterDetectedServers(detected);
        Set<String> addresses = new HashSet<>();
        for (LanServer server : vanilla) addresses.add(lanworlds$normalizeAddress(server.getAddress()));

        for (LanRoomSnapshot room : LanWorldsClient.ROOMS.visibleRooms()) {
            String address = lanworlds$formatAddress(room.key().ip(), room.key().port());
            if (!lanworlds$isValidAddress(address)) continue;
            if (addresses.add(lanworlds$normalizeAddress(address))
                    && !LanWorldsClient.CONFIG.isBlocked(room.key().ip(), room.key().port(), room.motd())) {
                String motd = room.motd() == null || room.motd().isBlank() ? "LAN World" : room.motd();
                String safeMotd = motd.replace("[MOTD]", "").replace("[/MOTD]", "").replace("[AD]", "").replace("[/AD]", "");
                vanilla.add(new LanServer(safeMotd, address));
            }
        }
        StringBuilder signature = new StringBuilder();
        for (LanServer server : vanilla) {
            signature.append(server.getAddress()).append('\u0000').append(server.getMotd()).append('\u0001');
        }
        String nextSignature = signature.toString();
        if (nextSignature.equals(lanworlds$lastLanSignature)) return;
        lanworlds$lastLanSignature = nextSignature;
        serverSelectionList.updateNetworkServers(vanilla);
    }

    private List<LanServer> lanworlds$filterDetectedServers(List<LanServer> detected) {
        List<LanServer> filtered = new ArrayList<>(detected);
        // A malformed multicast advertisement must never reach ServerAddress.parseString.
        filtered.removeIf(server -> server == null || !lanworlds$isValidAddress(server.getAddress())
                || lanworlds$isBlocked(server.getAddress(), server.getMotd()));
        return filtered;
    }

    private static boolean lanworlds$isValidAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String value = address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return false;
        String host = value.substring(0, separator);
        String portText = value.substring(separator + 1);
        if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            if (!(host.startsWith("[") && host.endsWith("]"))) return false;
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(portText);
            return port > 0 && port <= 65535 && !host.isBlank() && !host.contains("§");
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean lanworlds$isBlocked(String address, String motd) {
        if (LanWorldsClient.CONFIG == null) return false;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return false;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            return LanWorldsClient.CONFIG.isBlocked(host, Integer.parseInt(address.substring(separator + 1)), motd);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String lanworlds$formatAddress(String host, int port) {
        String normalizedHost = host == null ? "" : host.trim();
        return (normalizedHost.indexOf(':') >= 0 && !normalizedHost.startsWith("[")
                ? "[" + normalizedHost + "]" : normalizedHost) + ":" + port;
    }

    private static String lanworlds$normalizeAddress(String address) {
        String value = address == null ? "" : address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return value.toLowerCase(Locale.ROOT);
        String host = value.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.toLowerCase(Locale.ROOT) + ":" + value.substring(separator + 1).trim();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void lanworlds$addButton(CallbackInfo ci) {
        Button button = Button.builder(Component.translatable("screen.lanworlds.title"),
                        ignored -> minecraft.setScreen(new LanWorldsScreen(this)))
                .bounds(Math.max(4, width - 92), 8, 84, 20).build();
        addRenderableWidget(button);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void lanworlds$addRoomFooterActions(CallbackInfo ci) {
        if (editButton == null || deleteButton == null) return;
        lanworlds$roomDetailsButton = Button.builder(Component.translatable("screen.lanworlds.room_details"),
                        ignored -> lanworlds$openRoomDetails())
                .bounds(editButton.getX(), editButton.getY(), editButton.getWidth(), editButton.getHeight()).build();
        lanworlds$roomBlockButton = Button.builder(Component.translatable("screen.lanworlds.block_room"),
                        ignored -> lanworlds$blockSelectedRoom())
                .bounds(deleteButton.getX(), deleteButton.getY(), deleteButton.getWidth(), deleteButton.getHeight()).build();
        lanworlds$roomDetailsButton.visible = false;
        lanworlds$roomBlockButton.visible = false;
        addRenderableWidget(lanworlds$roomDetailsButton);
        addRenderableWidget(lanworlds$roomBlockButton);
        lanworlds$updateRoomFooterActions();
    }

    @Inject(method = "onSelectedChange", at = @At("TAIL"))
    private void lanworlds$updateRoomFooterActions(CallbackInfo ci) {
        lanworlds$updateRoomFooterActions();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void lanworlds$repositionRoomFooterActions(CallbackInfo ci) {
        // Header/footer layout recalculates button bounds during init and
        // window resize. Mirror the vanilla edit/delete slots immediately so
        // the custom room actions never flash over the top row.
        lanworlds$positionRoomFooterActions();
    }

    private void lanworlds$updateRoomFooterActions() {
        if (lanworlds$roomDetailsButton == null || serverSelectionList == null) return;
        lanworlds$positionRoomFooterActions();
        boolean selectedLan = serverSelectionList.getSelected() instanceof ServerSelectionList.NetworkServerEntry;
        lanworlds$roomDetailsButton.visible = selectedLan;
        lanworlds$roomBlockButton.visible = selectedLan;
        lanworlds$roomDetailsButton.active = selectedLan;
        lanworlds$roomBlockButton.active = selectedLan;
        if (editButton != null) editButton.visible = !selectedLan;
        if (deleteButton != null) deleteButton.visible = !selectedLan;
    }

    private void lanworlds$positionRoomFooterActions() {
        if (lanworlds$roomDetailsButton == null || lanworlds$roomBlockButton == null) return;
        // The vanilla footer is a two-row layout: join/direct/add on the
        // first row and edit/delete/refresh/back on the second. The buttons
        // are added after vanilla's layout pass, so mirror the live edit and
        // delete geometry once it is available instead of guessing a row.
        if (editButton == null || deleteButton == null
                || editButton.getWidth() <= 0 || editButton.getHeight() <= 0
                || deleteButton.getWidth() <= 0 || deleteButton.getHeight() <= 0) return;
        lanworlds$roomDetailsButton.setX(editButton.getX());
        lanworlds$roomDetailsButton.setY(editButton.getY());
        lanworlds$roomDetailsButton.setWidth(editButton.getWidth());
        lanworlds$roomDetailsButton.setHeight(editButton.getHeight());
        lanworlds$roomBlockButton.setX(deleteButton.getX());
        lanworlds$roomBlockButton.setY(deleteButton.getY());
        lanworlds$roomBlockButton.setWidth(deleteButton.getWidth());
        lanworlds$roomBlockButton.setHeight(deleteButton.getHeight());
    }

    private void lanworlds$openRoomDetails() {
        LanRoomSnapshot room = lanworlds$selectedRoom();
        if (room == null) return;
        minecraft.setScreen(new LanRoomContextScreen((Screen) (Object) this, room));
    }

    private void lanworlds$blockSelectedRoom() {
        LanServer server = lanworlds$selectedLanServer();
        RoomKey key = server == null ? null : lanworlds$roomKey(server.getAddress());
        if (server == null || key == null || LanWorldsClient.CONFIG == null) return;
        LanWorldsClient.CONFIG.block(key.ip(), key.port(), server.getMotd());
        if (LanWorldsClient.ROOMS != null) LanWorldsClient.ROOMS.remove(key);
        lanworlds$refreshLanEntries();
        if (serverSelectionList != null) serverSelectionList.setSelected(null);
    }

    private LanRoomSnapshot lanworlds$selectedRoom() {
        LanServer server = lanworlds$selectedLanServer();
        RoomKey key = server == null ? null : lanworlds$roomKey(server.getAddress());
        if (key == null) return null;
        LanRoomSnapshot room = LanWorldsClient.ROOMS == null ? null : LanWorldsClient.ROOMS.get(key);
        return room != null ? room : new LanRoomSnapshot(UUID.randomUUID(), key, server.getMotd(), "", "",
                -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED);
    }

    private LanServer lanworlds$selectedLanServer() {
        if (serverSelectionList == null) return null;
        var selected = serverSelectionList.getSelected();
        if (!(selected instanceof ServerSelectionList.NetworkServerEntry entry)
                || !(entry instanceof LanServerEntryDataAccessor accessor)) return null;
        return accessor.lanworlds$getServerData();
    }

    private static RoomKey lanworlds$roomKey(String address) {
        if (address == null || address.isBlank()) return null;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return null;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try { return new RoomKey(host, Integer.parseInt(address.substring(separator + 1))); }
        catch (NumberFormatException ignored) { return null; }
    }
}
