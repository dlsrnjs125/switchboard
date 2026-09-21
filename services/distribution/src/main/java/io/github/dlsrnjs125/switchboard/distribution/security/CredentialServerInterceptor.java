package io.github.dlsrnjs125.switchboard.distribution.security;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CredentialServerInterceptor implements ServerInterceptor {
    public static final Context.Key<CredentialPrincipal> PRINCIPAL = Context.key("switchboard-credential-principal");
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final DistributionRepository repository;

    public CredentialServerInterceptor(DistributionRepository repository) {
        this.repository = repository;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        try {
            CredentialToken token = parse(headers.get(AUTHORIZATION));
            CredentialPrincipal principal = repository.authenticate(token.id(), token.secret())
                    .orElseThrow(() -> Status.UNAUTHENTICATED.asRuntimeException());
            return Contexts.interceptCall(Context.current().withValue(PRINCIPAL, principal), call, headers, next);
        } catch (RuntimeException exception) {
            call.close(Status.UNAUTHENTICATED.withDescription("invalid service credential"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
    }

    private CredentialToken parse(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("missing bearer credential");
        }
        String[] parts = authorization.substring("Bearer ".length()).split("\\.", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            throw new IllegalArgumentException("invalid bearer credential");
        }
        return new CredentialToken(UUID.fromString(parts[0]), parts[1]);
    }

    private record CredentialToken(UUID id, String secret) {
    }
}
