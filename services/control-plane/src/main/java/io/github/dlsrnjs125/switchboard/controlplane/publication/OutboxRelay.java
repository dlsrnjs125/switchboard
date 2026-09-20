package io.github.dlsrnjs125.switchboard.controlplane.publication;

import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.OutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "switchboard.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {
    private static final int BATCH_SIZE = 100;
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final ControlPlaneRepository repository;
    private final SnapshotEventPublisher publisher;
    private final Clock clock;

    public OutboxRelay(
            ControlPlaneRepository repository,
            SnapshotEventPublisher publisher,
            Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${switchboard.outbox.poll-interval:PT1S}")
    @Transactional
    public void relay() {
        Instant now = clock.instant();
        for (OutboxEvent event : repository.lockPendingOutbox(BATCH_SIZE, now)) {
            try {
                publisher.publish(event.id(), event.payload());
                repository.markOutboxPublished(event.id(), clock.instant());
            } catch (Exception exception) {
                repository.markOutboxFailed(
                        event.id(),
                        now.plus(retryDelay(event.attemptCount() + 1)),
                        exception.getMessage());
            }
        }
    }

    private Duration retryDelay(int attempt) {
        long seconds = 1L << Math.min(attempt, 8);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }
}
