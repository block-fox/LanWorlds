package dev.lanworlds.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** A vanilla button surface with a compact multiplayer icon. */
public final class ToolbarButton extends Button {
    public enum Icon { SELECTION, SIGNAL, RADMIN }

    private static final ResourceLocation SHIELD = ResourceLocation.fromNamespaceAndPath("radminlan", "textures/radmin_pixel.png");
    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted"));
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
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.blitSprite(SPRITES.get(active, isHoveredOrFocused()), getX(), getY(), getWidth(), getHeight());
        int iconX = getX() + (style == ToolbarLayout.Style.ICON ? (getWidth() - 16) / 2 : 3);
        if (style != ToolbarLayout.Style.TEXT) drawIcon(graphics, iconX, getY() + 2);
        if (style != ToolbarLayout.Style.ICON) {
            var font = Minecraft.getInstance().font;
            int left = getX() + (style == ToolbarLayout.Style.BOTH ? 22 : 4);
            int right = getX() + getWidth() - 4;
            int space = Math.max(0, right - left);
            String label = font.plainSubstrByWidth(getMessage().getString(), space);
            graphics.drawString(font, label, left + (space - font.width(label)) / 2, getY() + 6, 0xFFFFFFFF);
        }
    }

    private void drawIcon(GuiGraphics graphics, int x, int y) {
        switch (icon) {
            case RADMIN -> graphics.blit(SHIELD, x, y, 0, 0, 16, 16, 16, 16);
            case SIGNAL -> {
                for (int bar = 0; bar < 4; bar++) {
                    int left = x + 1 + 4 * bar;
                    int top = y + 11 - 3 * bar;
                    graphics.fill(left + 1, top + 1, left + 4, y + 15, 0xFF135620);
                    graphics.fill(left, top, left + 2, y + 14, 0xFF56F661);
                }
            }
            case SELECTION -> {
                graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFFE0E0E0);
                graphics.fill(x + 2, y + 2, x + 14, y + 14, selected ? 0xFF244F38 : 0xFF363636);
                if (selected) {
                    for (int step = 0; step < 3; step++) {
                        graphics.fill(x + 4 + step, y + 7 + step, x + 6 + step, y + 9 + step, 0xFF9DFF9D);
                    }
                    for (int step = 0; step < 6; step++) {
                        graphics.fill(x + 6 + step, y + 9 - step, x + 8 + step, y + 11 - step, 0xFF9DFF9D);
                    }
                }
            }
        }
    }
}
