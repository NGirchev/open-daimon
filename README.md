# AI Bot Router

Многомодульный Java-проект для взаимодействия с различными AI-сервисами через разные интерфейсы (Telegram, REST API, Web UI) с интеграцией через Spring AI (OpenRouter, Ollama).

## Технологический стек

- **Java 21** (LTS)
- **Spring Boot 3.3.3**
- **PostgreSQL 17.0** с Flyway миграциями
- **Docker & Docker Compose**
- **Prometheus + Grafana** для мониторинга
- **Elasticsearch + Kibana** для логирования

## Быстрый старт

### Локальный запуск (для разработки)

1. **Запустить инфраструктуру:**
```bash
docker-compose up -d postgres prometheus grafana
```

2. **Собрать проект:**
```bash
mvn clean install
```

3. **Запустить приложение:**
```bash
mvn spring-boot:run -pl aibot-app
```

4. **Настроить переменные окружения** (создайте `.env` или установите в системе):
```bash
export TELEGRAM_USERNAME=your_bot_username
export TELEGRAM_TOKEN=your_telegram_bot_token
export OPENROUTER_KEY=your_openrouter_api_key
export SERPER_KEY=your_serper_api_key
```

### Запуск через Docker Compose (рекомендуется)

1. **Собрать проект:**
```bash
mvn clean package -DskipTests
```

2. **Создать `.env` файл** в корне проекта:
```bash
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token
OPENROUTER_KEY=your_openrouter_api_key
SERPER_KEY=your_serper_api_key
POSTGRES_PASSWORD=your_secure_password
```

3. **Запустить все сервисы:**
```bash
docker-compose up -d
```

   **Или с пересборкой образа:**
```bash
docker-compose up -d --build
```

4. **Проверить статус:**
```bash
docker-compose ps
docker-compose logs -f aibot-app
```

## Build и запуск

### Предварительные требования
```bash
# Java 21
java -version

# Maven 3.11+
mvn -version

# Docker (для БД и мониторинга)
docker --version
```

### Запуск инфраструктуры
```bash
# Запуск PostgreSQL, Prometheus, Grafana, Elasticsearch, Kibana
docker-compose up -d

# Проверка статуса
docker-compose ps
```

### Сборка проекта
```bash
# Сборка всех модулей
mvn clean install

# Сборка без тестов
mvn clean install -DskipTests

# Сборка конкретного модуля
mvn clean install -pl aibot-telegram

# Сборка с зависимостями
mvn clean install -pl aibot-app -am
```

### Запуск приложения
```bash
# Из корня проекта
mvn spring-boot:run -pl aibot-app

# Или через JAR
java -jar aibot-app/target/aibot-app-1.0-SNAPSHOT.jar
```

### Миграции БД
```bash
# Применить миграции
mvn flyway:migrate

# Информация о миграциях
mvn flyway:info

# Очистить БД (осторожно!)
mvn flyway:clean
```

## Деплой на сервере

Подробная инструкция по деплою на production сервере: **[DEPLOYMENT.md](DEPLOYMENT.md)**

## Полезные ссылки

После запуска приложения доступны:

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **Actuator Health**: http://localhost:8080/actuator/health
- **Prometheus Metrics**: http://localhost:8080/actuator/prometheus
- **Prometheus UI**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin123456)
- **Kibana**: http://localhost:5601

## Тестирование

### Запуск всех тестов
```bash
mvn test
```

### Запуск тестов конкретного модуля
```bash
mvn test -pl aibot-common
mvn test -pl aibot-telegram
```

### Запуск конкретного теста
```bash
# Пример из README
mvn test -Dtest=repository.telegram.ru.girchev.aibot.common.TelegramUserRepositoryTest -pl aibot-app

# Конкретный метод
mvn test "-Dtest=repository.telegram.ru.girchev.aibot.common.TelegramUserRepositoryTest#whenSaveUser_thenUserIsSaved" -pl aibot-app

# SpringAIGatewayIT (стриминг)
mvn test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
```

### Запуск тестов на Windows
- Для **mvnw.cmd** нужна переменная **JAVA_HOME** (JDK 21). Часто путь: `C:\Users\<user>\.jdks\corretto-21.0.10` (IDEA) или из File → Project Structure → SDKs.
- **PowerShell** из корня проекта:
  ```powershell
  $env:JAVA_HOME = "C:\Users\ngirc\.jdks\corretto-21.0.10"; cd c:\Work\IdeaProjects\ai-bot; .\mvnw.cmd test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
  ```
  (замените путь на свой JDK).
