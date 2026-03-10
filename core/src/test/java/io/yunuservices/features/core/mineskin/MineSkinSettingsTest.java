package io.yunuservices.features.core.mineskin;

import org.junit.jupiter.api.Test;
import org.mineskin.data.Visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineSkinSettingsTest {
    @Test
    void nullsAreNormalizedToSafeDefaults() {
        MineSkinSettings settings = new MineSkinSettings();
        settings.setVisibility(null);
        settings.setLimits(null);

        assertTrue(settings.isEnabled());
        assertSame(Visibility.UNLISTED, settings.getVisibility());
        assertEquals(200, settings.getLimits().getIntervalMillis());
        assertEquals(1, settings.getLimits().getConcurrency());
        assertEquals(300, settings.getLimits().getTimeoutSeconds());
    }

    @Test
    void limitsAreClampedToSafeBounds() {
        MineSkinSettings.Limits limits = new MineSkinSettings.Limits();
        limits.setIntervalMillis(1);
        limits.setConcurrency(99);
        limits.setTimeoutSeconds(5_000);

        assertEquals(50, limits.getIntervalMillis());
        assertEquals(4, limits.getConcurrency());
        assertEquals(600, limits.getTimeoutSeconds());
    }

    @Test
    void mineSkinAvailabilityRequiresEnabledFlagAndApiKey() {
        MineSkinSettings settings = new MineSkinSettings();
        settings.setApiKey("demo");

        assertTrue(settings.hasApiKey());
        assertTrue(settings.isAvailable());

        settings.setEnabled(false);
        assertFalse(settings.isAvailable());
    }
}
