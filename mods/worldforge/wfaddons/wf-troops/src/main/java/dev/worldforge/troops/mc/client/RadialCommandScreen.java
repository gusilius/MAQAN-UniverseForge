package dev.worldforge.troops.mc.client;

import dev.worldforge.troops.Squads;
import dev.worldforge.troops.mc.OrderPacket;
import dev.worldforge.troops.mc.TroopEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Hold-Alt radial command wheel for the first-person command layer. */
final class RadialCommandScreen extends Screen {
    private record Option(Squads.Kind kind, String label) {}

    private static final List<Option> OPTIONS = List.of(
            new Option(Squads.Kind.MOVE, "Переместиться"),
            new Option(Squads.Kind.ATTACK_MOVE, "Атаковать по пути"),
            new Option(Squads.Kind.ATTACK, "Атаковать цель"),
            new Option(Squads.Kind.HOLD, "Удерживать"),
            new Option(Squads.Kind.STOP, "Стоп"),
            new Option(Squads.Kind.PATROL, "Патруль"),
            new Option(Squads.Kind.FOLLOW, "Следовать"),
            new Option(Squads.Kind.GUARD, "Охранять"),
            new Option(Squads.Kind.RETREAT, "Отступить")
    );

    private int hovered = -1;
    private boolean committed;

    RadialCommandScreen() {
        super(Component.literal("MAQAN · Команды"));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        return true;
    }

    private int optionAt(double mx, double my) {
        double dx = mx - width * 0.5, dy = my - height * 0.5;
        double radius = Math.hypot(dx, dy);
        if (radius < 34 || radius > 158) return -1;
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0;
        double turn = (angle % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
        return Math.min(OPTIONS.size() - 1, (int) Math.floor(turn / (Math.PI * 2) * OPTIONS.size()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hovered = optionAt(mouseX, mouseY);
        g.fill(0, 0, width, height, 0x77060910);
        int cx = width / 2, cy = height / 2;
        g.fill(cx - 31, cy - 31, cx + 31, cy + 31, 0xEE151B28);
        g.fill(cx - 30, cy - 30, cx + 30, cy - 29, 0xFFD4AD62);
        g.drawCenteredString(font, "MAQAN", cx, cy - 10, 0xFFF1D89A);
        g.drawCenteredString(font, "ALT", cx, cy + 4, 0xFFB9C5D7);

        int radius = 112;
        for (int i = 0; i < OPTIONS.size(); i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / OPTIONS.size();
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int y = cy + (int) Math.round(Math.sin(angle) * radius);
            boolean active = i == hovered;
            int left = x - 66, top = y - 15;
            g.fill(left, top, left + 132, top + 30, active ? 0xF0A87D35 : 0xD91B2635);
            g.fill(left, top, left + 132, top + 1, active ? 0xFFFFD98A : 0xFF53657A);
            g.drawCenteredString(font, OPTIONS.get(i).label(), x, y - 4,
                    active ? 0xFFFFFFFF : 0xFFD7DFEA);
        }
        String hint = hovered < 0
                ? "Наведите курсор на команду и отпустите Alt"
                : OPTIONS.get(hovered).label() + " · отпустите Alt для подтверждения";
        g.drawCenteredString(font, hint, cx, Math.min(height - 24, cy + 174), 0xFFE6D5B2);
        super.render(g, mouseX, mouseY, partialTick);
    }

    void commit() {
        if (committed) return;
        committed = true;
        if (hovered < 0 || CommandMode.selected().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Squads.Kind kind = OPTIONS.get(hovered).kind();
        String target = "";
        double x = 0, z = 0;

        if (kind == Squads.Kind.ATTACK) {
            if (!(mc.hitResult instanceof EntityHitResult hit)
                    || !(hit.getEntity() instanceof LivingEntity living)
                    || !living.isAlive() || CommandMode.selectable(mc, living)
                    || (!(living instanceof Enemy) && !(living instanceof TroopEntity && !CommandMode.selectable(mc, living)))) return;
            target = living.getUUID().toString();
        } else if (kind == Squads.Kind.MOVE || kind == Squads.Kind.ATTACK_MOVE || kind == Squads.Kind.PATROL) {
            if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
            Vec3 point = hit.getLocation();
            x = point.x;
            z = point.z;
        }

        boolean queue = hasShiftDown();
        CommandMode.send(new OrderPacket(OrderPacket.ORDER, CommandMode.selected(),
                kind.ordinal(), x, z, target, queue, 0));
        mc.player.displayClientMessage(Component.literal("Приказ: " + OPTIONS.get(hovered).label()), true);
    }
}
