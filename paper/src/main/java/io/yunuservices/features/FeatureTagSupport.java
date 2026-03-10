package io.yunuservices.features;

import io.yunuservices.features.core.model.HeadSpriteImage;
import io.yunuservices.features.core.model.PlayerHeadSymbol;
import io.yunuservices.features.core.render.HeadSpriteRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FeatureTagSupport {
    private static final Pattern TEXTURE_URL_PATTERN =
        Pattern.compile("https?://textures\\.minecraft\\.net/texture/([A-Za-z0-9]+)");

    private FeatureTagSupport() {
    }

    static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    static String placeholderToken(String name) {
        return "<features_tag:" + normalizeName(name) + ">";
    }

    static String imagePlaceholderToken(String rawName, HeadSpriteImage image) {
        String name = normalizeName(rawName);
        if (image == null || !image.isValidGrid()) {
            return placeholderToken(name);
        }

        StringBuilder rendered = new StringBuilder();
        for (int y = 0; y < image.getHeightSymbols(); y++) {
            if (y > 0) {
                rendered.append('\n');
            }
            for (int x = 0; x < image.getWidthSymbols(); x++) {
                String token = symbolHeadToken(image.symbolAt(x, y));
                if (token == null) {
                    return placeholderToken(name);
                }
                rendered.append(token);
            }
        }
        return rendered.toString();
    }

    static Tag resolveTag(Map<String, HeadSpriteImage> tags, String rawName) {
        String name = normalizeName(rawName);
        HeadSpriteImage image = tags.get(name);
        if (image == null || !image.isValidGrid()) {
            return Tag.inserting(Component.text("<missing-tag:" + name + ">"));
        }
        return Tag.inserting(HeadSpriteRenderer.renderImage(image));
    }

    private static String symbolHeadToken(PlayerHeadSymbol symbol) {
        String value = symbol == null ? null : symbol.getValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmedValue = value.trim();
        String signature = symbol.getSignature();
        if (signature != null && !signature.isBlank()) {
            return "<head:signed_texture:" + trimmedValue + ";" + signature.trim() + ">";
        }

        String decoded = decodeTexturePayload(trimmedValue);
        if (decoded == null) {
            return null;
        }
        Matcher matcher = TEXTURE_URL_PATTERN.matcher(decoded);
        if (!matcher.find()) {
            return null;
        }
        return "<head:texture:" + matcher.group(1) + ">";
    }

    private static String decodeTexturePayload(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            try {
                return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignoredAgain) {
                return null;
            }
        }
    }
}
