package dev.lanworlds.ui;

import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.config.LanWorldsConfig;
import dev.lanworlds.integration.RadminIntegration;
import dev.lanworlds.input.MouseButtons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Independent room block rules and LAN discovery settings. */
public final class LanWorldsScreen extends Screen {
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 38;
    private static final int RULE_HEIGHT = 52;
    private static final int SETTING_HEIGHT = 80;
    private static final String[] SETTING_LABELS = {"toolbar_style", "toolbar_drag", "selection_position",
            "lanworlds_position", "radmin_position", "query", "timeout", "block_type"};
    private static final int ROOM_SETTINGS_START = 5;
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
    private boolean multiSelect;
    private final Set<LanWorldsConfig.RuleKey> selectedRules = new LinkedHashSet<>();
    private LanWorldsConfig.RuleKey selectionAnchor;

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
        int tabs = Tab.values().length;
        int tabWidth = Math.max(1, Math.min(132, (width - 28 - (tabs - 1) * 4) / tabs));
        int left = (width - tabWidth * tabs - (tabs - 1) * 4) / 2;
        for (Tab value : Tab.values()) {
            Button button = addRenderableWidget(new PageTab(value, left + value.ordinal() * (tabWidth + 4), tabWidth));
            fixedButtons.add(button);
        }
        int buttonWidth = Math.max(1, Math.min(200, width - 24));
        fixedButtons.add(addRenderableWidget(Button.builder(Component.translatable("screen.lanworlds.done"), ignored -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build()));
        if (tab == Tab.BLOCKED) {
            buildBlocked();
            buildSelectionControls();
        }
        else buildSettings();
        positionControls();
    }

    private final class PageTab extends Button.Plain {
        private final Tab page;

