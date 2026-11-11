package dev.worldforge.worldsim.mc;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the optional Windows native decision core. Java remains authoritative if it is unavailable. */
public final class NativeWorldSimulation {
    private static volatile boolean attempted;
    private static volatile boolean available;
    private NativeWorldSimulation() {}

    public static synchronized boolean load() {
        if (attempted) return available;
        attempted = true;
        String resource = "/maqan-native-windows-x86_64/worldforge_worldsim.dll";
        try (InputStream input = NativeWorldSimulation.class.getResourceAsStream(resource)) {
            if (input == null) return false;
            Path directory = FMLPaths.GAMEDIR.get().resolve("config").resolve("worldforge").resolve("native");
            Files.createDirectories(directory);
            Path library = directory.resolve("worldforge_worldsim.dll");
            Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(library.toAbsolutePath().toString());
            available = true;
        } catch (IOException | UnsatisfiedLinkError | SecurityException error) {
            available = false;
        }
        return available;
    }

    public static byte[] decide(byte[] snapshot) {
        if (!load()) throw new IllegalStateException("WorldForge native decision core is unavailable");
        byte[] result = nativeDecide(snapshot);
        if (result == null || result.length == 0) throw new IllegalStateException("WorldForge native core returned an empty frame");
        return result;
    }

    private static native byte[] nativeDecide(byte[] snapshot);
}
