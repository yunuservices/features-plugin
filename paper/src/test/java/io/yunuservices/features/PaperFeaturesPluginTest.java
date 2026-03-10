package io.yunuservices.features;

import io.yunuservices.features.core.model.HeadSpriteImage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperFeaturesPluginTest {
    @Test
    void generationSettingsClampToSafeBounds() {
        PaperFeaturesPlugin.GenerationSettings settings = new PaperFeaturesPlugin.GenerationSettings();
        settings.setMaxSymbolsPerTag(10_000);
        settings.setMaxConcurrentGenerations(0);
        settings.setProgressMessageInterval(10_000);

        assertEquals(256, settings.getMaxSymbolsPerTag());
        assertEquals(1, settings.getMaxConcurrentGenerations());
        assertEquals(64, settings.getProgressMessageInterval());
    }

    @Test
    void tagStorageNormalizesNullMaps() {
        PaperFeaturesPlugin.TagStorage storage = new PaperFeaturesPlugin.TagStorage();
        storage.setTags(null);

        assertNotNull(storage.getTags());
        assertTrue(storage.getTags().isEmpty());

        storage.setTags(
            java.util.Map.of("demo", new HeadSpriteImage(1, 1, List.of()))
        );
        assertEquals(1, storage.getTags().size());
    }
}
