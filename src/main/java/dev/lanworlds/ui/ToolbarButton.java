package dev.lanworlds.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A vanilla button surface with a compact multiplayer icon. */
public final class ToolbarButton extends Button.Plain {
    public enum Icon { SELECTION, SIGNAL, RADMIN, MORE }

    private static final Identifier SHIELD = Identifier.fromNamespaceAndPath("radminlan", "textures/radmin_pixel.png");
    private static final Identifier LINKER_SHIELD = Identifier.fromNamespaceAndPath("radminlinker", "textures/radmin_pixel.png");

    private static Identifier shield() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("radminlinker") ? LINKER_SHIELD : SHIELD;
    }
    private final Icon icon;
    private final Runnable action;
    private ToolbarLayout.Style style = ToolbarLayout.Style.ICON;
    private boolean selected;

    public ToolbarButton(Icon icon, Component label, Runnable action) {
        super(0, 0, ToolbarLayout.SIZE, ToolbarLayout.SIZE, label, ignored -> action.run(), DEFAULT_NARRATION);
        this.icon = icon;
        this.action = action;
        setTooltip(Tooltip.create(label));
    }

    public Icon icon() { return icon; }

    public void appearance(ToolbarLayout.Style style, boolean selected, int maxWidth) {
        this.style = style;
        this.selected = selected;
        int preferred = style == ToolbarLayout.Style.ICON ? ToolbarLayout.SIZE
                : Minecraft.getInstance().font.width(getMessage()) + (style == ToolbarLayout.Style.BOTH ? 28 : 12);
        setWidth(Math.max(ToolbarLayout.SIZE, Math.min(preferred, maxWidth)));
    }

    public void activate() {
        playDownSound(Minecraft.getInstance().getSoundManager());
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractDefaultSprite(graphics);
        int iconX = getX() + (style == ToolbarLayout.Style.ICON ? (getWidth() - 16) / 2 : 3);
        if (style != ToolbarLayout.Style.TEXT) drawIcon(graphics, iconX, getY() + 2);
        if (style != ToolbarLayout.Style.ICON) {
            var font = Minecraft.getInstance().font;
            int left = getX() + (style == ToolbarLayout.Style.BOTH ? 22 : 4);
            int right = getX() + getWidth() - 4;
            int space = Math.max(0, right - left);
            String label = font.plainSubstrByWidth(getMessage().getString(), space);
            graphics.text(font, label, left + (space - font.width(label)) / 2, getY() + 6, 0xFFFFFFFF);
        }
    }

    private void drawIcon(GuiGraphicsExtractor graphics, int x, int y) {
        switch (icon) {
            case RADMIN -> graphics.blit(RenderPipelines.GUI_TEXTURED, shield(), x, y, 0, 0, 16, 16, 16, 16, 16, 16);
            case SIGNAL -> UiSprites.draw(graphics, UiSprites.Art.BEACON, x, y, 16);
            case MORE -> graphics.text(Minecraft.getInstance().font, "…", x + 5, y + 4, 0xFFFFFFFF);
            case SELECTION -> UiSprites.checkbox(graphics, selected, x, y, 16);
        }
    }
}
