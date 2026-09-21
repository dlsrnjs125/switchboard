package io.github.dlsrnjs125.switchboard.distribution.grpc;

import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private final SnapshotDistributionGrpcService service;
    private final CredentialServerInterceptor authentication;
    private final SessionRegistry sessions;
    private final int configuredPort;
    private final HealthStatusManager health = new HealthStatusManager();
    private volatile Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(
            SnapshotDistributionGrpcService service,
            CredentialServerInterceptor authentication,
            SessionRegistry sessions,
            @Value("${switchboard.distribution.grpc-port:9090}") int configuredPort) {
        this.service = service;
        this.authentication = authentication;
        this.sessions = sessions;
        this.configuredPort = configuredPort;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(configuredPort)
                    .addService(ServerInterceptors.intercept(service, authentication))
                    .addService(health.getHealthService())
                    .build()
                    .start();
            health.setStatus("", ServingStatus.SERVING);
            running = true;
        } catch (IOException exception) {
            throw new IllegalStateException("gRPC server cannot start", exception);
        }
    }

    @Override
    public void stop() {
        running = false;
        health.enterTerminalState();
        sessions.closeAll();
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public int port() {
        return server == null ? configuredPort : server.getPort();
    }
}
