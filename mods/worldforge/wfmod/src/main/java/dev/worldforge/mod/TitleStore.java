package dev.worldforge.mod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Сохранение правок в папку мира (titles.json, realms.json, provinces.png) и загрузка их поверх карты. */
public final class TitleStore {
    private TitleStore() {}

    public static void save(MapData m, Path dir) throws IOException {
        TitleData d = m.titles;
        if (d == null) throw new IOException("У карты нет титулов");
        Files.createDirectories(dir);
        writeAtomic(dir.resolve("titles.json"), d.titlesJson().getBytes(StandardCharsets.UTF_8));
        writeAtomic(dir.resolve("realms.json"), d.realmsJson().getBytes(StandardCharsets.UTF_8));
        BufferedImage img = new BufferedImage(d.w, d.h, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster r = img.getRaster();
        short[] px = d.pixels();
        for (int z = 0; z < d.h; z++) for (int x = 0; x < d.w; x++) r.setSample(x, z, 0, px[z * d.w + x] & 0xFFFF);
        Path tmp = dir.resolve("provinces.png.tmp");
        ImageIO.write(img, "png", tmp.toFile());
        Files.move(tmp, dir.resolve("provinces.png"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Накладывает сохранённые правки на загруженную карту. Возвращает true, если правки были. */
    public static boolean applyOverlay(MapData m, Path dir) throws IOException {
        TitleData d = m.titles;
        Path tj = dir.resolve("titles.json"), rj = dir.resolve("realms.json"), pp = dir.resolve("provinces.png");
        if (d == null || !Files.isRegularFile(tj) || !Files.isRegularFile(pp)) return false;
        BufferedImage img = ImageIO.read(pp.toFile());
        if (img == null || img.getWidth() != d.w || img.getHeight() != d.h) throw new IOException("provinces.png в " + dir + " не подходит карте");
        Raster r = img.getRaster();
        short[] px = new short[d.w * d.h];
        for (int z = 0; z < d.h; z++) for (int x = 0; x < d.w; x++) px[z * d.w + x] = (short) r.getSample(x, z, 0);
        d.setPixels(px);
        d.loadMeta(Files.readString(tj, StandardCharsets.UTF_8), Files.isRegularFile(rj) ? Files.readString(rj, StandardCharsets.UTF_8) : null);
        return true;
    }

    private static void writeAtomic(Path p, byte[] data) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
}
