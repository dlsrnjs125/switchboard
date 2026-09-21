# Observability Conventions

## Signal contract

Switchboard uses Micrometer Observation as the instrumentation boundary, Prometheus for metrics, OpenTelemetry Protocol for traces, Tempo for trace storage, and structured application logs for event detail. Metrics answer whether a layer is healthy; traces and logs explain an individual publish or delivery path.

Metric names start with `switchboard.` in Java and are exported by Prometheus with underscores. Every metric label must be selected from the shared allowlist in `TelemetryPolicy`: `component`, `operation`, `outcome`, `reason`, `error_code`, `provider_state`, `event_type`, and `result_type`.

## Cardinality and privacy

Low-cardinality metric labels contain only bounded enums or fixed operation names. The following values are forbidden in metric labels and logs:

- `targetingKey`, user ID, email, or any evaluation-context attribute;
- credential IDs, secrets, bearer tokens, authorization headers, and passwords;
- Snapshot payloads, flag values, or tenant-provided configuration content;
- environment, project, Snapshot, event, or correlation identifiers as metric labels.

High-cardinality identifiers may be trace attributes or structured-log fields when they are non-secret operational identifiers. Current approved fields are `correlation.id`, `event.id`, `snapshot.version`, `environment.key`, and `client.application.id`. Raw credentials and evaluation identity remain forbidden in every signal.

`TelemetryPolicyTest` enforces the label allowlist and sensitive-key rejection. Service-level tests verify that dynamic reasons collapse to a bounded value and that SDK meters do not expose targeting or environment dimensions.

## Correlation model

The publish path is correlated with stable identifiers rather than one long-lived parent span:

1. Control Plane publication records a prepared signal after the transaction body succeeds, then records the final success signal and stops the publication observation only after transaction completion reports a commit. Rollback never increments final publish success.
2. The outbox relay creates one finite delivery observation keyed by stable event ID.
3. Distribution creates one finite reconciliation observation keyed by event ID and Snapshot version.
4. Each gRPC subscribe, Snapshot send, ACK, NACK, and resync is recorded as a short event observation.
5. The SDK creates finite Snapshot-apply and evaluation observations and records provider state separately.

Kafka producer/consumer observation is enabled so supported headers propagate transport trace context. Database-to-outbox and gRPC stream boundaries remain searchable by event ID and Snapshot version. A Subscribe stream is deliberately not represented as a span that remains open for the lifetime of the connection.

## Primary signals

| Layer | Signals | Diagnostic question |
| --- | --- | --- |
| Control Plane | prepared publication count, commit-qualified `switchboard.control.publish.*`, compile/validation duration, transaction outcome | Was the change prepared, then actually committed? |
| Outbox | pending count, oldest pending age, delivery outcome, broker-ACK latency | Is a committed change waiting for Kafka? |
| Distribution | reconcile outcome/duration, cache version, connected sessions, gRPC events, per-client Snapshot-send-to-ACK latency | Was the event reconciled, emitted to a client, and acknowledged? |
| Java SDK | active state, Snapshot age, apply outcome, reconnects, stale duration, evaluation duration/reason | Is a client current and evaluating locally? |

The dashboard intentionally avoids per-tenant or per-client labels. Identifying one stale client requires trace/log search using its operational identifiers; fleet health remains low-cardinality.

## Trace and log rules

- Record exception class names in logs, not exception messages that may contain input.
- Use trace/span IDs in the logging pattern for cross-navigation.
- End spans at operation boundaries. Retries, reconnects, and stream messages create separate observations.
- Do not attach Snapshot JSON, evaluation context, flag values, or credentials to spans.
- Treat a metric-label change as a compatibility and capacity review item.
