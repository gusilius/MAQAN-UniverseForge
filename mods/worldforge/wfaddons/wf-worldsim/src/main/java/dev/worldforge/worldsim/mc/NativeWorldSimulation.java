package dev.worldforge.worldsim.mc;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Loads the platform-native WorldSim core; Java remains authoritative if no supported binary is present. */
public final class NativeWorldSimulation {
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
    public static final int NATIVE_ABI_VERSION = 2;
    private static volatile boolean attempted;
    private static volatile boolean available;
    private NativeWorldSimulation() {}

    public static synchronized boolean load() {
        if (attempted) return available;
        attempted = true;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String resource;
        String libraryName;
        if (os.contains("win")) {
            resource = "/maqan-native-windows-x86_64/worldforge_worldsim.dll";
            libraryName = "worldforge_worldsim.dll";
        } else if (os.contains("linux")) {
            resource = "/maqan-native-linux-x86_64/libworldforge_worldsim.so";
            libraryName = "libworldforge_worldsim.so";
        } else if (os.contains("mac") || os.contains("darwin")) {
            resource = "/maqan-native-macos-aarch64/libworldforge_worldsim.dylib";
            libraryName = "libworldforge_worldsim.dylib";
        } else {
            return false;
        }
        try (InputStream input = NativeWorldSimulation.class.getResourceAsStream(resource)) {
            if (input == null) return false;
            Path directory = FMLPaths.GAMEDIR.get().resolve("config").resolve("worldforge").resolve("native");
            Files.createDirectories(directory);
            Path library = directory.resolve(libraryName);
            Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(library.toAbsolutePath().toString());
            int loadedAbi = nativeAbiVersion();
            if (loadedAbi != NATIVE_ABI_VERSION) {
                throw new UnsatisfiedLinkError("WorldSim native ABI mismatch: expected "
                        + NATIVE_ABI_VERSION + ", found " + loadedAbi);
            }
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

    public static byte[] generate(byte[] request) {
        if (!load()) throw new IllegalStateException("WorldForge native decision core is unavailable");
        byte[] result = nativeGenerate(request);
        if (result == null || result.length == 0) throw new IllegalStateException("WorldForge native generator returned an empty result");
        return result;
    }

    public static byte[] advanceEmergence(byte[] request) {
        if (!load()) throw new IllegalStateException("WorldForge native emergence core is unavailable");
        byte[] result = nativeAdvanceEmergence(request);
        if (result == null || result.length == 0) throw new IllegalStateException("WorldForge native emergence core returned an empty frame");
        return result;
    }

    public static byte[] advancePopulation(byte[] request) {
        if (!load()) throw new IllegalStateException("WorldForge native population core is unavailable");
        byte[] result = nativeAdvancePopulation(request);
        if (result == null || result.length == 0) throw new IllegalStateException("WorldForge native population core returned an empty frame");
        return result;
    }

    private static native int nativeAbiVersion();
    private static native byte[] nativeDecide(byte[] snapshot);
    private static native byte[] nativeGenerate(byte[] request);
    private static native byte[] nativeAdvanceEmergence(byte[] request);
    private static native byte[] nativeAdvancePopulation(byte[] request);
}
