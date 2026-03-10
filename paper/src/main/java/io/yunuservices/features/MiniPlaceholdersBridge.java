package io.yunuservices.features;

import io.github.miniplaceholders.api.Expansion;

final class MiniPlaceholdersBridge {
    private MiniPlaceholdersBridge() {
    }

    static Runnable register(PaperFeaturesPlugin plugin) {
        Expansion featuresExpansion = Expansion.builder("features")
            .author("yunuservices")
            .version(plugin.getPluginMeta().getVersion())
            .globalPlaceholder("tag", (queue, ctx) -> FeatureTagSupport.resolveTag(
                plugin.getTagStorage().getTags(),
                queue.popOr("missing tag name").value()
            ))
            .build();

        featuresExpansion.register();
        return featuresExpansion::unregister;
    }
}
