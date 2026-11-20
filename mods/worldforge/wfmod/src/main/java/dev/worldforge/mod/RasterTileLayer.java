package dev.worldforge.mod;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;

/**
 * PNG-backed raster with on-demand rectangular decoding and a strictly bounded tile cache.
 *
 * The compressed PNG bytes stay resident, while decoded pixels are held only for recently
 * accessed 256x256 tiles. Concurrent chunk workers share an in-flight tile decode, avoiding
 * duplicate work when several chunks touch the same area.
 */
final class RasterTileLayer {
    private static final int TILE_SIZE = 256;
    private static final int MAX_CACHED_TILES = 12;
    /** Chunk workers may request tiles concurrently; only two PNG decodes may consume CPU at once. */
    private static final Semaphore DECODE_PERMITS = new Semaphore(2);

    private final byte[] png;
    private final int width;
    private final int height;
    private final String label;
    private final LinkedHashMap<Long, FutureTask<Tile>> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    private RasterTileLayer(byte[] png, int width, int height, String label) {
        this.png = png;
        this.width = width;
        this.height = height;
        this.label = label;
    }

    static RasterTileLayer load(byte[] png, int expectedWidth, int expectedHeight, String label)
            throws IOException {
        if (png == null || png.length == 0) throw new IOException("Missing raster data: " + label);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            if (input == null) throw new IOException("Cannot open raster: " + label);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported raster format: " + label);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int actualWidth = reader.getWidth(0);
                int actualHeight = reader.getHeight(0);
                if (actualWidth != expectedWidth || actualHeight != expectedHeight)
                    throw new IOException(label + ": size " + actualWidth + "x" + actualHeight
                            + ", expected " + expectedWidth + "x" + expectedHeight);
            } finally {
                reader.dispose();
            }
        }
        return new RasterTileLayer(png, expectedWidth, expectedHeight, label);
    }

    int sample(int x, int z) {
        if (x < 0 || z < 0 || x >= width || z >= height)
            throw new IndexOutOfBoundsException(label + " sample outside raster: " + x + "," + z);
        int tileX = x / TILE_SIZE;
        int tileZ = z / TILE_SIZE;
        long key = ((long) tileZ << 32) | (tileX & 0xffff_ffffL);
        FutureTask<Tile> task;
        boolean run = false;
        synchronized (cache) {
            task = cache.get(key);
            if (task == null) {
                final int originX = tileX * TILE_SIZE;
                final int originZ = tileZ * TILE_SIZE;
                task = new FutureTask<>(() -> decodeTile(originX, originZ));
                cache.put(key, task);
                run = true;
            }
            trimCompletedTiles();
        }
        if (run) {
            DECODE_PERMITS.acquireUninterruptibly();
            try {
                task.run();
            } finally {
                DECODE_PERMITS.release();
            }
        }
        try {
            Tile tile = task.get();
            synchronized (cache) { trimCompletedTiles(); }
            return tile.samples[(z - tile.originZ) * tile.width + (x - tile.originX)] & 0xFFFF;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while decoding " + label, interrupted);
        } catch (ExecutionException failure) {
            synchronized (cache) { cache.remove(key, task); }
            Throwable cause = failure.getCause();
            throw new IllegalStateException("Unable to decode tile from " + label,
                    cause == null ? failure : cause);
        }
    }

    /** Evict only finished tiles. In-flight decodes remain shared by all requesting chunk workers. */
    private void trimCompletedTiles() {
        while (cache.size() > MAX_CACHED_TILES) {
            Long removable = null;
            for (Map.Entry<Long, FutureTask<Tile>> entry : cache.entrySet()) {
                if (entry.getValue().isDone()) {
                    removable = entry.getKey();
                    break;
                }
            }
            if (removable == null) return;
            cache.remove(removable);
        }
    }

    private Tile decodeTile(int originX, int originZ) throws IOException {
        int tileWidth = Math.min(TILE_SIZE, width - originX);
        int tileHeight = Math.min(TILE_SIZE, height - originZ);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            if (input == null) throw new IOException("Cannot open raster: " + label);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported raster format: " + label);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(originX, originZ, tileWidth, tileHeight));
                BufferedImage image = reader.read(0, param);
                if (image == null) throw new IOException("Image reader returned no tile for " + label);
                Raster raster = image.getRaster();
                short[] samples = new short[tileWidth * tileHeight];
                for (int z = 0; z < tileHeight; z++)
                    for (int x = 0; x < tileWidth; x++)
                        samples[z * tileWidth + x] = (short) raster.getSample(x, z, 0);
                return new Tile(originX, originZ, tileWidth, samples);
            } finally {
                reader.dispose();
            }
        }
    }

    private record Tile(int originX, int originZ, int width, short[] samples) { }
}
