package io.github.dlsrnjs125.switchboard.distribution.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

@SuppressWarnings("unchecked")
class CredentialServerInterceptorTest {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void mapsCredentialRepositoryFailureToUnavailable() {
        DistributionRepository repository = mock(DistributionRepository.class);
        UUID credentialId = UUID.randomUUID();
        when(repository.authenticate(credentialId, "secret"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        CredentialServerInterceptor interceptor = new CredentialServerInterceptor(repository);
        ServerCall<String, String> call = mock(ServerCall.class);
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, bearer(credentialId, "secret"), next);

        assertClosedWith(call, Status.Code.UNAVAILABLE);
        verifyNoInteractions(next);
    }

    @Test
    void keepsInvalidCredentialAsUnauthenticated() {
        DistributionRepository repository = mock(DistributionRepository.class);
        UUID credentialId = UUID.randomUUID();
        when(repository.authenticate(credentialId, "wrong-secret")).thenReturn(Optional.empty());
        CredentialServerInterceptor interceptor = new CredentialServerInterceptor(repository);
        ServerCall<String, String> call = mock(ServerCall.class);
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, bearer(credentialId, "wrong-secret"), next);

        assertClosedWith(call, Status.Code.UNAUTHENTICATED);
        verifyNoInteractions(next);
    }

    @Test
    void mapsUnexpectedRepositoryFailureToInternal() {
        DistributionRepository repository = mock(DistributionRepository.class);
        UUID credentialId = UUID.randomUUID();
        when(repository.authenticate(credentialId, "secret"))
                .thenThrow(new IllegalStateException("broken credential invariant"));
        CredentialServerInterceptor interceptor = new CredentialServerInterceptor(repository);
        ServerCall<String, String> call = mock(ServerCall.class);
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, bearer(credentialId, "secret"), next);

        assertClosedWith(call, Status.Code.INTERNAL);
        verifyNoInteractions(next);
    }

    private Metadata bearer(UUID credentialId, String secret) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + credentialId + "." + secret);
        return headers;
    }

    private void assertClosedWith(ServerCall<String, String> call, Status.Code expected) {
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        org.junit.jupiter.api.Assertions.assertEquals(expected, status.getValue().getCode());
    }
}
