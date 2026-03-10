package io.yunuservices.features.core.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ImageHash {
    private ImageHash() {
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return toHex(digest.digest());
    }

    public static String sha256(BufferedImage image) {
        MessageDigest digest = newDigest();
        int width = image.getWidth();
        int height = image.getHeight();
        updateInt(digest, width);
        updateInt(digest, height);

        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] buffer = new byte[pixels.length * 4];
        int offset = 0;
        for (int pixel : pixels) {
            buffer[offset++] = (byte) (pixel >> 24);
            buffer[offset++] = (byte) (pixel >> 16);
            buffer[offset++] = (byte) (pixel >> 8);
            buffer[offset++] = (byte) pixel;
        }
        digest.update(buffer);
        return toHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >> 24));
        digest.update((byte) (value >> 16));
        digest.update((byte) (value >> 8));
        digest.update((byte) value);
    }

    private static String toHex(byte[] hash) {
        char[] chars = new char[hash.length * 2];
        int index = 0;
        for (byte b : hash) {
            int value = b & 0xFF;
            chars[index++] = Character.forDigit(value >>> 4, 16);
            chars[index++] = Character.forDigit(value & 0x0F, 16);
        }
        return new String(chars);
    }
}
