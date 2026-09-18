# Phase 0A Bootstrap Verification

- **Evidence ID:** SWB-0A-BOOTSTRAP
- **Phase:** 0A — Repository Bootstrap
- **Git commit:** `dba15b897d6c843136fd5de27333c03984caee4e`
- **Environment fingerprint:** macOS 26.6.2 (Darwin arm64); Eclipse Temurin 21.0.12.1; Gradle 9.7.1; Docker 29.5.3; Docker Compose 5.1.4
- **Expected:** Gradle modules build and test on Java 21; Compose configuration is valid; PostgreSQL and Kafka become healthy when started
- **Result:** LOCAL PASS — GitHub Actions remains pending until the branch is pushed

## Commands

```bash
java -version
./gradlew projects
./gradlew clean test
./gradlew build
docker compose -f infra/docker/docker-compose.yml config --quiet
docker compose -f infra/docker/docker-compose.yml up -d --wait
docker compose -f infra/docker/docker-compose.yml ps
docker compose -f infra/docker/docker-compose.yml down
```

## Observed

- The Gradle multi-project test and build completed successfully with Java 21.
- `make verify` completed successfully and exercised clean tests, the full build, and Compose configuration validation.
- All three application skeleton entry points started and exited successfully.
- Compose configuration validation passed.
- PostgreSQL 18.6 and Kafka 4.3.1 both reached `healthy`; verification containers were then stopped without deleting the named data volume.
- The first PostgreSQL 18.6 start exposed the PostgreSQL 18 image's new major-version-aware data layout. The volume mount was corrected from `/var/lib/postgresql/data` to `/var/lib/postgresql` before the final health verification.

## Artifact paths

- Gradle test reports: `*/build/reports/tests/test/index.html`
- Compose definition: `infra/docker/docker-compose.yml`
- CI workflow: `.github/workflows/ci.yml`

## Limitations

This evidence verifies repository foundations only. It makes no claim about product behavior, runtime reliability, performance, security, or production readiness.
