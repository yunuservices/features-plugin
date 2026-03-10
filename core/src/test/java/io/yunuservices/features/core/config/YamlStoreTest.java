package io.yunuservices.features.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void writeOmitsDocumentStartMarker() throws Exception {
        Path file = tempDir.resolve("config.yml");
        TestConfig value = new TestConfig();
        value.enabled = true;

        new YamlStore().write(file, value);

        String yaml = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(yaml.startsWith("---"));
        assertTrue(yaml.startsWith("enabled: true"));
    }

    static final class TestConfig {
        public boolean enabled;
    }
}
