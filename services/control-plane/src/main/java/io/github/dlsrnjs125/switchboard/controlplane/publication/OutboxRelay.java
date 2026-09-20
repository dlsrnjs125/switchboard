package io.github.dlsrnjs125.switchboard.controlplane.publication;

import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.OutboxEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "switchboard.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {
    private static final int BATCH_SIZE = 100;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final ControlPlaneRepository repository;
    private final SnapshotEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OutboxRelay(
            ControlPlaneRepository repository,
            SnapshotEventPublisher publisher,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${switchboard.outbox.poll-interval:PT1S}")
    public void relay() {
        for (int claimed = 0; claimed < BATCH_SIZE; claimed++) {
            UUID claimToken = UUID.randomUUID();
            Instant claimedAt = clock.instant();
            OutboxEvent event = transactions.execute(status -> repository.claimNextOutbox(
                    claimToken, claimedAt, claimedAt.minus(CLAIM_LEASE)).orElse(null));
            if (event == null) {
                return;
            }
            try {
                publisher.publish(event.id(), event.payload());
                transactions.executeWithoutResult(status ->
                        repository.markOutboxPublished(event.id(), claimToken, clock.instant()));
            } catch (Exception exception) {
                Instant failedAt = clock.instant();
                transactions.executeWithoutResult(status -> repository.markOutboxFailed(
                        event.id(), claimToken, failedAt.plus(retryDelay(event.attemptCount() + 1)),
                        exception.getMessage()));
            }
        }
    }

    private Duration retryDelay(int attempt) {
        long seconds = 1L << Math.min(attempt, 8);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }
}
