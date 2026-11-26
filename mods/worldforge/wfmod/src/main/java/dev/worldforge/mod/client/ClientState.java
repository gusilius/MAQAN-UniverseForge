package dev.worldforge.mod.client;

import dev.worldforge.mod.MapSync;
import dev.worldforge.mod.Markers;

/** Состояние карты на клиенте: то, что прислал сервер. Меняется только в главном потоке. */
public final class ClientState {
    public static MapSync.State state;               // null, если в этом измерении нет карты
    public static int[] terrain;                     // основа ARGB (null, пока не пришла)
    public static String terrainHash = "";
    public static Markers markers = new Markers();
    public static final MapSync.Reassembler RE = new MapSync.Reassembler();
    public static int revision;                      // растёт при любом изменении (по нему перерисовываются текстуры)
    public static String mode = "kingdom";
    public static boolean minimap = true;
    public static java.util.List<ClientAction> actions = java.util.List.of();       // действия, зарегистрированные на сервере (кнопки панелей)

    private ClientState() {}
}
