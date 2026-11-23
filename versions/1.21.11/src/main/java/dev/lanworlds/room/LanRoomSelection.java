package dev.lanworlds.room;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selection belongs to room endpoints, so replacing rows or query snapshots cannot move it. */
public final class LanRoomSelection {
    private final Set<RoomKey> selected = new LinkedHashSet<>();
    private boolean enabled;
    private RoomKey anchor;

    public boolean enabled() { return enabled; }
    public boolean contains(RoomKey key) { return selected.contains(key); }
    public Set<RoomKey> selected() { return Set.copyOf(selected); }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) clear();
    }

    /** Vanilla's explicit refresh replaces the screen; carry the endpoint state to its replacement. */
    public void copyFrom(LanRoomSelection previous) {
        if (previous == this) return;
        clear();
        enabled = previous != null && previous.enabled;
        if (enabled) {
            selected.addAll(previous.selected);
            anchor = previous.anchor;
        }
    }

    /** Returns false only when the ordinary single-selection/join interaction should continue. */
    public boolean click(RoomKey key, List<RoomKey> visible, boolean shift, boolean control) {
        if (key == null || visible == null || !visible.contains(key)) return false;
        if (!enabled && !shift && !control) return false;
        enabled = true;
        int from = anchor == null ? -1 : visible.indexOf(anchor);
        int to = visible.indexOf(key);
        if (shift && from >= 0) {
            if (!control) selected.clear();
            for (int index = Math.min(from, to); index <= Math.max(from, to); index++) selected.add(visible.get(index));
        } else {
            if (!selected.add(key)) selected.remove(key);
            anchor = key;
        }
        return true;
    }

    public List<RoomKey> selectedAmong(List<RoomKey> visible) {
        return visible == null ? List.of() : visible.stream().filter(selected::contains).distinct().toList();
    }

    public void selectAll(Collection<RoomKey> visible) {
        enabled = true;
        if (visible == null) return;
        for (RoomKey key : visible) if (key != null) selected.add(key);
    }

    public void invert(Collection<RoomKey> visible) {
        enabled = true;
        if (visible == null) return;
        for (RoomKey key : new LinkedHashSet<>(visible)) {
            if (key != null && !selected.add(key)) selected.remove(key);
        }
    }

    public void remove(Collection<RoomKey> keys) {
        if (keys == null) return;
        selected.removeAll(keys);
        if (anchor != null && keys.contains(anchor)) anchor = null;
    }

    public void clear() {
        selected.clear();
        anchor = null;
    }
}
