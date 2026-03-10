package io.yunuservices.features.core.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImageTiles {
    private static final int REMOTE_CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int REMOTE_READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_REMOTE_IMAGE_BYTES = 8 * 1024 * 1024;

    private ImageTiles() {
    }

    public static BufferedImage readFile(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Unsupported image format: " + file);
        }
        return image;
    }

    public static BufferedImage readUrl(URL url) throws IOException {
        return readUrl(url, false);
    }

    public static BufferedImage readUrl(URL url, boolean allowPrivateAddressUrls) throws IOException {
        byte[] data = PinnedHttpFetcher.fetch(
            url,
            allowPrivateAddressUrls,
            REMOTE_CONNECT_TIMEOUT_MILLIS,
            REMOTE_READ_TIMEOUT_MILLIS,
            MAX_REMOTE_IMAGE_BYTES
        );

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("Unsupported image format from URL: " + url);
        }
        return image;
    }

    public static void requireDivisibleBy8(BufferedImage image) {
        if (image.getWidth() % 8 != 0 || image.getHeight() % 8 != 0) {
            throw new IllegalArgumentException(
                "Image width and height must be divisible by 8. Got "
                    + image.getWidth()
                    + "x"
                    + image.getHeight()
            );
        }
    }

    public static void requireTagDimensions(BufferedImage image) {
        requireDivisibleBy8(image);
        if (image.getHeight() != 8) {
            throw new IllegalArgumentException(
                "Tag image height must be exactly 8. Got " + image.getHeight()
            );
        }
    }

    public static int widthSymbols(BufferedImage image) {
        return image.getWidth() / 8;
    }

    public static int heightSymbols(BufferedImage image) {
        return image.getHeight() / 8;
    }

    public static BufferedImage tileAt(BufferedImage image, int tileX, int tileY) {
        return image.getSubimage(tileX * 8, tileY * 8, 8, 8);
    }

    public static BufferedImage toSkinCanvas(BufferedImage tile) {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var graphics = skin.createGraphics();
        try {
            graphics.drawImage(tile, 8, 8, null);
        } finally {
            graphics.dispose();
        }
        return skin;
    }

    public static void ensureExists(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Image file does not exist: " + file);
        }
    }
}
