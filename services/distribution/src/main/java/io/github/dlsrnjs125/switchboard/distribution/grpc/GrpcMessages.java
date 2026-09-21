package io.github.dlsrnjs125.switchboard.distribution.grpc;

import com.google.protobuf.ByteString;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.CredentialRevoked;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.Heartbeat;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ResyncRequired;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;

final class GrpcMessages {
    private GrpcMessages() {
    }

    static ServerMessage snapshot(SnapshotArtifact artifact) {
        return ServerMessage.newBuilder()
                .setFullSnapshot(FullSnapshot.newBuilder()
                        .setSnapshotVersion(artifact.snapshotVersion())
                        .setSchemaVersion(artifact.schemaVersion())
                        .setChecksum(artifact.checksum())
                        .setCanonicalJson(ByteString.copyFrom(artifact.canonicalJson())))
                .build();
    }

    static ServerMessage heartbeat(long nowMillis, long currentVersion) {
        return ServerMessage.newBuilder()
                .setHeartbeat(Heartbeat.newBuilder()
                        .setServerTimeEpochMillis(nowMillis)
                        .setCurrentSnapshotVersion(currentVersion))
                .build();
    }

    static ServerMessage resyncRequired(String reason, long currentVersion) {
        return ServerMessage.newBuilder()
                .setResyncRequired(ResyncRequired.newBuilder()
                        .setReasonCode(reason)
                        .setCurrentSnapshotVersion(currentVersion))
                .build();
    }

    static ServerMessage credentialRevoked(String credentialId) {
        return ServerMessage.newBuilder()
                .setCredentialRevoked(CredentialRevoked.newBuilder().setCredentialId(credentialId))
                .build();
    }
}
