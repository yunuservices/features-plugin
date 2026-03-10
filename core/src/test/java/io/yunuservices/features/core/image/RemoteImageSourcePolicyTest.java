package io.yunuservices.features.core.image;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteImageSourcePolicyTest {
    @Test
    void resolveImportFileRejectsTraversal() {
        Path importDir = Path.of("build", "tmp", "import-root");

        IOException exception = assertThrows(
            IOException.class,
            () -> RemoteImageSourcePolicy.resolveImportFile(importDir, "..\\..\\secret.png")
        );

        assertEquals("File path escapes import directory.", exception.getMessage());
    }

    @Test
    void resolveRemoteUrlRejectsLocalhostWhenPrivateHostsAreBlocked() {
        IOException exception = assertThrows(
            IOException.class,
            () -> RemoteImageSourcePolicy.resolveRemoteUrl(
                "http://127.0.0.1/example.png",
                true,
                false
            )
        );

        assertEquals("Private/local URLs are not allowed.", exception.getMessage());
    }

    @Test
    void resolveRemoteUrlAllowsExplicitPrivateHostsWhenEnabled() throws Exception {
        URL url = RemoteImageSourcePolicy.resolveRemoteUrl(
            "http://127.0.0.1/example.png",
            true,
            true
        );

        assertEquals("http://127.0.0.1/example.png", url.toString());
    }
}
