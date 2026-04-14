# Local Testing Guide for the Setup Wizard

## Prerequisites

- Node.js 18+ (`node --version`)
- Docker Desktop running (`docker info`)
- Real or fake Telegram bot token (format: `1234567890:ABCdef...`)
- Real or fake OpenRouter API key (format: `sk-or-v1-...`)

---

## Quickest way to test — closest to real `npx` usage

Run from the repository root:

```bash
cd cli
npm pack --pack-destination /tmp/
```

Then run the wizard from the test directory:

```bash
cd /tmp/test-pack
npx file:/tmp/ngirchev-open-daimon-1.0.1.tgz
```

This is the closest simulation to what an end-user runs. It verifies:
- all required files are included in the published package
- the binary entry-point (`bin/setup.js`) is wired correctly
- the wizard works end-to-end from a cold install

> ⚠️ Run `npm pack` from `cli/` — the root directory has no `package.json`.

### Testing with locally built image

Pass `--local-image` to the wizard — it generates `docker-compose.yml` with `open-daimon:local` and `pull_policy: never` instead of pulling from the internet.

**Step 1** — build the local image from the repository root:

```bash
cd ..
docker build -t open-daimon:local .
```

**Step 2** — pack the wizard:

```bash
cd cli
npm pack --pack-destination /tmp/
```

**Step 3** — run the wizard from your test directory:

```bash
cd /tmp/test-pack
npx file:/tmp/ngirchev-open-daimon-1.0.1.tgz --local-image
```

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
- `SPRING_PROFILES_ACTIVE=local`
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
| Pull qwen3.5:4b? | No (or Yes if Ollama is running) |
| Serper? | No |
| Services | uncheck all |
| DB password | `mypassword` |
| Start stack? | No |

**Checks:**

Verify OPENROUTER_KEY is empty:
```bash
grep OPENROUTER_KEY /tmp/test-ollama/.env
```

Verify OLLAMA_BASE_URL is set:
```bash
grep OLLAMA_BASE_URL /tmp/test-ollama/.env
```

Verify application-local.yml contains Ollama models:
```bash
cat /tmp/test-ollama/application-local.yml
```

- `COMPOSE_PROFILES=` (empty string — no optional profiles active)
- `SPRING_PROFILES_ACTIVE=local`
- `prometheus.yml` must NOT be created (monitoring not selected)
- `logstash.conf` must NOT be created (logging not selected)
- `application-local.yml` contains `provider-type: OLLAMA`, `qwen3.5:4b`, `nomic-embed-text:v1.5`
- `spring.ai.ollama.base-url: ${OLLAMA_BASE_URL:http://localhost:11434}`

---

### Test 3 — Resume mode (existing `.env`)

Run the wizard again in an existing directory (e.g. from test 1):

```bash
cd /tmp/test-openrouter
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

```bash
grep SPRING_PROFILES_ACTIVE /tmp/test-all-services/.env
```
Expected: `SPRING_PROFILES_ACTIVE=local,logging`

Verify `logstash.conf` was created:
```bash
ls /tmp/test-all-services/logstash.conf
```

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
templates/logstash.conf
templates/prometheus.yml
templates/application-local.yml.example
package.json
README.md
```

Full simulation with packing:
```bash
npm pack --pack-destination /tmp/
```

Then from the test directory:
```bash
cd /tmp/test-pack
npx file:/tmp/ngirchev-open-daimon-1.0.1.tgz
```

---

## Important limitation when testing

The `cli/templates/docker-compose.yml` template uses:
```yaml
image: ghcr.io/ngirchev/open-daimon:latest
```

Until the Docker image is published, `docker compose up -d` will fail with a pull error.
You have two options:

**Option A** — use `--local-image` flag (see "Testing with locally built image" above).

**Option B** — use the **root** `docker-compose.yml` (which builds from source):

```bash
OPEN_DAIMON_REPO=/path/to/open-daimon
cp /tmp/test-openrouter/.env "$OPEN_DAIMON_REPO/"
cp /tmp/test-openrouter/application-local.yml "$OPEN_DAIMON_REPO/"
cd "$OPEN_DAIMON_REPO"
docker compose up -d --build
docker compose logs -f opendaimon-app
```

---

## Pre-publish checklist

- [ ] Test 1 passed: all files created, content is correct
- [ ] Test 2 passed: Ollama config in `application-local.yml` is correct
- [ ] Test 3 passed: resume mode pre-fills values from existing `.env`
- [ ] Test 4 passed: Ctrl+C does not produce a stack trace
- [ ] Test 5 passed: `COMPOSE_PROFILES` contains all three profiles, `SPRING_PROFILES_ACTIVE=local,logging`
- [ ] Test 6 passed: field validation works
- [ ] Test 7 passed: `npm pack --dry-run` shows all required files including `templates/logstash.conf`
- [ ] Stack starts via root `docker-compose.yml` with `.env` from the wizard
- [ ] `application-local.yml` is mounted and picked up by Spring Boot (no config errors in `docker compose logs`)
- [ ] No logstash warnings when logging profile is NOT selected
- [ ] Docker image published to GHCR (`docker-publish.yml` workflow)
