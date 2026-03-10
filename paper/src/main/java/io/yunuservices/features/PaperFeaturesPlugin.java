package io.yunuservices.features;

import io.yunuservices.features.core.config.YamlStore;
import io.yunuservices.features.core.mineskin.MineSkinSettings;
import io.yunuservices.features.core.mineskin.MineSkinUploadService;
import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.render.HeadSpriteRenderer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class PaperFeaturesPlugin extends JavaPlugin {
    private final YamlStore yamlStore = new YamlStore();
    private final Set<String> activeGenerations = ConcurrentHashMap.newKeySet();
    private final PaperMotdSupport paperMotdSupport = new PaperMotdSupport(this, yamlStore);
    private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, new PaperWorkerThreadFactory());

    private Path settingsPath;
    private Path tagsPath;
    private Path importDir;

    private Settings settings;
    private TagStorage tagStorage;
    private MineSkinUploadService mineSkinUploadService;
    private TagsCommand tagsCommand;
    private LegacyPaperCommandManager<CommandSender> commandManager;
    private Runnable placeholderApiClose = () -> {};
    private Runnable miniPlaceholdersClose = () -> {};
    private Runnable packetEventsMotdClose = () -> {};

    private MiniMessage miniMessage;

    @Override
    public void onEnable() {
        if (!isSupportedServerVersion(Bukkit.getMinecraftVersion())) {
            getLogger().severe("features requires Paper/Folia 1.21.9+ (found " + Bukkit.getMinecraftVersion() + ")");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            bootstrapFiles();
            reloadPlugin();
            registerCommands();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable Features paper module", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(paperMotdSupport, this);

        maybeRegisterPacketEventsMotdBridge();
        maybeRegisterPlaceholderExpansion();
        maybeRegisterMiniPlaceholders();
        getLogger().info("features enabled.");
    }

    @Override
    public void onDisable() {
        asyncExecutor.shutdownNow();
        placeholderApiClose.run();
        placeholderApiClose = () -> {};
        miniPlaceholdersClose.run();
        miniPlaceholdersClose = () -> {};
        packetEventsMotdClose.run();
        packetEventsMotdClose = () -> {};
    }

    private void bootstrapFiles() throws IOException {
        Files.createDirectories(getDataPath());
        this.settingsPath = getDataPath().resolve("settings.yml");
        this.tagsPath = getDataPath().resolve("tags.yml");
        this.importDir = getDataPath().resolve("import");
        Files.createDirectories(importDir);
        paperMotdSupport.bootstrapFiles();
    }

    public synchronized void reloadPlugin() throws IOException {
        Settings loadedSettings = yamlStore.readOrDefault(settingsPath, Settings.class, Settings::new);
        TagStorage loadedTagStorage = yamlStore.readOrDefault(tagsPath, TagStorage.class, TagStorage::new);
        MiniMessage loadedMiniMessage = MiniMessage.builder()
            .tags(TagResolver.builder()
                .resolver(StandardTags.defaults())
                .resolver(tagResolver("features_tag", loadedTagStorage))
                .build())
            .build();
        MineSkinUploadService loadedMineSkinUploadService = new MineSkinUploadService(
            loadedSettings.getMineSkin(),
            "Features/Paper-1.0.0"
        );

        this.settings = loadedSettings;
        this.tagStorage = loadedTagStorage;
        this.miniMessage = loadedMiniMessage;
        this.mineSkinUploadService = loadedMineSkinUploadService;
        this.paperMotdSupport.reload(loadedMineSkinUploadService);

        if (!loadedSettings.getMineSkin().isEnabled()) {
            getLogger().warning("MineSkin is disabled in settings.yml (mineSkin.enabled=false). /tags generate will be disabled.");
        } else if (!loadedSettings.getMineSkin().hasApiKey()) {
            getLogger().warning("MineSkin API key is empty. /tags generate will be disabled.");
        }
    }

    private TagResolver tagResolver(String tagName, TagStorage storage) {
        return TagResolver.resolver(tagName, (ArgumentQueue queue, net.kyori.adventure.text.minimessage.Context ctx) ->
            FeatureTagSupport.resolveTag(storage.getTags(), queue.popOr("missing tag name").value())
        );
    }

    private void maybeRegisterPlaceholderExpansion() {
        Plugin placeholder = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (!(placeholder instanceof PlaceholderAPIPlugin)) {
            return;
        }

        ArrayList<PlaceholderExpansion> registered = new ArrayList<>();
        try {
            PaperTagPlaceholderExpansion featuresExpansion = new PaperTagPlaceholderExpansion(
                this,
                "features",
                Map.of(
                    "tag_", FeatureTagSupport::placeholderToken,
                    "image_", name -> FeatureTagSupport.imagePlaceholderToken(name, getTagStorage().getTags().get(name))
                )
            );

            for (PaperTagPlaceholderExpansion expansion : new PaperTagPlaceholderExpansion[]{featuresExpansion}) {
                if (!expansion.register()) {
                    throw new IllegalStateException("PlaceholderAPI expansion registration returned false for " + expansion.getIdentifier());
                }
                registered.add(expansion);
            }
            placeholderApiClose = () -> registered.forEach(PlaceholderExpansion::unregister);
            getLogger().info("PlaceholderAPI expansion registered.");
        } catch (Throwable t) {
            registered.forEach(PlaceholderExpansion::unregister);
            placeholderApiClose = () -> {};
            getLogger().log(Level.WARNING, "Failed to register PlaceholderAPI expansion", t);
        }
    }

    private void maybeRegisterMiniPlaceholders() {
        Plugin miniPlaceholders = Bukkit.getPluginManager().getPlugin("MiniPlaceholders");
        if (miniPlaceholders == null) {
            return;
        }

        try {
            miniPlaceholdersClose = MiniPlaceholdersBridge.register(this);
            getLogger().info("MiniPlaceholders expansion registered.");
        } catch (Throwable t) {
            miniPlaceholdersClose = () -> {};
            getLogger().log(Level.WARNING, "Failed to register MiniPlaceholders expansion", t);
        }
    }

    private void maybeRegisterPacketEventsMotdBridge() {
        Plugin packetEvents = Bukkit.getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            if (paperMotdSupport.isEnabled()) {
                getLogger().warning(
                    "PacketEvents was not found. Paper join-time MOTD refresh will be unavailable until PacketEvents is installed."
                );
            }
            return;
        }

        try {
            packetEventsMotdClose = PaperPacketEventsMotdBridge.register(this, paperMotdSupport);
            getLogger().info("PacketEvents MOTD refresh bridge enabled.");
        } catch (Throwable t) {
            packetEventsMotdClose = () -> {};
            getLogger().log(Level.WARNING, "Failed to enable PacketEvents MOTD refresh bridge", t);
        }
    }

    public Path getDataPath() {
        return getDataFolder().toPath();
    }

    public Path getImportDir() {
        return importDir;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public synchronized Settings getSettings() {
        return settings;
    }

    public synchronized TagStorage getTagStorage() {
        return tagStorage;
    }

    public synchronized MineSkinUploadService getMineSkinUploadService() {
        return mineSkinUploadService;
    }

    public synchronized MiniMessage getMiniMessage() {
        return miniMessage;
    }

    public synchronized void upsertTag(String name, HeadSpriteImage image) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        tagStorage.getTags().put(key, image);
        saveTags();
    }

    public synchronized HeadSpriteImage removeTag(String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        HeadSpriteImage removed = tagStorage.getTags().remove(key);
        saveTags();
        return removed;
    }

    public synchronized boolean renameTag(String oldName, String newName) throws IOException {
        String oldKey = oldName.toLowerCase(Locale.ROOT);
        String newKey = newName.toLowerCase(Locale.ROOT);
        Map<String, HeadSpriteImage> tags = new ConcurrentHashMap<>(tagStorage.getTags());

        if (!tags.containsKey(oldKey) || tags.containsKey(newKey)) {
            return false;
        }

        HeadSpriteImage value = tags.remove(oldKey);
        tags.put(newKey, value);
        tagStorage.getTags().clear();
        tagStorage.getTags().putAll(tags);
        saveTags();
        return true;
    }

    public synchronized void saveTags() throws IOException {
        yamlStore.write(tagsPath, tagStorage);
    }

    public void runSync(Runnable runnable) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(this, runnable);
        } catch (Throwable ignored) {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    public Component parseMiniMessage(String input) {
        return getMiniMessage().deserialize(input);
    }

    public synchronized GenerationReservation tryBeginGeneration(String name, int maxConcurrentGenerations) {
        String key = name.toLowerCase(Locale.ROOT);
        if (activeGenerations.contains(key)) {
            return GenerationReservation.ALREADY_RUNNING;
        }
        if (maxConcurrentGenerations > 0 && activeGenerations.size() >= maxConcurrentGenerations) {
            return GenerationReservation.LIMIT_REACHED;
        }
        activeGenerations.add(key);
        return GenerationReservation.STARTED;
    }

    public synchronized void finishGeneration(String name) {
        activeGenerations.remove(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean isGenerationInProgress(String name) {
        return activeGenerations.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean tryBeginReload() {
        return reloadInProgress.compareAndSet(false, true);
    }

    public void finishReload() {
        reloadInProgress.set(false);
    }

    private void registerCommands() throws Exception {
        this.tagsCommand = new TagsCommand(this);
        this.commandManager = LegacyPaperCommandManager.createNative(this, ExecutionCoordinator.simpleCoordinator());
        this.commandManager.registerBrigadier();
        this.commandManager.registerAsynchronousCompletions();

        AnnotationParser<CommandSender> parser = new AnnotationParser<>(commandManager, CommandSender.class);
        parser.parse(new PaperAnnotatedCommands(tagsCommand));
    }

    private boolean isSupportedServerVersion(String version) {
        int[] parsed = parseVersion(version);
        int[] minimum = {1, 21, 9};
        return compareVersion(parsed, minimum) >= 0;
    }

    private int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int major = parseIntOrZero(parts, 0);
        int minor = parseIntOrZero(parts, 1);
        int patch = parseIntOrZero(parts, 2);
        return new int[]{major, minor, patch};
    }

    private int parseIntOrZero(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String raw = parts[index].replaceAll("[^0-9].*$", "");
        if (raw.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(raw);
    }

    private int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    public static class Settings {
        private MineSkinSettings mineSkin = new MineSkinSettings();
        private GenerationSettings generation = new GenerationSettings();

        public MineSkinSettings getMineSkin() {
            if (mineSkin == null) {
                mineSkin = new MineSkinSettings();
            }
            return mineSkin;
        }

        public void setMineSkin(MineSkinSettings mineSkin) {
            this.mineSkin = mineSkin == null ? new MineSkinSettings() : mineSkin;
        }

        public GenerationSettings getGeneration() {
            if (generation == null) {
                generation = new GenerationSettings();
            }
            return generation;
        }

        public void setGeneration(GenerationSettings generation) {
            this.generation = generation == null ? new GenerationSettings() : generation;
        }
    }

    public static class TagStorage {
        private Map<String, HeadSpriteImage> tags = new ConcurrentHashMap<>();

        public Map<String, HeadSpriteImage> getTags() {
            if (tags == null) {
                tags = new ConcurrentHashMap<>();
            }
            return tags;
        }

        public void setTags(Map<String, HeadSpriteImage> tags) {
            this.tags = tags == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(tags);
        }
    }

    public static class GenerationSettings {
        private static final int MAX_SYMBOLS_CAP = 256;
        private static final int MAX_CONCURRENT_GENERATIONS_CAP = 4;
        private static final int MAX_PROGRESS_MESSAGE_INTERVAL = 64;

        private int maxSymbolsPerTag = 128;
        private int maxConcurrentGenerations = 1;
        private boolean allowUrlSources = true;
        private boolean allowPrivateAddressUrls = false;
        private int progressMessageInterval = 8;

        public int getMaxSymbolsPerTag() {
            return Math.max(1, Math.min(MAX_SYMBOLS_CAP, maxSymbolsPerTag));
        }

        public void setMaxSymbolsPerTag(int maxSymbolsPerTag) {
            this.maxSymbolsPerTag = maxSymbolsPerTag;
        }

        public int getMaxConcurrentGenerations() {
            return Math.max(1, Math.min(MAX_CONCURRENT_GENERATIONS_CAP, maxConcurrentGenerations));
        }

        public void setMaxConcurrentGenerations(int maxConcurrentGenerations) {
            this.maxConcurrentGenerations = maxConcurrentGenerations;
        }

        public boolean isAllowUrlSources() {
            return allowUrlSources;
        }

        public void setAllowUrlSources(boolean allowUrlSources) {
            this.allowUrlSources = allowUrlSources;
        }

        public boolean isAllowPrivateAddressUrls() {
            return allowPrivateAddressUrls;
        }

        public void setAllowPrivateAddressUrls(boolean allowPrivateAddressUrls) {
            this.allowPrivateAddressUrls = allowPrivateAddressUrls;
        }

        public int getProgressMessageInterval() {
            return Math.max(1, Math.min(MAX_PROGRESS_MESSAGE_INTERVAL, progressMessageInterval));
        }

        public void setProgressMessageInterval(int progressMessageInterval) {
            this.progressMessageInterval = progressMessageInterval;
        }
    }

    public enum GenerationReservation {
        STARTED,
        ALREADY_RUNNING,
        LIMIT_REACHED
    }

    private static final class PaperWorkerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "features-paper-worker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
