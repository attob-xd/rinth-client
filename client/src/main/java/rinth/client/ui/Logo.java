package rinth.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import rinth.client.platform.ClientPlatform;
import rinth.client.ui.UiAtlas.Region;

/**
 * Rinth's mark: the game's own carrot, read from the game's resources as it runs, so the mark is
 * whatever carrot the game draws and none of its textures is copied into the client. Drawn the way
 * the game draws items, each of its pixels a whole number of screen pixels square, so it stays crisp
 * at any size and GUI scale. Until the game's resources can be read (early in loading) nothing is
 * drawn. Unlike {@link Icons} it has its own colours, so a draw only fades it.
 *
 * <p>The first time the carrot is read, the page around the game gets it as its icon too ({@link
 * rinth.platform.api.PlatformShell#setPageIcon}).
 *
 * <p>Render thread. After the mark's first use at a size, nothing allocates.
 */
public final class Logo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier CARROT = Identifier.withDefaultNamespace("textures/item/carrot.png");
    private static final int MAX_PIXELS = 256;
    /** The page's icon: the carrot's pixels this many of the icon's pixels square. */
    private static final int PAGE_ICON_SCALE = 4;
    /** How long to wait before trying the game's carrot again, in milliseconds. */
    private static final long RETRY_MILLIS = 1000;

    /** The mark by its size in pixels, made as needed. */
    private static final @Nullable Region[] MADE = new Region[MAX_PIXELS + 1];

    /** The carrot's ARGB pixels, row by row, once read; its size beside it. */
    private static int @Nullable [] carrot;

    private static int carrotWidth;
    private static int carrotHeight;
    private static long nextTry;

    private Logo() {}

    /** Reads the game's carrot as soon as the game can give it, so the page gets its icon early. */
    public static void tick() {
        if (carrot == null) {
            carrot();
        }
    }

    /** Draws the mark {@code size} GUI units square at ({@code x}, {@code y}), at {@code alpha} (0 to 1). */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int size, float alpha) {
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int pixels = Math.min(MAX_PIXELS, size * scale);
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        if (pixels <= 0 || a == 0) {
            return;
        }
        int[] argb = carrot();
        if (argb == null) {
            return;
        }
        Region region = MADE[pixels];
        if (region == null) {
            region = UiAtlas.add(sized(argb, pixels));
            MADE[pixels] = region;
        }
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        int px = x * scale;
        int py = y * scale;
        RoundRects.quad(
                graphics, pose, region, px, py, px + pixels, py + pixels, 0, 0, pixels, pixels, a << 24 | 0xFFFFFF);
    }

    /** The game's carrot, or null while the game can't give it yet. */
    private static int @Nullable [] carrot() {
        int[] argb = carrot;
        if (argb != null) {
            return argb;
        }
        long now = System.currentTimeMillis();
        if (now < nextTry) {
            return null;
        }
        nextTry = now + RETRY_MILLIS;
        try {
            Optional<Resource> resource =
                    Minecraft.getInstance().getResourceManager().getResource(CARROT);
            if (resource.isEmpty()) {
                return null;
            }
            argb = read(resource.get());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read the game's carrot for the mark: {}", e.toString());
            return null;
        }
        carrot = argb;
        pageIcon(argb);
        return argb;
    }

    private static int[] read(Resource resource) throws IOException {
        try (InputStream in = resource.open();
                NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = new int[width * height];
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    argb[row * width + column] = image.getPixel(column, row);
                }
            }
            carrotWidth = width;
            carrotHeight = height;
            return argb;
        }
    }

    /**
     * The carrot in a {@code pixels} square: each of its pixels the nearest whole number of pixels
     * square to filling it, centred, the edges cut if that's a little larger than the square.
     */
    private static NativeImage sized(int[] argb, int pixels) {
        int each = Math.max(1, Math.round((float) pixels / Math.max(carrotWidth, carrotHeight)));
        int left = (pixels - carrotWidth * each) / 2;
        int top = (pixels - carrotHeight * each) / 2;
        NativeImage image = new NativeImage(pixels, pixels, false);
        for (int py = 0; py < pixels; py++) {
            int row = Math.floorDiv(py - top, each);
            for (int px = 0; px < pixels; px++) {
                int column = Math.floorDiv(px - left, each);
                int pixel = 0;
                if (row >= 0 && row < carrotHeight && column >= 0 && column < carrotWidth) {
                    pixel = argb[row * carrotWidth + column];
                }
                image.setPixel(px, py, pixel);
            }
        }
        return image;
    }

    /** Hands the carrot, scaled up, to the page as its icon. */
    private static void pageIcon(int[] argb) {
        int width = carrotWidth * PAGE_ICON_SCALE;
        int height = carrotHeight * PAGE_ICON_SCALE;
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[(y / PAGE_ICON_SCALE) * carrotWidth + x / PAGE_ICON_SCALE];
                int i = (y * width + x) * 4;
                rgba[i] = (byte) (pixel >> 16);
                rgba[i + 1] = (byte) (pixel >> 8);
                rgba[i + 2] = (byte) pixel;
                rgba[i + 3] = (byte) (pixel >>> 24);
            }
        }
        try {
            ClientPlatform.get().shell().setPageIcon(width, height, rgba);
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't give the page its icon: {}", e.toString());
        }
    }
}
