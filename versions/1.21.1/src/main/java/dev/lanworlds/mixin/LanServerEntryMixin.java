package dev.lanworlds.mixin;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.integration.ServerProtocol;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.RoomKey;
import dev.lanworlds.room.LanRoomFolder;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.ui.LanRoomSelectionHost;
import dev.lanworlds.ui.UiSprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Mixin(ServerSelectionList.NetworkServerEntry.class)
public abstract class LanServerEntryMixin {
    private static final int LANWORLDS$PING_WIDTH = 10;
    private static final int LANWORLDS$PING_HEIGHT = 8;

    @Shadow @Final protected LanServer serverData;
    @Shadow @Final protected Minecraft minecraft;
    @Shadow @Final private JoinMultiplayerScreen screen;
    @Shadow @Final private static Component LAN_SERVER_HEADER;

    private String lanworlds$cachedFaviconAddress;
    private String lanworlds$cachedFaviconData;
    private ResourceLocation lanworlds$cachedFaviconTexture;
    private long lanworlds$cachedFaviconGeneration = -1L;
    private String lanworlds$cachedRoomAddress;
    private RoomKey lanworlds$cachedRoomKey;

    private int lanworlds$rowLeft;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void lanworlds$drawRoomDetails(GuiGraphics graphics, int index, int contentY, int contentX,
                                            int rowWidth, int rowHeight, int mouseX, int mouseY,
                                            boolean hovered, float delta, CallbackInfo ci) {
        if (LanWorldsClient.CONFIG == null) return;
        lanworlds$rowLeft = contentX;
        if (serverData instanceof LanRoomFolder folder && (Object) screen instanceof LanRoomSelectionHost host) {
            lanworlds$drawFolder(graphics, folder, host, contentX, contentY, rowWidth);
            ci.cancel();
            return;
        }
        ci.cancel();

        RoomKey key = lanworlds$cachedRoomKey(serverData.getAddress());
        int contentRight = contentX + rowWidth;
        if ((Object) screen instanceof LanRoomSelectionHost host && host.lanworlds$roomSelection().enabled()
                && key != null && host.lanworlds$roomSelection().contains(key)) {
            graphics.fill(contentX, contentY, contentRight, contentY + rowHeight, 0x554D86B8);
        }
        LanRoomSnapshot room = lanworlds$roomSnapshot();
        int textX = contentX + 35;
        int textWidth = Math.max(0, contentRight - 6 - textX);
        Component title = key == null || RadminIntegration.lookup(key.ip()) == null ? LAN_SERVER_HEADER
                : Component.translatable("screen.lanworlds.radmin_lan_world");
        graphics.drawString(minecraft.font, title, textX, contentY + 1, 0xFFFFFFFF, false);

        boolean failed = room != null && (room.state() == LanRoomSnapshot.State.TIMED_OUT
                || room.state() == LanRoomSnapshot.State.FAILED);
        var motdLines = minecraft.font.split(Component.literal(serverData.getMotd()), Math.max(1, textWidth));
        for (int line = 0; line < Math.min(2, motdLines.size()); line++) {
            graphics.drawString(minecraft.font, motdLines.get(line), textX, contentY + 12 + line * 9, 0xFFAAAAAA);
        }
        if (motdLines.size() < 2) {
            String address = minecraft.options.hideServerAddress
                    ? Component.translatable("selectServer.hiddenAddress").getString() : serverData.getAddress();
            lanworlds$drawDetail(graphics, address, textX, contentY + 23, textWidth, 0xFFAAAAAA);
        }

        ResourceLocation favicon = room == null ? null
                : lanworlds$cachedFavicon(serverData.getAddress(), room.favicon());
        UiSprites.roomCover(graphics, favicon, contentX, contentY, 32);

        if ((Object) screen instanceof LanRoomSelectionHost host && host.lanworlds$roomSelection().enabled()) {
            boolean selected = host.lanworlds$roomSelection().contains(lanworlds$cachedRoomKey(serverData.getAddress()));
            int x = contentX + 2;
            int y = contentY + 2;
            UiSprites.checkbox(graphics, selected, x - 2, y - 2, 16);
        }

        if (room == null) return;

        int pingX = contentRight - 18;
        int countRight = pingX - 5;
        var target = ServerProtocol.target();
        boolean incompatible = room.state() == LanRoomSnapshot.State.READY && room.protocol() > 0 && room.protocol() != target.id();
        String countText = failed ? "" : incompatible ? room.versionName() : room.players() >= 0 && room.maxPlayers() >= 0
                ? room.players() + "/" + room.maxPlayers() : "";
        countText = minecraft.font.plainSubstrByWidth(countText, Math.max(0, (pingX - textX) / 2));
        int countWidth = minecraft.font.width(countText);
        int countX = countRight - countWidth;
        if (!countText.isEmpty()) {
            graphics.drawString(minecraft.font, countText, countX, contentY + 1, incompatible ? 0xFFFF6666 : 0xFFE0E0E0);
        }

        ResourceLocation pingSprite = incompatible ? ResourceLocation.withDefaultNamespace("server_list/incompatible")
                : failed ? ResourceLocation.withDefaultNamespace("server_list/unreachable")
                : room.latencyMs() >= 0 ? lanworlds$pingSprite(room.latencyMs())
                : room.state() == LanRoomSnapshot.State.QUERYING ? ResourceLocation.withDefaultNamespace("server_list/pinging_1") : null;
        if (pingSprite != null) {
            graphics.blitSprite(pingSprite, pingX, contentY + 1,
                    LANWORLDS$PING_WIDTH, LANWORLDS$PING_HEIGHT);
        }

        boolean pingHovered = pingSprite != null && lanworlds$contains(mouseX, mouseY, pingX, contentY + 1,
                LANWORLDS$PING_WIDTH, LANWORLDS$PING_HEIGHT);
        boolean playersHovered = !countText.isEmpty() && lanworlds$contains(mouseX, mouseY,
                countX, contentY, countWidth, 10);
        if (pingHovered || incompatible && playersHovered) {
            List<Component> versionTooltip = new ArrayList<>();
            versionTooltip.add(incompatible ? Component.translatable("multiplayer.status.incompatible")
                    : failed ? Component.translatable("multiplayer.status.cannot_connect")
                    : room.latencyMs() >= 0 ? Component.translatable("multiplayer.status.ping", room.latencyMs())
                    : Component.translatable("multiplayer.status.pinging"));
            versionTooltip.add(Component.translatable("screen.lanworlds.target_protocol", target.name(), target.id()));
            if (room.protocol() >= 0) versionTooltip.add(Component.translatable("screen.lanworlds.server_protocol", room.versionName(), room.protocol()));
            screen.setTooltipForNextRenderPass(versionTooltip.stream().map(Component::getVisualOrderText).toList());
        } else if (playersHovered) {
            screen.setTooltipForNextRenderPass(lanworlds$playerTooltip(room).stream().map(Component::getVisualOrderText).toList());
        }
    }

