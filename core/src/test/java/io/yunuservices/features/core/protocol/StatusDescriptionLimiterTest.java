package io.yunuservices.features.core.protocol;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusDescriptionLimiterTest {
    @Test
    void fitsWithinLimitAcceptsShortDescriptions() {
        assertTrue(StatusDescriptionLimiter.fitsWithinLimit(Component.text("Welcome")));
    }

    @Test
    void chooseSafeDescriptionFallsBackWhenPreferredIsTooLarge() {
        Component preferred = Component.text("x".repeat(StatusDescriptionLimiter.MAX_STATUS_DESCRIPTION_CHARS + 1));
        Component fallback = Component.text("Fallback");
        Component defaultDescription = Component.text("Default");

        Component chosen = StatusDescriptionLimiter.chooseSafeDescription(preferred, fallback, defaultDescription);

        assertSame(fallback, chosen);
    }

    @Test
    void chooseSafeDescriptionUsesDefaultWhenPreferredAndFallbackAreTooLarge() {
        Component oversized = Component.text("x".repeat(StatusDescriptionLimiter.MAX_STATUS_DESCRIPTION_CHARS + 1));
        Component defaultDescription = Component.text("Default");

        Component chosen = StatusDescriptionLimiter.chooseSafeDescription(oversized, oversized, defaultDescription);

        assertSame(defaultDescription, chosen);
    }

    @Test
    void fitsWithinLimitRejectsOversizedDescriptions() {
        assertFalse(
            StatusDescriptionLimiter.fitsWithinLimit(
                Component.text("x".repeat(StatusDescriptionLimiter.MAX_STATUS_DESCRIPTION_CHARS + 1))
            )
        );
    }
}
