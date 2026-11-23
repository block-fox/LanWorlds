package dev.lanworlds.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Optional bridge uses only Loader, Java values and the shared Minecraft Screen type. */
public final class RadminIntegration {
    public static final String PROVIDER_KEY = "radminlan:room-provider-v1";

    private static final String MANAGEMENT_KEY = "radminlan:open-management-v1";

    private RadminIntegration() {}

    public static void registerToolbar() {
        FabricLoader.getInstance().getObjectShare().put("lanworlds:manages-radmin-entry-v1", true);
    }

    @SuppressWarnings("unchecked")
    public static Consumer<Screen> managementAction() {
        Object action = FabricLoader.getInstance().getObjectShare().get(MANAGEMENT_KEY);
        return action instanceof Consumer<?> consumer ? (Consumer<Screen>) consumer : null;
    }

    public static boolean available() {
        return FabricLoader.getInstance().getObjectShare().get(PROVIDER_KEY) instanceof Function<?, ?>;
    }

    public static RoomInfo lookup(String ip) {
        if (ip == null || ip.isBlank()) return null;
        Object provider = FabricLoader.getInstance().getObjectShare().get(PROVIDER_KEY);
        if (!(provider instanceof Function<?, ?> function)) return null;
        try {
            @SuppressWarnings("unchecked")
            Object result = ((Function<String, ?>) function).apply(ip);
            if (!(result instanceof Map<?, ?> values) || values.isEmpty()) return null;
            List<PropertyAction> actions = new ArrayList<>();
            if (values.get("actions") instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (!(entry instanceof Map<?, ?> action) || !(action.get("open") instanceof Consumer<?> consumer)) continue;
                    String label = string(action, "label");
                    if (label.isBlank()) continue;
                    @SuppressWarnings("unchecked")
                    Consumer<Screen> open = (Consumer<Screen>) consumer;
                    actions.add(new PropertyAction(label, open));
                }
            }
            return new RoomInfo(string(values, "name"), string(values, "virtualIp"), string(values, "virtualIpv6"),
                    string(values, "nodeId"), string(values, "publicIp"), string(values, "connectionType"),
                    string(values, "version"), string(values, "state"), number(values, "latencyMs"),
                    string(values, "networks"), actions);
        } catch (RuntimeException | LinkageError ignored) {
            // A missing, outdated or unavailable optional provider must never
            // prevent vanilla LAN rooms from being displayed or inspected.
            return null;
        }
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof String text ? text : value instanceof Number ? value.toString() : "";
    }

    private static long number(Map<?, ?> values, String key) {
        return values.get(key) instanceof Number value ? value.longValue() : -1;
    }

    public record RoomInfo(String name, String virtualIp, String virtualIpv6, String nodeId, String publicIp,
                           String connectionType, String version, String state, long latencyMs,
                           String networks, List<PropertyAction> actions) {
        public RoomInfo { actions = List.copyOf(actions); }
    }

    public record PropertyAction(String label, Consumer<Screen> open) {}
}
