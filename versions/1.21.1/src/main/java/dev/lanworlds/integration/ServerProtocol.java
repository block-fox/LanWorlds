package dev.lanworlds.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Read the optional ViaFabricPlus target without requiring the mod or its implementation classes. */
public record ServerProtocol(String name, int id) {
    public static ServerProtocol target() {
        return FabricLoader.getInstance().isModLoaded("viafabricplus") ? viaTarget() : vanilla();
    }

    private static ServerProtocol vanilla() {
        return new ServerProtocol(SharedConstants.getCurrentVersion().getName(), SharedConstants.getCurrentVersion().getProtocolVersion());
    }

    private static volatile java.util.function.Supplier<ServerProtocol> via;

    private static ServerProtocol viaTarget() {
        var lookup = via;
        if (lookup == null) via = lookup = findViaTarget();
        ServerProtocol protocol = lookup.get();
        return protocol == null ? vanilla() : protocol;
    }

    private static java.util.function.Supplier<ServerProtocol> findViaTarget() {
        String facade = "com.viaversion.viafabricplus.ViaFabricPlus";
        String apiPackage = "com.viaversion.viafabricplus.api.";
        String[][] versions = {
                {facade, "api", apiPackage + "ViaFabricPlusAPI", "targetVersion"},
                {facade, "getImpl", apiPackage + "ViaFabricPlusBase", "getTargetVersion"},
                {"de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator", "", "", "getTargetVersion"}
        };
        for (String[] version : versions) {
            try {
                var type = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
                var name = type.getMethod("getName");
                var id = type.getMethod("getVersion");
                var entry = Class.forName(version[0]);
                var api = version[1].isEmpty() ? null : entry.getMethod(version[1]);
                var target = (api == null ? entry : Class.forName(version[2])).getMethod(version[3]);
                return () -> {
                    try {
                        Object protocol = target.invoke(api == null ? null : api.invoke(null));
                        if (protocol == null) return null;
                        int protocolId = (Integer) id.invoke(protocol);
                        return protocolId > 0 ? new ServerProtocol((String) name.invoke(protocol), protocolId) : null;
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                        return null;
                    }
                };
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // The optional API has different entry points on older branches.
            }
        }
        return () -> null;
    }
}
