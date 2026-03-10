package io.yunuservices.features.core.render;

import io.yunuservices.features.core.model.PlayerHeadSymbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TexturePropertyNormalizerTest {
    @Test
    void normalizePreservesSignature() {
        PlayerHeadSymbol normalized = TexturePropertyNormalizer.normalize(new PlayerHeadSymbol(
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg4NDVmMzNlNzk1NGI2NmVkNjIxMGRjMzRhMmNmZTZhNThiZGQ5YWUyYTNiYzY4Nzk2Yzg5ZTU4MDNiYWNjNiIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9LCJleHRyYSI6InJlbW92ZS1tZSJ9fX0=",
            "signature-value"
        ));

        assertEquals(
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg4NDVmMzNlNzk1NGI2NmVkNjIxMGRjMzRhMmNmZTZhNThiZGQ5YWUyYTNiYzY4Nzk2Yzg5ZTU4MDNiYWNjNiIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9fX19",
            normalized.getValue()
        );
        assertEquals("signature-value", normalized.getSignature());
    }
}
