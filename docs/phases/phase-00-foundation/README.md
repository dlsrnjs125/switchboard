# Phase 0A — Repository Bootstrap

## Goal

Create the executable and verifiable monorepo foundation before feature work begins.

## Scope

- Gradle multi-project build with Kotlin DSL and Java 21 toolchains
- Deployable application boundaries for control plane, distribution, and sample service
- Framework-independent boundaries for evaluation core and Java SDK
- PostgreSQL and Kafka local infrastructure
- Minimal GitHub Actions CI and repository commands
- Documentation, contract, infrastructure, and load-test directory boundaries

## Decisions

- Module names follow product responsibility rather than framework or technical layer.
- `evaluation-core` has no runtime dependency and remains pure Java.
- Application skeletons are deliberately minimal; framework and protocol choices are introduced only after their baseline phases.
- PostgreSQL and Kafka are development dependencies in Compose, not runtime implementations in this phase.

## Implementation result

The repository can discover and build all five Gradle modules. Three application entry points can start and exit successfully. CI runs the Java build and validates the Compose model.

## Known limitations

- No product domain or feature behavior exists yet.
- Compose health is environment-dependent and must be verified with a running Docker engine.
- Contract directories are placeholders until Phase 0D.

## Evidence

- [Bootstrap verification](../../evidence/phase-00a/bootstrap-verification.md)
- [TRB-001 — PostgreSQL 18 Compose volume layout](../../troubleshooting/TRB-001-postgresql-18-compose-volume-layout.md)
