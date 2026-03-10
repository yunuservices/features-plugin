package io.yunuservices.features.core.render;

import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;

import java.util.List;

public final class HeadSpriteRenderer {
    private HeadSpriteRenderer() {
    }

    public static Component renderTag(List<PlayerHeadSymbol> symbols) {
        TextComponent.Builder builder = Component.text();
        for (PlayerHeadSymbol symbol : symbols) {
            builder.append(renderSymbol(symbol));
        }
        return builder.build();
    }

    public static Component renderImage(HeadSpriteImage image) {
        return renderImage(image, ShadowColor.shadowColor(255, 255, 255, 255));
    }

    public static Component renderImage(HeadSpriteImage image, ShadowColor shadowColor) {
        if (!image.isValidGrid()) {
            return Component.text("<invalid-image-grid>");
        }

        TextComponent.Builder full = Component.text();
        for (int y = 0; y < image.getHeightSymbols(); y++) {
            if (y > 0) {
                full.appendNewline();
            }
            for (int x = 0; x < image.getWidthSymbols(); x++) {
                full.append(renderSymbol(image.symbolAt(x, y), shadowColor));
            }
        }
        return full.build();
    }

    public static Component renderImageRow(HeadSpriteImage image, int rowIndex) {
        return renderImageRow(image, rowIndex, ShadowColor.shadowColor(255, 255, 255, 255));
    }

    public static Component renderImageRow(HeadSpriteImage image, int rowIndex, ShadowColor shadowColor) {
        if (!image.isValidGrid()) {
            return Component.text("<invalid-image-grid>");
        }
        if (rowIndex < 0 || rowIndex >= image.getHeightSymbols()) {
            return Component.text("<invalid-image-row:" + rowIndex + ">");
        }

        TextComponent.Builder row = Component.text();
        for (int x = 0; x < image.getWidthSymbols(); x++) {
            row.append(renderSymbol(image.symbolAt(x, rowIndex), shadowColor));
        }
        return row.build();
    }

    public static Component renderSymbol(PlayerHeadSymbol symbol) {
        return renderSymbol(symbol, ShadowColor.shadowColor(255, 255, 255, 255));
    }

    public static Component renderSymbol(PlayerHeadSymbol symbol, ShadowColor shadowColor) {
        PlayerHeadObjectContents.Builder builder = ObjectContents.playerHead();
        String value = symbol.getValue() == null ? "" : symbol.getValue().trim();
        String signature = symbol.getSignature() == null ? "" : symbol.getSignature().trim();
        if (!signature.isEmpty()) {
            builder.profileProperty(
                PlayerHeadObjectContents.property("textures", value, signature)
            );
        } else {
            builder.profileProperty(
                PlayerHeadObjectContents.property("textures", value)
            );
        }

        Style forcedStyle = Style.style(NamedTextColor.WHITE)
            .font(Key.key("minecraft:uniform"))
            .shadowColor(shadowColor == null ? ShadowColor.shadowColor(255, 255, 255, 255) : shadowColor);
        return Component.object(builder.build()).style(forcedStyle);
    }
}
