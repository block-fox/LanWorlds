import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Loads the multiplayer mixin targets without navigating or joining a server. */
public final class LanWorldsMixinSmoke implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            ClassLoader loader = getClass().getClassLoader();
            String[] targets = {
                    "net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen",
                    "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList",
                    "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$NetworkServerEntry",
                    "net.minecraft.client.server.LanServerDetection$LanServerList",
                    "net.minecraft.client.gui.components.AbstractSelectionList"
            };
            for (String target : targets) {
                Class<?> type = Class.forName(target, false, loader);
                type.getDeclaredFields();
                type.getDeclaredMethods();
            }
            Class<?> screen = Class.forName(targets[0], false, loader);
            Class<?> host = Class.forName("dev.lanworlds.ui.LanRoomSelectionHost", false, loader);
            if (!host.isAssignableFrom(screen)) throw new AssertionError("multiplayer selection mixin was not applied");
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            System.out.println("LANWORLDS_MIXIN_SMOKE_OK Minecraft=" + version + " targets=" + targets.length);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
