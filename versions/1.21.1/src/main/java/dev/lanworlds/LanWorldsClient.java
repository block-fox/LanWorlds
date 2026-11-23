package dev.lanworlds;

import dev.lanworlds.config.LanWorldsConfig;
import dev.lanworlds.room.LanRoomFaviconCache;
import dev.lanworlds.room.LanRoomRepository;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.gui.screens.Screen;

/** LAN discovery and room information work independently of optional VPN integrations. */
public final class LanWorldsClient implements ClientModInitializer {
    public static final String MOD_ID = "lanworlds";
    public static LanWorldsConfig CONFIG;
    public static LanRoomRepository ROOMS;
    public static volatile Screen MULTIPLAYER_SCREEN;

    @Override
    public void onInitializeClient() {
        CONFIG = LanWorldsConfig.load();
        ROOMS = new LanRoomRepository(CONFIG);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ROOMS.close();
            LanRoomFaviconCache.clear();
            CONFIG.save();
            MULTIPLAYER_SCREEN = null;
        });
    }
}
