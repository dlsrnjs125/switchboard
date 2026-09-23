# TRB-012 — SDK ACK Retry Under Reconnect Pressure

## Context

After validating, persisting, and atomically applying a Snapshot, the Java Provider sends a unary ACK carrying the server-generated delivery ID. Reconnect storms create short-lived contention and transient unary RPC failures even after the stream has delivered the Snapshot.

## Symptom

The 500-client reconnect candidate recovered every client to the new Snapshot, but the server observed only 492 accepted ACKs. `GrpcSnapshotTransport` caught an ACK exception, marked the listener disconnected/stale, and discarded the acknowledgement without retrying it.

## Expected vs Actual

- Expected: a transient ACK RPC failure is retried with the same delivery ID; success is eventually observable without reapplying the Snapshot.
- Actual: one failed unary call permanently lost that delivery acknowledgement.

## Reproduction

Return gRPC `UNAVAILABLE` for the first two ACK calls after a valid full Snapshot, then allow the third call. The original transport sent only the first call.

## Impact

The SDK had safely applied the configuration, but server-side delivery latency and acknowledgement state were incomplete. During a storm this created false recovery gaps and could trigger unnecessary operational investigation.

## Initial Hypothesis

Stream reconnection was assumed to be sufficient recovery for every gRPC operation, even though ACK is a separate unary RPC and its failure did not schedule a new subscription.

## Evidence

`Phase9ReconnectStormEvidenceTest` exposed missing accepted ACKs after complete Snapshot recovery. `GrpcSnapshotTransportIntegrationTest.retriesTransientAcknowledgementFailureWithTheSameDelivery` injects two failures and requires the third identical delivery ACK to succeed.

## Root Cause

Reconnect retry policy covered the streaming `Subscribe` call but not post-apply unary acknowledgement. The catch path notified state but had no retry ownership.

## Fix

Build the immutable `AckRequest` once, allow a 30-second background RPC deadline, and retry retryable gRPC failures up to five attempts with 50/100/200/400 ms bounded exponential delays. Every retry preserves delivery ID, version, checksum, and authenticated scope. Permanent status codes fail immediately; only retry exhaustion notifies the listener of disconnection.

## Verification

Run `./gradlew :sdk:java-openfeature-provider:test` and `make phase9-reconnect-evidence`. The injected integration test must observe exactly three attempts and the reconnect cohorts must receive one accepted ACK per recovered client.

## Trade-off

A server may process a successful ACK whose response is lost, then receive a duplicate. ACK correctness is already idempotent; delivery-latency telemetry consumes the matching delivery ID once and ignores later duplicates.

## Prevention

List retry ownership separately for every streaming and unary RPC. Test response loss after server processing, not only inability to connect.

## Related ADR / PR / Commit

- ADR-004, ADR-009
- Phase 9 final runtime evidence branch
- Commit assigned when this change is committed
- `EV-P09-RCN-001`

## Blog Candidate Summary

Why reconnect logic does not automatically make post-apply unary acknowledgements reliable.
