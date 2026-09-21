package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotCoordinatorIntegrationTest extends DistributionPostgresSupport {
    @Test
    void authenticatesScopeAndReconcilesDuplicateOutOfOrderGapAndConflict() {
        SnapshotFixture versionSeven = insertSnapshot(7);
        List<Long> applied = new ArrayList<>();
        SnapshotCoordinator coordinator = coordinator(applied);
        UUID eventSeven = UUID.randomUUID();

        assertTrue(repository.authenticate(credentialId, secret).isPresent());
        assertTrue(repository.authenticate(credentialId, "wrong-secret").isEmpty());
        assertEquals(SnapshotCoordinator.ReconcileOutcome.APPLIED_CURRENT, coordinator.reconcile(notification(
                eventSeven, versionSeven.version(), versionSeven.checksum())));
        assertEquals(SnapshotCoordinator.ReconcileOutcome.DUPLICATE_EVENT, coordinator.reconcile(notification(
                eventSeven, versionSeven.version(), versionSeven.checksum())));
        assertEquals(SnapshotCoordinator.ReconcileOutcome.ALREADY_CURRENT, coordinator.reconcile(notification(
                UUID.randomUUID(), 6, "a".repeat(64))));

        assertThrows(SnapshotIntegrityException.class, () -> coordinator.reconcile(notification(
                UUID.randomUUID(), versionSeven.version(), "b".repeat(64))));
        assertEquals(7, coordinator.cached(scope()).orElseThrow().snapshotVersion());

        SnapshotFixture versionNine = insertSnapshot(9);
        assertEquals(SnapshotCoordinator.ReconcileOutcome.APPLIED_CURRENT, coordinator.reconcile(notification(
                UUID.randomUUID(), versionNine.version(), versionNine.checksum())));
        assertEquals(List.of(7L, 9L), applied);
        assertEquals(9, coordinator.cached(scope()).orElseThrow().snapshotVersion());
    }

    @Test
    void rejectsCorruptAuthoritativeSnapshotWithoutReplacingActiveCache() {
        SnapshotFixture valid = insertSnapshot(1);
        SnapshotCoordinator coordinator = coordinator(new ArrayList<>());
        coordinator.reconcile(notification(UUID.randomUUID(), 1, valid.checksum()));

        jdbc.execute("ALTER TABLE configuration_snapshots DISABLE TRIGGER trg_configuration_snapshots_immutable");
        jdbc.update("UPDATE configuration_snapshots SET payload = jsonb_set(payload, '{checksum}', '" +
                "\"" + "f".repeat(64) + "\"'::jsonb) WHERE id = ?", valid.id());
        jdbc.execute("ALTER TABLE configuration_snapshots ENABLE TRIGGER trg_configuration_snapshots_immutable");

        assertThrows(SnapshotIntegrityException.class, () -> coordinator.current(scope()));
        assertEquals(valid.checksum(), coordinator.cached(scope()).orElseThrow().checksum());

        SnapshotFixture recovered = insertSnapshot(2);
        assertEquals(recovered.checksum(), coordinator.current(scope()).orElseThrow().checksum());
        assertEquals(2, coordinator.cached(scope()).orElseThrow().snapshotVersion());
    }

    @Test
    void revokedCredentialCannotAuthenticate() {
        assertTrue(repository.authenticate(credentialId, secret).isPresent());
        revokeCredential();
        assertTrue(repository.authenticate(credentialId, secret).isEmpty());
    }

    private SnapshotCoordinator coordinator(List<Long> applied) {
        return new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                new SnapshotCache(),
                new ProcessedEventWindow(),
                List.of(snapshot -> applied.add(snapshot.snapshotVersion())));
    }

    private SnapshotNotification notification(UUID eventId, long version, String checksum) {
        UUID snapshotId = repository.loadCurrentSnapshot(scope()).orElseThrow().snapshotId();
        return new SnapshotNotification(
                eventId, "acme", "checkout", "production", snapshotId, version, checksum);
    }
}
