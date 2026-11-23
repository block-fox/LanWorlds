import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.input.MouseButtons;
import dev.lanworlds.ui.LanRoomSelectionHost;
import dev.lanworlds.ui.LanWorldsScreen;
import dev.lanworlds.ui.ToolbarButton;
import dev.lanworlds.ui.ToolbarLayout;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
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
                if (++warmup != 5) return;
                checkToolbar(client);
                client.stop();
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        });
    }

    private void checkToolbar(Minecraft client) {
        var config = LanWorldsClient.CONFIG;
        var entries = multiplayer.children().stream().filter(ToolbarButton.class::isInstance)
                .map(ToolbarButton.class::cast).toList();
        require(entries.size() == 3, "expected selection, LanWorlds and Radmin icons");
        Button via = multiplayer.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals("ViaFabricPlus")).findFirst().orElseThrow();
        for (var entry : entries) require(!bounds(entry).overlaps(bounds(via)), "ViaFabricPlus button was covered");
        var selection = entry(ToolbarButton.Icon.SELECTION);
        click(selection);
        require(((LanRoomSelectionHost) multiplayer).lanworlds$roomSelection().enabled(), "selection click was lost");
        click(selection);
        require(!((LanRoomSelectionHost) multiplayer).lanworlds$roomSelection().enabled(), "selection did not turn off");

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
        client.setScreenAndShow(multiplayer);

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
        System.out.println("LANWORLDS_TOOLBAR_SMOKE_OK drag=true persisted=true viaCollision=false styles=3 radmin=true");
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
