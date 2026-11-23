package dev.lanworlds.mixin;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.ui.ToolbarButton;
import dev.lanworlds.ui.ToolbarLayout;
import net.minecraft.client.gui.components.AbstractWidget;
import dev.lanworlds.ui.LanWorldsScreen;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.ui.LanRoomSelectionHost;
import dev.lanworlds.room.LanRoomSelection;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.RoomKey;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen implements LanRoomSelectionHost {
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

    @org.spongepowered.asm.mixin.Shadow(aliases = {"selectButton"})
    private Button joinButton;

    @org.spongepowered.asm.mixin.Shadow @Final
    private HeaderAndFooterLayout layout;

    private String lanworlds$lastLanSignature = "";
    private int lanworlds$refreshCooldown;
    private int lanworlds$discoveryRetryCooldown;
    private Button lanworlds$roomDetailsButton;
    private Button lanworlds$roomBlockButton;
    private final List<ToolbarButton> lanworlds$entries = new ArrayList<>();
    private ToolbarButton lanworlds$draggedEntry;
    private double lanworlds$pressX, lanworlds$pressY;
    private double lanworlds$grabX, lanworlds$grabY;
    private boolean lanworlds$entryMoved;
    private final LanRoomSelection lanworlds$roomSelection = new LanRoomSelection();
    private ToolbarButton lanworlds$multiSelectButton;
    private Button lanworlds$selectAllButton;
    private Button lanworlds$invertButton;
    private Button lanworlds$clearSelectionButton;

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
        lanworlds$updateRoomFooterActions();
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
        lanworlds$entries.clear();
        lanworlds$draggedEntry = null;
        lanworlds$multiSelectButton = new ToolbarButton(ToolbarButton.Icon.SELECTION,
                Component.translatable("screen.lanworlds.multi_select"), () -> {
                    lanworlds$roomSelection.setEnabled(!lanworlds$roomSelection.enabled());
                    lanworlds$updateRoomFooterActions();
                });
        lanworlds$entries.add(lanworlds$multiSelectButton);
        lanworlds$entries.add(new ToolbarButton(ToolbarButton.Icon.SIGNAL, Component.translatable("screen.lanworlds.title"),
                () -> minecraft.setScreenAndShow(new LanWorldsScreen(this))));
        var radmin = RadminIntegration.managementAction();
        if (radmin != null) {
            lanworlds$entries.add(new ToolbarButton(ToolbarButton.Icon.RADMIN,
                    Component.translatable("screen.radminlan.title"), () -> radmin.accept(this)));
        }
        lanworlds$entries.forEach(this::addRenderableWidget);
        lanworlds$selectAllButton = addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.select_all"),
                ignored -> {
                    lanworlds$roomSelection.selectAll(lanworlds$visibleRoomKeys());
                    lanworlds$updateRoomFooterActions();
                }).bounds(0, 34, 80, 20).build());
        lanworlds$invertButton = addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.invert_selection"),
                ignored -> {
                    lanworlds$roomSelection.invert(lanworlds$visibleRoomKeys());
                    lanworlds$updateRoomFooterActions();
                }).bounds(0, 34, 80, 20).build());
        lanworlds$clearSelectionButton = addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.clear_selection"),
                ignored -> {
                    lanworlds$roomSelection.clear();
                    lanworlds$updateRoomFooterActions();
                }).bounds(0, 34, 80, 20).build());
        lanworlds$updateRoomFooterActions();
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
        lanworlds$updateRoomFooterActions();
    }

    private void lanworlds$updateRoomFooterActions() {
        lanworlds$updateSelectionControls();
        if (lanworlds$roomDetailsButton == null || serverSelectionList == null) return;
        lanworlds$positionRoomFooterActions();
        boolean selectedLan = serverSelectionList.getSelected() instanceof ServerSelectionList.NetworkServerEntry;
        boolean multiple = lanworlds$roomSelection.enabled();
        int selectedCount = lanworlds$roomSelection.selectedAmong(lanworlds$visibleRoomKeys()).size();
        lanworlds$roomDetailsButton.visible = selectedLan || multiple;
        lanworlds$roomBlockButton.visible = selectedLan || multiple;
        lanworlds$roomDetailsButton.active = multiple ? selectedCount == 1 : selectedLan;
        lanworlds$roomBlockButton.active = multiple ? selectedCount > 0 : selectedLan;
        lanworlds$roomBlockButton.setMessage(Component.translatable(multiple ? "screen.lanworlds.block_selected" : "screen.lanworlds.block_room", selectedCount));
        if (editButton != null) editButton.visible = !(selectedLan || multiple);
        if (deleteButton != null) deleteButton.visible = !(selectedLan || multiple);
        if (joinButton != null) joinButton.active = !multiple && serverSelectionList.getSelected() != null
                && !(serverSelectionList.getSelected() instanceof ServerSelectionList.LANHeader);
    }

    private void lanworlds$updateSelectionControls() {
        if (lanworlds$multiSelectButton == null || serverSelectionList == null) return;
        boolean selecting = lanworlds$roomSelection.enabled();
        List<RoomKey> visible = lanworlds$visibleRoomKeys();
        int count = lanworlds$roomSelection.selectedAmong(visible).size();
        lanworlds$multiSelectButton.setMessage(Component.translatable(selecting ? "screen.lanworlds.single_select" : "screen.lanworlds.multi_select"));
        lanworlds$multiSelectButton.setTooltip(Tooltip.create(Component.translatable("screen.lanworlds.selected_count", count)
                .append("\n").append(Component.translatable("screen.lanworlds.selection_hint"))));
        int buttonWidth = Math.max(1, Math.min(100, (width - 32 - 8) / 3));
        int start = (width - buttonWidth * 3 - 8) / 2;
        Button[] toolbar = {lanworlds$selectAllButton, lanworlds$invertButton, lanworlds$clearSelectionButton};
        for (int index = 0; index < toolbar.length; index++) {
            Button button = toolbar[index];
            if (button == null) continue;
            button.visible = selecting;
            button.setX(start + index * (buttonWidth + 4));
            button.setWidth(buttonWidth);
            button.active = index == 2 ? !lanworlds$roomSelection.selected().isEmpty() : !visible.isEmpty();
        }
        int top = layout.getHeaderHeight() + (selecting ? 26 : 0);
        int listHeight = Math.max(1, height - layout.getFooterHeight() - top);
        if (serverSelectionList.getY() != top || serverSelectionList.getHeight() != listHeight) {
            serverSelectionList.updateSizeAndPosition(width, listHeight, top);
        }
        lanworlds$positionEntries();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lanworlds$layoutEntries(CallbackInfo ci) {
        lanworlds$positionEntries();
    }

    private void lanworlds$positionEntries() {
        var config = LanWorldsClient.CONFIG;
        if (config == null || lanworlds$entries.isEmpty()) return;
        var occupied = new ArrayList<ToolbarLayout.Rect>();
        int titleWidth = font.width(title);
        occupied.add(new ToolbarLayout.Rect((width - titleWidth) / 2, 5, titleWidth, 22));
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && widget.visible
                    && !(widget instanceof ServerSelectionList) && !lanworlds$entries.contains(widget)) {
                occupied.add(new ToolbarLayout.Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
        }
        for (ToolbarButton entry : lanworlds$entries) {
            entry.appearance(config.toolbarStyle, lanworlds$roomSelection.enabled(), Math.max(20, (width - 24) / 2));
            if (entry == lanworlds$draggedEntry) continue;
            var saved = switch (entry.icon()) {
                case SELECTION -> config.selectionButtonPosition;
                case SIGNAL -> config.lanWorldsButtonPosition;
                case RADMIN -> config.radminButtonPosition;
            };
            var anchor = switch (entry.icon()) {
                case SELECTION -> config.selectionButtonAnchor;
                case SIGNAL -> config.lanWorldsButtonAnchor;
                case RADMIN -> config.radminButtonAnchor;
            };
            var position = config.canDragToolbar() && saved != null
                    ? ToolbarLayout.restore(saved, entry.getWidth(), width, height)
                    : ToolbarLayout.place(anchor, entry.getWidth(), width, height, occupied);
            entry.setX(position.x());
            entry.setY(position.y());
            occupied.add(position);
        }
    }

    private boolean lanworlds$pressEntry(double mouseX, double mouseY, int button) {
        if (button != MouseButtons.left() || LanWorldsClient.CONFIG == null) return false;
        for (ToolbarButton entry : lanworlds$entries.reversed()) {
            if (!entry.isMouseOver(mouseX, mouseY)) continue;
            if (LanWorldsClient.CONFIG.canDragToolbar()) {
                lanworlds$draggedEntry = entry;
                lanworlds$pressX = mouseX;
                lanworlds$pressY = mouseY;
                lanworlds$grabX = mouseX - entry.getX();
                lanworlds$grabY = mouseY - entry.getY();
                lanworlds$entryMoved = false;
            } else {
                entry.activate();
                lanworlds$positionEntries();
            }
            return true;
        }
        return false;
    }

    private boolean lanworlds$moveEntry(double mouseX, double mouseY, int button) {
        ToolbarButton entry = lanworlds$draggedEntry;
        if (entry == null || button != MouseButtons.left()) return false;
        if (!lanworlds$entryMoved && Math.hypot(mouseX - lanworlds$pressX, mouseY - lanworlds$pressY) < 3) return true;
        lanworlds$entryMoved = true;
        var position = ToolbarLayout.onScreen((int) (mouseX - lanworlds$grabX), (int) (mouseY - lanworlds$grabY),
                entry.getWidth(), width, height);
        boolean overlapsEntry = lanworlds$entries.stream().filter(other -> other != entry)
                .anyMatch(other -> position.overlaps(new ToolbarLayout.Rect(other.getX(), other.getY(),
                        other.getWidth(), other.getHeight())));
        if (!overlapsEntry) {
            entry.setX(position.x());
            entry.setY(position.y());
        }
        return true;
    }

    private boolean lanworlds$releaseEntry(double mouseX, double mouseY, int button) {
        ToolbarButton entry = lanworlds$draggedEntry;
        if (entry == null || button != MouseButtons.left()) return false;
        lanworlds$moveEntry(mouseX, mouseY, button);
        lanworlds$draggedEntry = null;
        if (lanworlds$entryMoved) {
            var config = LanWorldsClient.CONFIG;
            var point = ToolbarLayout.save(new ToolbarLayout.Rect(entry.getX(), entry.getY(), entry.getWidth(), entry.getHeight()), width, height);
            switch (entry.icon()) {
                case SELECTION -> config.selectionButtonPosition = point;
                case SIGNAL -> config.lanWorldsButtonPosition = point;
                case RADMIN -> config.radminButtonPosition = point;
            }
            config.save();
        } else if (entry.isMouseOver(mouseX, mouseY)) {
            entry.activate();
        }
        lanworlds$positionEntries();
        return true;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return lanworlds$pressEntry(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return lanworlds$moveEntry(event.x(), event.y(), event.button()) || super.mouseDragged(event, dragX, dragY);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return lanworlds$releaseEntry(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    @Override public LanRoomSelection lanworlds$roomSelection() { return lanworlds$roomSelection; }

    @ModifyArg(method = "refreshServerList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private Screen lanworlds$preserveSelectionOnRefresh(Screen replacement) {
        if (replacement instanceof LanRoomSelectionHost host) {
            host.lanworlds$roomSelection().copyFrom(lanworlds$roomSelection);
        }
        return replacement;
    }

    @Override public boolean lanworlds$selectRoom(RoomKey key, boolean shift, boolean control) {
        List<RoomKey> visible = lanworlds$visibleRoomKeys();
        if (!lanworlds$roomSelection.enabled() && shift) {
            LanServer focused = lanworlds$selectedLanServer();
            RoomKey anchor = focused == null ? null : lanworlds$roomKey(focused.getAddress());
            if (anchor != null) lanworlds$roomSelection.click(anchor, visible, false, true);
        }
        boolean consumed = lanworlds$roomSelection.click(key, visible, shift, control);
        if (consumed) {
            for (ServerSelectionList.Entry entry : serverSelectionList.children()) {
                if (entry instanceof LanServerEntryDataAccessor accessor
                        && key.equals(lanworlds$roomKey(accessor.lanworlds$getServerData().getAddress()))) {
                    serverSelectionList.setSelected(entry);
                    break;
                }
            }
            lanworlds$updateRoomFooterActions();
        }
        return consumed;
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void lanworlds$selectionKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.isSelectAll() && !lanworlds$visibleRoomKeys().isEmpty()) {
            lanworlds$roomSelection.selectAll(lanworlds$visibleRoomKeys());
        } else if (lanworlds$roomSelection.enabled() && event.isEscape()) {
            if (lanworlds$roomSelection.selected().isEmpty()) lanworlds$roomSelection.setEnabled(false);
            else lanworlds$roomSelection.clear();
        } else return;
        lanworlds$updateRoomFooterActions();
        cir.setReturnValue(true);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void lanworlds$preventJoiningWhileSelecting(ServerData server, CallbackInfo ci) {
        if (lanworlds$roomSelection.enabled()) ci.cancel();
    }

    private Map<RoomKey, LanServer> lanworlds$visibleRoomServers() {
        Map<RoomKey, LanServer> rooms = new LinkedHashMap<>();
        if (serverSelectionList == null) return rooms;
        for (ServerSelectionList.Entry entry : serverSelectionList.children()) {
            if (!(entry instanceof ServerSelectionList.NetworkServerEntry)
                    || !(entry instanceof LanServerEntryDataAccessor accessor)) continue;
            LanServer server = accessor.lanworlds$getServerData();
            RoomKey key = lanworlds$roomKey(server.getAddress());
            if (key != null) rooms.putIfAbsent(key, server);
        }
        return rooms;
    }

    private List<RoomKey> lanworlds$visibleRoomKeys() { return List.copyOf(lanworlds$visibleRoomServers().keySet()); }

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
        LanRoomSnapshot room;
        if (lanworlds$roomSelection.enabled()) {
            List<LanRoomSnapshot> selected = lanworlds$selectedRooms();
            room = selected.size() == 1 ? selected.getFirst() : null;
        } else room = lanworlds$selectedRoom();
        if (room == null) return;
        minecraft.setScreenAndShow(new LanRoomContextScreen((Screen) (Object) this, room));
    }

    private void lanworlds$blockSelectedRoom() {
        if (lanworlds$roomSelection.enabled()) {
            List<LanRoomSnapshot> rooms = lanworlds$selectedRooms();
            if (rooms.isEmpty() || LanWorldsClient.CONFIG == null) return;
            LanWorldsClient.CONFIG.blockRooms(rooms);
            List<RoomKey> keys = rooms.stream().map(LanRoomSnapshot::key).toList();
            if (LanWorldsClient.ROOMS != null) keys.forEach(LanWorldsClient.ROOMS::remove);
            lanworlds$roomSelection.remove(keys);
            lanworlds$refreshLanEntries();
            serverSelectionList.setSelected(null);
            lanworlds$updateRoomFooterActions();
            return;
        }
        LanServer server = lanworlds$selectedLanServer();
        RoomKey key = server == null ? null : lanworlds$roomKey(server.getAddress());
        if (server == null || key == null || LanWorldsClient.CONFIG == null) return;
        LanWorldsClient.CONFIG.block(key.ip(), key.port(), server.getMotd());
        if (LanWorldsClient.ROOMS != null) LanWorldsClient.ROOMS.remove(key);
        lanworlds$refreshLanEntries();
        if (serverSelectionList != null) serverSelectionList.setSelected(null);
    }

    private List<LanRoomSnapshot> lanworlds$selectedRooms() {
        Map<RoomKey, LanServer> visible = lanworlds$visibleRoomServers();
        List<LanRoomSnapshot> rooms = new ArrayList<>();
        for (RoomKey key : lanworlds$roomSelection.selectedAmong(List.copyOf(visible.keySet()))) {
            LanRoomSnapshot room = LanWorldsClient.ROOMS == null ? null : LanWorldsClient.ROOMS.get(key);
            rooms.add(room != null ? room : new LanRoomSnapshot(UUID.randomUUID(), key, visible.get(key).getMotd(), "", "",
                    -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED));
        }
        return rooms;
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
        return RoomKey.fromAddress(address);
    }
}
