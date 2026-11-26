package dev.worldforge.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.worldforge.mod.MapImages;
import dev.worldforge.mod.MapSync;
import dev.worldforge.mod.TitleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Текстура текущего режима карты: строится в фоне по копии данных, общая для экрана карты и миникарты. */
public final class MapTextures {
    public static final class Label {
        public final String text;
        public final double px, py, areaPx;

        Label(String text, double px, double py, double areaPx) { this.text = text; this.px = px; this.py = py; this.areaPx = areaPx; }
    }

    private static final class Result {
        int[] img;
        int w, h;
        TitleData titles;
        int[] lut;
        List<Label> labels;
    }

    public static ResourceLocation id;
    public static int w, h;
    public static TitleData titles;       // копия, согласованная с текстурой
    public static int[] lut;
    public static List<Label> labels = List.of();
    public static String mode = "";
    private static DynamicTexture tex;
    private static int builtRev = -1;
    private static volatile boolean building;
    private static int counter;

    private MapTextures() {}

    public static boolean ready() {
        return id != null && titles != null;
    }

    /** Запускает перестройку, если режим или данные изменились. */
    public static void ensure(String wantMode) {
        MapSync.State st = ClientState.state;
        if (st == null) {
            release();
            return;
        }
        if (building || (builtRev == ClientState.revision && wantMode.equals(mode) && id != null)) return;
        building = true;
        final int rev = ClientState.revision;
        final TitleData copy = st.titles.copy();
        final int[] terrain = ClientState.terrain;
        final int tw = st.w, th = st.h;
        CompletableFuture.supplyAsync(() -> {
            Result r = new Result();
            int[] base = terrain;
            if (base == null || base.length != tw * th) {
                base = new int[tw * th];
                java.util.Arrays.fill(base, 0xFF1E2A3A);
            }
            r.img = MapImages.render(copy, base, wantMode);
            r.w = tw;
            r.h = th;
            r.titles = copy;
            r.lut = MapImages.lutFor(copy, wantMode);
            r.labels = makeLabels(copy, wantMode);
            return r;
        }).whenComplete((r, err) -> Minecraft.getInstance().execute(() -> {
            building = false;
            if (err != null || r == null) return;
            upload(r, rev, wantMode);
        }));
    }

    private static List<Label> makeLabels(TitleData d, String m) {
        List<Label> out = new ArrayList<>();
        if (MapImages.REALMS.equals(m) || MapImages.isDip(m)) {
            for (TitleData.Realm r : d.realmList()) {
                TitleData.Title top = null;
                for (String k : r.titleKeys) {
                    TitleData.Title t = d.byKey(k);
                    if (t != null && (top == null || t.tier > top.tier)) top = t;
                }
                if (top != null) out.add(new Label(r.name, top.capNx * d.w, top.capNy * d.h, r.areaBlocks / (d.bpp * d.bpp) * (r.overlord == null ? 3 : 1)));
            }
        } else if (MapImages.isProp(m)) {
            MapImages.PropInfo pi = MapImages.propInfo(d, m.substring(2));
            double[] sx = new double[pi.vals.size() + 1], sy = new double[pi.vals.size() + 1];
            int[] cnt = new int[pi.vals.size() + 1];
            for (TitleData.Title t : d.allTitles()) {
                if (t.tier != 0 || t.id >= pi.lut.length || pi.lut[t.id] == 0) continue;
                int v = pi.lut[t.id];
                sx[v] += t.capNx * d.w; sy[v] += t.capNy * d.h; cnt[v]++;
            }
            for (int v = 1; v <= pi.vals.size(); v++) {
                if (cnt[v] == 0) continue;
                double mx = sx[v] / cnt[v], my = sy[v] / cnt[v], best = Double.MAX_VALUE, bx = mx, by = my;
                for (TitleData.Title t : d.allTitles()) {           // центр ставим в ближайшее баронство со значением, чтобы подпись не попала в море
                    if (t.tier != 0 || t.id >= pi.lut.length || pi.lut[t.id] != v) continue;
                    double px = t.capNx * d.w, py = t.capNy * d.h, dd = (px - mx) * (px - mx) + (py - my) * (py - my);
                    if (dd < best) { best = dd; bx = px; by = py; }
                }
                out.add(new Label(pi.vals.get(v - 1), bx, by, pi.area[v - 1] / (d.bpp * d.bpp) * 0.5));
            }
        } else {
            int ti = -1;
            for (TitleData.Tier t : d.tiers) if (t.key.equals(m)) ti = t.index;
            if (ti >= 1) {
                for (TitleData.Title t : d.allTitles()) {
                    if (t.tier == ti) out.add(new Label(t.name.replaceFirst("^(Баронство|Графство|Герцогство|Королевство|Империя) ", ""), t.capNx * d.w, t.capNy * d.h, t.areaBlocks / (d.bpp * d.bpp)));
                }
            }
        }
        out.sort(Comparator.comparingDouble((Label l) -> -l.areaPx));
        return out;
    }

    private static void upload(Result r, int rev, String m) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage ni = new NativeImage(r.w, r.h, false);
        for (int z = 0; z < r.h; z++) {
            for (int x = 0; x < r.w; x++) {
                int a = r.img[z * r.w + x];
                ni.setPixelRGBA(x, z, (a & 0xFF00FF00) | ((a & 0xFF) << 16) | ((a >> 16) & 0xFF));
            }
        }
        DynamicTexture nt = new DynamicTexture(ni);
        ResourceLocation nid = new ResourceLocation("worldforge", "map/" + (counter++));
        mc.getTextureManager().register(nid, nt);
        ResourceLocation old = id;
        id = nid;
        tex = nt;
        w = r.w;
        h = r.h;
        titles = r.titles;
        lut = r.lut;
        labels = r.labels;
        mode = m;
        builtRev = rev;
        if (old != null) mc.getTextureManager().release(old);
    }

    public static void release() {
        if (id != null) Minecraft.getInstance().getTextureManager().release(id);
        id = null;
        tex = null;
        titles = null;
        builtRev = -1;
        mode = "";
    }
}
