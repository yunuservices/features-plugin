package io.yunuservices.features;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.yunuservices.features.core.config.YamlStore;
import io.yunuservices.features.core.image.ImageHash;
import io.yunuservices.features.core.image.ImageTiles;
import io.yunuservices.features.core.mineskin.MineSkinSettings;
import io.yunuservices.features.core.mineskin.MineSkinUploadService;
import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import io.yunuservices.features.core.protocol.MotdProtocolSupport;
import io.yunuservices.features.core.render.HeadSpriteRenderer;
import io.yunuservices.features.core.render.TexturePropertyNormalizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.slf4j.Logger;

import java.awt.Graphics2D;
import java.awt.Color;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
    id = "features",
    name = "features",
    version = "1.0.0",
    authors = {"yunuservices"}
)
public final class VelocityFeaturesPlugin {
    private static final String RELOAD_PERMISSION = "features.admin";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final YamlStore yamlStore = new YamlStore();
    private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(new ReloadThreadFactory());

    private Path settingsPath;
    private Path configPath;
    private Path imageCachePath;
    private Path spriteCachePath;

    private volatile VelocitySettings settings;
    private volatile VelocityConfig config;
    private volatile ImageCache imageCache;
    private volatile SpriteCache spriteCache;

    private volatile MineSkinUploadService mineSkinUploadService;
    private volatile MiniMessage miniMessage;

    private final Map<String, HeadSpriteImage> renderedImages = new LinkedHashMap<>();
    private final Map<String, String> resolvedImageShadowColors = new LinkedHashMap<>();
    private final List<RuntimeMotd> runtimeMotds = new CopyOnWriteArrayList<>();
    private volatile List<RuntimeMotd> spriteRuntimeMotds = List.of();
    private volatile List<RuntimeMotd> textRuntimeMotds = List.of();

