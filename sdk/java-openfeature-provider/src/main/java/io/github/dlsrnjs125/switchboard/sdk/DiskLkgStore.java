package io.github.dlsrnjs125.switchboard.sdk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class DiskLkgStore {
    private final Path path;
    private final DirectorySync directorySync;

    public DiskLkgStore(Path path) {
        this(path, DiskLkgStore::forceDirectory);
    }

    DiskLkgStore(Path path, DirectorySync directorySync) {
        this.path = path.toAbsolutePath().normalize();
        this.directorySync = directorySync;
    }

    public Optional<SdkSnapshot> load(
            SnapshotDecoder decoder, Instant now, Duration maxAge) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            SdkSnapshot snapshot = decoder.decode(Files.readAllBytes(path));
            if (snapshot.generatedAt().plus(maxAge).isBefore(now)) {
                quarantine("expired");
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (RuntimeException | IOException exception) {
            quarantine("corrupt");
            return Optional.empty();
        }
    }

    public PersistenceResult persist(SdkSnapshot snapshot) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("LKG path must have a parent directory");
        }
        boolean replaced = false;
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(
                        temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(ByteBuffer.wrap(snapshot.canonicalJson()));
                    channel.force(true);
                }
                moveAtomically(temporary, path);
                replaced = true;
                directorySync.sync(parent);
                return PersistenceResult.DURABLE;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            if (replaced) {
                return PersistenceResult.COMMITTED_DURABILITY_UNCERTAIN;
            }
            throw new SnapshotIntegrityException("durable LKG cannot be persisted", exception);
        }
    }

    public boolean confirmDurability() {
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }
        try {
            directorySync.sync(parent);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void quarantine(String suffix) {
        try {
            Files.move(
                    path,
                    path.resolveSibling(path.getFileName() + "." + suffix + "." + Instant.now().toEpochMilli()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // A failed quarantine must not make an invalid LKG usable.
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public enum PersistenceResult {
        DURABLE,
        COMMITTED_DURABILITY_UNCERTAIN
    }

    @FunctionalInterface
    interface DirectorySync {
        void sync(Path directory) throws IOException;
    }
}
