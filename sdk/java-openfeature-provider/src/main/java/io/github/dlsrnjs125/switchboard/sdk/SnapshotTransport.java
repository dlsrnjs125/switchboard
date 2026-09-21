package io.github.dlsrnjs125.switchboard.sdk;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import java.util.function.LongSupplier;

public interface SnapshotTransport extends AutoCloseable {
    void start(LongSupplier lastAppliedVersion, Listener listener);

    void acknowledge(String deliveryId, long snapshotVersion, String checksum);

    void reject(long snapshotVersion, String reasonCode, String detail);

    void requestResync(long lastAppliedVersion, String reasonCode);

    @Override
    void close();

    interface Listener {
        void onConnected();

        void onSnapshot(FullSnapshot snapshot);

        void onHeartbeat(long currentSnapshotVersion);

        void onResyncRequired(String reasonCode, long currentSnapshotVersion);

        void onCredentialRevoked();

        void onDisconnected(Throwable cause);
    }
}
