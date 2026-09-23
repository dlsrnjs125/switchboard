# TRB-001 — PostgreSQL 18 Compose Volume Layout

## Context

Phase 0A local infrastructure uses the official PostgreSQL 18.6 Alpine image.

## Symptom

The container exited during its first Compose bootstrap and reported that `/var/lib/postgresql/data` was an unused mount for PostgreSQL 18+.

## Expected vs Actual

- Expected: PostgreSQL initializes and reaches healthy state.
- Actual: initialization stopped before the server started.

## Reproduction

Mount a named volume at `/var/lib/postgresql/data` while using `postgres:18.6-alpine`, then start the container.

## Impact

Local Compose bootstrap cannot satisfy the Phase 0A infrastructure gate.

## Initial Hypothesis

The image changed its persistent data layout at the PostgreSQL 18 major version boundary.

## Evidence

The official image startup log explicitly recommends a single mount at `/var/lib/postgresql` so data can live in a major-version-specific subdirectory and support `pg_upgrade --link` workflows.

## Root Cause

The Compose file used the pre-18 mount convention.

## Fix

Mount the named volume at `/var/lib/postgresql`.

## Verification

Run `docker compose -f infra/docker/docker-compose.yml up -d --wait` and confirm the PostgreSQL service is healthy.

## Trade-off

The volume now owns the whole PostgreSQL data root rather than only the legacy `data` child directory. This matches the official image's upgrade-aware layout.

## Prevention

Keep the image major version explicit and validate Compose by starting services, not only by parsing the Compose model.

## Related ADR / PR / Commit

- PR #1 — Phase 0A Repository Bootstrap
- `dba15b8`, `ae54d34`, merge `fceb633`
- `infra/docker/docker-compose.yml`

## Blog Candidate Summary

Why a valid Compose file can still fail at runtime after a database major-version upgrade.
