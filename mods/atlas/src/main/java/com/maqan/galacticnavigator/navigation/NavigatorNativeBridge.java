package com.maqan.galacticnavigator.navigation;

import java.util.Locale;

/**
 * Optional boundary to MAQAN's Rust native core.
 *
 * Java remains authoritative for Minecraft data and persistence. The native
 * implementation is enabled only after a compatible library is loaded and its
 * ABI handshake succeeds. Every caller must tolerate nativeAvailable()==false.
 */
public final class NavigatorNativeBridge {
    private static final int EXPECTED_ABI = 1;
    private static final boolean AVAILABLE;
    private static final String STATUS;

    static {
        boolean loaded = false;
        String status = "Rust native core unavailable; Java implementation active";
        if (Boolean.parseBoolean(System.getProperty("maqan.native.disabled", "false"))) {
            status = "Rust native core disabled by configuration; Java implementation active";
        } else {
            try {
                String explicitPath = System.getProperty("maqan.native.path", "").trim();
                if (!explicitPath.isEmpty()) {
                    System.load(new java.io.File(explicitPath).getAbsolutePath());
                } else {
                    try {
                        System.loadLibrary("maqan_core");
                    } catch (UnsatisfiedLinkError missingFromLibraryPath) {
                        loadBundledNativeLibrary();
                    }
                }
                int abi = nativeAbiVersion();
                if (abi != EXPECTED_ABI) {
                    status = "Rust native ABI mismatch (expected " + EXPECTED_ABI + ", got " + abi + ")";
                } else {
                    loaded = true;
                    status = "Rust native core active · ABI " + abi;
                }
            } catch (Throwable error) {
                status = "Rust native core unavailable (" + error.getClass().getSimpleName()
                        + "); Java implementation active";
            }
        }
        AVAILABLE = loaded;
        STATUS = status;
    }


