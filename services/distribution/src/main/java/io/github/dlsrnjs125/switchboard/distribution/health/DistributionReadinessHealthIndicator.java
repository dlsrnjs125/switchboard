package io.github.dlsrnjs125.switchboard.distribution.health;

import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("distributionReadiness")
public class DistributionReadinessHealthIndicator implements HealthIndicator {
    private final DistributionRepository repository;

    public DistributionReadinessHealthIndicator(DistributionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        return repository.isAvailable()
                ? Health.up().withDetail("authoritativeStore", "reachable").build()
                : Health.down().withDetail("authoritativeStore", "unreachable").build();
    }
}
