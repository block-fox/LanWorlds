package dev.lanworlds.mixin;

import dev.lanworlds.input.MouseButtons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerSelectionList.class)
public abstract class LanServerListMixin extends ObjectSelectionList<ServerSelectionList.Entry> {
    protected LanServerListMixin(Minecraft minecraft, int width, int height, int top, int rowHeight) {
        super(minecraft, width, height, top, rowHeight);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // In 1.21.1 the vanilla selection list forwards only left clicks to rows.
        if (button == MouseButtons.right() && isMouseOver(mouseX, mouseY)
                && getEntryAtPosition(mouseX, mouseY) instanceof ServerSelectionList.NetworkServerEntry room) {
            return room.mouseClicked(mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
