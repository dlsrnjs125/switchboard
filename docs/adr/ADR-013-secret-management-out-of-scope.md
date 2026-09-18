# ADR-013: Keep secret management outside Switchboard

- Status: Accepted
- Date: 2026-09-18
- Foundation: Runtime configuration, ServiceCredential, Audit boundary

## Context

Runtime configuration can contain typed values, but secret storage requires encryption, access brokerage, rotation, redaction, and threat controls that differ from feature delivery. Mixing them would expand the platform's security boundary substantially.

## Decision

Switchboard MUST NOT be used to store application passwords, API secrets, private keys, or database credentials. Such values remain in a dedicated secret manager such as Vault or a cloud KMS-backed service. Switchboard service credentials authenticate clients but are security metadata, not runtime configuration values.

## Alternatives considered

- **Encrypt secret flag values in PostgreSQL** — rejected because encryption alone does not provide secret lifecycle and access brokerage.
- **Proxy an external secret manager** — deferred because it couples unrelated availability and authorization models.

## Trade-offs

- **Benefit:** smaller attack surface and clearer operational ownership.
- **Cost:** applications may integrate with both Switchboard and a secret manager.

## Consequences

- Contracts and validation reject or warn on prohibited secret use according to the later security policy.
- Logs, snapshots, and audit views are not designed as secret-bearing channels.
- Raw service credential material is handled only by issuance and is not stored reversibly.

## Revisit conditions

- Only through a separate security design, threat model, key-management plan, and explicit product scope decision.
