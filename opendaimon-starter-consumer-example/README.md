# OpenDaimon Starter Consumer Example

This is a standalone Maven project that simulates an external Spring Boot application consuming OpenDaimon through the starter.

It is intentionally not listed in the root `pom.xml` modules and is not part of the published OpenDaimon reactor.

The example declares two OpenDaimon dependencies:

```xml
<dependency>
    <groupId>io.github.ngirchev</groupId>
    <artifactId>opendaimon-spring-boot-starter</artifactId>
    <version>${open-daimon.version}</version>
</dependency>
<dependency>
    <groupId>io.github.ngirchev</groupId>
    <artifactId>opendaimon-rest</artifactId>
    <version>${open-daimon.version}</version>
</dependency>
```

The starter brings the common and Spring AI modules, auto-configuration imports, and low-priority OpenDaimon defaults from `META-INF/opendaimon/opendaimon-defaults.yml`. `opendaimon-rest` is declared separately because REST API delivery is optional and is not part of the minimal starter dependency set.

Starter defaults, overrideable from the consumer application's own configuration:

- `open-daimon.ai.spring-ai.enabled=true`
- `open-daimon.agent.enabled=true`
- safe defaults for common token limits, summarization, storage, bulkhead, and priority routing
- Spring AI sample: OpenRouter-compatible OpenAI provider with `openrouter/auto`
- Spring AI provider endpoint: `spring.ai.openai.base-url=https://openrouter.ai/api`
- Spring AI defaults for OpenRouter auto-rotation, Serper, URL checking, SSL, RAG, and agent settings
- infrastructure-backed features are disabled by default where enabling them would require extra services or optional dependencies: storage, bulkhead, RAG, and OpenRouter model rotation
- Optional overrides: `OPENDAIMON_DEFAULT_MODEL`, `OPENDAIMON_DEFAULT_PROVIDER`, and `OPENROUTER_CONTRACT_MODEL`

Included OpenDaimon configuration in `src/main/resources/application.yml`:

- REST opt-in: `open-daimon.rest.enabled=true`
- REST access sample: `admin@example.com` and `user@example.com`

The consumer application provides regular Spring Boot infrastructure dependencies (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, and PostgreSQL JDBC). It also calls `DotEnvLoader.loadDotEnv()` on startup, so secrets such as `OPENROUTER_KEY`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, or `SPRING_DATASOURCE_PASSWORD` can be kept in a local `.env` file. At runtime it expects PostgreSQL and OpenRouter unless you replace those settings.

For local PostgreSQL the example accepts standard Spring Boot datasource variables:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/opendaimon
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
```

If PostgreSQL reports `password authentication failed`, make these values match the credentials used when the local `open-daimon-postgres` container or database was first created.

Run it against locally installed snapshots:

```bash
./mvnw -pl opendaimon-spring-boot-starter,opendaimon-rest -am install -DskipTests -DskipITs -DskipIT
mvn -f opendaimon-starter-consumer-example/pom.xml test
```

The smoke test verifies that Spring Boot can discover OpenDaimon auto-configuration candidates from the consumer classpath without manually importing OpenDaimon configuration, that starter defaults for Spring AI are present on the classpath, and that REST is enabled by the consumer application because `opendaimon-rest` is an explicit dependency.

The example also contains a manual-only contract test that starts a PostgreSQL Testcontainer, boots the REST API, sends a chat-style request to `/api/v1/session`, and expects the answer to come from real OpenRouter:

```bash
mvn -f opendaimon-starter-consumer-example/pom.xml verify \
  -DskipITs=false \
  -Dit.test=OpenRouterRestContractIT \
  -Dmanual.openrouter.rest-contract=true
```

The contract test loads `.env` from the repository root and from `opendaimon-starter-consumer-example/.env`. It requires Docker, outbound network access, and `OPENROUTER_KEY`; `OPENROUTER_CONTRACT_MODEL` is optional and defaults to `openrouter/auto`.
