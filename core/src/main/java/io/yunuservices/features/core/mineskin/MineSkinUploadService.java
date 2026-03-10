package io.yunuservices.features.core.mineskin;

import io.yunuservices.features.core.image.ImageTiles;
import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import io.yunuservices.features.core.render.TexturePropertyNormalizer;
import org.mineskin.JsoupRequestHandler;
import org.mineskin.MineSkinClient;
import org.mineskin.data.JobInfo;
import org.mineskin.data.SkinInfo;
import org.mineskin.options.GenerateQueueOptions;
import org.mineskin.options.IQueueOptions;
import org.mineskin.request.GenerateRequest;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MineSkinUploadService {
    private final MineSkinSettings settings;
    private final MineSkinClient client;

    public MineSkinUploadService(MineSkinSettings settings, String userAgent) {
        this.settings = settings == null ? new MineSkinSettings() : settings;

        if (!this.settings.isAvailable()) {
            this.client = null;
            return;
        }

        MineSkinSettings.Limits limits = this.settings.getLimits();
        int intervalMillis = Math.max(0, limits.getIntervalMillis());
        int concurrency = Math.max(1, limits.getConcurrency());
        IQueueOptions queueOptions = limits.getMode() == MineSkinSettings.Limits.Mode.AUTO
            ? GenerateQueueOptions.createAuto()
            : GenerateQueueOptions.create().withInterval(intervalMillis, TimeUnit.MILLISECONDS)
                .withConcurrency(concurrency);

        this.client = MineSkinClient.builder()
            .requestHandler(JsoupRequestHandler::new)
            .userAgent(userAgent)
            .apiKey(this.settings.getApiKey())
            .generateQueueOptions(queueOptions)
            .build();
    }

    public boolean isEnabled() {
        return settings.isAvailable();
    }

    public CompletableFuture<HeadSpriteImage> generate(BufferedImage image, String namePrefix, ProgressListener progressListener) {
        if (!isEnabled()) {
            return CompletableFuture.failedFuture(disabledException());
        }

        ImageTiles.requireDivisibleBy8(image);

        int widthSymbols = ImageTiles.widthSymbols(image);
        int heightSymbols = ImageTiles.heightSymbols(image);
        int total = widthSymbols * heightSymbols;

        PlayerHeadSymbol[] symbols = new PlayerHeadSymbol[total];
        CompletableFuture<?>[] uploads = new CompletableFuture<?>[total];
        AtomicInteger done = new AtomicInteger(0);

        for (int y = 0; y < heightSymbols; y++) {
            for (int x = 0; x < widthSymbols; x++) {
                int index = y * widthSymbols + x;
                BufferedImage tile = ImageTiles.tileAt(image, x, y);
                BufferedImage skinCanvas = ImageTiles.toSkinCanvas(tile);

                uploads[index] = upload(namePrefix + "_" + index, skinCanvas)
                    .thenAccept(symbol -> {
                        symbols[index] = symbol;
                        int current = done.incrementAndGet();
                        if (progressListener != null) {
                            progressListener.onProgress(current, total);
                        }
                    });
            }
        }

        return CompletableFuture.allOf(uploads)
            .thenApply(v -> {
                List<PlayerHeadSymbol> list = new ArrayList<>(symbols.length);
                for (PlayerHeadSymbol symbol : symbols) {
                    list.add(symbol);
                }
                return new HeadSpriteImage(widthSymbols, heightSymbols, list);
            });
    }

    public CompletableFuture<PlayerHeadSymbol> generateSymbol(BufferedImage tile, String name) {
        if (!isEnabled()) {
            return CompletableFuture.failedFuture(disabledException());
        }
        if (tile.getWidth() != 8 || tile.getHeight() != 8) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Tile size must be 8x8. Got " + tile.getWidth() + "x" + tile.getHeight())
            );
        }
        return upload(name, ImageTiles.toSkinCanvas(tile));
    }

    private CompletableFuture<PlayerHeadSymbol> upload(String name, BufferedImage image) {
        GenerateRequest request = GenerateRequest.upload(image)
            .name(name)
            .visibility(settings.getVisibility());

        CompletableFuture<PlayerHeadSymbol> future = client.queue().submit(request)
            .thenCompose(queueResponse -> {
                JobInfo job = queueResponse.getJob();
                return job.waitForCompletion(client);
            })
            .thenCompose(jobResponse -> jobResponse.getOrLoadSkin(client))
            .thenApply(MineSkinUploadService::toSymbol);

        int timeoutSeconds = settings.getLimits().getTimeoutSeconds();
        if (timeoutSeconds > 0) {
            return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        }
        return future;
    }

    private static PlayerHeadSymbol toSymbol(SkinInfo skinInfo) {
        String value = skinInfo.texture().data().value();
        String signature = skinInfo.texture().data().signature();
        return TexturePropertyNormalizer.normalize(new PlayerHeadSymbol(value, signature));
    }

    private IllegalStateException disabledException() {
        if (!settings.isEnabled()) {
            return new IllegalStateException("MineSkin is disabled in settings.yml (mineSkin.enabled=false).");
        }
        return new IllegalStateException("MineSkin API key is missing in settings.yml (mineSkin.apiKey).");
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int current, int total);
    }
}
