package io.github.dlsrnjs125.switchboard.sdk;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

final class FakeSnapshotTransport implements SnapshotTransport {
    Listener listener;
    LongSupplier lastVersion;
    final List<String> actions = new ArrayList<>();

    @Override
    public void start(LongSupplier lastAppliedVersion, Listener listener) {
        this.lastVersion = lastAppliedVersion;
        this.listener = listener;
        actions.add("start:" + lastAppliedVersion.getAsLong());
    }

    @Override
    public void acknowledge(String deliveryId, long snapshotVersion, String checksum) {
        actions.add("ack:" + snapshotVersion + ":" + checksum);
    }

    @Override
    public void reject(long snapshotVersion, String reasonCode, String detail) {
        actions.add("nack:" + snapshotVersion + ":" + reasonCode);
    }

    @Override
    public void requestResync(long lastAppliedVersion, String reasonCode) {
        actions.add("resync:" + lastAppliedVersion + ":" + reasonCode);
    }

    @Override
    public void close() {
        actions.add("close");
    }

    void emit(FullSnapshot snapshot) {
        listener.onSnapshot(snapshot);
    }

    void disconnect() {
        listener.onDisconnected(new IllegalStateException("offline"));
    }

    void heartbeat(long version) {
        listener.onHeartbeat(version);
    }
}
