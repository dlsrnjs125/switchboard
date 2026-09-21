package io.github.dlsrnjs125.switchboard.distribution.snapshot;

public class SnapshotIntegrityException extends RuntimeException {
    public SnapshotIntegrityException(String message) {
        super(message);
    }

    public SnapshotIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
