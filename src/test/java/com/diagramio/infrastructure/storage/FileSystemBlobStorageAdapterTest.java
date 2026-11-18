package com.diagramio.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemBlobStorageAdapterTest {

    @TempDir
    Path tempDir;

    private FileSystemBlobStorageAdapter storageAdapter;

    @BeforeEach
    void setUp() {
        storageAdapter = new FileSystemBlobStorageAdapter(tempDir);
    }

    @Test
    void storeAndLoadBlobSuccessfully() {
        String key = "diagrams/d1/data.json";
        String content = "{\"nodes\": [{\"id\": \"1\"}], \"edges\": []}";

        storageAdapter.store(key, content);

        Optional<String> loaded = storageAdapter.load(key);
        assertThat(loaded).isPresent().contains(content);
        assertThat(Files.exists(tempDir.resolve(key))).isTrue();
    }

    @Test
    void loadNonExistentKeyReturnsEmpty() {
        Optional<String> loaded = storageAdapter.load("non/existent/path.json");
        assertThat(loaded).isEmpty();
    }

    @Test
    void overwriteExistingBlob() {
        String key = "diagrams/d1/data.json";
        storageAdapter.store(key, "{\"nodes\": []}");
        storageAdapter.store(key, "{\"nodes\": [{\"id\": \"updated\"}]}");

        Optional<String> loaded = storageAdapter.load(key);
        assertThat(loaded).isPresent().contains("{\"nodes\": [{\"id\": \"updated\"}]}");
    }

    @Test
    void deleteRemovesFile() {
        String key = "diagrams/d1/data.json";
        storageAdapter.store(key, "{\"nodes\": []}");
        assertThat(storageAdapter.load(key)).isPresent();

        storageAdapter.delete(key);
        assertThat(storageAdapter.load(key)).isEmpty();
    }

    @Test
    void deleteAllPrefixDeletesDirectoryTree() {
        storageAdapter.store("diagrams/d1/data.json", "{\"data\": 1}");
        storageAdapter.store("diagrams/d1/versions/v1.json", "{\"v\": 1}");
        storageAdapter.store("diagrams/d1/versions/v2.json", "{\"v\": 2}");
        storageAdapter.store("diagrams/d2/data.json", "{\"d\": 2}");

        storageAdapter.deleteAllPrefix("diagrams/d1");

        assertThat(storageAdapter.load("diagrams/d1/data.json")).isEmpty();
        assertThat(storageAdapter.load("diagrams/d1/versions/v1.json")).isEmpty();
        assertThat(storageAdapter.load("diagrams/d1/versions/v2.json")).isEmpty();
        // d2 remains intact
        assertThat(storageAdapter.load("diagrams/d2/data.json")).isPresent();
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> storageAdapter.store("../outside.json", "hacked"))
                .isInstanceOf(SecurityException.class);
    }
}
