package dev.worldforge.rts;

import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

/**
 * Разбор строк {@code wfsettle ...}, которые выдаёт {@link Orders}, и их применение к Settlement. Нужен для одиночной проверки
 * (тесты, предпросмотр экрана) и как образец для сервера: те же команды, что у mc/SettleCommands, плюс {@code priority-work}.
 * Бросает {@link Settlement.OrderException} с причиной, как серверная команда.
 */
public final class Dispatch {
    private Dispatch() {}

    public static String apply(Settlement s, WorkPriorities prios, String line) {
        String[] t = line.trim().replaceFirst("^/", "").split("\\s+");
        if (t.length < 2 || !t[0].equals("wfsettle")) throw new Settlement.OrderException("Не команда поселения: " + line);
        try {
            switch (t[1]) {
                case "assign": s.assign(Integer.parseInt(t[2]), Integer.parseInt(t[3])); return "assign";
                case "unassign": s.unassign(Integer.parseInt(t[2])); return "unassign";
                case "profession": s.setProfession(Integer.parseInt(t[2]), t[3]); return "profession";
                case "build": s.place(t[2], Integer.parseInt(t[3]), Integer.parseInt(t[4])); return "build";
                case "cancel": s.cancel(Integer.parseInt(t[2])); return "cancel";
                case "priority": s.setPriority(Integer.parseInt(t[2]), Integer.parseInt(t[3])); return "priority";
                case "auto": s.auto = t[2].equals("on"); return "auto";
                case "priority-work": {
                    Worker w = s.worker(Integer.parseInt(t[2]));
                    if (w == null) throw new Settlement.OrderException("Нет рабочего " + t[2]);
                    if (prios != null) prios.set(w, t[3], Integer.parseInt(t[4]));
                    return "priority-work";
                }
                default: throw new Settlement.OrderException("Неизвестная команда: " + t[1]);
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            throw new Settlement.OrderException("Плохие аргументы: " + line);
        }
    }
}
