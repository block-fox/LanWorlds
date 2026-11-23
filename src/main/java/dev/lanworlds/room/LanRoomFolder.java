package dev.lanworlds.room;

import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;
import java.util.List;

/** A presentation row. Its address is never passed to the network connector. */
public final class LanRoomFolder extends LanServer {
    private final String key;
    private final Component label;
    private final List<RoomKey> members;
    public LanRoomFolder(String key, Component label, List<RoomKey> members) {
        super(label.getString(), "lanworlds-folder.invalid:" + Math.floorMod(key.hashCode(), 65534));
        this.key = key;
        this.label = label;
        this.members = List.copyOf(members);
    }
    public String key() { return key; }
    public Component label() { return label; }
    public List<RoomKey> members() { return members; }
}
