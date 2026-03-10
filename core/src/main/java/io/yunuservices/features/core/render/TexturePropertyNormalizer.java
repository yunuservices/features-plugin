package io.yunuservices.features.core.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.yunuservices.features.core.model.PlayerHeadSymbol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TexturePropertyNormalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private TexturePropertyNormalizer() {
    }

    public static PlayerHeadSymbol normalize(PlayerHeadSymbol symbol) {
        if (symbol == null) {
            return null;
        }

        String normalizedValue = normalizeValue(symbol.getValue());
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return symbol;
        }

        String currentValue = symbol.getValue() == null ? "" : symbol.getValue().trim();
        String currentSignature = symbol.getSignature() == null ? "" : symbol.getSignature().trim();
        if (normalizedValue.equals(currentValue) && currentSignature.isEmpty()) {
            return symbol;
        }

        return new PlayerHeadSymbol(normalizedValue, currentSignature.isEmpty() ? null : currentSignature);
    }

    public static String normalizeValue(String base64Value) {
        if (base64Value == null || base64Value.isBlank()) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(DECODER.decode(base64Value.trim()));
            JsonNode skinNode = root.path("textures").path("SKIN");
            JsonNode urlNode = skinNode.path("url");
            if (!urlNode.isTextual() || urlNode.asText().isBlank()) {
                return null;
            }

            ObjectNode normalizedRoot = MAPPER.createObjectNode();
            ObjectNode textures = normalizedRoot.putObject("textures");
            ObjectNode skin = textures.putObject("SKIN");
            skin.put("url", urlNode.asText());

            JsonNode metadataNode = skinNode.path("metadata");
            if (metadataNode.isObject()) {
                skin.set("metadata", metadataNode.deepCopy());
            }

            String minimalJson = MAPPER.writeValueAsString(normalizedRoot);
            return ENCODER.encodeToString(minimalJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }
}
