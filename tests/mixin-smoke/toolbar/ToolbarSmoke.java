import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.integration.ServerProtocol;
import dev.lanworlds.room.LanRoomSnapshot;
import dev.lanworlds.room.RoomKey;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.ui.LanRoomSelectionHost;
import dev.lanworlds.ui.LanWorldsScreen;
import dev.lanworlds.ui.ToolbarButton;
import dev.lanworlds.ui.ToolbarLayout;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Checks the real multiplayer controls with the ViaFabricPlus and RadminLAN JARs loaded. */
public final class ToolbarSmoke implements ClientModInitializer {
    private JoinMultiplayerScreen multiplayer;
    private int warmup;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (multiplayer == null) {
                    if (!(client.gui.screen() instanceof TitleScreen title)) return;
                    multiplayer = new JoinMultiplayerScreen(title);
                    client.setScreenAndShow(multiplayer);
                    return;
                }
                if (++warmup == 5) checkToolbar(client);
                if (warmup == 18 && Boolean.getBoolean("lanworlds.smokeScreenshots")) net.minecraft.client.Screenshot.grab(client, false);
                if (warmup == 30) checkRoomDetails(client);
                if (warmup == 45 && Boolean.getBoolean("lanworlds.smokeScreenshots")) net.minecraft.client.Screenshot.grab(client, false);
                if (warmup == 52) showLanRowFixtures(client);
                if (warmup == 57) checkFoldersAndSearch(client);
                if (warmup == 62 && Boolean.getBoolean("lanworlds.smokeScreenshots")) net.minecraft.client.Screenshot.grab(client, false);
                if (warmup == 75) client.stop();
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        });
    }

    private void checkToolbar(Minecraft client) throws ReflectiveOperationException {
        var config = LanWorldsClient.CONFIG;
        require(client.getLanguageManager().getLanguages().keySet().containsAll(java.util.List.of("zh_mo", "zh_sg", "zh_my", "lzh")),
                "regional and Literary Chinese languages were not loaded");
        var entries = multiplayer.children().stream().filter(ToolbarButton.class::isInstance)
                .map(ToolbarButton.class::cast).toList();
        require(entries.stream().filter(entry -> entry.visible).count() == 3, "expected More, LanWorlds and Radmin icons");
        if (FabricLoader.getInstance().isModLoaded("viafabricplus")) {
            Button via = button(multiplayer, Component.literal("ViaFabricPlus"));
            for (var entry : entries) require(!bounds(entry).overlaps(bounds(via)), "ViaFabricPlus button was covered");
        }
        click(entry(ToolbarButton.Icon.MORE));
        var selection = entry(ToolbarButton.Icon.SELECTION);
        click(selection);
        require(((LanRoomSelectionHost) multiplayer).lanworlds$roomSelection().enabled(), "selection click was lost");
        click(selection);
        require(!((LanRoomSelectionHost) multiplayer).lanworlds$roomSelection().enabled(), "selection did not turn off");
        click(entry(ToolbarButton.Icon.MORE));

        var lan = entry(ToolbarButton.Icon.SIGNAL);
        click(lan);
        require(client.gui.screen() instanceof LanWorldsScreen, "LanWorlds button did not open its screen");
        var settings = (LanWorldsScreen) client.gui.screen();
        settings.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(Component.translatable("screen.lanworlds.settings").getString()))
                .findFirst().orElseThrow().onPress(event(0, 0));
        var style = settings.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(Component.translatable("screen.lanworlds.toolbar.icon").getString()))
                .findFirst().orElseThrow();
        style.onPress(event(0, 0));
        require(config.toolbarStyle == ToolbarLayout.Style.TEXT && !config.canDragToolbar(), "text mode is wrong");
        style.onPress(event(0, 0));
        require(config.toolbarStyle == ToolbarLayout.Style.BOTH, "combined mode is wrong");
        style.onPress(event(0, 0));
        require(config.toolbarStyle == ToolbarLayout.Style.ICON, "icon mode is wrong");
        String[] anchors = {"title_before", "title_after", "bottom_left", "bottom_right"};
        Button anchor = button(settings, Component.translatable("screen.lanworlds.radmin_position." + anchors[0]));
        for (int index = 0; index < anchors.length; index++) {
            require(anchor.getMessage().getString().equals(Component.translatable("screen.lanworlds.radmin_position." + anchors[index]).getString()),
                    "incorrect Radmin anchor option");
            anchor.onPress(event(0, 0));
        }
        require(config.radminButtonAnchor == ToolbarLayout.RadminAnchor.TITLE_BEFORE, "anchor did not cycle through exactly four choices");
        client.setScreenAndShow(multiplayer);
        int center = client.getWindow().getGuiScaledWidth() / 2;
        int titleWidth = client.font.width(multiplayer.getTitle());
        require(entry(ToolbarButton.Icon.RADMIN).getX() + ToolbarLayout.SIZE <= center - titleWidth / 2,
                "shield was not placed left of the title");
        config.radminButtonAnchor = ToolbarLayout.RadminAnchor.TITLE_AFTER;
        multiplayer.tick();
        require(entry(ToolbarButton.Icon.RADMIN).getX() >= center + titleWidth / 2,
                "shield was not placed right of the title");

        config.dragToolbarIcons = true;
        config.save();
        lan = entry(ToolbarButton.Icon.SIGNAL);
        double x = lan.getX() + 8, y = lan.getY() + 8;
        double endX = client.getWindow().getGuiScaledWidth() - 20, endY = 78;
        require(multiplayer.mouseClicked(event(x, y), false), "drag did not start");
        require(multiplayer.mouseDragged(event(endX, endY), endX - x, endY - y), "drag was not consumed");
        require(multiplayer.mouseReleased(event(endX, endY)), "release was not consumed");
        require(client.gui.screen() == multiplayer, "drag opened settings");
        var saved = config.lanWorldsButtonPosition;
        require(saved != null && lan.getY() == (int) endY - 8, "drag coordinates were not saved");
        require(saved.equals(dev.lanworlds.config.LanWorldsConfig.load().lanWorldsButtonPosition), "drag did not persist on disk");
        click(entry(ToolbarButton.Icon.RADMIN));
        require(client.gui.screen().getClass().getSimpleName().equals("RadminScreen"), "shield did not open Radmin management");
        var settingsFactory = client.gui.screen().getClass().getMethod("settings", Screen.class);
        Screen radminSettings = (Screen) settingsFactory.invoke(null, multiplayer);
        client.setScreenAndShow(radminSettings);
        require(!button(radminSettings, Component.translatable("screen.radminlinker.settings.position_managed")).active,
                "Radmin settings still allows changing a position managed by LanWorlds");
        System.out.println("LANWORLDS_TOOLBAR_SMOKE_OK drag=true persisted=true styles=3 radminAnchors=4 radminSettingDisabled=true");
    }

    private void checkRoomDetails(Minecraft client) throws Exception {
        if (FabricLoader.getInstance().isModLoaded("viafabricplus")) {
            Class<?> protocol = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Class<?> facade = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
            Class<?> api;
            Object instance;
            String getter;
            try {
                api = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusAPI");
                instance = facade.getMethod("api").invoke(null);
                getter = "targetVersion";
            } catch (ClassNotFoundException olderApi) {
                api = Class.forName("com.viaversion.viafabricplus.api.ViaFabricPlusBase");
                instance = facade.getMethod("getImpl").invoke(null);
                getter = "getTargetVersion";
            }
            var get = api.getMethod(getter);
            var set = api.getMethod("setTargetVersion", protocol);
            Object original = get.invoke(instance);
            try {
                set.invoke(instance, protocol.getMethod("getProtocol", int.class).invoke(null, 767));
                require(ServerProtocol.target().id() == 767, "LAN status ignored ViaFabricPlus target");
            } finally {
                set.invoke(instance, original);
            }
            System.out.println("LANWORLDS_VIA_TARGET_SMOKE_OK");
        }
        var key = RoomKey.fromAddress("127.0.0.1:25565");
        var room = new LanRoomSnapshot(java.util.UUID.randomUUID(), key, "Local test room", "Test MOTD", "",
                3, 12, 42, System.currentTimeMillis(), LanRoomSnapshot.State.READY);
        var details = new LanRoomContextScreen(multiplayer, room);
        client.setScreenAndShow(details);
        var propertyField = details.getClass().getDeclaredField("properties");
        propertyField.setAccessible(true);
        var listTop = details.getClass().getDeclaredMethod("listTop");
        listTop.setAccessible(true);
        for (Object row : (java.util.List<?>) propertyField.get(details)) {
            var rowType = row.getClass();
            var value = rowType.getDeclaredMethod("value");
            value.setAccessible(true);
            if (!key.toString().equals(value.invoke(row))) continue;
            var x = rowType.getDeclaredMethod("x");
            var y = rowType.getDeclaredMethod("y");
            x.setAccessible(true);
            y.setAccessible(true);
            String before = client.keyboardHandler.getClipboard();
            try {
                require(details.mouseClicked(event((int) x.invoke(row) + 10,
                        (int) listTop.invoke(details) + (int) y.invoke(row) + 2), false), "room row click was not consumed");
                require(key.toString().equals(client.keyboardHandler.getClipboard()), "room address was not copied");
            } finally {
                client.keyboardHandler.setClipboard(before);
            }
            System.out.println("LANWORLDS_ROOM_COPY_SMOKE_OK");
            return;
        }
        throw new AssertionError("No room address row");
    }

    private void showLanRowFixtures(Minecraft client) throws Exception {
        var screen = new JoinMultiplayerScreen(new TitleScreen());
        client.setScreenAndShow(screen);
        var cacheField = JoinMultiplayerScreen.class.getDeclaredField("lanServerList");
        cacheField.setAccessible(true);
        var cache = (net.minecraft.client.server.LanServerDetection.LanServerList) cacheField.get(screen);
        LanWorldsClient.CONFIG.queryLanStatus = false;
        var host = java.net.InetAddress.getByName("127.0.0.1");
        String shortMotd = "Local broadcast survives failed status";
        String longMotd = "A longer local broadcast automatically wraps onto the second line and hides this room's address";
        cache.addServer("[MOTD]" + shortMotd + "[/MOTD][AD]25341[/AD]", host);
        cache.addServer("[MOTD]" + longMotd + "[/MOTD][AD]25342[/AD]", host);
        var status = Component.literal("Server status description should stay in details");
        LanWorldsClient.ROOMS.upsert(new LanRoomSnapshot(java.util.UUID.randomUUID(),
                RoomKey.fromAddress("127.0.0.1:25341"), shortMotd, "", "", -1, -1, -1,
                System.currentTimeMillis(), LanRoomSnapshot.State.TIMED_OUT));
        LanWorldsClient.ROOMS.upsert(new LanRoomSnapshot(java.util.UUID.randomUUID(),
                RoomKey.fromAddress("127.0.0.1:25342"), longMotd, status.getString(), "", 2, 8, 42,
                System.currentTimeMillis(), LanRoomSnapshot.State.READY, status, java.util.List.of()));
        System.out.println("LANWORLDS_ROW_RENDER_FIXTURES_READY");
    }

    private void checkFoldersAndSearch(Minecraft client) throws Exception {
        var screen = (JoinMultiplayerScreen) client.gui.screen();
        var host = (LanRoomSelectionHost) screen;
        var list = screen.children().stream().filter(net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.class::isInstance)
                .map(net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.class::cast).findFirst().orElseThrow();
        var options = LanWorldsClient.CONFIG;
        options.groupRooms = true;
        options.roomGrouping = "IP";
        var refresh = screen.getClass().getDeclaredMethod("lanworlds$refreshLanEntries");
        refresh.setAccessible(true);
        refresh.invoke(screen);
        var alpha = RoomKey.fromAddress("127.0.0.1:25341");
        var beta = RoomKey.fromAddress("127.0.0.1:25342");
        var folderRow = list.children().stream().filter(dev.lanworlds.mixin.LanServerEntryDataAccessor.class::isInstance)
                .filter(entry -> ((dev.lanworlds.mixin.LanServerEntryDataAccessor) entry).lanworlds$getServerData()
                        instanceof dev.lanworlds.room.LanRoomFolder folder && folder.members().contains(alpha)).findFirst().orElseThrow();
        var folder = (dev.lanworlds.room.LanRoomFolder) ((dev.lanworlds.mixin.LanServerEntryDataAccessor) folderRow).lanworlds$getServerData();
        require(folder.members().contains(beta), "rooms with the same host must share a folder");
        require(folderRow.mouseClicked(event(folderRow.getContentX() + 42, folderRow.getContentY() + 12), false), "folder click was not consumed");
        require(host.lanworlds$folderCollapsed(folder.key()), "folder did not collapse");
        host.lanworlds$roomSelection().setEnabled(true);
        host.lanworlds$activateFolder(folder, true);
        require(host.lanworlds$roomSelection().contains(alpha) && host.lanworlds$roomSelection().contains(beta), "collapsed rooms were not selected");
        host.lanworlds$activateFolder(folder, true);
        require(host.lanworlds$roomSelection().selected().isEmpty(), "folder did not deselect all");
        host.lanworlds$roomSelection().setEnabled(false);
        var more = screen.children().stream().filter(ToolbarButton.class::isInstance).map(ToolbarButton.class::cast)
                .filter(b -> b.icon() == ToolbarButton.Icon.MORE).findFirst().orElseThrow();
        require(screen.mouseClicked(event(more.getX() + 8, more.getY() + 8), false), "More button did not open");
        screen.mouseReleased(event(more.getX() + 8, more.getY() + 8));
        var search = screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                .map(net.minecraft.client.gui.components.EditBox.class::cast).filter(b -> b.visible).findFirst().orElseThrow();
        search.setValue("survives failed status");
        var result = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.getMessage().getString().startsWith("Local broadcast"))
                .findFirst().orElseThrow(() -> new AssertionError("Query did not create a suggestion"));
        require(host.lanworlds$folderCollapsed(folder.key()), "typing changed the main list");
        require(screen.mouseClicked(event(result.getX() + 8, result.getY() + 8), false), "suggestion click was not consumed");
        require(((dev.lanworlds.mixin.LanServerEntryDataAccessor) list.getSelected()).lanworlds$getServerData()
                .getAddress().equals(alpha.toString()), "suggestion did not locate the room");
        System.out.println("LANWORLDS_FOLDERS_SEARCH_SMOKE_OK");
    }

    private static Button button(Screen screen, Component message) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> candidate.getMessage().getString().equals(message.getString()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + message.getString()));
    }

    private ToolbarButton entry(ToolbarButton.Icon icon) {
        return multiplayer.children().stream().filter(ToolbarButton.class::isInstance).map(ToolbarButton.class::cast)
                .filter(button -> button.icon() == icon).findFirst().orElseThrow();
    }

    private void click(ToolbarButton button) {
        var event = event(button.getX() + 8, button.getY() + 8);
        require(multiplayer.mouseClicked(event, false), "toolbar click was not consumed");
        multiplayer.mouseReleased(event);
    }

    private static MouseButtonEvent event(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(MouseButtons.left(), 0));
    }

    private static ToolbarLayout.Rect bounds(Button button) {
        return new ToolbarLayout.Rect(button.getX(), button.getY(), button.getWidth(), button.getHeight());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