    private static void loadBundledNativeLibrary() throws java.io.IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform;
        String library;
        if (os.contains("win") && (arch.contains("64") || arch.equals("amd64"))) {
            platform = "windows-x86_64"; library = "maqan_core.dll";
        } else if (os.contains("linux") && (arch.contains("64") || arch.equals("amd64"))) {
            platform = "linux-x86_64"; library = "libmaqan_core.so";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos"; library = "libmaqan_core.dylib";
        } else {
            throw new UnsatisfiedLinkError("Unsupported MAQAN native platform: " + os + " / " + arch);
        }
        String resource = "/maqan-native-" + platform + "/" + library;
        try (java.io.InputStream input = NavigatorNativeBridge.class.getResourceAsStream(resource)) {
            if (input == null) throw new UnsatisfiedLinkError("Bundled MAQAN native library not found: " + resource);
            java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("maqan-native-");
            java.nio.file.Path extracted = directory.resolve(library);
            java.nio.file.Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            directory.toFile().deleteOnExit();
            extracted.toFile().deleteOnExit();
            System.load(extracted.toAbsolutePath().toString());
        }
    }

    private NavigatorNativeBridge() {}

    public static boolean nativeAvailable() { return AVAILABLE; }
    public static String status() { return STATUS; }

    /**
     * Computes a route through the native explored-chunk index.
     * Input arrays are packed as x,z,height triples. Returns packed x,z pairs,
     * or an empty array if no route exists. This method is not enabled until
     * the JNI library implements ABI 1.
     */
    public static int[] routeExploredChunks(int dimensionHashLow, int dimensionHashHigh,
                                            int[] exploredXzHeight, int startX, int startZ,
                                            int goalX, int goalZ, int maxExpansions) {
        if (!AVAILABLE || exploredXzHeight == null || exploredXzHeight.length % 3 != 0)
            return new int[0];
        try {
            return nativeRouteExploredChunks(dimensionHashLow, dimensionHashHigh,
                    exploredXzHeight, startX, startZ, goalX, goalZ,
                    Math.max(1, Math.min(maxExpansions, 2_000_000)));
        } catch (Throwable ignored) {
            return new int[0];
        }
    }


    /**
     * Requests one complete animated chrome frame from Rust and submits its
     * batched draw commands to Minecraft's existing GuiGraphics context.
     * The Java renderer is deliberately a thin host adapter; no new GL context
     * or window is created. Returns false when native rendering is unavailable.
     */
    public static boolean renderChrome(net.minecraft.client.gui.GuiGraphics graphics,
                                       net.minecraft.client.gui.Font font,
                                       int width, int height, float partialTick, int flags) {
        if (!AVAILABLE || graphics == null || font == null || width <= 0 || height <= 0) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            byte[] packet = nativeComposeChrome(width, height, time + partialTick / 20.0f, flags);
            return drawPacket(graphics, font, packet, width, height);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean drawPacket(net.minecraft.client.gui.GuiGraphics g,
                                      net.minecraft.client.gui.Font font, byte[] packet,
                                      int expectedWidth, int expectedHeight) {
        if (packet == null || packet.length < 24 || packet.length > 16 * 1024 * 1024) return false;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'M' || b.get() != 'Q' || b.get() != 'U' || b.get() != 'I') return false;
        int version = Short.toUnsignedInt(b.getShort());
        b.getShort(); // reserved
        int frameWidth = b.getInt(), frameHeight = b.getInt();
        float scale = b.getFloat();
        int count = b.getInt();
        if (version != 1 || frameWidth != expectedWidth || frameHeight != expectedHeight
                || !Float.isFinite(scale) || scale <= 0 || count < 0 || count > 100_000) return false;
        // Validate the complete frame before drawing anything. A malformed tail must
        // never leave half a panel or a partially rendered screen behind.
        if (!validateCommands(b.asReadOnlyBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN), count)) return false;
        int activeClips = 0;
        try {
            for (int i = 0; i < count; i++) {
                int op = Byte.toUnsignedInt(b.get());
                switch (op) {
                    case 1 -> {
                        float[] r = readRect(b); int color = b.getInt();
                        fill(g, r, color);
                    }
                    case 2 -> {
                        float[] r = readRect(b); int color = b.getInt(); float t = b.getFloat();
                        if (Float.isFinite(t) && t > 0) stroke(g, r, color, t);
                    }
                    case 3 -> {
                        float x1 = b.getFloat(), y1 = b.getFloat(), x2 = b.getFloat(), y2 = b.getFloat();
                        int color = b.getInt(); float t = b.getFloat();
                        if (finite(x1,y1,x2,y2,t) && t > 0) line(g,x1,y1,x2,y2,color,t);
                    }
                    case 4 -> {
                        float x = b.getFloat(), y = b.getFloat();
                        int role = Byte.toUnsignedInt(b.get()), shadow = Byte.toUnsignedInt(b.get());
                        int length = b.getInt();
                        if (!finite(x,y) || length < 0 || length > 65_536 || length > b.remaining()) return false;
                        byte[] bytes = new byte[length]; b.get(bytes);
                        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        int color = switch (role) {
                            case 0 -> 0xFFEAF2FA;
                            case 1 -> 0xFFE0EAF4;
                            case 2, 4 -> 0xFF8FA4B9;
                            case 3 -> 0xFF79D9F2;
                            default -> 0xFFEAF2FA;
                        };
                        g.drawString(font, value, Math.round(x), Math.round(y), color, shadow != 0);
                    }
                    case 5 -> {
                        float[] r = readRect(b);
                        g.enableScissor(Math.round(r[0]), Math.round(r[1]),
                                Math.round(r[0] + r[2]), Math.round(r[1] + r[3]));
                        activeClips++;
                    }
                    case 6 -> {
                        if (activeClips <= 0) return false;
                        g.disableScissor();
                        activeClips--;
                    }
                    case 7 -> {
                        float cx=b.getFloat(), cy=b.getFloat(), radius=b.getFloat();
                        int color=b.getInt(); float intensity=b.getFloat();
                        if (finite(cx,cy,radius,intensity) && radius > 0 && intensity > 0 && intensity <= 1)
                            glow(g,cx,cy,radius,color,intensity);
                    }
                    default -> { return false; }
                }
            }
            return !b.hasRemaining();
        } catch (java.nio.BufferUnderflowException malformed) {
            return false;
        } finally {
            while (activeClips-- > 0) g.disableScissor();
        }
    }

    private static boolean validateCommands(java.nio.ByteBuffer b, int count) {
        int clipDepth = 0;
        try {
            for (int i = 0; i < count; i++) {
                int op = Byte.toUnsignedInt(b.get());
                switch (op) {
                    case 1 -> { readRect(b); b.getInt(); }
                    case 2 -> {
                        readRect(b); b.getInt();
                        float thickness = b.getFloat();
                        if (!Float.isFinite(thickness) || thickness <= 0 || thickness > 64) return false;
                    }
                    case 3 -> {
                        float x1=b.getFloat(), y1=b.getFloat(), x2=b.getFloat(), y2=b.getFloat();
                        b.getInt(); float thickness=b.getFloat();
                        if (!finite(x1,y1,x2,y2,thickness) || thickness <= 0 || thickness > 64
                                || Math.abs(x1)>65_536 || Math.abs(y1)>65_536
                                || Math.abs(x2)>65_536 || Math.abs(y2)>65_536) return false;
                    }
                    case 4 -> {
                        float x=b.getFloat(), y=b.getFloat();
                        int role=Byte.toUnsignedInt(b.get()), shadow=Byte.toUnsignedInt(b.get());
                        int length=b.getInt();
                        if (!finite(x,y) || Math.abs(x)>65_536 || Math.abs(y)>65_536
                                || role>4 || shadow>1 || length<0 || length>65_536
                                || length>b.remaining()) return false;
                        b.position(b.position()+length);
                    }
                    case 5 -> { readRect(b); clipDepth++; if (clipDepth>32) return false; }
                    case 6 -> { if (--clipDepth < 0) return false; }
                    case 7 -> {
                        float cx=b.getFloat(), cy=b.getFloat(), radius=b.getFloat();
                        b.getInt(); float intensity=b.getFloat();
                        if (!finite(cx,cy,radius,intensity) || Math.abs(cx)>65_536
                                || Math.abs(cy)>65_536 || radius<=0 || radius>16_384
                                || intensity<=0 || intensity>1) return false;
                    }
                    default -> { return false; }
                }
            }
            return clipDepth==0 && !b.hasRemaining();
        } catch (java.nio.BufferUnderflowException malformed) {
            return false;
        }
    }

    private static float[] readRect(java.nio.ByteBuffer b) {
        float x=b.getFloat(), y=b.getFloat(), w=b.getFloat(), h=b.getFloat();
        if (!finite(x,y,w,h) || Math.abs(x)>65_536 || Math.abs(y)>65_536 || w < 0 || h < 0 || w>65_536 || h>65_536) throw new java.nio.BufferUnderflowException();
        return new float[]{x,y,w,h};
    }
    private static boolean finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static void glow(net.minecraft.client.gui.GuiGraphics g,
                             float cx,float cy,float radius,int color,float intensity) {
        float bounded=Math.min(radius, 1800.0f);
        int rows=12;
        int rgb=color & 0x00FFFFFF;
        for(int i=-rows;i<=rows;i++){
            float ny=i/(float)rows;
            float falloff=1.0f-ny*ny;
            float half=bounded*(float)Math.sqrt(Math.max(0.0f,falloff));
            int alpha=Math.max(0,Math.min(90,Math.round(210.0f*intensity*falloff*falloff)));
            if(alpha==0) continue;
            int y=Math.round(cy+ny*bounded);
            int x0=Math.round(cx-half), x1=Math.round(cx+half);
            if(x1>x0) g.fill(x0,y,x1,y+2,(alpha<<24)|rgb);
        }
    }

    private static void fill(net.minecraft.client.gui.GuiGraphics g, float[] r, int color) {
        int x=Math.round(r[0]), y=Math.round(r[1]), right=Math.round(r[0]+r[2]), bottom=Math.round(r[1]+r[3]);
        if (right>x && bottom>y) g.fill(x,y,right,bottom,color);
    }
    private static void stroke(net.minecraft.client.gui.GuiGraphics g, float[] r, int color, float thickness) {
        int t=Math.max(1,Math.min(8,Math.round(thickness)));
        int x=Math.round(r[0]), y=Math.round(r[1]), right=Math.round(r[0]+r[2]), bottom=Math.round(r[1]+r[3]);
        if (right<=x || bottom<=y) return;
        g.fill(x,y,right,Math.min(bottom,y+t),color);
        g.fill(x,Math.max(y,bottom-t),right,bottom,color);
        g.fill(x,y+t,Math.min(right,x+t),Math.max(y+t,bottom-t),color);
        g.fill(Math.max(x,right-t),y+t,right,Math.max(y+t,bottom-t),color);
    }
    private static void line(net.minecraft.client.gui.GuiGraphics g, float x1,float y1,float x2,float y2,int color,float thickness) {
        int t=Math.max(1,Math.min(8,Math.round(thickness)));
        if (Math.abs(y2-y1)<0.01f) {
            int left=Math.round(Math.min(x1,x2)), right=Math.round(Math.max(x1,x2))+1, y=Math.round(y1);
            g.fill(left,y,right,y+t,color);
        } else if (Math.abs(x2-x1)<0.01f) {
            int top=Math.round(Math.min(y1,y2)), bottom=Math.round(Math.max(y1,y2))+1, x=Math.round(x1);
            g.fill(x,top,x+t,bottom,color);
        } else {
            int steps=Math.max(1,Math.round(Math.max(Math.abs(x2-x1),Math.abs(y2-y1))));
            for(int i=0;i<=steps;i++){
                float p=i/(float)steps; int x=Math.round(x1+(x2-x1)*p), y=Math.round(y1+(y2-y1)*p);
                g.fill(x,y,x+t,y+t,color);
            }
        }
    }




    /** Rust lays out title/subtitle typography and the associated accent rule. */
    public static boolean renderTitle(net.minecraft.client.gui.GuiGraphics graphics,
                                      net.minecraft.client.gui.Font font, String title,
                                      String subtitle, int width) {
        if (!AVAILABLE || graphics == null || font == null || title == null || width <= 0) return false;
        try {
            byte[] titleBytes = title.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] subtitleBytes = (subtitle == null ? "" : subtitle)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] packet = nativeComposeTitle(width, titleBytes, subtitleBytes,
                    font.width(subtitle == null ? "" : subtitle), false);
            return drawPacket(graphics, font, packet, width, 48);
        } catch (Throwable ignored) {
            return false;
        }
    }


    /** Rust header variant with a right-aligned metadata label. */
    public static boolean renderTitleRight(net.minecraft.client.gui.GuiGraphics graphics,
                                           net.minecraft.client.gui.Font font, String title,
                                           String subtitle, int width) {
        if (!AVAILABLE || graphics == null || font == null || title == null || width <= 0) return false;
        try {
            byte[] titleBytes = title.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String safeSubtitle = subtitle == null ? "" : subtitle;
            byte[] subtitleBytes = safeSubtitle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] packet = nativeComposeTitle(width, titleBytes, subtitleBytes,
                    font.width(safeSubtitle), true);
            return drawPacket(graphics, font, packet, width, 48);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Rust-composed button surface, state, label placement and hover bloom. */
    public static boolean renderButton(net.minecraft.client.gui.GuiGraphics graphics,
                                       net.minecraft.client.gui.components.Button button) {
        if (!AVAILABLE || graphics == null || button == null || !button.visible) return false;
        try {
            int state = !button.active ? 2 : button.isHoveredOrFocused() ? 1 : 0;
            byte[] label = button.getMessage().getString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            float time = MaqanAnimationClock.frameTimeSeconds();
            byte[] packet = nativeComposeButton(button.getX(), button.getY(), button.getWidth(),
                    button.getHeight(), time, state,
                    net.minecraft.client.Minecraft.getInstance().font.width(button.getMessage()), label);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    packet, button.getX() + button.getWidth(), button.getY() + button.getHeight());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Rust-composed section heading shared by planner, atlas and settings panels. */
    public static boolean renderSectionHeader(net.minecraft.client.gui.GuiGraphics graphics,
                                              net.minecraft.client.gui.Font font, String label,
                                              int x, int y, int width, int height) {
        if (!AVAILABLE || graphics == null || font == null || label == null
                || x < 0 || y < 0 || width <= 0 || height <= 0) return false;
        try {
            float phase = (float)((System.nanoTime() % 120_000_000_000L) / 1_000_000_000.0);
            byte[] packet = nativeComposeSectionHeader(x, y, width, height, phase,
                    font.width(label), label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return drawPacket(graphics, font, packet, x + width, y + height);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns [mapTop,mapBottom,listLeft,listRight,infoLeft,infoRight,mapLeft,mapRight], or null for Java fallback. */
    public static int[] computePanelLayout(int width, int height, boolean showList, boolean showInfo, int listWidth, int infoWidth, String[] panelOrder) {
        if (!AVAILABLE || width < 1 || height < 1 || panelOrder == null) return null;
        try {
            int[] order = {2, 0, 1};
            for (int i = 0; i < Math.min(3, panelOrder.length); i++) {
                String id = panelOrder[i];
                order[i] = "list".equals(id) ? 0 : "info".equals(id) ? 1 : "map".equals(id) ? 2 : 255;
            }
            byte[] packet = nativeComputePanelLayout(width, height, showList, showInfo, listWidth, infoWidth, order[0], order[1], order[2]);
            if (packet == null || packet.length != 40) return null;
            java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (data.get() != 'M' || data.get() != 'L' || data.get() != 'A' || data.get() != 'Y'
                    || Short.toUnsignedInt(data.getShort()) != 1) return null;
            data.getShort();
            int[] result = new int[8];
            for (int i = 0; i < result.length; i++) result[i] = data.getInt();
            int mapTop = result[0], mapBottom = result[1];
            if (mapTop < 0 || mapBottom < mapTop || mapBottom > height
                    || result[2] < 0 || result[3] < result[2] || result[3] > width
                    || result[4] < 0 || result[5] < result[4] || result[5] > width
                    || result[6] < 0 || result[7] < result[6] || result[7] > width
                    || result[7] <= result[6]) return null;
            return result;
        } catch (Throwable ignored) { return null; }
    }

    /** Rust-composed full-viewport transition overlay. */
    public static boolean renderTransitionOverlay(net.minecraft.client.gui.GuiGraphics graphics,
                                                   int width, int height, float progress, boolean backward) {
        if (!AVAILABLE || graphics == null || width < 80 || height < 60) return false;
        try {
            byte[] packet = nativeComposeTransitionOverlay(width, height, progress, backward ? 1 : 0);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font, packet, width, height);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed context menu surface. Java remains authoritative for menu content and actions. */
    public static boolean renderContextMenu(net.minecraft.client.gui.GuiGraphics graphics,
                                             int left, int top, int right, int bottom,
                                             int hoveredRow, float opacity) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top
                || hoveredRow < -1 || hoveredRow > 3) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            byte[] packet = nativeComposeContextMenu(left, top, right, bottom, time, hoveredRow, opacity);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font, packet, right, bottom);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed breadcrumb chip; Java retains hierarchy, labels and click targets. */
    public static boolean renderBreadcrumbChip(net.minecraft.client.gui.GuiGraphics graphics,
                                               int left, int top, int right, int bottom,
                                               boolean current, boolean hovered) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            int state = current ? 2 : hovered ? 1 : 0;
            byte[] packet = nativeComposeBreadcrumbChip(left, top, right, bottom, time, state);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font, packet, right, bottom);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed inspector tab; Java retains the selected tab and click behavior. */
    public static boolean renderNavigationTab(net.minecraft.client.gui.GuiGraphics graphics,
                                              int left, int top, int right, int bottom,
                                              boolean active, boolean hovered) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            int state = active ? 2 : hovered ? 1 : 0;
            byte[] packet = nativeComposeNavigationTab(left, top, right, bottom, time, state);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font, packet, right, bottom);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed catalogue row surface; Java retains hit-testing and catalogue data. */
    public static boolean renderCatalogueCard(net.minecraft.client.gui.GuiGraphics graphics,
                                               int left, int top, int right, int bottom,
                                               boolean selected, boolean hovered) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            int state = selected ? 2 : hovered ? 1 : 0;
            byte[] packet = nativeComposeCatalogueCard(left, top, right, bottom, time, state);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    packet, right, bottom);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed selected-object dossier surface; Minecraft still owns text and widgets. */
    public static boolean renderDossierCard(net.minecraft.client.gui.GuiGraphics graphics,
                                            int left, int top, int right, int bottom, boolean selected) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            byte[] packet = nativeComposeDossierCard(left, top, right, bottom, time, selected);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    packet, right, bottom);
        } catch (Throwable ignored) { return false; }
    }

    /** Rust-composed panel with a soft animated edge glow. */
    public static boolean renderPanel(net.minecraft.client.gui.GuiGraphics graphics,
                                      int left, int top, int right, int bottom, int accent) {
        if (!AVAILABLE || graphics == null || left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            float time = MaqanAnimationClock.frameTimeSeconds();
            byte[] packet = nativeComposePanel(left, top, right, bottom, time, accent);
            return drawPacket(graphics, net.minecraft.client.Minecraft.getInstance().font,
                    packet, right, bottom);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static native int nativeAbiVersion();
    private static native byte[] nativeComposeChrome(int width, int height, float time, int flags);
    private static native byte[] nativeComposeTitle(int width, byte[] title, byte[] subtitle, int subtitleWidth, boolean alignRight);
    private static native byte[] nativeComposePanel(int left, int top, int right, int bottom, float time, int accent);
    private static native byte[] nativeComposeDossierCard(int left, int top, int right, int bottom, float time, boolean selected);
    private static native byte[] nativeComposeCatalogueCard(int left, int top, int right, int bottom, float time, int state);
    private static native byte[] nativeComposeNavigationTab(int left, int top, int right, int bottom, float time, int state);
    private static native byte[] nativeComposeBreadcrumbChip(int left, int top, int right, int bottom, float time, int state);
    private static native byte[] nativeComposeContextMenu(int left, int top, int right, int bottom, float time, int hoveredRow, float opacity);
    private static native byte[] nativeComputePanelLayout(int width, int height, boolean showList, boolean showInfo, int listWidth, int infoWidth, int order0, int order1, int order2);
    private static native byte[] nativeComposeTransitionOverlay(int width, int height, float progress, int direction);
    private static native byte[] nativeComposeSectionHeader(int x, int y, int width, int height, float phase, int labelWidth, byte[] label);
    private static native byte[] nativeComposeButton(int x, int y, int width, int height, float time, int state, int labelWidth, byte[] label);
    private static native int[] nativeRouteExploredChunks(int dimensionHashLow,
            int dimensionHashHigh, int[] exploredXzHeight, int startX, int startZ,
            int goalX, int goalZ, int maxExpansions);
}