    @Inject
    public VelocityFeaturesPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            bootstrapFiles();
            registerCommands();
            reloadInProgress.set(true);
            reloadExecutor.execute(() -> {
                try {
                    reloadInternal();
                } catch (Exception e) {
                    logger.error("Failed to initialize features runtime state. The plugin will stay loaded but MOTD rendering is bypassed until reload succeeds.", e);
                } finally {
                    reloadInProgress.set(false);
                }
            });
            logger.info("features enabled.");
        } catch (Exception e) {
            logger.error("Failed to initialize Features velocity module", e);
            throw new IllegalStateException("Failed to initialize Features velocity module", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        reloadExecutor.shutdownNow();
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (config == null || !config.isEnabled()) {
            return;
        }

        ProtocolVersion protocolVersion = event.getConnection().getProtocolVersion();
        boolean supportsSpriteMotd = supportsSpriteMotd(protocolVersion);
        RuntimeMotd motd = chooseMotd(supportsSpriteMotd);
        if (motd == null) {
            return;
        }

        ServerPing.Builder pingBuilder = event.getPing().asBuilder()
            .description(motd.resolveDescription(supportsSpriteMotd));

        event.setPing(pingBuilder.build());
    }

    private void registerCommands() {
        CommandManager manager = server.getCommandManager();
        CommandMeta meta = manager.metaBuilder("features")
            .plugin(this)
            .build();

        manager.register(meta, new FeaturesCommand());
    }

    private void bootstrapFiles() throws IOException {
        Files.createDirectories(dataDirectory);

        this.settingsPath = dataDirectory.resolve("settings.yml");
        this.configPath = dataDirectory.resolve("config.yml");
        this.imageCachePath = dataDirectory.resolve("image-cache.yml");
        this.spriteCachePath = dataDirectory.resolve("sprite-cache.yml");

        if (!Files.exists(configPath)) {
            VelocityConfig defaults = VelocityConfig.defaultConfig();
            yamlStore.write(configPath, defaults);
        }
    }

    public synchronized void reloadInternal() throws IOException {
        ReloadSnapshot snapshot = snapshotState();
        try {
            this.settings = yamlStore.readOrDefault(settingsPath, VelocitySettings.class, VelocitySettings::new);
            this.config = yamlStore.readOrDefault(configPath, VelocityConfig.class, VelocityConfig::defaultConfig);
            normalizeConfig();
            this.imageCache = yamlStore.readOrDefault(imageCachePath, ImageCache.class, ImageCache::new);
            this.spriteCache = yamlStore.readOrDefault(spriteCachePath, SpriteCache.class, SpriteCache::new);
            normalizeCachedSymbols();

            this.mineSkinUploadService = new MineSkinUploadService(settings.getMineSkin(), "Features/Velocity-1.0.0");
            this.renderedImages.clear();
            this.resolvedImageShadowColors.clear();

            this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.builder()
                    .resolver(StandardTags.defaults())
                    .resolver(imageResolver())
                    .resolver(imageRowResolver())
                    .build())
                .build();

            if (!config.isEnabled()) {
                this.runtimeMotds.clear();
                this.spriteRuntimeMotds = List.of();
                this.textRuntimeMotds = List.of();
                logger.info("features is disabled in config (enabled=false). Ping modifications are bypassed.");
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
        Map<String, VelocityConfig.ImageEntry> configuredImages = config.getImages();
        if (configuredImages == null || configuredImages.isEmpty()) {
            return;
        }

        for (Map.Entry<String, VelocityConfig.ImageEntry> entry : configuredImages.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            VelocityConfig.ImageEntry imageEntry = entry.getValue();
            if (imageEntry == null || imageEntry.getFile() == null || imageEntry.getFile().isBlank()) {
                logger.warn("Image '{}' has an empty file path, skipping.", id);
                continue;
            }

            Path file;
            try {
                file = resolveDataFile(imageEntry.getFile());
            } catch (IOException e) {
                logger.warn("Image '{}' has an invalid file path '{}': {}", id, imageEntry.getFile(), e.getMessage());
                continue;
            }

            if (!Files.exists(file)) {
                logger.warn("Image file missing for '{}': {}", id, file);
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
                    logger.info("Image '{}' cache hit (image-cache.yml).", id);
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
                logger.error("Failed to process image '{}': {}", id, e.getMessage(), e);
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

    private double resolveBrightness(VelocityConfig.ImageEntry entry, String imageId) {
        return resolveCompensation(entry.getBrightness(), "brightness", imageId);
    }

    private double resolveSaturation(VelocityConfig.ImageEntry entry, String imageId) {
        return resolveCompensation(entry.getSaturation(), "saturation", imageId);
    }

    private double resolveCompensation(Double value, String key, String imageId) {
        if (value == null) {
            return 1.0D;
        }
        if (value.isNaN() || value.isInfinite()) {
            logger.warn(
                "Invalid {} '{}' for image '{}'. Using 1.0.",
                key,
                value,
                imageId
            );
            return 1.0D;
        }
        if (value < 0.0D || value > 3.0D) {
            logger.warn(
                "Out-of-range {} '{}' for image '{}'. Expected 0.0-3.0. Clamping.",
                key,
                value,
                imageId
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

                int compensatedRed = (int) Math.round(clamp(red * brightness, 0.0D, 255.0D));
                int compensatedGreen = (int) Math.round(clamp(green * brightness, 0.0D, 255.0D));
                int compensatedBlue = (int) Math.round(clamp(blue * brightness, 0.0D, 255.0D));

                float[] hsb = Color.RGBtoHSB(compensatedRed, compensatedGreen, compensatedBlue, null);
                float adjustedSaturation = (float) clamp(hsb[1] * saturation, 0.0D, 1.0D);
                int compensatedRgb = Color.HSBtoRGB(hsb[0], adjustedSaturation, hsb[2]) & 0x00FFFFFF;

                adjusted.setRGB(x, y, (alpha << 24) | compensatedRgb);
            }
        }

        logger.info(
            "Applied color compensation for image '{}': brightness={}, saturation={}.",
            imageId,
            formatCompensation(brightness),
            formatCompensation(saturation)
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
            logger.info("Image '{}' cache hit (sprite-cache.yml, {} symbols).", id, total);
        } else {
            int maxUncachedSymbolsPerImage = config == null ? 128 : config.getMaxUncachedSymbolsPerImage();
            if (maxUncachedSymbolsPerImage > 0 && missing.size() > maxUncachedSymbolsPerImage) {
                logger.warn(
                    "Image '{}' requires {} unique uncached symbols ({} total uncached tiles), which exceeds maxUncachedSymbolsPerImage={}. Skipping image.",
                    id,
                    missing.size(),
                    missingSymbols,
                    maxUncachedSymbolsPerImage
                );
                return null;
            }

            if (!mineSkinUploadService.isEnabled()) {
                String reason = settings.getMineSkin().isEnabled()
                    ? "MineSkin API key missing"
                    : "MineSkin disabled in settings.yml";
                logger.warn(
                    "{} and '{}' requires {} unique uncached symbols ({} total uncached tiles). Skipping image.",
                    reason,
                    id,
                    missing.size(),
                    missingSymbols
                );
                return null;
            }

            logger.info(
                "Image '{}' has {} unique uncached symbols ({} total uncached tiles). Generating unique symbols only.",
                id,
                missing.size(),
                missingSymbols
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
                        logger.info(
                            "[{}] generated unique uncached symbol {}/{} (covers {} tiles)",
                            id,
                            current,
                            missing.size(),
                            request.indices().size()
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

        for (Map.Entry<String, VelocityConfig.MotdEntry> entry : config.getMotds().entrySet()) {
            String id = entry.getKey();
            VelocityConfig.MotdEntry motd = entry.getValue();
            if (motd == null) {
                continue;
            }

            String effectiveShadowColor = null;
            ShadowColor shadowColor = null;
            String imageId = null;
            Component imageDescription = null;
            Component textDescription = null;
            Component fallbackDescription = parseFallback(motd.getFallbackDescription());
            boolean descriptionUsesSpriteTags = containsSpriteTags(motd.getDescription());

            if (motd.getImage() != null && !motd.getImage().isBlank()) {
                imageId = motd.getImage().toLowerCase(Locale.ROOT);
                HeadSpriteImage image = renderedImages.get(imageId);
                if (image != null && image.isValidGrid()) {
                    effectiveShadowColor = resolveEffectiveShadowColor(id, motd, imageId, true);
                    shadowColor = resolveShadowColor(effectiveShadowColor, id, true);
                    imageDescription = HeadSpriteRenderer.renderImage(image, shadowColor);
                } else {
                    logger.warn("MOTD '{}' references missing/invalid image '{}'.", id, motd.getImage());
                }
            }

            if (motd.getDescription() != null && !motd.getDescription().isEmpty()) {
                textDescription = parseLines(motd.getDescription());
            }

            if (imageDescription != null && !descriptionUsesSpriteTags) {
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : (textDescription != null ? textDescription : Component.text("Welcome to the network"));
                built.add(new RuntimeMotd(id, true, imageDescription, fallback));
                continue;
            }

            if (textDescription != null) {
                boolean spriteBased = descriptionUsesSpriteTags;
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : (spriteBased ? Component.text("Welcome to the network") : textDescription);
                built.add(new RuntimeMotd(id, spriteBased, textDescription, fallback));
                continue;
            }

            if (imageDescription != null) {
                Component fallback = fallbackDescription != null
                    ? fallbackDescription
                    : Component.text("Welcome to the network");
                built.add(new RuntimeMotd(id, true, imageDescription, fallback));
                continue;
            }

            if (fallbackDescription != null) {
                built.add(new RuntimeMotd(id, false, fallbackDescription, fallbackDescription));
                continue;
            }

            logger.warn("MOTD '{}' has no valid image/description/fallback and was skipped.", id);
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
            if (rowSystemEnabled
                && (lower.contains("<image_row:")
                || lower.contains("<image-row:"))) {
                return true;
            }
        }
        return false;
    }

    private String resolveEffectiveShadowColor(
        String motdId,
        VelocityConfig.MotdEntry motd,
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

        logger.warn(
            "Invalid shadow_color '{}' for MOTD '{}'. Use #RRGGBB or #RRGGBBAA. Keeping default behavior.",
            configuredValue,
            motdId
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

        logger.warn(
            "Invalid shadow_color '{}' for {}. Expected #RRGGBB or #RRGGBBAA.",
            configuredValue,
            context
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
            Path root = dataDirectory.toAbsolutePath().normalize();
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

    private boolean supportsSpriteMotd(ProtocolVersion protocolVersion) {
        return MotdProtocolSupport.supportsSpriteMotd(
            protocolVersion.getProtocol(),
            config.getProtocolRange().getMin(),
            config.getProtocolRange().getMax()
        );
    }

    private void normalizeConfig() {
        VelocityConfig.ProtocolRange protocolRange = config.getProtocolRange();
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
            for (VelocityConfig.MotdEntry motd : config.getMotds().values()) {
                if (motd == null || motd.getImage() == null || motd.getImage().isBlank()) {
                    continue;
                }
                String motdShadow = normalizeShadowColorHex(motd.getShadowColor(), "MOTD image shadow migration");
                if (!"#00000000".equals(motdShadow)) {
                    continue;
                }

                VelocityConfig.ImageEntry imageEntry = findImageEntry(motd.getImage());
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
                logger.info(
                    "Migrated {} MOTD shadow_color overrides (#00000000) to auto image seam shadow.",
                    migratedShadowOverrides
                );
            }
        }
    }

    private VelocityConfig.ImageEntry findImageEntry(String imageId) {
        if (imageId == null || imageId.isBlank() || config.getImages() == null) {
            return null;
        }

        VelocityConfig.ImageEntry direct = config.getImages().get(imageId);
        if (direct != null) {
            return direct;
        }

        String lookup = imageId.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, VelocityConfig.ImageEntry> entry : config.getImages().entrySet()) {
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
            logger.info(
                "Normalized cached MOTD head properties (image-cache symbols: {}, sprite-cache symbols: {}).",
                normalizedImageSymbols,
                normalizedSpriteSymbols
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
            settings,
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
        this.settings = snapshot.settings();
        this.config = snapshot.config();
        this.imageCache = snapshot.imageCache();
        this.spriteCache = snapshot.spriteCache();
        this.mineSkinUploadService = snapshot.mineSkinUploadService();
        this.miniMessage = snapshot.miniMessage();

        this.renderedImages.clear();
        this.renderedImages.putAll(snapshot.renderedImages());

        this.resolvedImageShadowColors.clear();
        this.resolvedImageShadowColors.putAll(snapshot.resolvedImageShadowColors());

        this.runtimeMotds.clear();
        this.runtimeMotds.addAll(snapshot.runtimeMotds());
        rebuildMotdBuckets();
    }

    private MiniMessage commandMiniMessage() {
        return miniMessage == null ? MiniMessage.miniMessage() : miniMessage;
    }

    private void scheduleSourceMessage(com.velocitypowered.api.command.CommandSource source, Component message) {
        server.getScheduler().buildTask(this, () -> source.sendMessage(message)).schedule();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private final class FeaturesCommand implements SimpleCommand {
        private static final String PREFIX = "<gradient:#38d7ff:#9e6dff><bold>[features]</bold></gradient> ";

        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();

            if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
                if (!reloadInProgress.compareAndSet(false, true)) {
                    invocation.source().sendMessage(
                        commandMiniMessage().deserialize(PREFIX + "<yellow>Reload already in progress.</yellow>")
                    );
                    return;
                }

                invocation.source().sendMessage(
                    commandMiniMessage().deserialize(PREFIX + "<gray>Reload started...</gray>")
                );

                var source = invocation.source();
                CompletableFuture.runAsync(() -> {
                    try {
                        reloadInternal();
                        scheduleSourceMessage(
                            source,
                            commandMiniMessage().deserialize(PREFIX + "<green>Velocity config reloaded.</green>")
                        );
                    } catch (Exception e) {
                        logger.error("Reload failed", e);
                        scheduleSourceMessage(
                            source,
                            commandMiniMessage().deserialize(
                                PREFIX + "<red>Reload failed:</red> <gray>" + safeMessage(e) + "</gray>"
                            )
                        );
                    } finally {
                        reloadInProgress.set(false);
                    }
                }, reloadExecutor);
                return;
            }

            invocation.source().sendMessage(
                commandMiniMessage().deserialize(PREFIX + "<gradient:#00c6ff:#0072ff>/features reload</gradient>")
            );
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(RELOAD_PERMISSION);
        }

        @Override
        public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1) {
                String lower = args[0].toLowerCase(Locale.ROOT);
                List<String> suggestions = List.of("reload").stream()
                    .filter(v -> v.startsWith(lower))
                    .toList();
                return CompletableFuture.completedFuture(suggestions);
            }
            return CompletableFuture.completedFuture(List.of());
        }
    }

    public static class VelocitySettings {
        private MineSkinSettings mineSkin = new MineSkinSettings();

        public MineSkinSettings getMineSkin() {
            if (mineSkin == null) {
                mineSkin = new MineSkinSettings();
            }
            return mineSkin;
        }

        public void setMineSkin(MineSkinSettings mineSkin) {
            this.mineSkin = mineSkin == null ? new MineSkinSettings() : mineSkin;
        }
    }

        public static class VelocityConfig {
        private boolean enabled = true;
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

        public static VelocityConfig defaultConfig() {
            VelocityConfig cfg = new VelocityConfig();
            cfg.setEnabled(true);
            cfg.setRowSystemEnabled(true);
            cfg.setProtocolRange(ProtocolRange.defaultRange());
            cfg.setMaxUncachedSymbolsPerImage(128);

            ImageEntry sampleImage = new ImageEntry();
            sampleImage.setFile("images/features_style.png");
            cfg.images.put("features_style", sampleImage);

            MotdEntry sampleMotd = new MotdEntry();
            sampleMotd.setImage("features_style");
            sampleMotd.setFallbackDescription("Welcome to the network");
            cfg.motds.put("default", sampleMotd);

            return cfg;
        }

        public static class ProtocolRange {
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

        public static class ImageEntry {
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

        public static class MotdEntry {
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

    public static class ImageCache {
        private Map<String, CachedImage> images = new LinkedHashMap<>();

        public Map<String, CachedImage> getImages() {
            return images;
        }

        public void setImages(Map<String, CachedImage> images) {
            this.images = images;
        }
    }

    public static class SpriteCache {
        private Map<String, PlayerHeadSymbol> symbols = new ConcurrentHashMap<>();

        public Map<String, PlayerHeadSymbol> getSymbols() {
            return symbols;
        }

        public void setSymbols(Map<String, PlayerHeadSymbol> symbols) {
            this.symbols = symbols;
        }
    }

    public static class CachedImage {
        private String sha256;
        private HeadSpriteImage rendered;

        public CachedImage() {
        }

        public CachedImage(String sha256, HeadSpriteImage rendered) {
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
        VelocitySettings settings,
        VelocityConfig config,
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
        Component resolveDescription(boolean supportsSpriteMotd) {
            if (supportsSpriteMotd && spriteBased && spriteDescription != null) {
                return spriteDescription;
            }
            if (fallbackDescription != null) {
                return fallbackDescription;
            }
            if (spriteDescription != null) {
                return spriteDescription;
            }
            return Component.text("Features is loaded.");
        }
    }

    private static final class ReloadThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "features-velocity-reload");
            thread.setDaemon(true);
            return thread;
        }
    }
}
