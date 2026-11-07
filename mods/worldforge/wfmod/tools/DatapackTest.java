import dev.worldforge.mod.*;
import java.io.*;
import java.util.zip.*;

/** Читает карту прямо из датапака (как это делает мод через ResourceManager) и сверяет посадку. */
public class DatapackTest {
    public static void main(String[] a) throws Exception {
        String zipPath = a[0], id = a[1];              // id вида "planets:game_of_thrones"
        String ns = id.substring(0, id.indexOf(':')), name = id.substring(id.indexOf(':') + 1);
        try (ZipFile zf = new ZipFile(zipPath)) {
            MapData m = MapData.load(id, file -> {
                ZipEntry e = zf.getEntry("data/" + ns + "/worldforge_maps/" + name + "/" + file);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
            });
            TerrainSampler ts = new TerrainSampler(m, 1.0f);
            int[] sp = ts.findSpawn();
            TerrainSampler.Col c = ts.sample(sp[0], sp[2]);
            System.out.printf("карта из датапака: %dx%d, %.3f блоков/px%n", m.w, m.h, m.bpp);
            System.out.printf("спавн мода: x=%d y=%d z=%d, биом=%s%n", sp[0], sp[1], sp[2], m.keys[c.biome]);
        }
    }
}
