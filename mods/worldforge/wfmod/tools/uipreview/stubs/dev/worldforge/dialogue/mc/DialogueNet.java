package dev.worldforge.dialogue.mc;
import java.util.List;
public final class DialogueNet {
    public static final byte MODE_CHOICES = 0, MODE_CONTINUE = 1, MODE_END = 2;
    public static final class Ch { public void sendToServer(Object o) {} }
    public static Ch CHANNEL;
    public static final class OpenView { public int sessionId; public String speaker, text; public byte mode; public List<String> choices = List.of(); }
    public static final class CloseReq { public CloseReq(int i) {} }
    public static final class Choose { public Choose(int a, int b) {} }
}
