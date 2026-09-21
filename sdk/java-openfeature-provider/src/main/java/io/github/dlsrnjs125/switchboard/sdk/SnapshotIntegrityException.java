package io.github.dlsrnjs125.switchboard.sdk;

public final class SnapshotIntegrityException extends RuntimeException {
    public SnapshotIntegrityException(String message) {
        super(message);
    }

    public SnapshotIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
