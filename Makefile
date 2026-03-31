.PHONY: help build build-module run test test-module test-single test-windows clean db-migrate db-info sonar install-hooks check-commit-guards

help:
	@echo "OpenDaimon Build & Test Commands"
	@echo "=================================="
	@echo ""
	@echo "Build:"
	@echo "  make build                    - Full build with tests"
	@echo "  make build-module MODULE=<m>  - Build single module (e.g. opendaimon-common)"
	@echo "  make build-docker             - Build without tests (for Docker)"
	@echo ""
	@echo "Run:"
	@echo "  make run                      - Run application (requires infrastructure)"
	@echo "  make run-docker               - Full Docker deployment"
	@echo ""
	@echo "Test:"
	@echo "  make test                     - Run all tests"
	@echo "  make test-module MODULE=<m>   - Tests for one module"
	@echo "  make test-single CLASS=<c>    - Single test class"
	@echo "  make test-spring-ai           - SpringAIGatewayIT (streaming, no Ollama)"
	@echo "  make test-windows             - Run tests on Windows (sets JAVA_HOME)"
	@echo ""
	@echo "Database:"
	@echo "  make db-migrate               - Run Flyway migrations"
	@echo "  make db-info                  - Show Flyway migration info"
	@echo ""
	@echo "Quality:"
	@echo "  make sonar                    - Run SonarQube analysis"
	@echo "  make install-hooks            - Install repository pre-commit guards"
	@echo "  make check-commit-guards      - Run staged commit guards manually"
	@echo "  make clean                    - Clean build artifacts"
	@echo ""

# Build targets

build:
	mvn clean install

build-module:
	mvn clean install -pl ${MODULE}

build-docker:
	mvn clean package -DskipTests

# Run targets

run:
	docker-compose up -d postgres prometheus grafana
	mvn spring-boot:run -pl opendaimon-app

run-docker:
	mvn clean package -DskipTests
	docker-compose up -d

# Test targets

test:
	mvn clean test

test-module:
	mvn clean test -pl ${MODULE}

test-single:
	mvn clean test -Dtest=${CLASS} -pl opendaimon-app

test-spring-ai:
	mvn clean test -pl opendaimon-spring-ai -Dtest=SpringAIGatewayIT

test-windows:
	@echo "Running tests on Windows..."
	@echo "Note: JAVA_HOME must be set to JDK 21 location"
	@echo "Example: C:\Users\<user>\.jdks\corretto-21.0.10"
	powershell -Command "$$env:JAVA_HOME = 'C:\Users\$$env:USERNAME\.jdks\corretto-21.0.10'; cd $(pwd); .\mvnw.cmd test -pl opendaimon-spring-ai -Dtest=SpringAIGatewayIT"

# Database targets

db-migrate:
	mvn flyway:migrate -pl opendaimon-common

db-info:
	mvn flyway:info -pl opendaimon-common

# Quality targets

sonar:
	mvn verify sonar:sonar

install-hooks:
	chmod +x scripts/check-commit-guards.sh .githooks/pre-commit
	git config core.hooksPath .githooks
	@echo "Installed hooks path: $$(git config --get core.hooksPath)"

check-commit-guards:
	scripts/check-commit-guards.sh

clean:
	mvn clean
	rm -rf target/
