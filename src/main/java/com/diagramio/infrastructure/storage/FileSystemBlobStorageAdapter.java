package com.diagramio.infrastructure.storage;

import com.diagramio.domain.diagram.BlobStoragePort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSystemBlobStorageAdapter implements BlobStoragePort {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBlobStorageAdapter.class);

    private final Path rootDir;

    public FileSystemBlobStorageAdapter(BlobStorageProperties properties) {
        this(Paths.get(properties.getBlobDir()));
    }

    public FileSystemBlobStorageAdapter(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create blob storage root directory: " + this.rootDir, e);
        }
    }

    @Override
    public void store(String key, String content) {
        Path target = resolveKey(key);
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent, "blob-write-", ".tmp");
            try {
                Files.writeString(tempFile, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Stored blob for key {} at {}", key, target);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store blob for key: " + key, e);
        }
    }

    @Override
    public Optional<String> load(String key) {
        Path target = resolveKey(key);
        if (!Files.exists(target)) {
            log.debug("Blob not found for key {} at {}", key, target);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolveKey(key);
        try {
            Files.deleteIfExists(target);
            log.debug("Deleted blob for key {} at {}", key, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete blob for key: " + key, e);
        }
    }

    @Override
    public void deleteAllPrefix(String prefix) {
        Path prefixPath = resolveKey(prefix);
        if (prefixPath.equals(rootDir)) {
            throw new IllegalArgumentException("Cannot delete root directory as prefix");
        }
        if (!Files.exists(prefixPath)) {
            return;
        }
        try {
            Files.walkFileTree(prefixPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    if (!dir.equals(rootDir)) {
                        Files.deleteIfExists(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            log.debug("Deleted all blobs under prefix {}", prefix);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete blobs under prefix: " + prefix, e);
        }
    }

    private Path resolveKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be null or blank");
        }
        Path resolved = rootDir.resolve(key).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("Storage key attempts path traversal: " + key);
        }
        return resolved;
    }

    public Path getRootDir() {
        return rootDir;
    }
}
