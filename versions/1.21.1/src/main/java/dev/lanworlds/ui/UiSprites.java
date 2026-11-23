package dev.lanworlds.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** PNG sprites painted in Blockbench and bundled with the mod. */
public final class UiSprites {
    // The same fallback resource used by Minecraft's FaviconTexture.
    private static final ResourceLocation UNKNOWN_SERVER = ResourceLocation.withDefaultNamespace("textures/misc/unknown_server.png");

    public enum Art {
        EMPTY("checkbox_empty", 16), CHECKED("checkbox_checked", 16),
        BEACON("lan_beacon", 16);
        private final ResourceLocation texture;
        private final int size;
        Art(String name, int size) {
            this.size = size;
            this.texture = ResourceLocation.fromNamespaceAndPath("lanworlds", "textures/gui/" + name + ".png");
        }
    }

    private UiSprites() {}

    public static void draw(GuiGraphics graphics, Art art, int x, int y, int size) {
        graphics.blit(art.texture, x, y, size, size, 0.0F, 0.0F, art.size, art.size, art.size, art.size);
    }

    public static void roomCover(GuiGraphics graphics, ResourceLocation favicon, int x, int y, int size) {
        int sourceSize = favicon == null ? 128 : 64;
        graphics.blit(favicon == null ? UNKNOWN_SERVER : favicon,
                x, y, size, size, 0.0F, 0.0F, sourceSize, sourceSize, sourceSize, sourceSize);
    }

    public static void checkbox(GuiGraphics graphics, boolean checked, int x, int y, int size) {
        draw(graphics, checked ? Art.CHECKED : Art.EMPTY, x, y, size);
    }
}
