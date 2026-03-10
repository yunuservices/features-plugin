package io.yunuservices.features;

import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FeatureTagSupportTest {
    @Test
    void placeholderTokenUsesFeaturesTagNamespace() {
        assertEquals("<features_tag:demo>", FeatureTagSupport.placeholderToken("DeMo"));
    }

    @Test
    void imagePlaceholderTokenUsesRenderableHeadTags() {
        HeadSpriteImage image = new HeadSpriteImage(
            2,
            1,
            List.of(
                new PlayerHeadSymbol(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg4NDVmMzNlNzk1NGI2NmVkNjIxMGRjMzRhMmNmZTZhNThiZGQ5YWUyYTNiYzY4Nzk2Yzg5ZTU4MDNiYWNjNiIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9fX19",
                    null
                ),
                new PlayerHeadSymbol(
                    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWZhNjQzZjc3MGNlOTgwOGY1Zjc2NWFiZTljZTU0ZGE2MzMwMTYyN2Q5MmZhNzczNmUwNDZkZDQ5YTNlOTlhZCIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9fX19",
                    "signature-value"
                )
            )
        );

        assertEquals(
            "<head:texture:78845f33e7954b66ed6210dc34a2cfe6a58bdd9ae2a3bc68796c89e5803bacc6><head:signed_texture:eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWZhNjQzZjc3MGNlOTgwOGY1Zjc2NWFiZTljZTU0ZGE2MzMwMTYyN2Q5MmZhNzczNmUwNDZkZDQ5YTNlOTlhZCIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9fX19;signature-value>",
            FeatureTagSupport.imagePlaceholderToken("DeMo", image)
        );
    }

    @Test
    void resolveTagProducesMissingMarkerForUnknownTags() {
        MiniMessage miniMessage = MiniMessage.builder()
            .tags(TagResolver.resolver("features_tag", (queue, ctx) ->
                FeatureTagSupport.resolveTag(Map.of(), queue.popOr("missing tag name").value())
            ))
            .build();

        Component parsed = miniMessage.deserialize("<features_tag:demo>");
        assertEquals(Component.text("<missing-tag:demo>"), parsed);
    }

    @Test
    void resolveTagProducesComponentForKnownTags() {
        HeadSpriteImage image = new HeadSpriteImage(
            1,
            1,
            List.of(new PlayerHeadSymbol("value", "signature"))
        );

        MiniMessage miniMessage = MiniMessage.builder()
            .tags(TagResolver.resolver("features_tag", (queue, ctx) ->
                FeatureTagSupport.resolveTag(Map.of("demo", image), queue.popOr("missing tag name").value())
            ))
            .build();

        Component parsed = miniMessage.deserialize("<features_tag:demo>");
        assertNotEquals(Component.text("<missing-tag:demo>"), parsed);
    }
}
