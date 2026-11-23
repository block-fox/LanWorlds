import dev.lanworlds.LanWorldsClient;
import dev.lanworlds.room.RoomKey;
import dev.lanworlds.ui.LanRoomContextScreen;
import dev.lanworlds.ui.LanRoomSelectionHost;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.net.InetAddress;
import java.util.List;

/** Exercises the old list's real rendering, hit testing, and refresh on the client thread. */
public final class LanWorlds1211Smoke implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!(client.screen instanceof TitleScreen title) || client.getOverlay() != null) return;
            try {
                require(FabricLoader.getInstance().getGameDir().toString().contains("mixin-smoke-runtime"),
                        "expected an isolated smoke-test game directory");
                JoinMultiplayerScreen screen = new JoinMultiplayerScreen(title);
                client.setScreen(screen);
                var list = screen.children().stream()
                        .filter(ServerSelectionList.class::isInstance)
                        .map(ServerSelectionList.class::cast).findFirst().orElseThrow();
                var alpha = new LanServer("Test room A", "127.0.0.1:25101");
                var beta = new LanServer("Test room B", "127.0.0.1:25102");
                list.updateNetworkServers(List.of(alpha, beta));
                checkRightClick(screen, list);
                require(client.screen instanceof LanRoomContextScreen, "right-click did not open room details");
                client.setScreen(screen);
                list.updateNetworkServers(List.of(alpha, beta));

                clickButton(screen, Component.translatable("screen.lanworlds.more").getString());
                clickButton(screen, Component.translatable("screen.lanworlds.multi_select").getString());
                clickButton(screen, Component.translatable("screen.lanworlds.more").getString());
                int x = list.getRowLeft() + 10;
                int firstY = list.getY() + 4 + 36 + 7;
                require(screen.mouseClicked(x, firstY, GLFW.GLFW_MOUSE_BUTTON_LEFT), "room click was not consumed");
                require(screen.mouseClicked(x, firstY + 36, GLFW.GLFW_MOUSE_BUTTON_LEFT), "second click was not consumed");
                var selected = ((LanRoomSelectionHost) screen).lanworlds$roomSelection();
                var alphaKey = RoomKey.fromAddress(alpha.getAddress());
                var betaKey = RoomKey.fromAddress(beta.getAddress());
                require(selected.contains(alphaKey) && selected.contains(betaKey), "mouse selection did not retain both rooms");
                screen.joinSelectedServer();
                require(client.screen == screen, "joining was not blocked during multi-select");
                require(screen.keyPressed(GLFW.GLFW_KEY_F5, 0, 0), "refresh key was not consumed");
                require(client.screen instanceof JoinMultiplayerScreen && client.screen != screen, "refresh did not replace screen");
                JoinMultiplayerScreen replacement = (JoinMultiplayerScreen) client.screen;
                var reopenedSelection = ((LanRoomSelectionHost) replacement).lanworlds$roomSelection();
                require(reopenedSelection.enabled() && reopenedSelection.contains(alphaKey)
                        && reopenedSelection.contains(betaKey), "refresh lost selected rooms");

                // Seed the vanilla detector's cache so tick refreshes and paints both rows normally.
                var discovery = replacement.getClass().getDeclaredField("lanServerList");
                discovery.setAccessible(true);
                var cache = (LanServerDetection.LanServerList) discovery.get(replacement);
                cache.addServer("[MOTD]Test room A[/MOTD][AD]25101[/AD]", InetAddress.getByName("127.0.0.1"));
                cache.addServer("[MOTD]Test room B[/MOTD][AD]25102[/AD]", InetAddress.getByName("127.0.0.1"));
                ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
                    private int ticks;
                    @Override public void onEndTick(net.minecraft.client.Minecraft game) {
                        if (++ticks < 15) return;
                        try {
                            require(game.screen == replacement, "multiplayer screen did not stay open");
                            clickButton(replacement, Component.translatable("screen.lanworlds.block_selected", 2).getString());
                            require(LanWorldsClient.CONFIG.isBlocked("127.0.0.1", 25101, "Test room A")
                                    && LanWorldsClient.CONFIG.isBlocked("127.0.0.1", 25102, "Test room B"), "batch block missed a room");
                            System.out.println("LANWORLDS_MIXIN_SMOKE_OK Minecraft=1.21.1 rightClick=true multiSelect=true refresh=true joinGuard=true batchBlock=true");
                            game.stop();
                        } catch (Throwable failure) {
                            failure.printStackTrace();
                            System.exit(1);
                        }
                    }
                });
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        });
    }

    private static void checkRightClick(JoinMultiplayerScreen screen, ServerSelectionList list) {
        require(screen.mouseClicked(list.getRowLeft() + 10, list.getY() + 4 + 36 + 7,
                GLFW.GLFW_MOUSE_BUTTON_RIGHT), "LAN row right-click was not consumed");
    }

    private static void clickButton(JoinMultiplayerScreen screen, String label) {
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> candidate.visible && candidate.active && candidate.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("missing active button: " + label));
        button.onPress();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
