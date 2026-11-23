package dev.lanworlds.ui;

import dev.lanworlds.room.LanRoomSelection;
import dev.lanworlds.room.RoomKey;
import dev.lanworlds.room.LanRoomFolder;
import dev.lanworlds.room.RoomBrowser;

/** Connects the multiplayer screen's selection state to its replaceable vanilla LAN rows. */
public interface LanRoomSelectionHost {
    LanRoomSelection lanworlds$roomSelection();
    void lanworlds$activateFolder(LanRoomFolder folder, boolean select);
    boolean lanworlds$folderCollapsed(String key);
    RoomBrowser lanworlds$browserState();
    void lanworlds$copyBrowser(LanRoomSelectionHost previous);
    boolean lanworlds$selectRoom(RoomKey key, boolean shift, boolean control);
}
