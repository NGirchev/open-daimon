# Local Testing Guide for the Setup Wizard

## Prerequisites

- Node.js 18+ (`node --version`)
- Docker Desktop running (`docker info`)
- Real or fake Telegram bot token (format: `1234567890:ABCdef...`)
- Real or fake OpenRouter API key (format: `sk-or-v1-...`)

---

## Setup (once)

```bash
cd cli
npm install
```

---

## Test Cases

### Test 1 — OpenRouter path (happy path)

```bash
mkdir /tmp/test-openrouter && cd /tmp/test-openrouter
node /path/to/cli/bin/setup.js
```

Wizard input:
| Prompt | Value |
|--------|-------|
| Bot token | `1234567890:ABCdefGHIjklMNOpqrsTUVwxyz` (fake) |
| Bot username | `test_bot` |
| Admin Telegram ID | `123456789` |
| AI provider | OpenRouter |
| OpenRouter key | `sk-or-v1-fake` |
| Serper? | No |
| Optional services | leave Monitoring checked, uncheck rest |
| DB password | *(blank — auto-generate)* |
| Start stack? | No |

**Expected files created:**
```
/tmp/test-openrouter/
├── .env
├── docker-compose.yml
├── application-local.yml
├── prometheus.yml
└── application-local.yml.example
```

**Check `.env`:**
```bash
cat /tmp/test-openrouter/.env
```
- `TELEGRAM_TOKEN=1234567890:ABCdefGHIjklMNOpqrsTUVwxyz`
- `OPENROUTER_KEY=sk-or-v1-fake`
- `OLLAMA_BASE_URL=http://localhost:11434`
- `TELEGRAM_ACCESS_ADMIN_IDS=123456789`
- `COMPOSE_PROFILES=monitoring`
- `POSTGRES_PASSWORD=` — 32-char hex (auto-generated)

**Check `application-local.yml`:**
```bash
cat /tmp/test-openrouter/application-local.yml
```
- Should contain only commented-out lines (OpenRouter path = minimal file)

**Check `docker-compose.yml`:**
```bash
grep -E "profiles:|SPRING_CONFIG|application-local" /tmp/test-openrouter/docker-compose.yml
```
- `profiles: ["monitoring"]` on prometheus/grafana services
- `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/application-local.yml`
- `./application-local.yml:/app/config/application-local.yml:ro`

---

### Test 2 — Ollama path

```bash
mkdir /tmp/test-ollama && cd /tmp/test-ollama
node /path/to/cli/bin/setup.js
```

Wizard input:
| Prompt | Value |
|--------|-------|
| Bot token | `1234567890:ABCdefGHIjklMNOpqrsTUVwxyz` |
| Bot username | `test_bot` |
| Admin Telegram ID | `123456789` |
| AI provider | **Ollama** |
| Ollama URL | *(press Enter to accept default `http://localhost:11434`)* |
| Pull gemma3:1b? | No (or Yes if Ollama is running) |
| Serper? | No |
| Services | uncheck all |
| DB password | `mypassword` |
| Start stack? | No |

**Checks:**
```bash
# OPENROUTER_KEY must be empty
grep OPENROUTER_KEY /tmp/test-ollama/.env

# OLLAMA_BASE_URL must be set
grep OLLAMA_BASE_URL /tmp/test-ollama/.env

# application-local.yml must contain Ollama models
cat /tmp/test-ollama/application-local.yml
```

- `COMPOSE_PROFILES=` (empty string — no optional profiles active)
- `prometheus.yml` must NOT be created (monitoring not selected)
- `application-local.yml` contains `provider-type: OLLAMA`, `gemma3:1b`, `nomic-embed-text:v1.5`
- `spring.ai.ollama.base-url: ${OLLAMA_BASE_URL:http://localhost:11434}`

---

### Test 3 — Resume mode (existing `.env`)

```bash
cd /tmp/test-openrouter   # directory from test 1
node /path/to/cli/bin/setup.js
```

- Wizard should print `Found existing config — pre-filling values.`
- All prompts should be pre-filled with values from the existing `.env`
- Change one field, accept the rest with Enter
- Verify that only the changed field is updated in `.env`

---

### Test 4 — Cancel with Ctrl+C

```bash
mkdir /tmp/test-cancel && cd /tmp/test-cancel
node /path/to/cli/bin/setup.js
```

Press `Ctrl+C` at any prompt.

- Should print `Setup cancelled.`
- Must not crash with a stack trace
- No files should be created (or only those written before the cancel)

---

### Test 5 — All services enabled

```bash
mkdir /tmp/test-all-services && cd /tmp/test-all-services
node /path/to/cli/bin/setup.js
```

At the `Which optional services to start?` step, select all three (Space on each).

**Check:**
```bash
grep COMPOSE_PROFILES /tmp/test-all-services/.env
```
Expected: `COMPOSE_PROFILES=storage,monitoring,logging`

---

### Test 6 — Field validation

```bash
mkdir /tmp/test-validation && cd /tmp/test-validation
node /path/to/cli/bin/setup.js
```

- **Bot token**: enter a space → should show `Required`, must not proceed
- **Admin Telegram ID**: enter `abc123` → should show `Must be a number`
- **Admin Telegram ID**: enter `123456789` → passes

---

### Test 7 — npm pack (npx simulation)

Verifies that all required files are included in the package.

```bash
cd cli
npm pack --dry-run
```

Output must include:
```
bin/setup.js
templates/docker-compose.yml
templates/prometheus.yml
templates/application-local.yml.example
package.json
README.md
```

Full simulation with packing:
```bash
npm pack --pack-destination /tmp/
mkdir /tmp/test-pack && cd /tmp/test-pack
npx file:/tmp/ngirchev-open-daimon-1.0.0.tgz
```

---

## Important limitation when testing

The `cli/templates/docker-compose.yml` template uses:
```yaml
image: ghcr.io/ngirchev/open-daimon:latest
```

Until the Docker image is published, `docker compose up -d` will fail with a pull error. To verify the full stack starts — use the **root** `docker-compose.yml` (which builds from source):

```bash
# From the repository root:
cp /tmp/test-openrouter/.env .
cp /tmp/test-openrouter/application-local.yml .
docker compose up -d --build
docker compose logs -f opendaimon-app
```

---

## Pre-publish checklist

- [ ] Test 1 passed: all files created, content is correct
- [ ] Test 2 passed: Ollama config in `application-local.yml` is correct
- [ ] Test 3 passed: resume mode pre-fills values from existing `.env`
- [ ] Test 4 passed: Ctrl+C does not produce a stack trace
- [ ] Test 5 passed: `COMPOSE_PROFILES` contains all three profiles
- [ ] Test 6 passed: field validation works
- [ ] Test 7 passed: `npm pack --dry-run` shows all required files
- [ ] Stack starts via root `docker-compose.yml` with `.env` from the wizard
- [ ] `application-local.yml` is mounted and picked up by Spring Boot (no config errors in `docker compose logs`)
- [ ] Docker image published to GHCR (`docker-publish.yml` workflow)
