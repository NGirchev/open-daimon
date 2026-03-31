# OpenDaimon Project Overview

- Purpose: Multi-module Java assistant platform integrating Telegram, REST, UI, and AI providers with observability (Prometheus/Grafana + ELK).
- Primary stack: Java 21, Spring Boot, Maven, PostgreSQL, Flyway, Testcontainers, Docker Compose.
- Architecture: modular Maven project with core shared module + adapters (telegram/rest/ui) + AI integration + app entry module.
- Key modules:
  - opendaimon-common: shared domain, services, entities, metrics, infrastructure abstractions.
  - opendaimon-spring-ai: AI provider and model-routing integration.
  - opendaimon-telegram / opendaimon-rest / opendaimon-ui: delivery channels.
  - opendaimon-gateway-mock: mock gateway for tests/dev.
  - opendaimon-app: executable Spring Boot app and runtime configuration.
- Use-case docs: scenario documentation lives under `docs/usecases/`.
- Fixtures:
  - Integration fixture tests: `opendaimon-app/src/it/java/io/github/ngirchev/opendaimon/it/fixture/`.
  - Test resource fixtures (RAG PDFs): `opendaimon-spring-ai/src/test/resources/fixtures/`.