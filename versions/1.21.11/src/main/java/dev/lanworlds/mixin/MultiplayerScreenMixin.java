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
import dev.lanworlds.room.LanRoomFolder;
import dev.lanworlds.room.RoomBrowser;
import net.minecraft.client.gui.components.EditBox;
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

    @org.spongepowered.asm.mixin.Shadow
    private Button selectButton;

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
    private ToolbarButton lanworlds$moreButton;
    private final RoomBrowser lanworlds$browser = new RoomBrowser();
    private List<LanServer> lanworlds$rooms = List.of();
    private List<LanServer> lanworlds$results = List.of();
    private String lanworlds$searchSignature = "";
    private EditBox lanworlds$search;
    private EditBox lanworlds$motdFilter;
    private Button lanworlds$groupToggle;
    private Button lanworlds$groupMode;
    private Button lanworlds$searchMode;
    private Button lanworlds$resultPage;
    private final List<Button> lanworlds$resultButtons = new ArrayList<>();
    private final List<AbstractWidget> lanworlds$flyout = new ArrayList<>();
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
        var combined = new ArrayList<>(detected);
        combined.addAll(RadminIntegration.rooms());
        lanworlds$lastLanSignature = "";
        return lanworlds$displayRooms(lanworlds$filterDetectedServers(combined));
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
        detected.addAll(RadminIntegration.rooms());
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
        lanworlds$rooms = List.copyOf(vanilla);
        List<LanServer> rendered = lanworlds$displayRooms(vanilla);
        StringBuilder signature = new StringBuilder();
        for (LanServer server : rendered) {
            signature.append(server.getAddress()).append('\u0000').append(server.getMotd()).append('\u0001');
            if (server instanceof LanRoomFolder folder) signature.append(folder.members()).append('\u0002');
        }
        String nextSignature = signature.toString();
        if (nextSignature.equals(lanworlds$lastLanSignature)) return;
        lanworlds$lastLanSignature = nextSignature;
        serverSelectionList.updateNetworkServers(rendered);
    }

    private List<LanServer> lanworlds$filterDetectedServers(List<LanServer> detected) {
        Map<String, LanServer> unique = new LinkedHashMap<>();
        for (LanServer server : detected) {
            if (server == null || !lanworlds$isValidAddress(server.getAddress())
                    || lanworlds$isBlocked(server.getAddress(), server.getMotd())) continue;
            unique.putIfAbsent(lanworlds$normalizeAddress(server.getAddress()), server);
        }
        return new ArrayList<>(unique.values());
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
        lanworlds$moreButton = new ToolbarButton(ToolbarButton.Icon.MORE,
                Component.translatable("screen.lanworlds.more"), () -> {
                    lanworlds$browser.open = !lanworlds$browser.open;
                    if (!lanworlds$browser.open && lanworlds$flyout.contains(getFocused())) setFocused(null);
                    lanworlds$layoutFlyout();
                });
        lanworlds$entries.add(lanworlds$moreButton);
        lanworlds$entries.add(new ToolbarButton(ToolbarButton.Icon.SIGNAL, Component.translatable("screen.lanworlds.title"),
                () -> minecraft.setScreen(new LanWorldsScreen(this))));
        var radmin = RadminIntegration.managementAction();
        if (radmin != null) {
            lanworlds$entries.add(new ToolbarButton(ToolbarButton.Icon.RADMIN,
                    RadminIntegration.managementLabel(), () -> radmin.accept(this)));
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
        lanworlds$initFlyout();
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
        boolean selectedLan = lanworlds$selectedLanServer() != null;
        boolean multiple = lanworlds$roomSelection.enabled();
        int selectedCount = lanworlds$roomSelection.selectedAmong(lanworlds$visibleRoomKeys()).size();
        lanworlds$roomDetailsButton.visible = selectedLan || multiple;
        lanworlds$roomBlockButton.visible = selectedLan || multiple;
        lanworlds$roomDetailsButton.active = multiple ? selectedCount == 1 : selectedLan;
        lanworlds$roomBlockButton.active = multiple ? selectedCount > 0 : selectedLan;
        lanworlds$roomBlockButton.setMessage(Component.translatable(multiple ? "screen.lanworlds.block_selected" : "screen.lanworlds.block_room", selectedCount));
        if (editButton != null) editButton.visible = !(selectedLan || multiple);
        if (deleteButton != null) deleteButton.visible = !(selectedLan || multiple);
        if (selectButton != null) selectButton.active = !multiple && serverSelectionList.getSelected() != null
                && !(serverSelectionList.getSelected() instanceof ServerSelectionList.LANHeader)
                && !lanworlds$isSelectedFolder();
    }

    private void lanworlds$updateSelectionControls() {
        if (lanworlds$multiSelectButton == null || serverSelectionList == null) return;
        boolean selecting = lanworlds$roomSelection.enabled();
        List<RoomKey> visible = lanworlds$visibleRoomKeys();
        int count = lanworlds$roomSelection.selectedAmong(visible).size();
        lanworlds$multiSelectButton.appearance(ToolbarLayout.Style.ICON, selecting, 20);
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
        lanworlds$layoutFlyout();
    }

    private void lanworlds$positionEntries() {
        var config = LanWorldsClient.CONFIG;
        if (config == null || lanworlds$entries.isEmpty()) return;
        var occupied = new ArrayList<ToolbarLayout.Rect>();
        int titleWidth = font.width(title);
        var titleBounds = new ToolbarLayout.Rect((width - titleWidth) / 2, 5, titleWidth, 22);
        occupied.add(titleBounds);
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && widget.visible
                    && !(widget instanceof ServerSelectionList) && !lanworlds$entries.contains(widget)
                    && !lanworlds$flyout.contains(widget)) {
                occupied.add(new ToolbarLayout.Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
        }
        // Position the shield first so the other LanWorlds buttons can give it room.
        for (ToolbarButton entry : lanworlds$entries.reversed()) {
            entry.appearance(config.toolbarStyle, lanworlds$roomSelection.enabled(), Math.max(20, (width - 24) / 2));
            if (entry == lanworlds$draggedEntry) continue;
            var saved = switch (entry.icon()) {
                case MORE, SELECTION -> config.selectionButtonPosition;
                case SIGNAL -> config.lanWorldsButtonPosition;
                case RADMIN -> config.radminButtonPosition;
            };
            ToolbarLayout.Rect position;
            if (config.canDragToolbar() && saved != null) {
                position = ToolbarLayout.restore(saved, entry.getWidth(), width, height);
            } else if (entry.icon() == ToolbarButton.Icon.RADMIN) {
                position = ToolbarLayout.placeRadmin(config.radminButtonAnchor, titleBounds,
                        entry.getWidth(), width, height, occupied);
            } else {
                var anchor = (entry.icon() == ToolbarButton.Icon.MORE || entry.icon() == ToolbarButton.Icon.SELECTION) ? config.selectionButtonAnchor : config.lanWorldsButtonAnchor;
                position = ToolbarLayout.place(anchor, entry.getWidth(), width, height, occupied);
            }
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
                case MORE, SELECTION -> config.selectionButtonPosition = point;
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
        if (lanworlds$pressEntry(event.x(), event.y(), event.button())) return true;
        if (lanworlds$browser.open) {
            for (var widget : lanworlds$flyout.reversed()) {
                if (widget.visible && widget.isMouseOver(event.x(), event.y())) {
                    if (widget.mouseClicked(event, doubleClick) && widget instanceof EditBox) setFocused(widget);
                    return true;
                }
            }
            if (lanworlds$flyout.contains(getFocused())) setFocused(null);
            lanworlds$browser.open = false;
            lanworlds$layoutFlyout();
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return lanworlds$moveEntry(event.x(), event.y(), event.button()) || super.mouseDragged(event, dragX, dragY);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return lanworlds$releaseEntry(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    @Override public LanRoomSelection lanworlds$roomSelection() { return lanworlds$roomSelection; }

    @ModifyArg(method = "refreshServerList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private Screen lanworlds$preserveSelectionOnRefresh(Screen replacement) {
        if (replacement instanceof LanRoomSelectionHost host) {
            host.lanworlds$roomSelection().copyFrom(lanworlds$roomSelection);
            host.lanworlds$copyBrowser(this);
        }
        return replacement;
    }

    @Override public boolean lanworlds$selectRoom(RoomKey key, boolean shift, boolean control) {
        List<RoomKey> visible = serverSelectionList.children().stream().filter(LanServerEntryDataAccessor.class::isInstance)
                .map(LanServerEntryDataAccessor.class::cast).map(LanServerEntryDataAccessor::lanworlds$getServerData)
                .filter(server -> !(server instanceof LanRoomFolder)).map(server -> RoomKey.fromAddress(server.getAddress())).toList();
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
        if (lanworlds$browser.open && event.isEscape()) {
            lanworlds$browser.open = false;
            if (lanworlds$flyout.contains(getFocused())) setFocused(null);
            lanworlds$layoutFlyout();
            cir.setReturnValue(true);
            return;
        }
        if (getFocused() instanceof EditBox) return;
        if (event.isSelection() && serverSelectionList.getSelected() instanceof LanServerEntryDataAccessor selected
                && selected.lanworlds$getServerData() instanceof LanRoomFolder folder) {
            lanworlds$activateFolder(folder, event.hasShiftDown());
            cir.setReturnValue(true);
            return;
        }
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
        if (lanworlds$roomSelection.enabled() || lanworlds$isSelectedFolder()) ci.cancel();
    }

    private Map<RoomKey, LanServer> lanworlds$visibleRoomServers() {
        Map<RoomKey, LanServer> rooms = new LinkedHashMap<>();
        for (LanServer server : lanworlds$rooms) {
            RoomKey key = RoomKey.fromAddress(server.getAddress());
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
        minecraft.setScreen(new LanRoomContextScreen((Screen) (Object) this, room));
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
        var server = accessor.lanworlds$getServerData();
        return server instanceof LanRoomFolder ? null : server;
    }

    @Override public boolean lanworlds$folderCollapsed(String key) { return lanworlds$browser.collapsed.contains(key); }

    @Override public void lanworlds$activateFolder(LanRoomFolder folder, boolean select) {
        if (select && lanworlds$roomSelection.enabled()) {
            if (lanworlds$roomSelection.selectedAmong(folder.members()).size() == folder.members().size()) {
                lanworlds$roomSelection.remove(folder.members());
            } else lanworlds$roomSelection.selectAll(folder.members());
        } else {
            if (!lanworlds$browser.collapsed.add(folder.key())) lanworlds$browser.collapsed.remove(folder.key());
            lanworlds$refreshLanEntries();
        }
        lanworlds$updateRoomFooterActions();
    }

    @Override public void lanworlds$copyBrowser(LanRoomSelectionHost previous) {
        if ((Object) previous instanceof JoinMultiplayerScreen) {
            lanworlds$browser.copyFrom(previous.lanworlds$browserState());
        }
    }

    @Override public RoomBrowser lanworlds$browserState() { return lanworlds$browser; }

    private boolean lanworlds$isSelectedFolder() {
        return serverSelectionList != null && serverSelectionList.getSelected() instanceof LanServerEntryDataAccessor selected
                && selected.lanworlds$getServerData() instanceof LanRoomFolder;
    }

    private List<LanServer> lanworlds$displayRooms(List<LanServer> rooms) {
        var config = LanWorldsClient.CONFIG;
        if (config == null || !config.groupRooms) return rooms;
        var mode = RoomBrowser.grouping(config.roomGrouping);
        var groups = new LinkedHashMap<String, List<LanServer>>();
        var coreAddresses = new HashSet<String>();
        if (mode == RoomBrowser.Group.RADMIN) {
            for (LanServer room : RadminIntegration.rooms()) coreAddresses.add(room.getAddress());
        }
        for (LanServer room : rooms) {
            RoomKey key = RoomKey.fromAddress(room.getAddress());
            if (key == null) continue;
            var peer = mode == RoomBrowser.Group.RADMIN ? RadminIntegration.lookup(key.ip()) : null;
            String group = RoomBrowser.group(mode, key, room.getMotd(), peer != null || coreAddresses.contains(room.getAddress()), config.motdGroupFilter);
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(room);
        }
        List<LanServer> result = new ArrayList<>();
        for (var group : groups.entrySet()) {
            String id = mode.name() + ":" + group.getKey();
            Component label = group.getKey().startsWith("screen.lanworlds.folder.")
                    ? Component.translatable(group.getKey()) : Component.literal(group.getKey());
            if (minecraft.options.hideServerAddress && (mode == RoomBrowser.Group.IP || mode == RoomBrowser.Group.PORT)) {
                label = Component.translatable("selectServer.hiddenAddress");
            }
            result.add(new LanRoomFolder(id, label, group.getValue().stream()
                    .map(room -> RoomKey.fromAddress(room.getAddress())).toList()));
            if (!lanworlds$folderCollapsed(id)) result.addAll(group.getValue());
        }
        return result;
    }

    private void lanworlds$initFlyout() {
        lanworlds$flyout.clear();
        lanworlds$resultButtons.clear();
        lanworlds$searchSignature = "";
        lanworlds$flyout.add(addRenderableWidget(lanworlds$multiSelectButton));
        lanworlds$groupToggle = lanworlds$flyoutButton(() -> {
            var config = LanWorldsClient.CONFIG;
            config.groupRooms = !config.groupRooms;
            config.save();
            lanworlds$refreshLanEntries();
            lanworlds$layoutFlyout();
        });
        lanworlds$groupMode = lanworlds$flyoutButton(() -> {
            var config = LanWorldsClient.CONFIG;
            var values = RoomBrowser.Group.values();
            config.roomGrouping = values[(RoomBrowser.grouping(config.roomGrouping).ordinal() + 1) % values.length].name();
            config.save();
            lanworlds$refreshLanEntries();
            lanworlds$layoutFlyout();
        });
        lanworlds$searchMode = lanworlds$flyoutButton(() -> {
            var values = RoomBrowser.Search.values();
            lanworlds$browser.search = values[(lanworlds$browser.search.ordinal() + 1) % values.length];
            lanworlds$browser.page = 0;
            lanworlds$layoutFlyout();
        });
        lanworlds$search = addRenderableWidget(new EditBox(font, 0, 0, 80, 20,
                Component.translatable("screen.lanworlds.room_search")));
        lanworlds$search.setMaxLength(120);
        lanworlds$search.setHint(Component.translatable("screen.lanworlds.room_search"));
        lanworlds$search.setValue(lanworlds$browser.query);
        lanworlds$search.setResponder(value -> {
            lanworlds$browser.query = value;
            lanworlds$browser.page = 0;
            lanworlds$layoutFlyout();
        });
        lanworlds$flyout.add(lanworlds$search);
        lanworlds$motdFilter = addRenderableWidget(new EditBox(font, 0, 0, 80, 20,
                Component.translatable("screen.lanworlds.folder_filter")));
        lanworlds$motdFilter.setMaxLength(100);
        lanworlds$motdFilter.setHint(Component.translatable("screen.lanworlds.folder_filter"));
        lanworlds$motdFilter.setValue(LanWorldsClient.CONFIG == null || LanWorldsClient.CONFIG.motdGroupFilter == null
                ? "" : LanWorldsClient.CONFIG.motdGroupFilter);
        lanworlds$motdFilter.setResponder(value -> {
            if (LanWorldsClient.CONFIG == null) return;
            LanWorldsClient.CONFIG.motdGroupFilter = value;
            LanWorldsClient.CONFIG.save();
            lanworlds$refreshLanEntries();
        });
        lanworlds$flyout.add(lanworlds$motdFilter);
        for (int i = 0; i < 5; i++) {
            final int slot = i;
            lanworlds$resultButtons.add(lanworlds$flyoutButton(() -> {
                int index = lanworlds$browser.page * lanworlds$resultsPerPage + slot;
                if (index < lanworlds$results.size()) lanworlds$findRoom(lanworlds$results.get(index));
            }));
        }
        lanworlds$resultPage = lanworlds$flyoutButton(() -> {
            lanworlds$browser.page = (lanworlds$browser.page + 1) % ((lanworlds$results.size() - 1) / lanworlds$resultsPerPage + 1);
            lanworlds$layoutFlyout();
        });
        lanworlds$layoutFlyout();
    }

    private Button lanworlds$flyoutButton(Runnable action) {
        Button button = addRenderableWidget(Button.builder(Component.empty(), ignored -> action.run()).bounds(0, 0, 80, 20).build());
        lanworlds$flyout.add(button);
        return button;
    }

    private int lanworlds$resultsPerPage = 5;

    private void lanworlds$layoutFlyout() {
        if (lanworlds$search == null || lanworlds$moreButton == null || LanWorldsClient.CONFIG == null) return;
        for (var widget : lanworlds$flyout) widget.visible = false;
        if (!lanworlds$browser.open) return;
        var config = LanWorldsClient.CONFIG;
        boolean motd = config.groupRooms && RoomBrowser.grouping(config.roomGrouping) == RoomBrowser.Group.MOTD;
        int w = Math.max(48, Math.min(244, width - 12));
        int x = Math.clamp(lanworlds$moreButton.getX(), 4, Math.max(4, width - w - 4));
        boolean up = height - lanworlds$moreButton.getY() - 20 < lanworlds$moreButton.getY();
        int y = up ? lanworlds$moreButton.getY() - (motd ? 68 : 46) : lanworlds$moreButton.getY() + 24;
        y = Math.clamp(y, 4, Math.max(4, height - (motd ? 68 : 46)));
        int groupWidth = Math.max(9, (w - 28) / 2);
        int controlsY = up ? y + (motd ? 44 : 22) : y;
        lanworlds$place(lanworlds$multiSelectButton, x, controlsY, 20);
        lanworlds$place(lanworlds$groupToggle, x + 24, controlsY, groupWidth);
        lanworlds$place(lanworlds$groupMode, x + 28 + groupWidth, controlsY, w - 28 - groupWidth);
        lanworlds$groupToggle.setMessage(Component.translatable(config.groupRooms ? "screen.lanworlds.folders_on" : "screen.lanworlds.folders_off"));
        lanworlds$groupMode.setMessage(Component.translatable("screen.lanworlds.group." + RoomBrowser.grouping(config.roomGrouping).name().toLowerCase(Locale.ROOT)));
        lanworlds$groupMode.active = config.groupRooms;
        if (motd) lanworlds$place(lanworlds$motdFilter, x, y + 22, w);
        int sy = up ? y : y + (motd ? 44 : 22);
        int modeWidth = Math.min(75, Math.max(20, w / 3));
        lanworlds$place(lanworlds$searchMode, x, sy, modeWidth);
        lanworlds$searchMode.setMessage(Component.translatable("screen.lanworlds.search." + lanworlds$browser.search.name().toLowerCase(Locale.ROOT)));
        lanworlds$place(lanworlds$search, x + modeWidth + 4, sy, Math.max(16, w - modeWidth - 4));
        if (lanworlds$browser.query.isBlank()) return;
        String signature = lanworlds$browser.query + "\n" + lanworlds$browser.search + "\n" + lanworlds$lastLanSignature;
        if (!signature.equals(lanworlds$searchSignature) || lanworlds$browser.search == RoomBrowser.Search.USER
                || lanworlds$browser.search == RoomBrowser.Search.ALL) {
            lanworlds$searchSignature = signature;
            lanworlds$results = lanworlds$rooms.stream().filter(this::lanworlds$matchesSearch).toList();
        }
        boolean aboveSearch = up;
        int space = aboveSearch ? Math.max(0, sy - 4) : height - sy - 22;
        int slots = Math.clamp(space / 21, 1, 6);
        lanworlds$resultsPerPage = Math.max(1, Math.min(5, slots - (lanworlds$results.size() > slots ? 1 : 0)));
        int pages = Math.max(1, (lanworlds$results.size() + lanworlds$resultsPerPage - 1) / lanworlds$resultsPerPage);
        lanworlds$browser.page = Math.min(lanworlds$browser.page, pages - 1);
        int available = Math.min(lanworlds$resultsPerPage, lanworlds$results.size() - lanworlds$browser.page * lanworlds$resultsPerPage);
        int count = Math.max(1, available) + (pages > 1 ? 1 : 0);
        int ry = aboveSearch ? Math.max(0, sy - count * 21 - 2) : sy + 22;
        if (available == 0) {
            var empty = lanworlds$resultButtons.getFirst();
            empty.active = false;
            empty.setMessage(Component.translatable("screen.lanworlds.no_search_results"));
            lanworlds$place(empty, x + modeWidth + 4, ry, w - modeWidth - 4);
            return;
        }
        for (int i = 0; i < available; i++) {
            LanServer room = lanworlds$results.get(lanworlds$browser.page * lanworlds$resultsPerPage + i);
            var button = lanworlds$resultButtons.get(i);
            button.active = true;
            String label = RoomBrowser.clean(room.getMotd());
            if (label.isBlank()) label = minecraft.options.hideServerAddress ? Component.translatable("lanServer.title").getString() : room.getAddress();
            button.setMessage(Component.literal(font.plainSubstrByWidth(label, Math.max(1, w - modeWidth - 15))));
            lanworlds$place(button, x + modeWidth + 4, ry + 21 * i, w - modeWidth - 4);
        }
        if (pages > 1) {
            lanworlds$resultPage.setMessage(Component.literal((lanworlds$browser.page + 1) + "/" + pages + "  >"));
            lanworlds$place(lanworlds$resultPage, x + modeWidth + 4, ry + 21 * available, w - modeWidth - 4);
        }
    }

    private void lanworlds$place(AbstractWidget widget, int x, int y, int width) {
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(Math.max(1, width));
        widget.visible = true;
    }

    private boolean lanworlds$matchesSearch(LanServer room) {
        RoomKey key = RoomKey.fromAddress(room.getAddress());
        if (key == null) return false;
        var mode = lanworlds$browser.search;
        var peer = mode == RoomBrowser.Search.ALL || mode == RoomBrowser.Search.USER ? RadminIntegration.lookup(key.ip()) : null;
        var status = LanWorldsClient.ROOMS == null ? null : LanWorldsClient.ROOMS.get(key);
        return RoomBrowser.matches(mode, lanworlds$browser.query, key, room.getMotd(),
                status == null ? "" : status.statusMotd(), peer == null ? "" : peer.name(), !minecraft.options.hideServerAddress);
    }

    private void lanworlds$findRoom(LanServer room) {
        lanworlds$browser.collapsed.clear();
        lanworlds$browser.open = false;
        setFocused(null);
        lanworlds$refreshLanEntries();
        lanworlds$layoutFlyout();
        var entries = serverSelectionList.children();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry instanceof LanServerEntryDataAccessor accessor
                    && accessor.lanworlds$getServerData().getAddress().equals(room.getAddress())) {
                serverSelectionList.setSelected(entry);
                serverSelectionList.setScrollAmount(Math.max(0, i * 36 - serverSelectionList.getHeight() / 2));
                break;
            }
        }
    }

    private static RoomKey lanworlds$roomKey(String address) {
        return RoomKey.fromAddress(address);
    }
}
