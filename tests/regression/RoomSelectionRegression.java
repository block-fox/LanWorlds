import dev.lanworlds.room.LanRoomSelection;
import dev.lanworlds.room.RoomKey;

import java.util.List;
import java.util.Set;

/** Selection survives refreshed rows and reordered or filtered endpoint lists. */
public final class RoomSelectionRegression {
    public static void check() {
        RoomKey first = new RoomKey("192.168.1.4", 25565);
        RoomKey second = new RoomKey("192.168.1.4", 25566);
        RoomKey third = new RoomKey("192.168.1.6", 25565);
        RoomKey fourth = new RoomKey("192.168.1.7", 25565);
        List<RoomKey> visible = List.of(first, second, third, fourth);
        LanRoomSelection selection = new LanRoomSelection();
        require(!selection.click(first, visible, false, false), "normal clicks remain available for vanilla selection and joining");
        require(!selection.enabled() && selection.selected().isEmpty(), "ordinary interaction does not start multiple selection");
        require(selection.click(first, visible, false, true), "Control-click starts multiple selection and consumes the row action");
        require(selection.enabled() && selection.contains(first), "Control-click selects the endpoint");
        selection.click(third, visible, true, false);
        equal(selection.selected(), Set.of(first, second, third), "Shift-click selects the inclusive displayed range");
        selection.click(second, visible, true, false);
        equal(selection.selected(), Set.of(first, second), "consecutive Shift clicks keep their original range anchor");
        selection.click(fourth, visible, false, true);
        selection.click(third, visible, true, true);
        equal(selection.selected(), Set.of(first, second, third, fourth), "Control-Shift appends a range without discarding earlier selections");
        selection.click(second, visible, false, false);
        require(!selection.contains(second) && selection.contains(first), "ordinary clicks in selection mode toggle only one room");

        RoomKey refreshedFirst = RoomKey.fromAddress(" 192.168.1.4:25565 ");
        require(selection.contains(refreshedFirst), "new row objects preserve selection by endpoint equality");
        equal(selection.selectedAmong(List.of(fourth, refreshedFirst, third)), List.of(fourth, first, third),
                "bulk actions follow current visual order, independently of discovery order");
        equal(selection.selectedAmong(List.of(third)), List.of(third), "filtered-out selections cannot be affected by a visible-list bulk action");
        require(selection.contains(first), "filtering preserves a hidden room selection for when it returns");
        selection.invert(List.of(first, first, second));
        require(!selection.contains(first) && selection.contains(second), "invert handles each visible endpoint once");
        selection.remove(List.of(third, fourth));
        equal(selection.selected(), Set.of(second), "blocking removes only the affected selected endpoints");
        selection.selectAll(visible);
        equal(selection.selected(), Set.copyOf(visible), "select all uses all visible LAN rooms");
        selection.clear();
        selection.selectAll(visible);
        selection.remove(List.of(first));
        equal(selection.selected(), Set.of(second, third, fourth), "removing selected rooms works without a click anchor");
        selection.clear();
        require(selection.enabled() && selection.selected().isEmpty(), "clearing selection keeps the mode enabled");
        selection.setEnabled(false);
        require(!selection.enabled() && selection.selected().isEmpty(), "ending multiple selection clears the pending selection");
        require(!selection.click(null, visible, false, true)
                && !selection.click(new RoomKey("192.168.1.20", 1), visible, false, true),
                "missing or stale row keys never select a different room");

        equal(RoomKey.fromAddress("[FD00:AbCd::1]:25565"), new RoomKey("fd00:abcd::1", 25565),
                "IPv6 capitalization and brackets do not change a selection key");
        require(!first.equals(second), "two rooms sharing an IP still have independent selections");
        for (String invalid : new String[] {"", "host", ":25565", "host:", "host:0", "host:65536", "host:abc", "[host:25565", "host]:25565", "§host:25565"}) {
            require(RoomKey.fromAddress(invalid) == null, "malformed endpoint is rejected: " + invalid);
        }
        try {
            selection.selected().add(first);
            throw new AssertionError("selection snapshots must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        LanRoomSelection beforeRefresh = new LanRoomSelection();
        beforeRefresh.click(first, visible, false, true);
        beforeRefresh.click(third, visible, true, false);
        LanRoomSelection afterRefresh = new LanRoomSelection();
        afterRefresh.copyFrom(beforeRefresh);
        require(afterRefresh.enabled(), "explicit refresh preserves selection mode");
        equal(afterRefresh.selected(), beforeRefresh.selected(), "screen replacement preserves endpoint selection");
        afterRefresh.click(second, visible, true, false);
        equal(afterRefresh.selected(), Set.of(first, second), "screen replacement preserves the range anchor");
        equal(beforeRefresh.selected(), Set.of(first, second, third), "refreshed selection has independent storage");
        System.out.println("ROOM_SELECTION_REGRESSION_OK modifiers=true stableKeys=true filteredActions=true ipv6=true");
    }

    private static void equal(Object actual, Object expected, String message) {
        if (!actual.equals(expected)) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
