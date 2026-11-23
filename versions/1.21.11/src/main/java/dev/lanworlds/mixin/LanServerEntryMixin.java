package dev.lanworlds.mixin;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.LanRoomLayout;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.RoomKey;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.ui.LanRoomSelectionHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    private String lanworlds$cachedFaviconAddress;
    private String lanworlds$cachedFaviconData;
    private Identifier lanworlds$cachedFaviconTexture;
    private long lanworlds$cachedFaviconGeneration = -1L;
    private String lanworlds$cachedRoomAddress;
    private RoomKey lanworlds$cachedRoomKey;

    @Inject(method = "renderContent", at = @At("HEAD"))
    private void lanworlds$drawSelectedBackground(GuiGraphics graphics, int mouseX, int mouseY,
                                                  boolean hovered, float delta, CallbackInfo ci) {
        if (!((Object) screen instanceof LanRoomSelectionHost host) || !host.lanworlds$roomSelection().enabled()) return;
        RoomKey key = lanworlds$cachedRoomKey(serverData.getAddress());
        if (key == null || !host.lanworlds$roomSelection().contains(key)) return;
        ServerSelectionList.Entry entry = (ServerSelectionList.Entry)(Object)this;
        graphics.fill(entry.getContentX(), entry.getContentY(), entry.getContentRight(),
                entry.getContentY() + LanRoomLayout.ROW_HEIGHT - 2, 0x554D86B8);
    }

    @Redirect(method = "renderContent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", ordinal = 0))
    private void lanworlds$replaceLanHeader(GuiGraphics graphics, Font font, Component header,
                                             int x, int y, int color) {
        RoomKey key = lanworlds$cachedRoomKey(serverData.getAddress());
        Component title = key == null || RadminIntegration.lookup(key.ip()) == null ? header
                : Component.translatable("screen.lanworlds.radmin_lan_world");
        graphics.drawString(font, title, x, y, color);
    }

    @Redirect(method = "renderContent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
    private void lanworlds$replaceVanillaDetails(GuiGraphics graphics, Font font, String text,
                                                  int x, int y, int color) {
        // LAN MOTD and address are drawn in the expanded row below, after status data is available.
    }

    @Inject(method = "renderContent", at = @At("TAIL"))
    private void lanworlds$drawRoomDetails(GuiGraphics graphics, int mouseX, int mouseY,
                                            boolean hovered, float delta, CallbackInfo ci) {
        if (LanWorldsClient.CONFIG == null) return;

        ServerSelectionList.Entry entry = (ServerSelectionList.Entry)(Object)this;
        LanRoomSnapshot room = lanworlds$roomSnapshot();
        int textX = entry.getContentX() + 35;
        int textRight = entry.getContentRight() - 6;
        int textWidth = Math.max(0, textRight - textX);
        int contentY = entry.getContentY();

        // Keep this row compact like the vanilla LAN entry. The operation
        // page owns peer and network details, so the list only shows the
        // cover, LAN title, LAN MOTD and address.
        lanworlds$drawLanMotd(graphics, serverData.getMotd(), textX, contentY + 12, textWidth);
        String addressText = minecraft.options.hideServerAddress
                ? Component.translatable("selectServer.hiddenAddress").getString() : serverData.getAddress();
        lanworlds$drawDetail(graphics, addressText, textX, contentY + 23, textWidth, 0xFFAAAAAA);

        Identifier favicon = room == null ? null
                : lanworlds$cachedFavicon(serverData.getAddress(), room.favicon());
        if (favicon != null) {
            // Match the vanilla entry content origin exactly.  The extra pixel
            // made the cover visibly drift right at higher GUI scales.
            graphics.blit(RenderPipelines.GUI_TEXTURED, favicon, entry.getContentX(), contentY,
                    0.0F, 0.0F, 32, 32, 32, 32);
        }

        if ((Object) screen instanceof LanRoomSelectionHost host && host.lanworlds$roomSelection().enabled()) {
            boolean selected = host.lanworlds$roomSelection().contains(lanworlds$cachedRoomKey(serverData.getAddress()));
            int x = entry.getContentX() + 2;
            int y = contentY + 2;
            graphics.fill(x, y, x + 13, y + 13, selected ? 0xFF4D86B8 : 0xEE151A1F);
            graphics.renderOutline(x, y, 13, 13, 0xFFE6EDF3);
            if (selected) {
                graphics.fill(x + 3, y + 6, x + 5, y + 10, 0xFFFFFFFF);
                graphics.fill(x + 5, y + 4, x + 10, y + 8, 0xFFFFFFFF);
            }
        }

        if (room == null) return;

        int pingX = entry.getContentRight() - 18;
        int countRight = pingX - 5;
        String countText = room.players() >= 0 && room.maxPlayers() >= 0
                ? room.players() + "/" + room.maxPlayers() : "";
        int countWidth = minecraft.font.width(countText);
        int countX = countRight - countWidth;
        if (!countText.isEmpty()) {
            graphics.drawString(minecraft.font, countText, countX, contentY + 1, 0xFFE0E0E0);
        }

        Identifier pingSprite = lanworlds$pingSprite(room.latencyMs());
        if (pingSprite != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, pingSprite, pingX, contentY + 1,
                    LANWORLDS$PING_WIDTH, LANWORLDS$PING_HEIGHT);
        }

        boolean pingHovered = room.latencyMs() >= 0 && lanworlds$contains(mouseX, mouseY, pingX, contentY + 1,
                LANWORLDS$PING_WIDTH, LANWORLDS$PING_HEIGHT);
        boolean playersHovered = !countText.isEmpty() && lanworlds$contains(mouseX, mouseY,
                countX, contentY, countWidth, 10);
        if (pingHovered) {
            graphics.setTooltipForNextFrame(minecraft.font,
                    Component.translatable("multiplayer.status.ping", room.latencyMs()), mouseX, mouseY);
        } else if (playersHovered) {
            graphics.setComponentTooltipForNextFrame(minecraft.font, lanworlds$playerTooltip(room), mouseX, mouseY);
        }
    }

    private Identifier lanworlds$cachedFavicon(String address, String encoded) {
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

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void lanworlds$blockOnControl(MouseButtonEvent event, boolean doubleClick,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (LanWorldsClient.CONFIG == null) return;

        ServerSelectionList.Entry entry = (ServerSelectionList.Entry)(Object)this;
        boolean onRow = event.x() >= entry.getContentX() && event.x() <= entry.getContentRight()
                && event.y() >= entry.getContentY() && event.y() <= entry.getContentY() + LanRoomLayout.ROW_HEIGHT;
        if (event.button() == MouseButtons.left()) {
            if (onRow && (Object) screen instanceof LanRoomSelectionHost host
                    && host.lanworlds$selectRoom(lanworlds$cachedRoomKey(serverData.getAddress()),
                    event.hasShiftDown(), event.hasControlDown())) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (event.button() == MouseButtons.right() && !onRow) return;
        if (event.button() != MouseButtons.left() && event.button() != MouseButtons.right()) return;

        RoomKey key = lanworlds$roomKey(serverData.getAddress());
        LanRoomSnapshot room = key == null || LanWorldsClient.ROOMS == null ? null : LanWorldsClient.ROOMS.get(key);
        if (room == null && key != null) {
            room = new LanRoomSnapshot(java.util.UUID.randomUUID(), key, serverData.getMotd(), "", "",
                    -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED);
        }
        minecraft.setScreen(new LanRoomContextScreen(screen, room));
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void lanworlds$selectWithKeyboard(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.isSelection() && (Object) screen instanceof LanRoomSelectionHost host
                && host.lanworlds$roomSelection().enabled()) {
            host.lanworlds$selectRoom(lanworlds$cachedRoomKey(serverData.getAddress()), event.hasShiftDown(), event.hasControlDown());
            cir.setReturnValue(true);
        }
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

    private void lanworlds$drawLanMotd(GuiGraphics graphics, String text, int x, int y, int width) {
        if (text == null || text.isBlank()) return;
        // The LAN MOTD is the primary room identifier.  Do not truncate it to
        // make room for status columns: those columns are on the first line,
        // while the MOTD owns the complete second line.
        graphics.drawString(minecraft.font, text, x, y, 0xFFAAAAAA);
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

    private static Identifier lanworlds$pingSprite(long latency) {
        if (latency < 0) return null;
        int level = latency < 150 ? 5 : latency < 300 ? 4 : latency < 600 ? 3 : latency < 1000 ? 2 : 1;
        return Identifier.withDefaultNamespace("server_list/ping_" + level);
    }

    private static boolean lanworlds$contains(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int lanworlds$titleWidth(ServerSelectionList.Entry entry) {
        int pingX = entry.getContentRight() - 18;
        int countRight = pingX - 5;
        return Math.max(0, countRight - (entry.getContentX() + 35) - 5);
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
