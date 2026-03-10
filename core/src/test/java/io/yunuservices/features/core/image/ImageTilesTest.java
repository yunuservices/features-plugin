package io.yunuservices.features.core.image;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageTilesTest {
    @Test
    void toSkinCanvasMapsTileDirectlyToFrontFace() {
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(0, 0, 0xFFFF0000);
        tile.setRGB(0, 7, 0xFF00FF00);

        BufferedImage skin = ImageTiles.toSkinCanvas(tile);

        assertEquals(0xFFFF0000, skin.getRGB(8, 8));
        assertEquals(0xFF00FF00, skin.getRGB(8, 15));
    }

    @Test
    void readUrlRejectsOversizedPayload() throws Exception {
        byte[] payload = new byte[(8 * 1024 * 1024) + 1];

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/too-large.png", exchange -> {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();

            URL url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/too-large.png").toURL();
            IOException exception = assertThrows(IOException.class, () -> ImageTiles.readUrl(url, true));
            assertEquals("Remote image exceeds 8388608 bytes: " + url, exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readUrlLoadsValidImage() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        byte[] png = encodePng(image);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/valid.png", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            });
            server.start();

            URL url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/valid.png").toURL();
            BufferedImage loaded = ImageTiles.readUrl(url, true);
            assertEquals(8, loaded.getWidth());
            assertEquals(8, loaded.getHeight());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readUrlFollowsRedirectsOnPinnedConnection() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        byte[] png = encodePng(image);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/redirect.png", exchange -> {
                exchange.getResponseHeaders().add("Location", "/final.png");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/final.png", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            });
            server.start();

            URL url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect.png").toURL();
            BufferedImage loaded = ImageTiles.readUrl(url, true);
            assertEquals(8, loaded.getWidth());
            assertEquals(8, loaded.getHeight());
        } finally {
            server.stop(0);
        }
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