- Если тест одного модуля падает с «Could not find artifact aibot-common», сначала: `.\mvnw.cmd install -DskipTests`, затем команду `test`.
- **Из IntelliJ IDEA**: правый клик по `SpringAIGatewayIT` → Run 'SpringAIGatewayIT'.

### Интеграционные тесты
Используются **Testcontainers** для PostgreSQL:
- Автоматически поднимается Docker-контейнер с PostgreSQL
- Применяются Flyway миграции
- После тестов контейнер удаляется
- TelegramMockGatewayIntegrationTest - главный тест для проверки телеграм части
- SpringAIGatewayOpenRouterIntegrationTest - главный тест для проверки spring ai части
- SpringAIGatewayIT - тест стриминга (без Ollama, мок Flux с задержками)

## Мониторинг и отладка

### Endpoints
- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **Actuator Metrics**: http://localhost:8080/actuator/metrics/telegram.message.processing.time
- **Prometheus**: http://localhost:9090/query
- **Grafana**: http://localhost:3000/ (admin/admin123456)
- **Kibana**: http://localhost:5601/

### Логирование
- **Root level**: INFO
- **Flyway**: DEBUG
- **Spring JDBC**: INFO
- **Bulkhead**: INFO

Логи отправляются в Elasticsearch через Metricbeat.

## Troubleshooting

### Проблема: Flyway миграции не применяются
```bash
# Проверь статус
mvn flyway:info

# Принудительно примени
mvn flyway:migrate

# Если нужно - baseline
mvn flyway:baseline
```

### Проблема: Тесты падают с ошибкой БД
- Проверь, что Docker запущен
- Testcontainers автоматически поднимает PostgreSQL
- Проверь логи: `docker logs ai-bot-postgres`

### Проблема: «Could not find a valid Docker environment» / Status 400 (Windows)
На Windows Docker Desktop по умолчанию отдаёт по npipe ответ 400, и Testcontainers не может работать. Нужно включить доступ к демону по TCP:

1. **Docker Desktop** → Settings → General → включи **«Expose daemon on tcp://localhost:2375 without TLS»** → Apply & Restart.
2. Перед запуском тестов задай переменную (PowerShell):
   ```powershell
   $env:DOCKER_HOST = "tcp://localhost:2375"
   ```
3. Запусти тесты:
   ```powershell
   .\mvnw.cmd verify -q
   ```
   Или в одной строке: `$env:DOCKER_HOST = "tcp://localhost:2375"; .\mvnw.cmd verify -q`

### Проблема: Модуль не видит зависимости
```bash
# Пересобери с зависимостями
mvn clean install -am

# Обнови IDE (IntelliJ IDEA)
File -> Invalidate Caches / Restart
```

### Проблема: Метрики не отображаются в Grafana
- Проверь Prometheus: http://localhost:9090/targets
- Проверь, что приложение экспортирует метрики: http://localhost:8080/actuator/prometheus
- Перезапусти Grafana: `docker-compose restart grafana`

## Документация

- **[AGENTS.md](AGENTS.md)** - Подробная документация для AI-агентов (архитектура, структура модулей, code style)
- **[DEPLOYMENT.md](DEPLOYMENT.md)** - Инструкция по деплою на сервере
- **[MODULAR_MIGRATIONS.md](MODULAR_MIGRATIONS.md)** - Документация по модульным миграциям Flyway

## Структура проекта

```
ai-bot/
├── aibot-common/        # Базовый модуль с общей логикой
├── aibot-telegram/      # Telegram Bot интерфейс
├── aibot-rest/          # REST API интерфейс
├── aibot-ui/            # Web UI интерфейс
├── aibot-spring-ai/     # Интеграция через Spring AI
├── aibot-gateway-mock/  # Провайдер-заглушка для тестов
└── aibot-app/           # Главный модуль приложения
```

## Лицензия

MIT

### Полезные команды

## Web UI для ollama
```
docker run -d \
  --name open-webui \
  -p 3000:8080 \
  --add-host=host.docker.internal:host-gateway \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -v open-webui:/app/backend/data \
  ghcr.io/open-webui/open-webui:main
```

## Проброска портов
ssh -N -L 23750:/var/run/docker.sock user@your-server.local

## Удаление и поднятие всего
docker-compose -H tcp://localhost:23750 down -v
docker-compose -H tcp://localhost:23750 up -d