package dev.lanworlds.ui;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.LanRoomSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Room properties and actions share one viewport between a fixed header and footer. */
public final class LanRoomContextScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ACTION_HEIGHT = 24;
    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 38;
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private final LanRoomSnapshot room;
    private RadminIntegration.RoomInfo peer;
    private boolean integrationAvailable;
    private final List<NoticeRow> notices = new ArrayList<>();
    private final List<ActionEntry> entries = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<PropertyRow> properties = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private Button cancelButton;
    private int actionsTop;
    private int contentHeight;
    private int faviconTop;
    private int scrollOffset;
    private int integrationRefreshTicks;
    private boolean draggingScrollbar;
    private int dragGrabOffset;

    public LanRoomContextScreen(Screen parent, LanRoomSnapshot room) {
        super(Component.translatable("screen.lanworlds.room_properties"));
        this.parent = parent;
        this.room = room;
    }

    @Override
    protected void init() {
        clearWidgets();
        rowButtons.clear();
        draggingScrollbar = false;
        integrationRefreshTicks = 0;
        integrationAvailable = RadminIntegration.available();
        peer = lookupRoomPeer();
        rebuildActions();
        layoutContent();
        int buttonWidth = Math.min(200, Math.max(1, panelWidth() - 24));
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.cancel"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
        // Fixed footer receives input before any partially clipped action row.
        rebuildRows();
    }

    @Override
    public void tick() {
        super.tick();
        if (++integrationRefreshTicks < 20) return;
        integrationRefreshTicks = 0;
        boolean available = RadminIntegration.available();
        RadminIntegration.RoomInfo updatedPeer = lookupRoomPeer();
        if (available == integrationAvailable && Objects.equals(updatedPeer, peer)) return;
        integrationAvailable = available;
        peer = updatedPeer;
        rebuildActions();
        // Relayout clamps the existing offset only when the new content is shorter.
        layoutContent();
        rebuildRows();
    }

    private RadminIntegration.RoomInfo lookupRoomPeer() {
        return room == null || room.key() == null ? null : RadminIntegration.lookup(room.key().ip());
    }

    private void rebuildActions() {
        entries.clear();
        if (peer != null) {
            for (RadminIntegration.PropertyAction action : peer.actions()) {
                entries.add(new ActionEntry(Component.literal(action.label()), () -> action.open().accept(this)));
            }
        }
        entries.add(new ActionEntry(Component.translatable("screen.lanworlds.block_room"), this::blockRoom));
    }

    private void layoutContent() {
        properties.clear();
        groups.clear();
        int x = contentLeft();
        int available = contentWidth();
        int y = 10;

        int roomTop = y;
        y += 24;
        faviconTop = y;
        int summaryX = x + 60;
        int summaryWidth = Math.max(1, available - 60);
        addProperty("room_lan_motd", room == null ? "" : room.motd(), summaryX, y, summaryWidth);
        addProperty("room_server_motd", room == null ? "" : room.displayMotd(), summaryX, y + ROW_HEIGHT, summaryWidth);
        y += 56;
        addProperty("room_address", room == null || room.key() == null ? "" : room.key().toString(), x, y, available);
        y += ROW_HEIGHT;
        y = addGrid(List.of(new Property("room_players", formatPlayers()),
                new Property("room_latency", formatLatency(room == null ? -1 : room.latencyMs()))), y);
        if (room != null && room.protocol() >= 0) {
            var target = dev.lanworlds.integration.ServerProtocol.target();
            y = addGrid(List.of(
                    new Property("room_server_version", room.versionName() + " (" + room.protocol() + ")"),
                    new Property("room_target_version", target.name() + " (" + target.id() + ")")), y);
        }
        groups.add(new Group(Component.translatable("screen.lanworlds.room_information"), roomTop, y - roomTop + 4));

        if (peer != null) {
            y += 16;
            int radminTop = y;
            y += 24;
            y = addGrid(List.of(
                    new Property("room_user", peer.name()),
                    new Property("room_virtual_ip", peer.virtualIp()),
                    new Property("room_node_id", peer.nodeId()),
                    new Property("room_real_ip", peer.publicIp()),
                    new Property("room_connection", peer.connectionType()),
                    new Property("room_version", peer.version()),
                    new Property("room_state", peerState()),
                    new Property("room_latency", "online".equalsIgnoreCase(peer.state()) ? formatLatency(peer.latencyMs()) : "-")), y);
            addProperty("room_virtual_ipv6", peer.virtualIpv6(), x, y, available);
            y += ROW_HEIGHT;
            addProperty("room_networks", peer.networks(), x, y, available);
            y += ROW_HEIGHT;
            groups.add(new Group(Component.translatable("screen.lanworlds.room_radmin"), radminTop, y - radminTop + 4));
        }

        y += 16;
        int actionGroupTop = y;
        y += 24;
        notices.clear();
        if (peer == null) {
            Component notice = Component.translatable(integrationAvailable
                    ? "screen.lanworlds.no_radmin_peer" : "screen.lanworlds.requires_radmin");
            int noticeHeight = font.split(notice, Math.max(1, available - 16)).size() * 11 + 8;
            notices.add(new NoticeRow(notice, y, noticeHeight));
            y += noticeHeight;
        }
        actionsTop = y;
        y += entries.size() * ACTION_HEIGHT;
        groups.add(new Group(Component.translatable("screen.lanworlds.room_operations"), actionGroupTop, y - actionGroupTop + 4));
        contentHeight = y + 14;
        clampScroll();
    }

    private int addGrid(List<Property> values, int y) {
        int columns = contentWidth() >= 600 ? 2 : 1;
        int gap = 20;
        int columnWidth = Math.max(1, (contentWidth() - gap * (columns - 1)) / columns);
        for (int index = 0; index < values.size(); index++) {
            Property value = values.get(index);
            addProperty(value.label(), value.value(), contentLeft() + index % columns * (columnWidth + gap),
                    y + index / columns * ROW_HEIGHT, columnWidth);
        }
        return y + (values.size() + columns - 1) / columns * ROW_HEIGHT;
    }

    private void addProperty(String label, String value, int x, int y, int rowWidth) {
        properties.add(new PropertyRow(Component.translatable("screen.lanworlds." + label),
                peerValue(value), x, y, rowWidth));
    }

    private void rebuildRows() {
        for (Button button : List.copyOf(rowButtons)) {
            if (getFocused() == button) setFocused(null);
            removeWidget(button);
        }
        rowButtons.clear();
        clampScroll();
        for (int index = 0; index < entries.size(); index++) {
            int y = listTop() + actionsTop + index * ACTION_HEIGHT - scrollOffset;
            if (y + 20 <= listTop() || y >= listBottom()) continue;
            ActionEntry entry = entries.get(index);
            Button button = Button.builder(entry.label(), ignored -> entry.action().run())
                    .bounds(contentLeft() + 8, y, Math.max(1, contentWidth() - 16), 20).build();
            button.setTooltip(Tooltip.create(entry.label()));
            rowButtons.add(button);
            addWidget(button);
        }
    }

    private void blockRoom() {
        if (room != null && room.key() != null) {
            LanWorldsClient.CONFIG.block(room.key().ip(), room.key().port(), room.motd());
            if (LanWorldsClient.ROOMS != null) LanWorldsClient.ROOMS.remove(room.key());
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        if (cancelButton != null && cancelButton.isMouseOver(mouseX, mouseY)) return Optional.of(cancelButton);
        if (!insideViewport(mouseX, mouseY)) return Optional.empty();
        for (Button button : rowButtons) {
            if (button.isMouseOver(mouseX, mouseY)) return Optional.of(button);
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButtons.left() && maxScroll() > 0 && overScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            int thumb = thumbHeight();
            int y = (int) event.y();
            if (y >= thumbY() && y < thumbY() + thumb) dragGrabOffset = y - thumbY();
            else {
                dragGrabOffset = thumb / 2;
                dragScrollbar(y);
            }
            return true;
        }
        if (event.button() == MouseButtons.left() && copyProperty(event.x(), event.y())) return true;
        if (!insideViewport(event.x(), event.y())
                && (cancelButton == null || !cancelButton.isMouseOver(event.x(), event.y()))) return false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingScrollbar) return super.mouseDragged(event, dragX, dragY);
        dragScrollbar((int) event.y());
        return true;
    }

    private void dragScrollbar(int mouseY) {
        int travel = Math.max(1, listViewportHeight() - thumbHeight());
        int relative = Math.max(0, Math.min(travel, mouseY - listTop() - dragGrabOffset));
        scrollOffset = (int) ((long) maxScroll() * relative / travel);
        rebuildRows();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean wasDragging = draggingScrollbar;
        draggingScrollbar = false;
        return super.mouseReleased(event) || wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!insideViewport(mouseX, mouseY) || verticalAmount == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int movement = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * ROW_HEIGHT * 2));
        scrollOffset += verticalAmount > 0 ? -movement : movement;
        rebuildRows();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(graphics);
        graphics.fillGradient(0, 0, width, height, 0xE4243C50, 0xF00B1929);
        graphics.drawCenteredString(font, Component.literal(truncate(title.getString(), Math.max(1, width - 24))),
                width / 2, 12, 0xFFFFFFFF);

        graphics.enableScissor(contentLeft(), listTop(), contentRight(), listBottom());
        try {
            for (Group group : groups) drawGroup(graphics, group);
            drawFavicon(graphics);
            for (PropertyRow property : properties) drawProperty(graphics, property, mouseX, mouseY);
            for (NoticeRow notice : notices) {
                int y = listTop() + notice.y() - scrollOffset;
                if (y + notice.height() <= listTop() || y >= listBottom()) continue;
                for (var line : font.split(notice.label(), Math.max(1, contentWidth() - 16))) {
                    graphics.drawString(font, line, contentLeft() + 8, y + 3, 0xFFB9C2CA);
                    y += 11;
                }
            }
            boolean overContent = insideViewport(mouseX, mouseY);
            for (Button button : rowButtons) {
                button.render(graphics, overContent ? mouseX : -1, overContent ? mouseY : -1, delta);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND,
                    scrollbarX(), listTop(), 6, listViewportHeight());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER,
                    scrollbarX(), thumbY(), 6, thumbHeight());
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR,
                0, listTop() - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR,
                0, listBottom(), 0.0F, 0.0F, width, 2, 32, 2);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawGroup(GuiGraphics graphics, Group group) {
        int y = listTop() + group.y() - scrollOffset;
        if (y + group.height() <= listTop() || y >= listBottom()) return;
        graphics.fill(contentLeft(), y, contentRight(), y + group.height(), 0xAD101820);
        graphics.drawString(font, truncate(group.label().getString(), Math.max(1, contentWidth() - 16)),
                contentLeft() + 8, y + 5, 0xFF9FC8E8);
        graphics.fill(contentLeft() + 8, y + 19, contentRight() - 8, y + 20, 0x886BA3B6);
        graphics.fill(contentLeft(), y, contentLeft() + 2, y + 20, 0xFF78C9D4);
    }

    private void drawFavicon(GuiGraphics graphics) {
        int x = contentLeft() + 8;
        int y = listTop() + faviconTop - scrollOffset;
        if (y + 48 <= listTop() || y >= listBottom()) return;
        Identifier favicon = room == null || room.key() == null ? null
                : LanRoomFaviconCache.texture(minecraft, room.key().toString(), room.favicon());
        UiSprites.roomCover(graphics, favicon, x, y, 48);
    }

    private boolean copyProperty(double x, double y) {
        if (minecraft == null || !insideViewport(x, y)) return false;
        for (PropertyRow row : properties) {
            if (row.value().isBlank() || row.value().equals(valueUnknown())) continue;
            int top = listTop() + row.y() - scrollOffset;
            if (x < row.x() || x >= row.x() + row.width() || y < top || y >= top + ROW_HEIGHT) continue;
            minecraft.keyboardHandler.setClipboard(row.value());
            copiedValue = row;
            copiedAt = System.nanoTime();
            return true;
        }
        return false;
    }

    private PropertyRow copiedValue;
    private long copiedAt;

    private void drawProperty(GuiGraphics graphics, PropertyRow row, int mouseX, int mouseY) {
        int y = listTop() + row.y() - scrollOffset;
        if (y + ROW_HEIGHT <= listTop() || y >= listBottom()) return;
        int left = row.x() + 8;
        int available = Math.max(1, row.width() - 16);
        int labelWidth = Math.min(116, Math.max(1, available * 2 / 5));
        int valueX = left + labelWidth + 8;
        int valueWidth = Math.max(1, row.x() + row.width() - 8 - valueX);
        boolean copyable = !row.value().isBlank() && !row.value().equals(valueUnknown());
        boolean hovered = copyable && insideViewport(mouseX, mouseY) && mouseX >= row.x()
                && mouseX < row.x() + row.width() && mouseY >= y && mouseY < y + ROW_HEIGHT;
        if (hovered) graphics.fill(row.x() + 2, y, row.x() + row.width() - 2, y + ROW_HEIGHT, 0x55305566);
        boolean copied = row.equals(copiedValue) && System.nanoTime() - copiedAt < 1_400_000_000L;
        String label = row.label().getString();
        String value = copied ? Component.translatable("screen.lanworlds.copied").getString() : singleLine(row.value());
        graphics.drawString(font, truncate(label, labelWidth), left, y + 5, 0xFFB9C2CA);
        graphics.drawString(font, truncate(value, valueWidth), valueX, y + 5, 0xFFE6EAF0);
        if (insideViewport(mouseX, mouseY) && mouseX >= left && mouseX < row.x() + row.width() - 8
                && mouseY >= y && mouseY < y + ROW_HEIGHT
                && (hovered || font.width(label) > labelWidth || font.width(value) > valueWidth)) {
            Component full = row.label().copy().append(": ").append(row.value());
            if (copyable) full = full.copy().append("\n").append(Component.translatable("screen.lanworlds.copy_value"));
            graphics.setTooltipForNextFrame(font, font.split(full, Math.max(1, Math.min(360, width - 32))), mouseX, mouseY);
        }
    }

    private String formatPlayers() {
        return room == null || room.players() < 0 || room.maxPlayers() < 0
                ? valueUnknown() : room.players() + "/" + room.maxPlayers();
    }

    private String formatLatency(long latency) {
        return Component.translatable(latency < 0 ? "screen.lanworlds.peer.latency_unknown"
                : "screen.lanworlds.peer.latency", latency).getString();
    }

    private String peerState() {
        String key = peer == null ? "unknown" : "online".equalsIgnoreCase(peer.state()) ? "online"
                : "offline".equalsIgnoreCase(peer.state()) ? "offline" : "unknown";
        return Component.translatable("screen.lanworlds.peer." + key).getString();
    }

    private String peerValue(String value) { return value == null || value.isBlank() ? valueUnknown() : value; }
    private String valueUnknown() { return Component.translatable("screen.lanworlds.property.not_provided").getString(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String singleLine(String value) { return safe(value).replace('\n', ' ').replace('\r', ' '); }
    private int panelWidth() { return Math.max(1, Math.min(760, width - 24)); }
    private int contentLeft() { return (width - panelWidth()) / 2; }
    private int contentRight() { return Math.max(contentLeft() + 1, scrollbarX() - 8); }
    private int contentWidth() { return contentRight() - contentLeft(); }
    private int scrollbarX() { return contentLeft() + panelWidth() - 6; }
    private int listTop() { return Math.min(HEADER_HEIGHT, Math.max(0, height - FOOTER_HEIGHT - 1)); }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER_HEIGHT); }
    private int listViewportHeight() { return Math.max(1, listBottom() - listTop()); }
    private int maxScroll() { return Math.max(0, contentHeight - listViewportHeight()); }
    private void clampScroll() { scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll())); }
    private boolean insideViewport(double x, double y) {
        return x >= contentLeft() && x < contentLeft() + panelWidth() && y >= listTop() && y < listBottom();
    }
    private boolean overScrollbar(double x, double y) {
        return insideViewport(x, y) && x >= scrollbarX() - 3 && x < scrollbarX() + 9;
    }
    private int thumbHeight() {
        int viewport = listViewportHeight();
        return Math.min(viewport, Math.max(18, (int) ((long) viewport * viewport / Math.max(viewport, contentHeight))));
    }
    private int thumbY() {
        int travel = Math.max(0, listViewportHeight() - thumbHeight());
        return listTop() + (maxScroll() == 0 ? 0 : (int) ((long) travel * scrollOffset / maxScroll()));
    }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return safe(value);
        String ellipsis = "…";
        if (font.width(ellipsis) > maxWidth) return "";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    private record NoticeRow(Component label, int y, int height) {}
    private record Property(String label, String value) {}
    private record PropertyRow(Component label, String value, int x, int y, int width) {}
    private record Group(Component label, int y, int height) {}
    private record ActionEntry(Component label, Runnable action) {}
}