        PageTab(Tab page, int x, int width) {
            super(x, 28, width, 22, tabLabel(page), ignored -> selectTab(page), DEFAULT_NARRATION);
            this.page = page;
        }

        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            boolean selected = page == tab;
            int x = getX(), y = getY();
            graphics.fill(x, y, x + getWidth(), y + 22, selected ? 0xFF4D869E : 0xFF2E465A);
            graphics.fillGradient(x + 1, y + 1, x + getWidth() - 1, y + 22,
                    selected || isHoveredOrFocused() ? 0xFF284B60 : 0xFF1A2F43, 0xFF102235);
            if (selected) graphics.fill(x + 3, y + 19, x + getWidth() - 3, y + 21, 0xFF79E0E0);
            String label = fit(getMessage().getString(), Math.max(1, getWidth() - 10));
            graphics.drawString(font, label, x + (getWidth() - font.width(label)) / 2, y + 6, 0xFFF2F9FF);
        }
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
            if (multiSelect) {
                Button select = Button.builder(Component.literal(selectedRules.contains(rule.key()) ? "\u2713" : ""),
                        ignored -> selectRule(rule.key(), false, true)).bounds(0, 0, 20, 20).build();
                select.setTooltip(Tooltip.create(Component.literal(singleLine(rule.value))));
                addContentWidget(select, contentLeft() + 8, y + 16, 20);
            }
            rules.add(new RuleRow(rule, y, restore));
            y += RULE_HEIGHT;
        }
        selectedRules.retainAll(rules.stream().map(row -> row.rule().key()).toList());
        if (selectionAnchor != null && rules.stream().noneMatch(row -> selectionAnchor.equals(row.rule().key()))) {
            selectionAnchor = null;
        }
        contentHeight = rules.isEmpty() ? 92 : y + 12;
    }

    private void buildSelectionControls() {
        int toggleWidth = Math.min(104, Math.max(1, contentWidth() / 2));
        Button toggle = Button.builder(Component.translatable(multiSelect ? "screen.lanworlds.single_select"
                : "screen.lanworlds.multi_select"), ignored -> {
            multiSelect = !multiSelect;
            if (!multiSelect) clearSelection();
            init();
        }).bounds(contentLeft(), HEADER_HEIGHT, toggleWidth, 20).build();
        toggle.active = multiSelect || !rules.isEmpty();
        toggle.setTooltip(Tooltip.create(Component.translatable("screen.lanworlds.selection_hint")));
        fixedButtons.add(addRenderableWidget(toggle));
        if (!multiSelect) return;

        int gap = 4;
        int buttonWidth = Math.max(1, (contentWidth() - gap * 3) / 4);
        String[] labels = {"select_all", "invert_selection", "clear_selection", "unblock_selected"};
        Runnable[] actions = {this::selectAllRules, this::invertSelection, () -> {
            clearSelection();
            init();
        }, this::unblockSelected};
        for (int index = 0; index < labels.length; index++) {
            final int actionIndex = index;
            Button button = Button.builder(Component.translatable("screen.lanworlds." + labels[index]),
                    ignored -> actions[actionIndex].run())
                    .bounds(contentLeft() + index * (buttonWidth + gap), HEADER_HEIGHT + 24, buttonWidth, 20).build();
            button.active = index < 2 ? !rules.isEmpty() : !selectedRules.isEmpty();
            fixedButtons.add(addRenderableWidget(button));
        }
    }

    private void clearSelection() {
        selectedRules.clear();
        selectionAnchor = null;
    }

    private void selectAllRules() {
        for (RuleRow row : rules) selectedRules.add(row.rule().key());
        init();
    }

    private void invertSelection() {
        for (LanWorldsConfig.RuleKey key : rules.stream().map(row -> row.rule().key()).distinct().toList()) {
            if (!selectedRules.add(key)) selectedRules.remove(key);
        }
        selectionAnchor = null;
        init();
    }

    private void selectRule(LanWorldsConfig.RuleKey key, boolean range, boolean additive) {
        List<LanWorldsConfig.RuleKey> keys = rules.stream().map(row -> row.rule().key()).distinct().toList();
        int from = selectionAnchor == null ? -1 : keys.indexOf(selectionAnchor);
        int to = keys.indexOf(key);
        if (range && from >= 0 && to >= 0) {
            if (!additive) selectedRules.clear();
            for (int index = Math.min(from, to); index <= Math.max(from, to); index++) selectedRules.add(keys.get(index));
        } else {
            if (!selectedRules.add(key)) selectedRules.remove(key);
            selectionAnchor = key;
        }
        init();
    }

    private void unblockSelected() {
        List<LanWorldsConfig.BlockRule> targets = rules.stream().map(RuleRow::rule)
                .filter(rule -> selectedRules.contains(rule.key())).toList();
        config().unblockRules(targets);
        clearSelection();
        init();
    }

    private void buildSettings() {
        if (tab == Tab.ROOM_SETTINGS) {
            buildRoomSettings();
            return;
        }
        var config = config();
        var styles = ToolbarLayout.Style.values();
        addSettingControl(Button.builder(toolbarValue(config.toolbarStyle), button -> {
            config.toolbarStyle = styles[(config.toolbarStyle.ordinal() + 1) % styles.length];
            config.save();
            button.setMessage(toolbarValue(config.toolbarStyle));
            updateToolbarControls();
        }).build(), 0);
        Button drag = Button.builder(toggleValue(config.canDragToolbar()), button -> {
            config.dragToolbarIcons = !config.dragToolbarIcons;
            config.save();
            button.setMessage(toggleValue(config.canDragToolbar()));
            updateToolbarControls();
        }).build();
        addSettingControl(drag, 1);
        addSettingControl(positionButton(() -> config.selectionButtonAnchor, value -> config.selectionButtonAnchor = value, ToolbarLayout.Anchor.values()), 2);
        addSettingControl(positionButton(() -> config.lanWorldsButtonAnchor, value -> config.lanWorldsButtonAnchor = value, ToolbarLayout.Anchor.values()), 3);
        addSettingControl(positionButton(() -> config.radminButtonAnchor, value -> config.radminButtonAnchor = value, ToolbarLayout.RadminAnchor.values()), 4);
        updateToolbarControls();
        contentHeight = ROOM_SETTINGS_START * SETTING_HEIGHT + 38;
    }

    private void buildRoomSettings() {
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
        contentHeight = (SETTING_LABELS.length - ROOM_SETTINGS_START) * SETTING_HEIGHT + 38;
    }

    private <T extends Enum<T>> Button positionButton(java.util.function.Supplier<T> current,
                                                       java.util.function.Consumer<T> save, T[] values) {
        return Button.builder(toolbarValue(current.get()), button -> {
            var next = values[(current.get().ordinal() + 1) % values.length];
            save.accept(next);
            button.setMessage(toolbarValue(next));
            config().save();
        }).build();
    }

    private void updateToolbarControls() {
        Button drag = (Button) controls.get(1).widget();
        drag.active = config().toolbarStyle == ToolbarLayout.Style.ICON;
        drag.setMessage(toggleValue(config().canDragToolbar()));
        for (int index = 2; index <= 4; index++) controls.get(index).widget().active = !config().canDragToolbar();
        controls.get(4).widget().active &= RadminIntegration.managementAction() != null;
    }

    private static Component toolbarValue(Enum<?> value) {
        String group = value instanceof ToolbarLayout.RadminAnchor ? "radmin_position." : "toolbar.";
        return Component.translatable("screen.lanworlds." + group + value.name().toLowerCase(Locale.ROOT));
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
        if (tab == Tab.BLOCKED && event.button() == MouseButtons.left() && insideViewport(event.x(), event.y())
                && event.x() < contentRight() && (multiSelect || event.hasControlDown() || event.hasShiftDown())) {
            int relativeY = (int) event.y() - listTop() + scrollOffset;
            for (RuleRow row : rules) {
                if (relativeY >= row.offset() && relativeY < row.offset() + RULE_HEIGHT - 2
                        && !row.button().isMouseOver(event.x(), event.y())) {
                    multiSelect = true;
                    selectRule(row.rule().key(), event.hasShiftDown(), event.hasControlDown());
                    return true;
                }
            }
        }
        if (!insideViewport(event.x(), event.y())
                && fixedButtons.stream().noneMatch(button -> button.isMouseOver(event.x(), event.y()))) return false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.BLOCKED && event.isSelectAll() && !rules.isEmpty()) {
            multiSelect = true;
            selectAllRules();
            return true;
        }
        if (tab == Tab.BLOCKED && multiSelect && event.isEscape()) {
            if (selectedRules.isEmpty()) multiSelect = false;
            clearSelection();
            init();
            return true;
        }
        return super.keyPressed(event);
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
        graphics.fillGradient(0, 0, width, height, 0xE522394D, 0xF00A1828);
        graphics.fill(contentLeft(), listTop(), contentRight(), listBottom(), 0x55112437);
        UiSprites.draw(graphics, UiSprites.Art.BEACON, Math.max(8, width / 2 - font.width(title) / 2 - 22), 6, 16);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        if (tab == Tab.BLOCKED && multiSelect) {
            int textX = contentLeft() + Math.min(104, Math.max(1, contentWidth() / 2)) + 8;
            graphics.drawString(font, fit(Component.translatable("screen.lanworlds.selected_count", selectedRules.size()).getString(),
                    Math.max(1, contentRight() - textX)), textX, HEADER_HEIGHT + 6, 0xFFB9D6EA);
        }
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
            boolean selected = multiSelect && selectedRules.contains(row.rule().key());
            graphics.fill(contentLeft(), y, contentRight(), y + RULE_HEIGHT - 2, selected ? 0xC0396573 : 0xBC182E40);
            if (multiSelect) UiSprites.checkbox(graphics, selected, contentLeft() + 10, y + 13, 16);
            int textX = contentLeft() + (multiSelect ? 36 : 8);
            int available = Math.max(1, row.button().getX() - textX - 12);
            Component type = blockTypeValue(row.rule().type);
            graphics.drawString(font, fit(type.getString(), available), textX, y + 9, 0xFFB9C2CA);
            String value = singleLine(row.rule().value);
            graphics.drawString(font, fit(value, available), textX, y + 28, row.rule().enabled ? 0xFFE6EDF3 : 0xFF81909B);
            if (insideViewport(mouseX, mouseY) && mouseX >= textX && mouseX < row.button().getX() - 4
                    && mouseY >= y && mouseY < y + RULE_HEIGHT - 2 && font.width(value) > available) {
                graphics.setTooltipForNextFrame(font, font.split(Component.literal(value), Math.max(1, Math.min(360, width - 32))), mouseX, mouseY);
            }
        }
    }

    private void drawSettings(GuiGraphics graphics) {
        int start = tab == Tab.ROOM_SETTINGS ? ROOM_SETTINGS_START : 0;
        int count = tab == Tab.ROOM_SETTINGS ? SETTING_LABELS.length - start : ROOM_SETTINGS_START;
        for (int index = 0; index < count; index++) {
            String label = SETTING_LABELS[start + index];
            int y = listTop() + index * SETTING_HEIGHT - scrollOffset;
            if (y + SETTING_HEIGHT <= listTop() || y >= listBottom()) continue;
            graphics.fill(contentLeft(), y, contentRight(), y + SETTING_HEIGHT - 4, 0xAB173043);
            graphics.drawString(font, fit(Component.translatable("screen.lanworlds.settings." + label).getString(), contentWidth() - 16),
                    contentLeft() + 8, y + 8, 0xFFE6EDF3);
            boolean invalid = tab == Tab.ROOM_SETTINGS && index == 1 && !timeoutValid;
            Component description = Component.translatable(invalid ? "screen.lanworlds.settings.timeout_invalid"
                    : "screen.lanworlds.settings." + label + "_description");
            drawWrapped(graphics, description, contentLeft() + 8, y + 23, contentWidth() - 16,
                    invalid ? 0xFFFF7777 : 0xFF8E9BA6, 2);
        }
        drawWrapped(graphics, Component.translatable("screen.lanworlds.settings.saved_automatically"), contentLeft() + 8,
                listTop() + count * SETTING_HEIGHT + 6 - scrollOffset, contentWidth() - 16, 0xFF81909B, 2);
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
    private int listTop() {
        int header = tab == Tab.BLOCKED ? HEADER_HEIGHT + (multiSelect ? 52 : 26) : HEADER_HEIGHT;
        return Math.min(header, Math.max(0, height - FOOTER_HEIGHT - 1));
    }
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

    private enum Tab { BLOCKED, SETTINGS, ROOM_SETTINGS }
    private record Control(AbstractWidget widget, int x, int offset, int width) {}
    private record RuleRow(LanWorldsConfig.BlockRule rule, int offset, Button button) {}
}
