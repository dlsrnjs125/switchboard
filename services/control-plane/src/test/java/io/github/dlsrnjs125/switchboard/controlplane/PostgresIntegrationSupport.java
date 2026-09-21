package io.github.dlsrnjs125.switchboard.controlplane;

import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotValidator;
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
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

abstract class PostgresIntegrationSupport {
    private static final Network NETWORK = Network.newNetwork();
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK);
    static final ToxiproxyContainer.ContainerProxy POSTGRES_PROXY;

    protected static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        POSTGRES_PROXY = TOXIPROXY.getProxy(POSTGRES, 5432);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES_PROXY.getContainerIpAddress() + ":"
                        + POSTGRES_PROXY.getProxyPort() + "/" + POSTGRES.getDatabaseName()
                        + "?connectTimeout=2&socketTimeout=2",
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        DATA_SOURCE = dataSource;
    }

    protected JdbcTemplate jdbc;
    protected TransactionTemplate transaction;
    protected ControlPlaneService service;
    protected ControlPlaneRepository repository;
    protected Clock clock;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(DATA_SOURCE);
        jdbc.execute("TRUNCATE TABLE tenants CASCADE");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new ControlPlaneRepository(
                new NamedParameterJdbcTemplate(DATA_SOURCE), objectMapper);
        clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
        service = new ControlPlaneService(
                repository,
                new UuidV7Generator(),
                clock,
                new SnapshotCompiler(objectMapper),
                new SnapshotValidator());
    }

    protected <T> T inTransaction(org.springframework.transaction.support.TransactionCallback<T> callback) {
        return transaction.execute(callback);
    }
}
