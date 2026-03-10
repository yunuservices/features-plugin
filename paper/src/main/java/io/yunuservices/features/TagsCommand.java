package io.yunuservices.features;

import io.yunuservices.features.core.image.ImageTiles;
import io.yunuservices.features.core.image.RemoteImageSourcePolicy;
import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.render.HeadSpriteRenderer;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class TagsCommand implements CommandExecutor, TabCompleter, BasicCommand {
    private static final String PREFIX = "<gradient:#38d7ff:#9e6dff><bold>[features]</bold></gradient> ";
    private final PaperFeaturesPlugin plugin;

    public TagsCommand(PaperFeaturesPlugin plugin) {
        this.plugin = plugin;
    }

    public void showHelpMessage(CommandSender sender) {
        showHelp(sender);
    }

    public void showFeaturesHelpMessage(CommandSender sender) {
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/features reload</gradient>"));
    }

    public void executeReloadCommand(CommandSender sender) {
        runSafely(sender, () -> handleReload(sender));
    }

    public void executeListCommand(CommandSender sender) {
        runSafely(sender, () -> handleList(sender));
    }

    public void executeViewCommand(CommandSender sender, String name) {
        runSafely(sender, () -> handleView(sender, new String[]{"view", name}));
    }

    public void executeDeleteCommand(CommandSender sender, String name) {
        runSafely(sender, () -> handleDelete(sender, new String[]{"delete", name}));
    }

    public void executeRenameCommand(CommandSender sender, String oldName, String newName) {
        runSafely(sender, () -> handleRename(sender, new String[]{"rename", oldName, newName}));
    }

    public void executeGenerateCommand(CommandSender sender, String name, String sourceType, String source) {
        runSafely(sender, () -> handleGenerate(sender, new String[]{"generate", name, sourceType, source}));
    }

    public List<String> suggestRoot(CommandSender sender, String input) {
        return suggest(sender, new String[]{input});
    }

    public List<String> suggestTagNames(String input) {
        ArrayList<String> options = new ArrayList<>(plugin.getTagStorage().getTags().keySet());
        options.sort(Comparator.naturalOrder());
        return filter(options, input);
    }

    public List<String> suggestSourceTypes(String input) {
        return filter(new ArrayList<>(List.of("url", "file")), input);
    }

    public List<String> suggestImportFiles(String input) {
        ArrayList<String> options = new ArrayList<>();
        try {
            try (var stream = Files.list(plugin.getImportDir())) {
                stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(options::add);
            }
        } catch (IOException ignored) {
        }
        return filter(options, input);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "reload" -> handleReload(sender);
                case "list" -> handleList(sender);
                case "view" -> handleView(sender, args);
                case "delete" -> handleDelete(sender, args);
                case "rename" -> handleRename(sender, args);
                case "generate" -> handleGenerate(sender, args);
                default -> {
                    showHelp(sender);
                    yield true;
                }
            };
        } catch (Exception e) {
            sender.sendMessage(mm(PREFIX + "<red>Error:</red> <gray>" + safeMessage(e) + "</gray>"));
            plugin.getLogger().log(Level.SEVERE, "Command failed", e);
            return true;
        }
    }

    private boolean handleReload(CommandSender sender) throws IOException {
        requireAdmin(sender);
        if (!plugin.tryBeginReload()) {
            sender.sendMessage(mm(PREFIX + "<yellow>Reload already in progress.</yellow>"));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<gray>Reload started...</gray>"));
        CompletableFuture.runAsync(() -> {
            try {
                plugin.reloadPlugin();
                plugin.runSync(() -> sender.sendMessage(mm(PREFIX + "<green>Reload complete.</green>")));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Reload failed", e);
                plugin.runSync(() -> sender.sendMessage(
                    mm(PREFIX + "<red>Reload failed:</red> <gray>" + safeMessage(e) + "</gray>")
                ));
            } finally {
                plugin.finishReload();
            }
        }, plugin.getAsyncExecutor());
        return true;
    }

    private boolean handleList(CommandSender sender) {
        requireAdmin(sender);
        Map<String, HeadSpriteImage> tags = plugin.getTagStorage().getTags();
        if (tags.isEmpty()) {
            sender.sendMessage(mm(PREFIX + "<yellow>No tags found.</yellow>"));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>Tags</gradient> <gray>(" + tags.size() + ")</gray>"));
        tags.keySet().stream().sorted().forEach(tag -> sender.sendMessage(Component.text(" - " + tag)));
        return true;
    }

    private boolean handleView(CommandSender sender, String[] args) {
        requireAdmin(sender);
        if (args.length < 2) {
            sender.sendMessage(mm(PREFIX + usageLine("/tags view", "(name)")));
            return true;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        HeadSpriteImage image = plugin.getTagStorage().getTags().get(name);
        if (image == null) {
            sender.sendMessage(mm(PREFIX + "<red>Tag not found:</red> <gray>" + name + "</gray>"));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<green>Showing tag:</green> <gradient:#38d7ff:#9e6dff>" + name + "</gradient>"));
        if (sender instanceof Player) {
            sender.sendMessage(HeadSpriteRenderer.renderImage(image));
        } else {
            sender.sendMessage(mm(PREFIX + "<gray>Preview is available in-game only.</gray>"));
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) throws IOException {
        requireAdmin(sender);
        if (args.length < 2) {
            sender.sendMessage(mm(PREFIX + usageLine("/tags delete", "(name)")));
            return true;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        HeadSpriteImage removed = plugin.removeTag(name);
        if (removed == null) {
            sender.sendMessage(mm(PREFIX + "<red>Tag not found:</red> <gray>" + name + "</gray>"));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<green>Tag deleted:</green> <gradient:#ff7a18:#ffd200>" + name + "</gradient>"));
        return true;
    }

    private boolean handleRename(CommandSender sender, String[] args) throws IOException {
        requireAdmin(sender);
        if (args.length < 3) {
            sender.sendMessage(mm(PREFIX + usageLine("/tags rename", "(old) (new)")));
            return true;
        }

        boolean ok = plugin.renameTag(args[1], args[2]);
        if (!ok) {
            sender.sendMessage(mm(PREFIX + "<red>Rename failed.</red> <gray>Check old/new names.</gray>"));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<green>Renamed:</green> <gradient:#38d7ff:#9e6dff>" + args[1]
            + "</gradient> <gray>-></gray> <gradient:#ff7a18:#ffd200>" + args[2] + "</gradient>"));
        return true;
    }

    private boolean handleGenerate(CommandSender sender, String[] args) {
        requireGenerate(sender);
        if (args.length < 4) {
            sender.sendMessage(mm(PREFIX + usageLine("/tags generate", "(name) <gradient:#00c6ff:#0072ff>(url|file)</gradient> (source)")));
            return true;
        }

        if (!plugin.getMineSkinUploadService().isEnabled()) {
            String reason = plugin.getSettings().getMineSkin().isEnabled()
                ? "MineSkin API key is missing."
                : "MineSkin is disabled.";
            sender.sendMessage(mm(PREFIX + "<red>" + reason + "</red> <gray>Check settings.yml.</gray>"));
            return true;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        String sourceType = args[2].toLowerCase(Locale.ROOT);
        String source = args[3];
        PaperFeaturesPlugin.GenerationSettings generationSettings = plugin.getSettings().getGeneration();
        int maxSymbolsPerTag = generationSettings.getMaxSymbolsPerTag();
        int progressStep = generationSettings.getProgressMessageInterval();
        boolean allowUrlSources = generationSettings.isAllowUrlSources();
        boolean allowPrivateAddressUrls = generationSettings.isAllowPrivateAddressUrls();
        Path importDir = plugin.getImportDir();
        var mineSkinUploadService = plugin.getMineSkinUploadService();

        if (plugin.getTagStorage().getTags().containsKey(name)) {
            sender.sendMessage(mm(PREFIX + "<yellow>Tag already exists:</yellow> <gray>" + name + "</gray>"));
            return true;
        }
        PaperFeaturesPlugin.GenerationReservation reservation =
            plugin.tryBeginGeneration(name, generationSettings.getMaxConcurrentGenerations());
        if (reservation == PaperFeaturesPlugin.GenerationReservation.ALREADY_RUNNING) {
            sender.sendMessage(mm(PREFIX + "<yellow>Generation already in progress for:</yellow> <gray>" + name + "</gray>"));
            return true;
        }
        if (reservation == PaperFeaturesPlugin.GenerationReservation.LIMIT_REACHED) {
            sender.sendMessage(mm(
                PREFIX
                    + "<yellow>Generation queue is full.</yellow> <gray>maxConcurrentGenerations="
                    + generationSettings.getMaxConcurrentGenerations()
                    + "</gray>"
            ));
            return true;
        }

        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>" + name + "</gradient> <gray>generation started...</gray>"));
        AtomicInteger lastProgress = new AtomicInteger(0);
        CompletableFuture<HeadSpriteImage> future = CompletableFuture
            .supplyAsync(() -> {
                try {
                    BufferedImage image = loadImage(sourceType, source, importDir, allowUrlSources, allowPrivateAddressUrls);
                    ImageTiles.requireTagDimensions(image);
                    int totalSymbols = ImageTiles.widthSymbols(image) * ImageTiles.heightSymbols(image);
                    if (maxSymbolsPerTag > 0 && totalSymbols > maxSymbolsPerTag) {
                        throw new IllegalArgumentException(
                            totalSymbols + " symbols exceeds maxSymbolsPerTag=" + maxSymbolsPerTag + "."
                        );
                    }
                    return image;
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }, plugin.getAsyncExecutor())
            .thenCompose(image -> mineSkinUploadService.generate(image, name, (current, total) -> {
                if (current < total) {
                    while (true) {
                        int previous = lastProgress.get();
                        if (current - previous < progressStep) {
                            return;
                        }
                        if (lastProgress.compareAndSet(previous, current)) {
                            break;
                        }
                    }
                } else {
                    lastProgress.set(current);
                }

                plugin.runSync(() -> sender.sendMessage(
                    mm(PREFIX + "<gray>Symbol generated:</gray> <gradient:#38d7ff:#9e6dff>"
                        + current + "/" + total + "</gradient>")
                ));
            }));

        future.whenComplete((generated, throwable) -> plugin.runSync(() -> {
            if (throwable != null) {
                sender.sendMessage(mm(PREFIX + "<red>Generation failed:</red> <gray>" + safeMessage(throwable) + "</gray>"));
                plugin.getLogger().log(Level.SEVERE, "Tag generation failed for " + name, throwable);
                plugin.finishGeneration(name);
                return;
            }

            try {
                plugin.upsertTag(name, generated);
                sender.sendMessage(mm(PREFIX + "<green>Tag generated:</green> <gradient:#38d7ff:#9e6dff>" + name + "</gradient>"));
                if (sender instanceof Player) {
                    sender.sendMessage(HeadSpriteRenderer.renderImage(generated));
                }
            } catch (IOException e) {
                sender.sendMessage(mm(PREFIX + "<red>Tag could not be saved:</red> <gray>" + safeMessage(e) + "</gray>"));
                plugin.getLogger().log(Level.SEVERE, "Tag save failed for " + name, e);
            } finally {
                plugin.finishGeneration(name);
            }
        }));

        return true;
    }

    private BufferedImage loadImage(
        String type,
        String source,
        Path importDir,
        boolean allowUrlSources,
        boolean allowPrivateAddressUrls
    ) throws IOException {
        return switch (type) {
            case "url" -> {
                URL url = RemoteImageSourcePolicy.resolveRemoteUrl(
                    source,
                    allowUrlSources,
                    allowPrivateAddressUrls
                );
                yield ImageTiles.readUrl(url, allowPrivateAddressUrls);
            }
            case "file" -> {
                Path file = RemoteImageSourcePolicy.resolveImportFile(importDir, source);
                ImageTiles.ensureExists(file);
                yield ImageTiles.readFile(file);
            }
            default -> throw new IOException("Invalid source type. Use 'url' or 'file'.");
        };
    }

    private void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("features.tags.admin")) {
            throw new IllegalStateException("Missing permission: features.tags.admin");
        }
    }

    private void requireGenerate(CommandSender sender) {
        if (!sender.hasPermission("features.tags.generate")) {
            throw new IllegalStateException("Missing permission: features.tags.generate");
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags reload</gradient>"));
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags list</gradient>"));
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags view</gradient> <gray>(name)</gray>"));
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags delete</gradient> <gray>(name)</gray>"));
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags rename</gradient> <gray>(old) (new)</gray>"));
        sender.sendMessage(mm(PREFIX + "<gradient:#00c6ff:#0072ff>/tags generate</gradient> "
            + "<gray>(name)</gray> <gradient:#ff7a18:#ffd200>(url|file)</gradient> <gray>(source)</gray>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return suggest(sender, args);
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        onCommand(stack.getSender(), null, "tags", args);
    }

    @Override
    public List<String> suggest(CommandSourceStack stack, String[] args) {
        return suggest(stack.getSender(), args);
    }

    public List<String> suggest(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("features.tags.admin")) {
                options.add("reload");
                options.add("list");
                options.add("view");
                options.add("delete");
                options.add("rename");
            }
            if (sender.hasPermission("features.tags.generate")) {
                options.add("generate");
            }
            return filter(options, args[0]);
        }

        if (args.length == 2 && List.of("view", "delete", "rename").contains(args[0].toLowerCase(Locale.ROOT))) {
            options.addAll(plugin.getTagStorage().getTags().keySet());
            options.sort(Comparator.naturalOrder());
            return filter(options, args[1]);
        }

        if (args.length == 3 && "generate".equalsIgnoreCase(args[0])) {
            options.add("url");
            options.add("file");
            return filter(options, args[2]);
        }

        if (args.length == 4
            && "generate".equalsIgnoreCase(args[0])
            && "file".equalsIgnoreCase(args[2])) {
            try {
                try (var stream = Files.list(plugin.getImportDir())) {
                    stream
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(options::add);
                }
            } catch (IOException ignored) {
            }
            return filter(options, args[3]);
        }

        return List.of();
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private Component mm(String value) {
        return plugin.parseMiniMessage(value);
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String usageLine(String command, String args) {
        return "<gradient:#00c6ff:#0072ff>" + command + "</gradient> <gray>" + args + "</gray>";
    }

    private void runSafely(CommandSender sender, CheckedCommand action) {
        try {
            action.run();
        } catch (Exception e) {
            sender.sendMessage(mm(PREFIX + "<red>Error:</red> <gray>" + safeMessage(e) + "</gray>"));
            plugin.getLogger().log(Level.SEVERE, "Command failed", e);
        }
    }

    @FunctionalInterface
    private interface CheckedCommand {
        void run() throws Exception;
    }
}
