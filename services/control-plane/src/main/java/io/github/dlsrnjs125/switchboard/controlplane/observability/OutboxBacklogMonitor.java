package io.github.dlsrnjs125.switchboard.controlplane.observability;

import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxBacklogMonitor {
    private final ControlPlaneRepository repository;
    private final OutboxTelemetry telemetry;
    private final Clock clock;

    public OutboxBacklogMonitor(
            ControlPlaneRepository repository,
            OutboxTelemetry telemetry,
            Clock clock) {
        this.repository = repository;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${switchboard.observability.outbox-sample-interval:PT5S}")
    public void sample() {
        var backlog = repository.outboxBacklog();
        Duration oldestAge = backlog.oldestCreatedAt() == null
                ? Duration.ZERO
                : Duration.between(backlog.oldestCreatedAt(), clock.instant());
        telemetry.updateBacklog(backlog.pendingCount(), oldestAge);
    }
}
