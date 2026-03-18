package io.yunuservices.features;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.yunuservices.features.core.config.YamlStore;
import io.yunuservices.features.core.image.ImageHash;
import io.yunuservices.features.core.image.ImageTiles;
import io.yunuservices.features.core.mineskin.MineSkinUploadService;
import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import io.yunuservices.features.core.protocol.MotdProtocolSupport;
import io.yunuservices.features.core.protocol.StatusDescriptionLimiter;
import io.yunuservices.features.core.render.HeadSpriteRenderer;
import io.yunuservices.features.core.render.TexturePropertyNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class PaperMotdSupport implements Listener {
    private static final Component SAFE_STATUS_FALLBACK = Component.text("Welcome to the server");

    private final PaperFeaturesPlugin plugin;
    private final YamlStore yamlStore;

    private final Path configPath;
    private final Path imageCachePath;
    private final Path spriteCachePath;

    private volatile PaperMotdConfig config;
    private ImageCache imageCache;
    private SpriteCache spriteCache;
    private MineSkinUploadService mineSkinUploadService;
    private MiniMessage miniMessage;

    private final Map<String, HeadSpriteImage> renderedImages = new LinkedHashMap<>();
    private final Map<String, String> resolvedImageShadowColors = new LinkedHashMap<>();
    private final List<RuntimeMotd> runtimeMotds = new CopyOnWriteArrayList<>();
    private volatile List<RuntimeMotd> spriteRuntimeMotds = List.of();
    private volatile List<RuntimeMotd> textRuntimeMotds = List.of();
    private volatile boolean packetEventsStatusOverrideEnabled;

    PaperMotdSupport(PaperFeaturesPlugin plugin, YamlStore yamlStore) {
        this.plugin = plugin;
        this.yamlStore = yamlStore;
        this.configPath = plugin.getDataPath().resolve("config.yml");
        this.imageCachePath = plugin.getDataPath().resolve("image-cache.yml");
        this.spriteCachePath = plugin.getDataPath().resolve("sprite-cache.yml");
    }

    void bootstrapFiles() throws IOException {
        if (!Files.exists(configPath)) {
            yamlStore.write(configPath, PaperMotdConfig.defaultConfig());
        }
    }

    synchronized void reload(MineSkinUploadService mineSkinUploadService) throws IOException {
        ReloadSnapshot snapshot = snapshotState();
        try {
            this.config = yamlStore.readOrDefault(configPath, PaperMotdConfig.class, PaperMotdConfig::defaultConfig);
            normalizeConfig();
            this.imageCache = yamlStore.readOrDefault(imageCachePath, ImageCache.class, ImageCache::new);
            this.spriteCache = yamlStore.readOrDefault(spriteCachePath, SpriteCache.class, SpriteCache::new);
            normalizeCachedSymbols();
            this.mineSkinUploadService = mineSkinUploadService;

            renderedImages.clear();
            resolvedImageShadowColors.clear();

            this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.builder()
                    .resolver(StandardTags.defaults())
                    .resolver(imageResolver())
                    .resolver(imageRowResolver())
                    .build())
                .build();

            if (!config.isEnabled()) {
                runtimeMotds.clear();
                spriteRuntimeMotds = List.of();
                textRuntimeMotds = List.of();
                logger().info("Paper MOTD is disabled in config.yml (enabled=false). Ping modifications are bypassed.");
                return;
            }

            resolveImages();
            List<RuntimeMotd> builtRuntimeMotds = buildRuntimeMotds();

            yamlStore.write(imageCachePath, imageCache);
            yamlStore.write(spriteCachePath, spriteCache);

            if (builtRuntimeMotds.isEmpty()) {
                builtRuntimeMotds.add(
                    new RuntimeMotd(
                        "default",
                        false,
                        Component.text("Features is loaded."),
                        Component.text("Features is loaded.")
                    )
                );
            }
            runtimeMotds.clear();
            runtimeMotds.addAll(builtRuntimeMotds);
            rebuildMotdBuckets();
        } catch (Exception e) {
            restoreSnapshot(snapshot);
            throw e;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onServerListPing(PaperServerListPingEvent event) {
        if (packetEventsStatusOverrideEnabled) {
            return;
        }
        Component description = resolveDescriptionForProtocol(event.getProtocolVersion());
        if (description == null) {
            return;
        }
        event.motd(description);
    }

    boolean isEnabled() {
        PaperMotdConfig currentConfig = config;
        return currentConfig != null && currentConfig.isEnabled();
    }

    void setPacketEventsStatusOverrideEnabled(boolean enabled) {
        this.packetEventsStatusOverrideEnabled = enabled;
    }

    Component resolveDescriptionForProtocol(int protocolVersion) {
        PaperMotdConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.isEnabled()) {
            return null;
        }

        boolean supportsSpriteMotd = MotdProtocolSupport.supportsSpriteMotd(
            protocolVersion,
            currentConfig.getProtocolRange().getMin(),
            currentConfig.getProtocolRange().getMax()
        );
        RuntimeMotd motd = chooseMotd(supportsSpriteMotd);
        if (motd == null) {
            return null;
        }
        return motd.resolveDescription(supportsSpriteMotd, SAFE_STATUS_FALLBACK);
    }

    private Logger logger() {
        return plugin.getLogger();
    }

    private TagResolver imageResolver() {
        return TagResolver.resolver("image", (ArgumentQueue queue, net.kyori.adventure.text.minimessage.Context ctx) -> {
            String id = queue.popOr("missing image id").value().toLowerCase(Locale.ROOT);
            HeadSpriteImage image = renderedImages.get(id);
            if (image == null || !image.isValidGrid()) {
                return Tag.inserting(Component.text("<missing-image:" + id + ">"));
            }
            return Tag.inserting(HeadSpriteRenderer.renderImage(image));
        });
    }

    private TagResolver imageRowResolver() {
        return TagResolver.builder()
            .resolver(TagResolver.resolver("image_row", (ArgumentQueue queue, net.kyori.adventure.text.minimessage.Context ctx) ->
                resolveImageRowTag(queue)))
            .resolver(TagResolver.resolver("image-row", (ArgumentQueue queue, net.kyori.adventure.text.minimessage.Context ctx) ->
                resolveImageRowTag(queue)))
            .build();
    }

    private Tag resolveImageRowTag(ArgumentQueue queue) {
        if (config == null || !config.isRowSystemEnabled()) {
            return Tag.inserting(Component.empty());
        }

        String id = queue.popOr("missing image id").value().toLowerCase(Locale.ROOT);
        String rowText = queue.popOr("missing row index").value();

        int rowIndex;
        try {
            rowIndex = Integer.parseInt(rowText);
        } catch (NumberFormatException ex) {
            return Tag.inserting(Component.text("<invalid-image-row:" + id + ":" + rowText + ">"));
        }

        HeadSpriteImage image = renderedImages.get(id);
        if (image == null || !image.isValidGrid()) {
            return Tag.inserting(Component.text("<missing-image:" + id + ">"));
        }
        if (rowIndex < 0 || rowIndex >= image.getHeightSymbols()) {
            return Tag.inserting(Component.text("<invalid-image-row:" + id + ":" + rowIndex + ">"));
        }

        return Tag.inserting(HeadSpriteRenderer.renderImageRow(image, rowIndex));
    }

    private void resolveImages() {
        Map<String, PaperMotdConfig.ImageEntry> configuredImages = config.getImages();
        if (configuredImages == null || configuredImages.isEmpty()) {
            return;
        }

        for (Map.Entry<String, PaperMotdConfig.ImageEntry> entry : configuredImages.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            PaperMotdConfig.ImageEntry imageEntry = entry.getValue();
            if (imageEntry == null || imageEntry.getFile() == null || imageEntry.getFile().isBlank()) {
                logger().warning("Image '" + id + "' has an empty file path, skipping.");
                continue;
            }

            Path file;
            try {
                file = resolveDataFile(imageEntry.getFile());
            } catch (IOException e) {
                logger().warning("Image '" + id + "' has an invalid file path '" + imageEntry.getFile() + "': " + e.getMessage());
                continue;
            }

            if (!Files.exists(file)) {
                logger().warning("Image file missing for '" + id + "': " + file);
                continue;
            }

            try {
                BufferedImage source = null;
                String explicitImageShadow = normalizeShadowColorHex(imageEntry.getShadowColor(), "image '" + id + "'");
                if (explicitImageShadow != null) {
                    resolvedImageShadowColors.put(id, explicitImageShadow);
                }
                double brightness = resolveBrightness(imageEntry, id);
                double saturation = resolveSaturation(imageEntry, id);

                String sha = imageCacheKey(file, brightness, saturation);
                CachedImage cached = imageCache.getImages().get(id);
                if (cached != null
                    && Objects.equals(cached.getSha256(), sha)
                    && cached.getRendered() != null
                    && cached.getRendered().isValidGrid()) {
                    if (explicitImageShadow == null) {
                        source = ImageTiles.readFile(file);
                        ImageTiles.requireDivisibleBy8(source);
                        String autoShadow = detectSeamShadowColor(source);
                        if (autoShadow != null) {
                            resolvedImageShadowColors.put(id, autoShadow);
                        }
                    }
                    renderedImages.put(id, cached.getRendered());
                    logger().info("Image '" + id + "' cache hit (image-cache.yml).");
                    continue;
                }

                if (source == null) {
                    source = ImageTiles.readFile(file);
                    ImageTiles.requireDivisibleBy8(source);
                }

                if (explicitImageShadow == null) {
                    String autoShadow = detectSeamShadowColor(source);
                    if (autoShadow != null) {
                        resolvedImageShadowColors.put(id, autoShadow);
                    }
                }

                BufferedImage prepared = applyImageColorCompensation(id, source, brightness, saturation);
                HeadSpriteImage generated = buildFromSpriteCacheOrMineSkin(id, prepared);
                if (generated == null) {
                    continue;
                }

                renderedImages.put(id, generated);
                imageCache.getImages().put(id, new CachedImage(sha, generated));
            } catch (Exception e) {
                logger().severe("Failed to process image '" + id + "': " + e.getMessage());
            }
        }
    }

    private String imageCacheKey(Path file, double brightness, double saturation) throws IOException {
        String base = ImageHash.sha256(file);
        if (isNeutralCompensation(brightness) && isNeutralCompensation(saturation)) {
            return base;
        }
        return base
            + "|b="
            + formatCompensation(brightness)
            + "|s="
            + formatCompensation(saturation)
            + "|pp=1";
    }

    private double resolveBrightness(PaperMotdConfig.ImageEntry entry, String imageId) {
        return resolveCompensation(entry.getBrightness(), "brightness", imageId);
    }

    private double resolveSaturation(PaperMotdConfig.ImageEntry entry, String imageId) {
        return resolveCompensation(entry.getSaturation(), "saturation", imageId);
    }

    private double resolveCompensation(Double value, String key, String imageId) {
        if (value == null) {
            return 1.0D;
        }
        if (value.isNaN() || value.isInfinite()) {
            logger().warning("Invalid " + key + " '" + value + "' for image '" + imageId + "'. Using 1.0.");
            return 1.0D;
        }
        if (value < 0.0D || value > 3.0D) {
            logger().warning(
                "Out-of-range " + key + " '" + value + "' for image '" + imageId + "'. Expected 0.0-3.0. Clamping."
            );
        }
        return clamp(value, 0.0D, 3.0D);
    }

    private boolean isNeutralCompensation(double value) {
        return Math.abs(value - 1.0D) < 0.000001D;
    }

    private String formatCompensation(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private BufferedImage applyImageColorCompensation(
        String imageId,
        BufferedImage source,
        double brightness,
        double saturation
    ) {
        if (isNeutralCompensation(brightness) && isNeutralCompensation(saturation)) {
            return source;
        }

        BufferedImage adjusted = new BufferedImage(
            source.getWidth(),
            source.getHeight(),
            BufferedImage.TYPE_INT_ARGB
        );

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    adjusted.setRGB(x, y, 0);
                    continue;
                }

                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;

                float[] hsb = Color.RGBtoHSB(red, green, blue, null);
                hsb[1] = (float) clamp(hsb[1] * saturation, 0.0D, 1.0D);
                int compensated = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                int compensatedRed = clampColor((int) Math.round(((compensated >>> 16) & 0xFF) * brightness));
                int compensatedGreen = clampColor((int) Math.round(((compensated >>> 8) & 0xFF) * brightness));
                int compensatedBlue = clampColor((int) Math.round((compensated & 0xFF) * brightness));
                adjusted.setRGB(
                    x,
                    y,
                    (alpha << 24)
                        | (compensatedRed << 16)
                        | (compensatedGreen << 8)
                        | compensatedBlue
                );
            }
        }

        logger().info(
            "Applied color compensation for image '"
                + imageId
                + "': brightness="
                + formatCompensation(brightness)
                + ", saturation="
                + formatCompensation(saturation)
                + "."
        );
        return adjusted;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private HeadSpriteImage buildFromSpriteCacheOrMineSkin(String id, BufferedImage source) throws IOException {
        int widthSymbols = ImageTiles.widthSymbols(source);
        int heightSymbols = ImageTiles.heightSymbols(source);
        int total = widthSymbols * heightSymbols;

        PlayerHeadSymbol[] symbols = new PlayerHeadSymbol[total];
        Map<String, TileRequest> missingByHash = new LinkedHashMap<>();
        int missingSymbols = 0;

        for (int y = 0; y < heightSymbols; y++) {
            for (int x = 0; x < widthSymbols; x++) {
                int index = y * widthSymbols + x;
                BufferedImage tile = copyTile(ImageTiles.tileAt(source, x, y));
                String tileHash = ImageHash.sha256(tile);
                PlayerHeadSymbol cached = spriteCache.getSymbols().get(tileHash);
                if (cached != null) {
                    symbols[index] = cached;
                } else {
                    missingSymbols++;
                    TileRequest request = missingByHash.get(tileHash);
                    if (request == null) {
                        List<Integer> indices = new ArrayList<>();
                        indices.add(index);
                        missingByHash.put(tileHash, new TileRequest(indices, tileHash, tile));
                    } else {
                        request.indices().add(index);
                    }
                }
            }
        }

        List<TileRequest> missing = new ArrayList<>(missingByHash.values());

        if (missing.isEmpty()) {
            logger().info("Image '" + id + "' cache hit (sprite-cache.yml, " + total + " symbols).");
        } else {
            int maxUncachedSymbolsPerImage = config == null ? 128 : config.getMaxUncachedSymbolsPerImage();
            if (maxUncachedSymbolsPerImage > 0 && missing.size() > maxUncachedSymbolsPerImage) {
                logger().warning(
                    "Image '"
                        + id
                        + "' requires "
                        + missing.size()
                        + " unique uncached symbols ("
                        + missingSymbols
                        + " total uncached tiles), which exceeds maxUncachedSymbolsPerImage="
                        + maxUncachedSymbolsPerImage
                        + ". Skipping image."
                );
                return null;
            }

            if (mineSkinUploadService == null || !mineSkinUploadService.isEnabled()) {
                String reason = plugin.getSettings().getMineSkin().isEnabled()
                    ? "MineSkin API key missing"
                    : "MineSkin disabled in settings.yml";
                logger().warning(
                    reason
                        + " and '"
                        + id
                        + "' requires "
                        + missing.size()
                        + " unique uncached symbols ("
                        + missingSymbols
                        + " total uncached tiles). Skipping image."
                );
                return null;
            }

            logger().info(
                "Image '"
                    + id
                    + "' has "
                    + missing.size()
                    + " unique uncached symbols ("
                    + missingSymbols
                    + " total uncached tiles). Generating unique symbols only."
            );

            AtomicInteger done = new AtomicInteger(0);
            CompletableFuture<?>[] uploads = new CompletableFuture<?>[missing.size()];

            for (int i = 0; i < missing.size(); i++) {
                TileRequest request = missing.get(i);
                int representativeIndex = request.indices().getFirst();
                uploads[i] = mineSkinUploadService
                    .generateSymbol(request.tile(), "motd_" + id + "_" + representativeIndex)
                    .thenAccept(symbol -> {
                        for (int index : request.indices()) {
                            symbols[index] = symbol;
                        }
                        spriteCache.getSymbols().put(request.hash(), symbol);
                        int current = done.incrementAndGet();
                        logger().info(
                            "[" + id + "] generated unique uncached symbol " + current + "/" + missing.size()
                                + " (covers " + request.indices().size() + " tiles)"
                        );
                    });
            }

            CompletableFuture.allOf(uploads).join();
        }

        List<PlayerHeadSymbol> list = new ArrayList<>(Arrays.asList(symbols));
        if (list.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Image '" + id + "' symbol generation incomplete.");
        }

        return new HeadSpriteImage(widthSymbols, heightSymbols, list);
    }

    private BufferedImage copyTile(BufferedImage tile) {
        BufferedImage copy = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(tile, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private List<RuntimeMotd> buildRuntimeMotds() {
        List<RuntimeMotd> built = new ArrayList<>();
        if (config.getMotds() == null) {
            return built;
        }

        for (Map.Entry<String, PaperMotdConfig.MotdEntry> entry : config.getMotds().entrySet()) {
            String id = entry.getKey();
            PaperMotdConfig.MotdEntry motd = entry.getValue();
            if (motd == null) {
                continue;
            }

            String imageId = null;
            Component imageDescription = null;
            Component textDescription = null;
            Component fallbackDescription = parseFallback(motd.getFallbackDescription());
            boolean descriptionUsesSpriteTags = containsSpriteTags(motd.getDescription());

            if (motd.getImage() != null && !motd.getImage().isBlank()) {
                imageId = motd.getImage().toLowerCase(Locale.ROOT);
                HeadSpriteImage image = renderedImages.get(imageId);
                if (image != null && image.isValidGrid()) {
                    String effectiveShadowColor = resolveEffectiveShadowColor(id, motd, imageId, true);
                    ShadowColor shadowColor = resolveShadowColor(effectiveShadowColor, id, true);
                    imageDescription = HeadSpriteRenderer.renderImage(image, shadowColor);
                } else {
                    logger().warning("MOTD '" + id + "' references missing/invalid image '" + motd.getImage() + "'.");
                }
            }

            if (motd.getDescription() != null && !motd.getDescription().isEmpty()) {
                textDescription = parseLines(motd.getDescription());
            }

            if (imageDescription != null && !descriptionUsesSpriteTags) {
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : (textDescription != null ? textDescription : Component.text("Welcome to the server"));
                built.add(new RuntimeMotd(id, true, imageDescription, fallback));
                continue;
            }

            if (textDescription != null) {
                boolean spriteBased = descriptionUsesSpriteTags;
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : (spriteBased ? Component.text("Welcome to the server") : textDescription);
                built.add(new RuntimeMotd(id, spriteBased, textDescription, fallback));
                continue;
            }

            if (imageDescription != null) {
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : Component.text("Welcome to the server");
                built.add(new RuntimeMotd(id, true, imageDescription, fallback));
                continue;
            }

            if (fallbackDescription != null) {
                built.add(new RuntimeMotd(id, false, fallbackDescription, fallbackDescription));
                continue;
            }

            logger().warning("MOTD '" + id + "' has no valid image/description/fallback and was skipped.");
        }
        return built;
    }

    private boolean containsSpriteTags(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        boolean rowSystemEnabled = config != null && config.isRowSystemEnabled();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("<image:")) {
                return true;
            }
            if (rowSystemEnabled && (lower.contains("<image_row:") || lower.contains("<image-row:"))) {
                return true;
            }
        }
        return false;
    }

    private String resolveEffectiveShadowColor(
        String motdId,
        PaperMotdConfig.MotdEntry motd,
        String imageId,
        boolean spriteBased
    ) {
        if (!spriteBased) {
            return null;
        }

        String motdShadow = normalizeShadowColorHex(motd.getShadowColor(), "MOTD '" + motdId + "'");
        if (motdShadow != null) {
            return motdShadow;
        }

        if (imageId == null || imageId.isBlank()) {
            return null;
        }

        return resolvedImageShadowColors.get(imageId);
    }

    private ShadowColor resolveShadowColor(String configuredValue, String motdId, boolean spriteBased) {
        String value = configuredValue == null ? "" : configuredValue.trim();
        if (value.isEmpty()) {
            return spriteBased ? ShadowColor.none() : null;
        }

        String hex = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (hex.length() == 6) {
                int rgb = Integer.parseInt(hex, 16);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                return ShadowColor.shadowColor(red, green, blue, 255);
            }
            if (hex.length() == 8) {
                int rgba = (int) Long.parseLong(hex, 16);
                int red = (rgba >> 24) & 0xFF;
                int green = (rgba >> 16) & 0xFF;
                int blue = (rgba >> 8) & 0xFF;
                int alpha = rgba & 0xFF;
                if (alpha == 0) {
                    return ShadowColor.none();
                }
                return ShadowColor.shadowColor(red, green, blue, alpha);
            }
        } catch (NumberFormatException ignored) {
        }

        logger().warning(
            "Invalid shadow_color '" + configuredValue + "' for MOTD '" + motdId + "'. Use #RRGGBB or #RRGGBBAA."
        );
        return spriteBased ? ShadowColor.none() : null;
    }

    private String normalizeShadowColorHex(String configuredValue, String context) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return null;
        }

        String hex = configuredValue.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        try {
            if (hex.length() == 6) {
                Integer.parseInt(hex, 16);
                return "#" + hex.toUpperCase(Locale.ROOT) + "FF";
            }
            if (hex.length() == 8) {
                Long.parseLong(hex, 16);
                return "#" + hex.toUpperCase(Locale.ROOT);
            }
        } catch (NumberFormatException ignored) {
        }

        logger().warning(
            "Invalid shadow_color '" + configuredValue + "' for " + context + ". Expected #RRGGBB or #RRGGBBAA."
        );
        return null;
    }

    private String detectSeamShadowColor(BufferedImage source) {
        int heightSymbols = ImageTiles.heightSymbols(source);
        if (heightSymbols < 2) {
            return null;
        }

        long weightedRed = 0L;
        long weightedGreen = 0L;
        long weightedBlue = 0L;
        long totalAlphaWeight = 0L;
        long alphaSum = 0L;
        long alphaCount = 0L;

        for (int seam = 1; seam < heightSymbols; seam++) {
            int topY = seam * 8 - 1;
            int bottomY = seam * 8;

            for (int x = 0; x < source.getWidth(); x++) {
                int topArgb = source.getRGB(x, topY);
                int topAlpha = (topArgb >>> 24) & 0xFF;
                if (topAlpha > 0) {
                    weightedRed += (long) ((topArgb >>> 16) & 0xFF) * topAlpha;
                    weightedGreen += (long) ((topArgb >>> 8) & 0xFF) * topAlpha;
                    weightedBlue += (long) (topArgb & 0xFF) * topAlpha;
                    totalAlphaWeight += topAlpha;
                    alphaSum += topAlpha;
                    alphaCount++;
                }

                int bottomArgb = source.getRGB(x, bottomY);
                int bottomAlpha = (bottomArgb >>> 24) & 0xFF;
                if (bottomAlpha > 0) {
                    weightedRed += (long) ((bottomArgb >>> 16) & 0xFF) * bottomAlpha;
                    weightedGreen += (long) ((bottomArgb >>> 8) & 0xFF) * bottomAlpha;
                    weightedBlue += (long) (bottomArgb & 0xFF) * bottomAlpha;
                    totalAlphaWeight += bottomAlpha;
                    alphaSum += bottomAlpha;
                    alphaCount++;
                }
            }
        }

        if (totalAlphaWeight <= 0 || alphaCount <= 0) {
            return "#00000000";
        }

        int red = clampColor((int) Math.round((double) weightedRed / totalAlphaWeight));
        int green = clampColor((int) Math.round((double) weightedGreen / totalAlphaWeight));
        int blue = clampColor((int) Math.round((double) weightedBlue / totalAlphaWeight));
        int alpha = clampColor((int) Math.round((double) alphaSum / alphaCount));

        return String.format(Locale.ROOT, "#%02X%02X%02X%02X", red, green, blue, alpha);
    }

    private int clampColor(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private Path resolveDataFile(String configuredPath) throws IOException {
        try {
            Path root = plugin.getDataPath().toAbsolutePath().normalize();
            Path candidate = root.resolve(configuredPath).normalize();
            if (!candidate.startsWith(root)) {
                throw new IOException("Path escapes plugin data directory");
            }
            return candidate;
        } catch (InvalidPathException e) {
            throw new IOException("Invalid path", e);
        }
    }

    private Component parseLines(List<String> lines) {
        List<Component> parsed = new ArrayList<>();
        for (String line : lines) {
            parsed.add(miniMessage.deserialize(line == null ? "" : line));
        }

        Component result = Component.empty();
        for (int i = 0; i < parsed.size(); i++) {
            if (i > 0) {
                result = result.appendNewline();
            }
            result = result.append(parsed.get(i));
        }
        return result;
    }

    private Component parseFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return null;
        }
        return miniMessage.deserialize(fallback);
    }

    private void normalizeConfig() {
        PaperMotdConfig.ProtocolRange protocolRange = config.getProtocolRange();
        int normalizedMin = MotdProtocolSupport.normalizeMinProtocol(protocolRange.getMin(), protocolRange.getMax());
        int normalizedMax = MotdProtocolSupport.normalizeMaxProtocol(protocolRange.getMin(), protocolRange.getMax());
        protocolRange.setMin(normalizedMin);
        protocolRange.setMax(normalizedMax);
        if (config.getImages() == null) {
            config.setImages(new LinkedHashMap<>());
        }
        if (config.getMotds() == null) {
            config.setMotds(new LinkedHashMap<>());
        } else {
            int migratedShadowOverrides = 0;
            for (PaperMotdConfig.MotdEntry motd : config.getMotds().values()) {
                if (motd == null || motd.getImage() == null || motd.getImage().isBlank()) {
                    continue;
                }
                String motdShadow = normalizeShadowColorHex(motd.getShadowColor(), "MOTD image shadow migration");
                if (!"#00000000".equals(motdShadow)) {
                    continue;
                }

                PaperMotdConfig.ImageEntry imageEntry = findImageEntry(motd.getImage());
                String imageShadow = imageEntry == null ? null : normalizeShadowColorHex(
                    imageEntry.getShadowColor(),
                    "image '" + motd.getImage() + "'"
                );
                if (imageShadow == null) {
                    motd.setShadowColor(null);
                    migratedShadowOverrides++;
                }
            }
            if (migratedShadowOverrides > 0) {
                logger().info(
                    "Migrated " + migratedShadowOverrides + " MOTD shadow_color overrides (#00000000) to auto image seam shadow."
                );
            }
        }
    }

    private PaperMotdConfig.ImageEntry findImageEntry(String imageId) {
        if (imageId == null || imageId.isBlank() || config.getImages() == null) {
            return null;
        }

        PaperMotdConfig.ImageEntry direct = config.getImages().get(imageId);
        if (direct != null) {
            return direct;
        }

        String lookup = imageId.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, PaperMotdConfig.ImageEntry> entry : config.getImages().entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(lookup)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void normalizeCachedSymbols() {
        int normalizedImageSymbols = 0;
        int normalizedSpriteSymbols = 0;

        if (imageCache != null && imageCache.getImages() != null) {
            for (CachedImage cachedImage : imageCache.getImages().values()) {
                if (cachedImage == null || cachedImage.getRendered() == null) {
                    continue;
                }
                List<PlayerHeadSymbol> symbols = cachedImage.getRendered().getSymbols();
                for (int i = 0; i < symbols.size(); i++) {
                    PlayerHeadSymbol current = symbols.get(i);
                    PlayerHeadSymbol normalized = TexturePropertyNormalizer.normalize(current);
                    if (normalized != null && !normalized.equals(current)) {
                        symbols.set(i, normalized);
                        normalizedImageSymbols++;
                    }
                }
            }
        }

        if (spriteCache != null && spriteCache.getSymbols() != null) {
            for (Map.Entry<String, PlayerHeadSymbol> entry : spriteCache.getSymbols().entrySet()) {
                PlayerHeadSymbol current = entry.getValue();
                PlayerHeadSymbol normalized = TexturePropertyNormalizer.normalize(current);
                if (normalized != null && !normalized.equals(current)) {
                    entry.setValue(normalized);
                    normalizedSpriteSymbols++;
                }
            }
        }

        if (normalizedImageSymbols > 0 || normalizedSpriteSymbols > 0) {
            logger().info(
                "Normalized cached MOTD head properties (image-cache symbols: "
                    + normalizedImageSymbols
                    + ", sprite-cache symbols: "
                    + normalizedSpriteSymbols
                    + ")."
            );
        }
    }

    private RuntimeMotd chooseMotd(boolean supportsSpriteMotd) {
        if (runtimeMotds.isEmpty()) {
            return null;
        }

        List<RuntimeMotd> spriteMotds = spriteRuntimeMotds;
        List<RuntimeMotd> textMotds = textRuntimeMotds;

        if (supportsSpriteMotd && !spriteMotds.isEmpty()) {
            return spriteMotds.get(ThreadLocalRandom.current().nextInt(spriteMotds.size()));
        }

        if (!supportsSpriteMotd && !textMotds.isEmpty()) {
            return textMotds.get(ThreadLocalRandom.current().nextInt(textMotds.size()));
        }

        if (!spriteMotds.isEmpty()) {
            return spriteMotds.get(ThreadLocalRandom.current().nextInt(spriteMotds.size()));
        }

        if (!textMotds.isEmpty()) {
            return textMotds.get(ThreadLocalRandom.current().nextInt(textMotds.size()));
        }

        return runtimeMotds.get(ThreadLocalRandom.current().nextInt(runtimeMotds.size()));
    }

    private void rebuildMotdBuckets() {
        ArrayList<RuntimeMotd> sprite = new ArrayList<>();
        ArrayList<RuntimeMotd> text = new ArrayList<>();
        for (RuntimeMotd motd : runtimeMotds) {
            if (motd.spriteBased()) {
                sprite.add(motd);
            } else {
                text.add(motd);
            }
        }
        this.spriteRuntimeMotds = List.copyOf(sprite);
        this.textRuntimeMotds = List.copyOf(text);
    }

    private ReloadSnapshot snapshotState() {
        return new ReloadSnapshot(
            config,
            imageCache,
            spriteCache,
            mineSkinUploadService,
            miniMessage,
            new LinkedHashMap<>(renderedImages),
            new LinkedHashMap<>(resolvedImageShadowColors),
            new ArrayList<>(runtimeMotds)
        );
    }

    private void restoreSnapshot(ReloadSnapshot snapshot) {
        this.config = snapshot.config();
        this.imageCache = snapshot.imageCache();
        this.spriteCache = snapshot.spriteCache();
        this.mineSkinUploadService = snapshot.mineSkinUploadService();
        this.miniMessage = snapshot.miniMessage();

        renderedImages.clear();
        renderedImages.putAll(snapshot.renderedImages());

        resolvedImageShadowColors.clear();
        resolvedImageShadowColors.putAll(snapshot.resolvedImageShadowColors());

        runtimeMotds.clear();
        runtimeMotds.addAll(snapshot.runtimeMotds());
        rebuildMotdBuckets();
    }

    static final class PaperMotdConfig {
        private boolean enabled;
        @JsonProperty("rowSystemEnabled")
        @JsonAlias({"rowEnabled", "imageRowEnabled", "imageRowTagsEnabled"})
        private boolean rowSystemEnabled = true;
        private ProtocolRange protocolRange = ProtocolRange.defaultRange();
        @JsonProperty("maxUncachedSymbolsPerImage")
        @JsonAlias({"maxSymbolsPerImage", "maxGenerateSymbolsPerImage"})
        private int maxUncachedSymbolsPerImage = 128;
        private Map<String, ImageEntry> images = new LinkedHashMap<>();
        private Map<String, MotdEntry> motds = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRowSystemEnabled() {
            return rowSystemEnabled;
        }

        public void setRowSystemEnabled(boolean rowSystemEnabled) {
            this.rowSystemEnabled = rowSystemEnabled;
        }

        public ProtocolRange getProtocolRange() {
            if (protocolRange == null) {
                protocolRange = ProtocolRange.defaultRange();
            }
            return protocolRange;
        }

        public void setProtocolRange(ProtocolRange protocolRange) {
            this.protocolRange = protocolRange == null ? ProtocolRange.defaultRange() : protocolRange;
        }

        @JsonProperty("minSpriteProtocol")
        private void setLegacyMinSpriteProtocol(int minSpriteProtocol) {
            getProtocolRange().setMin(minSpriteProtocol);
        }

        @JsonProperty("maxSpriteProtocol")
        private void setLegacyMaxSpriteProtocol(int maxSpriteProtocol) {
            getProtocolRange().setMax(maxSpriteProtocol);
        }

        public int getMaxUncachedSymbolsPerImage() {
            return Math.max(1, Math.min(256, maxUncachedSymbolsPerImage));
        }

        public void setMaxUncachedSymbolsPerImage(int maxUncachedSymbolsPerImage) {
            this.maxUncachedSymbolsPerImage = maxUncachedSymbolsPerImage;
        }

        public Map<String, ImageEntry> getImages() {
            return images;
        }

        public void setImages(Map<String, ImageEntry> images) {
            this.images = images;
        }

        public Map<String, MotdEntry> getMotds() {
            return motds;
        }

        public void setMotds(Map<String, MotdEntry> motds) {
            this.motds = motds;
        }

        static PaperMotdConfig defaultConfig() {
            PaperMotdConfig cfg = new PaperMotdConfig();
            cfg.setEnabled(false);
            cfg.setRowSystemEnabled(true);
            cfg.setProtocolRange(ProtocolRange.defaultRange());
            cfg.setMaxUncachedSymbolsPerImage(128);

            ImageEntry sampleImage = new ImageEntry();
            sampleImage.setFile("images/paper_motd.png");
            cfg.images.put("paper_motd", sampleImage);

            MotdEntry sampleMotd = new MotdEntry();
            sampleMotd.setImage("paper_motd");
            sampleMotd.setFallbackDescription("Welcome to the server");
            cfg.motds.put("default", sampleMotd);

            return cfg;
        }

        static final class ProtocolRange {
            private int min = MotdProtocolSupport.DEFAULT_MIN_SPRITE_PROTOCOL;
            private int max = MotdProtocolSupport.DEFAULT_MAX_SPRITE_PROTOCOL;

            static ProtocolRange defaultRange() {
                return new ProtocolRange();
            }

            public int getMin() {
                return min;
            }

            public void setMin(int min) {
                this.min = min;
            }

            public int getMax() {
                return max;
            }

            public void setMax(int max) {
                this.max = max;
            }
        }

        static final class ImageEntry {
            private String file;
            @JsonProperty("shadow_color")
            @JsonAlias({"shadowColor"})
            private String shadowColor;
            private Double brightness;
            private Double saturation;

            public String getFile() {
                return file;
            }

            public void setFile(String file) {
                this.file = file;
            }

            public String getShadowColor() {
                return shadowColor;
            }

            public void setShadowColor(String shadowColor) {
                this.shadowColor = shadowColor;
            }

            public Double getBrightness() {
                return brightness;
            }

            public void setBrightness(Double brightness) {
                this.brightness = brightness;
            }

            public Double getSaturation() {
                return saturation;
            }

            public void setSaturation(Double saturation) {
                this.saturation = saturation;
            }
        }

        static final class MotdEntry {
            private String image;
            private List<String> description = new ArrayList<>();
            private String fallbackDescription;
            @JsonProperty("shadow_color")
            @JsonAlias({"shadowColor"})
            private String shadowColor;

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
            }

            public List<String> getDescription() {
                return description;
            }

            public void setDescription(List<String> description) {
                this.description = description;
            }

            public String getFallbackDescription() {
                return fallbackDescription;
            }

            public void setFallbackDescription(String fallbackDescription) {
                this.fallbackDescription = fallbackDescription;
            }

            public String getShadowColor() {
                return shadowColor;
            }

            public void setShadowColor(String shadowColor) {
                this.shadowColor = shadowColor;
            }
        }
    }

    static final class ImageCache {
        private Map<String, CachedImage> images = new LinkedHashMap<>();

        public Map<String, CachedImage> getImages() {
            return images;
        }

        public void setImages(Map<String, CachedImage> images) {
            this.images = images;
        }
    }

    static final class SpriteCache {
        private Map<String, PlayerHeadSymbol> symbols = new ConcurrentHashMap<>();

        public Map<String, PlayerHeadSymbol> getSymbols() {
            return symbols;
        }

        public void setSymbols(Map<String, PlayerHeadSymbol> symbols) {
            this.symbols = symbols;
        }
    }

    static final class CachedImage {
        private String sha256;
        private HeadSpriteImage rendered;

        public CachedImage() {
        }

        CachedImage(String sha256, HeadSpriteImage rendered) {
            this.sha256 = sha256;
            this.rendered = rendered;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public HeadSpriteImage getRendered() {
            return rendered;
        }

        public void setRendered(HeadSpriteImage rendered) {
            this.rendered = rendered;
        }
    }

    private record ReloadSnapshot(
        PaperMotdConfig config,
        ImageCache imageCache,
        SpriteCache spriteCache,
        MineSkinUploadService mineSkinUploadService,
        MiniMessage miniMessage,
        Map<String, HeadSpriteImage> renderedImages,
        Map<String, String> resolvedImageShadowColors,
        List<RuntimeMotd> runtimeMotds
    ) {
    }

    private record TileRequest(List<Integer> indices, String hash, BufferedImage tile) {
    }

    private record RuntimeMotd(String id, boolean spriteBased, Component spriteDescription, Component fallbackDescription) {
        Component resolveDescription(boolean supportsSpriteMotd, Component defaultDescription) {
            Component preferred = supportsSpriteMotd && spriteBased && spriteDescription != null
                ? spriteDescription
                : (fallbackDescription != null
                    ? fallbackDescription
                    : (spriteDescription != null ? spriteDescription : defaultDescription));
            Component fallback = fallbackDescription != null ? fallbackDescription : defaultDescription;
            return StatusDescriptionLimiter.chooseSafeDescription(preferred, fallback, defaultDescription);
        }
    }
}
