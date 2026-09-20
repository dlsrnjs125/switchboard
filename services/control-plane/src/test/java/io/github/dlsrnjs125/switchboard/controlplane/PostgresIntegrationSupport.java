package io.github.dlsrnjs125.switchboard.controlplane;

import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.infrastructure.UuidV7Generator;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

abstract class PostgresIntegrationSupport {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    protected static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        DATA_SOURCE = dataSource;
    }

    protected JdbcTemplate jdbc;
    protected TransactionTemplate transaction;
    protected ControlPlaneService service;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(DATA_SOURCE);
        jdbc.execute("TRUNCATE TABLE tenants CASCADE");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        ControlPlaneRepository repository = new ControlPlaneRepository(
                new NamedParameterJdbcTemplate(DATA_SOURCE), new ObjectMapper());
        service = new ControlPlaneService(
                repository,
                new UuidV7Generator(),
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));
    }

    protected <T> T inTransaction(org.springframework.transaction.support.TransactionCallback<T> callback) {
        return transaction.execute(callback);
    }
}
