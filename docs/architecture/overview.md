# Switchboard Architecture Overview

Switchboard is a tenant-isolated feature-flag and runtime-configuration platform. The Control Plane owns authoring and atomic publication; PostgreSQL is authoritative; a transactional outbox publishes version notifications through Kafka; Distribution reloads and validates the immutable full Snapshot; authenticated gRPC streams deliver it to SDKs; the Java OpenFeature Provider evaluates locally from an atomically swapped memory Snapshot and durable last-known-good copy.

```text
operator -> Control Plane -> PostgreSQL (flag state + Snapshot + audit + outbox)
                               |
                         outbox relay -> Kafka notification
                                               |
                                               v
SDK/OpenFeature <- authenticated gRPC <- Distribution <- authoritative Snapshot reload
      |
      +-- atomic in-memory Snapshot + durable LKG -> local evaluation
```

The boundaries are intentional: management failure cannot add request-time evaluation I/O; Kafka carries notification, not authority; Distribution cannot invent state; SDKs never partially apply a Snapshot. Full details live in the [distribution dataflow](distribution-dataflow.md), [SDK lifecycle](sdk-lifecycle.md), and [contract versioning](contract-versioning.md).