    private ResourceLocation lanworlds$cachedFavicon(String address, String encoded) {
        long generation = LanRoomFaviconCache.generation();
        if (Objects.equals(lanworlds$cachedFaviconAddress, address)
                && Objects.equals(lanworlds$cachedFaviconData, encoded)
                && lanworlds$cachedFaviconGeneration == generation) {
            return lanworlds$cachedFaviconTexture;
        }
        lanworlds$cachedFaviconAddress = address;
        lanworlds$cachedFaviconData = encoded;
        lanworlds$cachedFaviconGeneration = generation;
        lanworlds$cachedFaviconTexture = LanRoomFaviconCache.texture(minecraft, address, encoded);
        return lanworlds$cachedFaviconTexture;
    }

    private void lanworlds$drawFolder(GuiGraphics graphics, LanRoomFolder folder, LanRoomSelectionHost host,
                                     int x, int y, int width) {
        graphics.fill(x, y + 3, x + width, y + 29, 0xA8213038);
        boolean selecting = host.lanworlds$roomSelection().enabled();
        if (selecting) {
            int selected = host.lanworlds$roomSelection().selectedAmong(folder.members()).size();
            UiSprites.checkbox(graphics, selected == folder.members().size(), x + 3, y + 8, 16);
            if (selected > 0 && selected < folder.members().size()) graphics.fill(x + 8, y + 15, x + 14, y + 17, 0xFFD5E7CB);
        }
        String arrow = host.lanworlds$folderCollapsed(folder.key()) ? ">" : "v";
        int labelX = x + (selecting ? 37 : 21);
        graphics.drawString(minecraft.font, arrow, labelX - 13, y + 12, 0xFF9BCD86);
        String label = folder.label().getString() + " (" + folder.members().size() + ")";
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(label, Math.max(1, x + width - labelX - 4)), labelX, y + 12, 0xFFF0F3EB);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void lanworlds$blockOnControl(double mouseX, double mouseY, int mouseButton,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (serverData instanceof LanRoomFolder folder && (Object) screen instanceof LanRoomSelectionHost host) {
            if (mouseButton == MouseButtons.left()) host.lanworlds$activateFolder(folder, mouseX < lanworlds$rowLeft + 22);
            cir.setReturnValue(true);
            return;
        }
        if (LanWorldsClient.CONFIG == null) return;

        if (mouseButton == MouseButtons.left()) {
            if ((Object) screen instanceof LanRoomSelectionHost host
                    && host.lanworlds$selectRoom(lanworlds$cachedRoomKey(serverData.getAddress()),
                    Screen.hasShiftDown(), Screen.hasControlDown())) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (mouseButton != MouseButtons.right()) return;

        RoomKey key = lanworlds$roomKey(serverData.getAddress());
        LanRoomSnapshot room = key == null || LanWorldsClient.ROOMS == null ? null : LanWorldsClient.ROOMS.get(key);
        if (room == null && key != null) {
            room = new LanRoomSnapshot(java.util.UUID.randomUUID(), key, serverData.getMotd(), "", "",
                    -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED);
        }
        minecraft.setScreen(new LanRoomContextScreen(screen, room));
        cir.setReturnValue(true);
    }

    @Inject(method = "getNarration", at = @At("HEAD"), cancellable = true)
    private void lanworlds$folderNarration(CallbackInfoReturnable<Component> result) {
        if (serverData instanceof LanRoomFolder folder) result.setReturnValue(folder.label());
    }

    private LanRoomSnapshot lanworlds$roomSnapshot() {
        if (LanWorldsClient.ROOMS == null) return null;
        RoomKey key = lanworlds$cachedRoomKey(serverData.getAddress());
        return key == null ? null : LanWorldsClient.ROOMS.get(key);
    }

    private RoomKey lanworlds$cachedRoomKey(String address) {
        if (Objects.equals(lanworlds$cachedRoomAddress, address)) return lanworlds$cachedRoomKey;
        lanworlds$cachedRoomAddress = address;
        lanworlds$cachedRoomKey = lanworlds$roomKey(address);
        return lanworlds$cachedRoomKey;
    }

    private void lanworlds$drawDetail(GuiGraphics graphics, String text, int x, int y,
                                       int width, int color) {
        if (text == null || text.isBlank() || width <= 0) return;
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(text, width), x, y, color);
    }

    private List<Component> lanworlds$playerTooltip(LanRoomSnapshot room) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.lanworlds.player_count", room.players(), room.maxPlayers()));
        for (String name : room.samplePlayers()) lines.add(Component.literal(name));
        if (!room.samplePlayers().isEmpty() && room.players() > room.samplePlayers().size()) {
            lines.add(Component.translatable("screen.lanworlds.more_players", room.players() - room.samplePlayers().size()));
        }
        return lines;
    }

    private static ResourceLocation lanworlds$pingSprite(long latency) {
        if (latency < 0) return null;
        int level = latency < 150 ? 5 : latency < 300 ? 4 : latency < 600 ? 3 : latency < 1000 ? 2 : 1;
        return ResourceLocation.withDefaultNamespace("server_list/ping_" + level);
    }

    private static boolean lanworlds$contains(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static RoomKey lanworlds$roomKey(String address) {
        if (address == null || address.isBlank()) return null;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return null;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            return new RoomKey(host, Integer.parseInt(address.substring(separator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
