package dev.lanworlds.ui;

import dev.lanworlds.room.LanRoomSelection;
import dev.lanworlds.room.RoomKey;

/** Connects the multiplayer screen's selection state to its replaceable vanilla LAN rows. */
public interface LanRoomSelectionHost {
    LanRoomSelection lanworlds$roomSelection();
    boolean lanworlds$selectRoom(RoomKey key, boolean shift, boolean control);
}
