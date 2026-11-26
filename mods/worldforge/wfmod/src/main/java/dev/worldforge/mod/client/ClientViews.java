package dev.worldforge.mod.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Окна аддонов на клиенте. Аддон регистрирует открывальщика по id окна (например "wf_war:main"); сервер шлёт
 * {@link dev.worldforge.mod.WFNet#sendView}, клиент разбирает полезную нагрузку и открывает (или обновляет) экран.
 * Приказы из окон уходят обычными командами: {@link #command}.
 */
public final class ClientViews {
    private static final Map<String, Consumer<String>> OPENERS = new ConcurrentHashMap<>();

    private ClientViews() {}

    public static void register(String viewId, Consumer<String> opener) { OPENERS.put(viewId, opener); }

    static void open(String viewId, String payload) {
        Consumer<String> o = OPENERS.get(viewId);
        if (o != null) o.accept(payload);
    }

    /** Выполнить команду от имени игрока (без слеша). */
    public static void command(String cmd) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
    }
}
