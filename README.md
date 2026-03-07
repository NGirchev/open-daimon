# AI Bot Router

Multi-module Java project for interacting with various AI services through different interfaces (Telegram, REST API, Web UI) with integration via Spring AI (OpenRouter, Ollama).

## Tech stack

- **Java 21** (LTS)
- **Spring Boot 3.3.3**
- **PostgreSQL 17.0** with Flyway migrations
- **Docker & Docker Compose**
- **Prometheus + Grafana** for monitoring
- **Elasticsearch + Kibana** for logging

## Quick start

### Local run (for development)

1. **Start infrastructure:**
```bash
docker-compose up -d postgres prometheus grafana
```

2. **Build the project:**
```bash
mvn clean install
```

3. **Run the application:**
```bash
mvn spring-boot:run -pl aibot-app
```

4. **Set environment variables** (create `.env` or set in the system):
```bash
export TELEGRAM_USERNAME=your_bot_username
export TELEGRAM_TOKEN=your_telegram_bot_token
export OPENROUTER_KEY=your_openrouter_api_key
export SERPER_KEY=your_serper_api_key
```

### Run with Docker Compose (recommended)

1. **Build the project:**
```bash
mvn clean package -DskipTests
```

2. **Create `.env` file** in the project root:
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
SERPER_KEY=your_serper_api_key
POSTGRES_PASSWORD=your_secure_password
```

3. **Start all services:**
```bash
docker-compose up -d
```

   **Or with image rebuild:**
```bash
docker-compose up -d --build
```

4. **Check status:**
```bash
docker-compose ps
docker-compose logs -f aibot-app
```

## Build and run

### Prerequisites
```bash
# Java 21
java -version

# Maven 3.11+
mvn -version

# Docker (for DB and monitoring)
docker --version
```

### Start infrastructure
```bash
# Start PostgreSQL, Prometheus, Grafana, Elasticsearch, Kibana
docker-compose up -d

# Check status
docker-compose ps
```

### Build project
```bash
# Build all modules
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Build a specific module
mvn clean install -pl aibot-telegram

# Build with dependencies
mvn clean install -pl aibot-app -am
```

### Run application
```bash
# From project root
mvn spring-boot:run -pl aibot-app

# Or via JAR
java -jar aibot-app/target/aibot-app-1.0-SNAPSHOT.jar
```

### DB migrations
```bash
# Apply migrations
mvn flyway:migrate

# Migration info
mvn flyway:info

# Clean DB (use with caution!)
mvn flyway:clean
```

## Server deployment

Detailed production deployment guide: **[DEPLOYMENT.md](DEPLOYMENT.md)**

## Useful links

After starting the application:

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **Actuator Health**: http://localhost:8080/actuator/health
- **Prometheus Metrics**: http://localhost:8080/actuator/prometheus
- **Prometheus UI**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin123456)
- **Kibana**: http://localhost:5601

## Testing

### Run all tests
```bash
mvn test
```

### Run tests for a specific module
```bash
mvn test -pl aibot-common
mvn test -pl aibot-telegram
```

### Run a specific test
```bash
# Example from README
mvn test -Dtest=repository.telegram.ru.girchev.aibot.common.TelegramUserRepositoryTest -pl aibot-app

# Specific method
mvn test "-Dtest=repository.telegram.ru.girchev.aibot.common.TelegramUserRepositoryTest#whenSaveUser_thenUserIsSaved" -pl aibot-app

