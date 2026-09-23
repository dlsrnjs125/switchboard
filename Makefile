.PHONY: build test clean contracts compose-config compose-up compose-down helm-validate kind-e2e verify reliability phase9-evidence phase9-publish-evidence phase9-reconnect-evidence phase9-backpressure-evidence final-check run-control-plane run-distribution run-sample

build:
	./gradlew build

test:
	./gradlew clean test

clean:
	./gradlew clean

contracts:
	./gradlew :contracts:check

compose-config:
	docker compose -f infra/docker/docker-compose.yml config --quiet

compose-up:
	docker compose -f infra/docker/docker-compose.yml up -d --wait

compose-down:
	docker compose -f infra/docker/docker-compose.yml down

helm-validate:
	./infra/kubernetes/validate.sh

kind-e2e:
	./infra/kubernetes/phase-08-kind.sh

verify: test build contracts compose-config helm-validate

reliability:
	./infra/reliability/phase-06-drill.sh all

phase9-evidence:
	./load-test/phase-09/run.sh all

phase9-publish-evidence:
	./load-test/phase-09/run.sh publish

phase9-reconnect-evidence:
	./load-test/phase-09/run.sh reconnect

phase9-backpressure-evidence:
	./load-test/phase-09/run.sh backpressure

final-check: phase9-evidence

run-control-plane:
	./gradlew :services:control-plane:run

run-distribution:
	./gradlew :services:distribution:run

run-sample:
	./gradlew :demo:sample-service:run
