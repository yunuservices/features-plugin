package io.yunuservices.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperMotdSupportTest {
    @Test
    void defaultPaperMotdConfigStartsDisabled() {
        PaperMotdSupport.PaperMotdConfig config = PaperMotdSupport.PaperMotdConfig.defaultConfig();

        assertFalse(config.isEnabled());
        assertTrue(config.isRowSystemEnabled());
        assertEquals(773, config.getProtocolRange().getMin());
        assertEquals(774, config.getProtocolRange().getMax());
        assertTrue(config.getImages().containsKey("paper_motd"));
        assertTrue(config.getMotds().containsKey("default"));
    }

    @Test
    void maxUncachedSymbolsPerImageClampsToSafeBounds() {
        PaperMotdSupport.PaperMotdConfig config = new PaperMotdSupport.PaperMotdConfig();
        config.setMaxUncachedSymbolsPerImage(0);
        assertEquals(1, config.getMaxUncachedSymbolsPerImage());

        config.setMaxUncachedSymbolsPerImage(500);
        assertEquals(256, config.getMaxUncachedSymbolsPerImage());
    }
}
