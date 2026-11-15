package dev.lanworlds.ui;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.config.LanWorldsConfig;
import dev.lanworlds.input.MouseButtons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Independent room block rules and LAN discovery settings. */
public final class LanWorldsScreen extends Screen {
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 38;
    private static final int RULE_HEIGHT = 52;
    private static final int SETTING_HEIGHT = 80;
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private final List<RuleRow> rules = new ArrayList<>();
    private final List<Button> fixedButtons = new ArrayList<>();
    private final int[] savedScroll = new int[Tab.values().length];
    private Tab tab = Tab.BLOCKED;
    private EditBox timeoutBox;
    private String timeoutDraft;
    private boolean timeoutValid = true;
    private int scrollOffset;
    private int contentHeight;
    private boolean draggingScrollbar;
    private int dragGrabOffset;

    public LanWorldsScreen(Screen parent) {
        super(Component.literal("LanWorlds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        fixedButtons.clear();
        contentWidgets.clear();
        controls.clear();
        rules.clear();
        draggingScrollbar = false;
        int tabWidth = Math.max(1, Math.min(150, (width - 28) / 2));
        int left = (width - tabWidth * 2 - 4) / 2;
        for (Tab value : Tab.values()) {
            Button button = addRenderableWidget(Button.builder(tabLabel(value), ignored -> selectTab(value))
                    .bounds(left + value.ordinal() * (tabWidth + 4), 28, tabWidth, 20).build());
            button.active = tab != value;
            fixedButtons.add(button);
        }
        int buttonWidth = Math.max(1, Math.min(200, width - 24));
        fixedButtons.add(addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.done"), ignored -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build()));
        if (tab == Tab.BLOCKED) buildBlocked();
        else buildSettings();
        positionControls();
    }

    private void selectTab(Tab selected) {
        if (tab == selected) return;
        savedScroll[tab.ordinal()] = scrollOffset;
        tab = selected;
        scrollOffset = savedScroll[tab.ordinal()];
        init();
    }

    private void buildBlocked() {
        int y = 32;
        int restoreWidth = Math.min(100, Math.max(68, contentWidth() / 3));
        for (LanWorldsConfig.BlockRule rule : List.copyOf(config().blocked)) {
            if (rule == null) continue;
            Button restore = Button.builder(Component.translatable("screen.lanworlds.unblock"), ignored -> {
                config().unblock(rule);
                init();
            }).bounds(0, 0, restoreWidth, 20).build();
            restore.setTooltip(Tooltip.create(Component.translatable("screen.lanworlds.unblock_description")));
            addContentWidget(restore, contentRight() - restoreWidth - 8, y + 16, restoreWidth);
            rules.add(new RuleRow(rule, y, restore));
            y += RULE_HEIGHT;
        }
        contentHeight = rules.isEmpty() ? 92 : y + 12;
    }

    private void buildSettings() {
        Button query = Button.builder(toggleValue(config().queryLanStatus), button -> {
            config().queryLanStatus = !config().queryLanStatus;
            button.setMessage(toggleValue(config().queryLanStatus));
            config().save();
        }).bounds(0, 0, 140, 20).build();
        query.setTooltip(Tooltip.create(Component.translatable("screen.lanworlds.settings.query_description")));
        addSettingControl(query, 0);

        if (timeoutDraft == null) timeoutDraft = Integer.toString(config().queryTimeoutMs);
        timeoutBox = new EditBox(font, 0, 0, 140, 20, Component.translatable("screen.lanworlds.settings.timeout"));
        timeoutBox.setMaxLength(5);
        timeoutBox.setValue(timeoutDraft);
        timeoutBox.setResponder(value -> {
            timeoutDraft = value;
            validateTimeout(true);
        });
        addSettingControl(timeoutBox, 1);
        validateTimeout(false);

        Button match = Button.builder(blockTypeValue(config().defaultBlockType), button -> {
            LanWorldsConfig.BlockMatchType[] values = LanWorldsConfig.BlockMatchType.values();
            int current = config().defaultBlockType == null ? 1 : config().defaultBlockType.ordinal();
            config().defaultBlockType = values[(current + 1) % values.length];
            button.setMessage(blockTypeValue(config().defaultBlockType));
            config().save();
        }).bounds(0, 0, 140, 20).build();
        match.setTooltip(Tooltip.create(Component.translatable("screen.lanworlds.settings.block_type_description")));
        addSettingControl(match, 2);
        contentHeight = 3 * SETTING_HEIGHT + 38;
    }

    private void addSettingControl(AbstractWidget widget, int index) {
        int controlWidth = Math.max(1, Math.min(180, contentWidth() - 16));
        addContentWidget(widget, contentRight() - controlWidth - 8, index * SETTING_HEIGHT + 52, controlWidth);
    }

    private void addContentWidget(AbstractWidget widget, int x, int offset, int widgetWidth) {
        controls.add(new Control(widget, x, offset, widgetWidth));
        contentWidgets.add(widget);
        addWidget(widget);
    }

    private void positionControls() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
        for (Control control : controls) {
            AbstractWidget widget = control.widget();
            widget.setX(control.x());
            widget.setY(listTop() + control.offset() - scrollOffset);
            widget.setWidth(control.width());
            widget.visible = widget.getY() + widget.getHeight() > listTop() && widget.getY() < listBottom();
            if (!widget.visible && widget.isFocused()) {
                widget.setFocused(false);
                if (getFocused() == widget) setFocused(null);
            }
        }
    }

    private void validateTimeout(boolean saveValid) {
        Integer value = parseTimeout(timeoutDraft);
        timeoutValid = value != null;
        if (timeoutBox != null) {
            timeoutBox.setTextColor(timeoutValid ? 0xFFE6EDF3 : 0xFFFF7777);
            timeoutBox.setTooltip(Tooltip.create(Component.translatable(timeoutValid
                    ? "screen.lanworlds.settings.timeout_description" : "screen.lanworlds.settings.timeout_invalid")));
        }
        if (saveValid && value != null) {
            config().queryTimeoutMs = value;
            config().save();
        }
    }

    private static Integer parseTimeout(String text) {
        try {
            int value = Integer.parseInt(text);
            return value >= 100 && value <= 10000 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void onClose() {
        config().save();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        for (Button button : fixedButtons) {
            if (button.isMouseOver(mouseX, mouseY)) return Optional.of(button);
        }
        if (!insideViewport(mouseX, mouseY)) return Optional.empty();
        for (AbstractWidget widget : contentWidgets) {
            if (widget.visible && widget.isMouseOver(mouseX, mouseY)) return Optional.of(widget);
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButtons.left() && maxScroll() > 0 && overScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            int y = (int) event.y();
            if (y >= thumbY() && y < thumbY() + thumbHeight()) dragGrabOffset = y - thumbY();
            else {
                dragGrabOffset = thumbHeight() / 2;
                dragScrollbar(y);
            }
            return true;
        }
        if (!insideViewport(event.x(), event.y())
                && fixedButtons.stream().noneMatch(button -> button.isMouseOver(event.x(), event.y()))) return false;
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
        positionControls();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean wasDragging = draggingScrollbar;
        draggingScrollbar = false;
        return super.mouseReleased(event) || wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!insideViewport(mouseX, mouseY) || verticalAmount == 0)
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int movement = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * 32));
        scrollOffset += verticalAmount > 0 ? -movement : movement;
        positionControls();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        graphics.enableScissor(contentLeft(), listTop(), contentRight(), listBottom());
        try {
            if (tab == Tab.BLOCKED) drawBlocked(graphics, mouseX, mouseY);
            else drawSettings(graphics);
            boolean inside = insideViewport(mouseX, mouseY) && mouseX < contentRight();
            for (AbstractWidget widget : contentWidgets) {
                if (widget.visible) widget.render(graphics, inside ? mouseX : -1, inside ? mouseY : -1, delta);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, scrollbarX(), listTop(), 6, listViewportHeight());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, scrollbarX(), thumbY(), 6, thumbHeight());
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, 0, listTop() - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, listBottom(), 0.0F, 0.0F, width, 2, 32, 2);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawBlocked(GuiGraphics graphics, int mouseX, int mouseY) {
        int origin = listTop() - scrollOffset;
        graphics.drawString(font, fit(Component.translatable("screen.lanworlds.blocked_count", rules.size()).getString(), contentWidth() - 16),
                contentLeft() + 8, origin + 12, 0xFFB9D6EA);
        if (rules.isEmpty()) {
            drawWrapped(graphics, Component.translatable("screen.lanworlds.no_blocked"), contentLeft() + 8, origin + 40,
                    contentWidth() - 16, 0xFF9BA8B2, 4);
        }
        for (RuleRow row : rules) {
            int y = origin + row.offset();
            if (y + RULE_HEIGHT <= listTop() || y >= listBottom()) continue;
            graphics.fill(contentLeft(), y, contentRight(), y + RULE_HEIGHT - 2, 0x66121920);
            int available = Math.max(1, row.button().getX() - contentLeft() - 20);
            Component type = blockTypeValue(row.rule().type);
            graphics.drawString(font, fit(type.getString(), available), contentLeft() + 8, y + 9, 0xFFB9C2CA);
            String value = singleLine(row.rule().value);
            graphics.drawString(font, fit(value, available), contentLeft() + 8, y + 28, row.rule().enabled ? 0xFFE6EDF3 : 0xFF81909B);
            if (insideViewport(mouseX, mouseY) && mouseX >= contentLeft() + 8 && mouseX < row.button().getX() - 4
                    && mouseY >= y && mouseY < y + RULE_HEIGHT - 2 && font.width(value) > available) {
                graphics.setTooltipForNextFrame(font, font.split(Component.literal(value), Math.max(1, Math.min(360, width - 32))), mouseX, mouseY);
            }
        }
    }

    private void drawSettings(GuiGraphics graphics) {
        String[] labels = {"query", "timeout", "block_type"};
        for (int index = 0; index < labels.length; index++) {
            int y = listTop() + index * SETTING_HEIGHT - scrollOffset;
            if (y + SETTING_HEIGHT <= listTop() || y >= listBottom()) continue;
            graphics.fill(contentLeft(), y, contentRight(), y + SETTING_HEIGHT - 4, 0x66121920);
            graphics.drawString(font, fit(Component.translatable("screen.lanworlds.settings." + labels[index]).getString(), contentWidth() - 16),
                    contentLeft() + 8, y + 8, 0xFFE6EDF3);
            boolean invalid = index == 1 && !timeoutValid;
            Component description = Component.translatable(invalid ? "screen.lanworlds.settings.timeout_invalid"
                    : "screen.lanworlds.settings." + labels[index] + "_description");
            drawWrapped(graphics, description, contentLeft() + 8, y + 23, contentWidth() - 16,
                    invalid ? 0xFFFF7777 : 0xFF8E9BA6, 2);
        }
        drawWrapped(graphics, Component.translatable("screen.lanworlds.settings.saved_automatically"), contentLeft() + 8,
                listTop() + 3 * SETTING_HEIGHT + 6 - scrollOffset, contentWidth() - 16, 0xFF81909B, 2);
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int available, int color, int maxLines) {
        var lines = font.split(text, Math.max(1, available));
        for (int index = 0; index < Math.min(maxLines, lines.size()); index++) {
            graphics.drawString(font, lines.get(index), x, y + index * 11, color);
        }
    }

    private static LanWorldsConfig config() { return LanWorldsClient.CONFIG; }
    private static Component tabLabel(Tab value) { return Component.translatable("screen.lanworlds." + value.name().toLowerCase(Locale.ROOT)); }
    private static Component toggleValue(boolean enabled) { return Component.translatable("screen.lanworlds." + (enabled ? "enabled" : "disabled")); }
    private static Component blockTypeValue(LanWorldsConfig.BlockMatchType type) {
        return Component.translatable("screen.lanworlds.block_match." + (type == null ? "ip_port" : type.name().toLowerCase(Locale.ROOT)));
    }
    private static String singleLine(String value) { return value == null ? "" : value.replace('\n', ' ').replace('\r', ' '); }
    private int panelWidth() { return Math.max(1, Math.min(620, width - 24)); }
    private int contentLeft() { return (width - panelWidth()) / 2; }
    private int contentRight() { return Math.max(contentLeft() + 1, scrollbarX() - 8); }
    private int contentWidth() { return contentRight() - contentLeft(); }
    private int scrollbarX() { return contentLeft() + panelWidth() - 6; }
    private int listTop() { return Math.min(HEADER_HEIGHT, Math.max(0, height - FOOTER_HEIGHT - 1)); }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER_HEIGHT); }
    private int listViewportHeight() { return Math.max(1, listBottom() - listTop()); }
    private int maxScroll() { return Math.max(0, contentHeight - listViewportHeight()); }
    private boolean insideViewport(double x, double y) {
        return x >= contentLeft() && x < contentLeft() + panelWidth() && y >= listTop() && y < listBottom();
    }
    private boolean overScrollbar(double x, double y) { return insideViewport(x, y) && x >= scrollbarX() - 3 && x < scrollbarX() + 9; }
    private int thumbHeight() {
        int viewport = listViewportHeight();
        return Math.min(viewport, Math.max(18, (int) ((long) viewport * viewport / Math.max(viewport, contentHeight))));
    }
    private int thumbY() {
        int travel = Math.max(0, listViewportHeight() - thumbHeight());
        return listTop() + (maxScroll() == 0 ? 0 : (int) ((long) travel * scrollOffset / maxScroll()));
    }
    private String fit(String value, int available) {
        if (font.width(value) <= available) return value;
        if (font.width("…") > available) return "";
        return font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
    }

    private enum Tab { BLOCKED, SETTINGS }
    private record Control(AbstractWidget widget, int x, int offset, int width) {}
    private record RuleRow(LanWorldsConfig.BlockRule rule, int offset, Button button) {}
}
