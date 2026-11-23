package dev.lanworlds.mixin;

import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exposes the vanilla LAN detector cache for room status and blocking. */
@Mixin(LanServerDetection.LanServerList.class)
public interface LanServerListAccessor {
    @Accessor("servers")
    List<LanServer> lanworlds$getServers();
}
