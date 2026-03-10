package io.yunuservices.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityFeaturesPluginTest {
    @Test
    void velocityConfigClampsUncachedSymbolLimit() {
        VelocityFeaturesPlugin.VelocityConfig config = new VelocityFeaturesPlugin.VelocityConfig();
        config.setMaxUncachedSymbolsPerImage(0);
        assertEquals(1, config.getMaxUncachedSymbolsPerImage());

        config.setMaxUncachedSymbolsPerImage(10_000);
        assertEquals(256, config.getMaxUncachedSymbolsPerImage());
    }

    @Test
    void defaultConfigBuildsSafeDefaults() {
        VelocityFeaturesPlugin.VelocityConfig config = VelocityFeaturesPlugin.VelocityConfig.defaultConfig();

        assertTrue(config.isEnabled());
        assertTrue(config.isRowSystemEnabled());
        assertEquals(773, config.getProtocolRange().getMin());
        assertEquals(774, config.getProtocolRange().getMax());
        assertFalse(config.getImages().isEmpty());
        assertFalse(config.getMotds().isEmpty());
    }

    @Test
    void velocitySettingsNormalizesNullMineSkinConfig() {
        VelocityFeaturesPlugin.VelocitySettings settings = new VelocityFeaturesPlugin.VelocitySettings();
        settings.setMineSkin(null);

        assertNotNull(settings.getMineSkin());
    }
}
