package io.yunuservices.features;

import io.yunuservices.features.core.model.HeadSpriteImage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public class PaperTagPlaceholderExpansion extends PlaceholderExpansion {
    private final PaperFeaturesPlugin plugin;
    private final String identifier;
    private final Map<String, Function<String, String>> resolvers;

    public PaperTagPlaceholderExpansion(
        PaperFeaturesPlugin plugin,
        String identifier,
        Map<String, Function<String, String>> resolvers
    ) {
        this.plugin = plugin;
        this.identifier = identifier;
        this.resolvers = new LinkedHashMap<>(resolvers);
    }

    @Override
    public @NotNull String getIdentifier() {
        return identifier;
    }

    @Override
    public @NotNull String getAuthor() {
        return "yunuservices";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        for (Map.Entry<String, Function<String, String>> entry : resolvers.entrySet()) {
            String prefix = entry.getKey();
            if (params.startsWith(prefix)) {
                String name = FeatureTagSupport.normalizeName(params.substring(prefix.length()));
                HeadSpriteImage image = plugin.getTagStorage().getTags().get(name);
                if (image == null) {
                    return "";
                }
                return entry.getValue().apply(name);
            }
        }
        return null;
    }
}
