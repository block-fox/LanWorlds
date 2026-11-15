package dev.lanworlds.mixin;

import dev.lanworlds.room.LanRoomLayout;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerSelectionList.class)
public abstract class LanServerListMixin {
    @Inject(method = "refreshEntries", at = @At("TAIL"))
    private void lanworlds$expandLanRows(CallbackInfo ci) {
        lanworlds$compactRows();
    }

    @Inject(method = "updateNetworkServers", at = @At("TAIL"))
    private void lanworlds$compactRowsAfterNetworkRefresh(List<LanServer> servers, CallbackInfo ci) {
        lanworlds$compactRows();
    }

    private void lanworlds$compactRows() {
        ServerSelectionList list = (ServerSelectionList)(Object)this;
        boolean changed = false;
        for (ServerSelectionList.Entry entry : list.children()) {
            if (entry instanceof ServerSelectionList.NetworkServerEntry
                    && entry.getHeight() != LanRoomLayout.ROW_HEIGHT) {
                entry.setHeight(LanRoomLayout.ROW_HEIGHT);
                changed = true;
            }
        }
        if (changed) ((AbstractSelectionListAccessor)(Object)list).lanworlds$repositionEntries();
    }
}