# SpringAIGatewayIT (streaming)
mvn test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
```

### Running tests on Windows
- **mvnw.cmd** requires **JAVA_HOME** (JDK 21). Common path: `C:\Users\<user>\.jdks\corretto-21.0.10` (IDEA) or File → Project Structure → SDKs.
- **PowerShell** from project root:
  ```powershell
  $env:JAVA_HOME = "C:\Users\<user>\.jdks\corretto-21.0.10"; cd c:\path\to\ai-bot; .\mvnw.cmd test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
  ```
  (replace `<user>` and path with your JDK and project location).
- If a single-module test fails with "Could not find artifact aibot-common", run `.\mvnw.cmd install -DskipTests` first, then the `test` command.
- **From IntelliJ IDEA**: right-click `SpringAIGatewayIT` → Run 'SpringAIGatewayIT'.

### Integration tests
Uses **Testcontainers** for PostgreSQL:
- Docker container with PostgreSQL is started automatically
- Flyway migrations are applied
- Container is removed after tests
- TelegramMockGatewayIntegrationTest — main test for the Telegram part
- SpringAIGatewayOpenRouterIntegrationTest — main test for the Spring AI part
- SpringAIGatewayIT — streaming test (no Ollama, mocked Flux with delays)

## Monitoring and debugging

### Endpoints
- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **Actuator Metrics**: http://localhost:8080/actuator/metrics/telegram.message.processing.time
- **Prometheus**: http://localhost:9090/query
- **Grafana**: http://localhost:3000/ (admin/admin123456)
- **Kibana**: http://localhost:5601/

### Logging
- **Root level**: INFO
- **Flyway**: DEBUG
- **Spring JDBC**: INFO
- **Bulkhead**: INFO

Logs are sent to Elasticsearch via Metricbeat.

## Troubleshooting

### Flyway migrations not applying
```bash
# Check status
mvn flyway:info

# Force apply
mvn flyway:migrate

# Baseline if needed
mvn flyway:baseline
```

### Tests fail with DB error
- Ensure Docker is running
- Testcontainers starts PostgreSQL automatically
- Check logs: `docker logs ai-bot-postgres`

### "Could not find a valid Docker environment" / Status 400 (Windows)
On Windows, Docker Desktop may return 400 over npipe and Testcontainers cannot connect. Enable TCP access to the daemon:

1. **Docker Desktop** → Settings → General → enable **"Expose daemon on tcp://localhost:2375 without TLS"** → Apply & Restart.
2. Before running tests, set (PowerShell):
   ```powershell
   $env:DOCKER_HOST = "tcp://localhost:2375"
   ```
3. Run tests:
   ```powershell
   .\mvnw.cmd verify -q
   ```
   Or in one line: `$env:DOCKER_HOST = "tcp://localhost:2375"; .\mvnw.cmd verify -q`

### Module cannot see dependencies
```bash
# Rebuild with dependencies
mvn clean install -am

# Refresh IDE (IntelliJ IDEA)
File -> Invalidate Caches / Restart
```

### Metrics not showing in Grafana
- Check Prometheus: http://localhost:9090/targets
- Ensure the app exports metrics: http://localhost:8080/actuator/prometheus
- Restart Grafana: `docker-compose restart grafana`

## Documentation

- **[AGENTS.md](AGENTS.md)** — Detailed documentation for AI agents (architecture, module structure, code style)
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — Server deployment guide
- **[MODULAR_MIGRATIONS.md](MODULAR_MIGRATIONS.md)** — Flyway modular migrations

## Project structure

```
ai-bot/
├── aibot-common/        # Core module with shared logic
├── aibot-telegram/      # Telegram Bot interface
├── aibot-rest/          # REST API interface
├── aibot-ui/            # Web UI interface
├── aibot-spring-ai/     # Spring AI integration
├── aibot-gateway-mock/  # Mock provider for tests
└── aibot-app/           # Main application module
```

## License

MIT

### Useful commands

## Web UI for Ollama
```
docker run -d \
  --name open-webui \
  -p 3000:8080 \
  --add-host=host.docker.internal:host-gateway \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -v open-webui:/app/backend/data \
  ghcr.io/open-webui/open-webui:main
```

## Port forwarding (example)
ssh -N -L 23750:/var/run/docker.sock user@your-server.local

## Teardown and full bring-up
docker-compose -H tcp://localhost:23750 down -v
docker-compose -H tcp://localhost:23750 up -d
