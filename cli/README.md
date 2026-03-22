# @ngirchev/open-daimon

Interactive setup wizard for [OpenDaimon](https://github.com/NGirchev/open-daimon) — a self-hosted AI Telegram bot with Spring Boot, OpenRouter, and Ollama.

## Usage

```bash
mkdir my-bot && cd my-bot
npx @ngirchev/open-daimon
```

The wizard will:
1. Ask for your Telegram bot token and username
2. Ask for your admin Telegram user ID
3. Let you choose AI provider: **OpenRouter** (cloud, free models available) or **Ollama** (local)
4. Configure optional services: Prometheus+Grafana, Elasticsearch+Kibana, MinIO
5. Generate `.env`, `docker-compose.yml`, and `application-local.yml`

Then start the stack:
```bash
docker compose up -d
docker compose logs -f opendaimon-app
```

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) — for running containers
- Node.js 18+ — for this wizard (already installed if you're using `npx`)

## Before running the wizard

- **Create a Telegram bot**: see [setup-telegram.md](https://github.com/NGirchev/open-daimon/blob/master/docs/setup-telegram.md)
- **Get an OpenRouter API key** (skip for Ollama): see [setup-openrouter.md](https://github.com/NGirchev/open-daimon/blob/master/docs/setup-openrouter.md)
- **Web search (optional)**: see [setup-serper.md](https://github.com/NGirchev/open-daimon/blob/master/docs/setup-serper.md)

## What gets created

| File | Description |
|------|-------------|
| `.env` | Secrets and credentials (do not commit) |
| `docker-compose.yml` | Service definitions |
| `application-local.yml` | App overrides — edit to customize models, limits, logging |
| `prometheus.yml` | Prometheus config (if monitoring selected) |
| `application-local.yml.example` | Reference template with all available options |

## After setup

Edit `application-local.yml` to customize:
- Model list (which OpenRouter or Ollama models to use per role)
- Bulkhead limits (max concurrent requests per tier)
- Token limits and summarization settings
- Logging levels

After editing, restart the app:
```bash
docker compose restart opendaimon-app
```

## Links

- [Full documentation](https://github.com/NGirchev/open-daimon)
- [Deployment guide](https://github.com/NGirchev/open-daimon/blob/master/DEPLOYMENT.md)
- [Issues](https://github.com/NGirchev/open-daimon/issues)
