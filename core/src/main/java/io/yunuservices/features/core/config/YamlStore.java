package io.yunuservices.features.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

public class YamlStore {
    private final ObjectMapper mapper;

    public YamlStore() {
        YAMLFactory yamlFactory = new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        this.mapper = new ObjectMapper(yamlFactory)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();
    }

    public <T> T readOrDefault(Path file, Class<T> clazz, Supplier<T> defaultSupplier) throws IOException {
        if (!Files.exists(file)) {
            T value = defaultSupplier.get();
            write(file, value);
            return value;
        }
        return mapper.readValue(file.toFile(), clazz);
    }

    public <T> void write(Path file, T value) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = parent != null
            ? Files.createTempFile(parent, file.getFileName().toString(), ".tmp")
            : Files.createTempFile(file.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(tempFile.toFile(), value);
            try {
                Files.move(
                    tempFile,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
