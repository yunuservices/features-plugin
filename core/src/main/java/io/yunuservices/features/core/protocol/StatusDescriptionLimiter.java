package io.yunuservices.features.core.protocol;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

public final class StatusDescriptionLimiter {
    public static final int MAX_STATUS_DESCRIPTION_CHARS = 32_767;

    private static final Component HARD_FALLBACK_DESCRIPTION = Component.text("Features is loaded.");
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private StatusDescriptionLimiter() {
    }

    public static boolean fitsWithinLimit(Component description) {
        return description != null && GSON.serialize(description).length() <= MAX_STATUS_DESCRIPTION_CHARS;
    }

    public static Component chooseSafeDescription(Component preferred, Component fallback, Component defaultDescription) {
        if (fitsWithinLimit(preferred)) {
            return preferred;
        }
        if (fallback != null && !fallback.equals(preferred) && fitsWithinLimit(fallback)) {
            return fallback;
        }
        if (fitsWithinLimit(defaultDescription)) {
            return defaultDescription;
        }
        return HARD_FALLBACK_DESCRIPTION;
    }
}
