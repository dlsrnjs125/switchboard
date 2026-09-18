.PHONY: build test clean compose-config compose-up compose-down verify run-control-plane run-distribution run-sample

build:
	./gradlew build

test:
	./gradlew clean test

clean:
	./gradlew clean

compose-config:
	docker compose -f infra/docker/docker-compose.yml config --quiet

compose-up:
	docker compose -f infra/docker/docker-compose.yml up -d --wait

compose-down:
	docker compose -f infra/docker/docker-compose.yml down

verify: test build compose-config

run-control-plane:
	./gradlew :services:control-plane:run

run-distribution:
	./gradlew :services:distribution:run

run-sample:
	./gradlew :demo:sample-service:run

