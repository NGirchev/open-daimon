# AI Bot Deployment Guide

> **For local development** see [README.md](README.md)

This guide describes deploying the application to a production server via Docker Compose.

## Summary: what happens on deploy

1. **Volumes are created automatically** on first run of `docker-compose up -d`:
   - `postgres-data` — database (critical!)
   - `grafana-storage` — Grafana dashboards
   - `elasticsearch-data` — Elasticsearch indices
   - `prometheus-data` — Prometheus metrics

2. **Data persists** across restarts and server reboots

3. **Important**: Removing volumes (`docker-compose down -v`) will delete all data!

## Prerequisites

1. **Java 21** (LTS)
2. **Maven 3.11+**
3. **Docker** and **Docker Compose**
4. **Git** (for cloning the repository)

## Step 1: Server setup

### Installing dependencies

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-21-jdk maven docker.io docker-compose git

# Check versions
java -version  # must be 21
mvn -version
docker --version
docker-compose --version
```

### Docker setup (if needed)

```bash
# Add user to docker group
sudo usermod -aG docker $USER
newgrp docker
```

## Step 2: Clone and build

```bash
# Clone repository
git clone <your-repo-url>
cd ai-bot

# Build project
mvn clean package -DskipTests

# Verify JAR was created
ls -lh aibot-app/target/aibot-app-1.0-SNAPSHOT.jar
```

## Step 3: Check configuration files

Ensure the following files are set up correctly:
- **[Dockerfile](Dockerfile)** — must be in project root
- **[docker-compose.yml](docker-compose.yml)** — must define service `aibot-app` with the correct environment variables

## Step 4: Create .env file

Create a `.env` file in the project root with environment variables:

```bash
# Telegram Bot
TELEGRAM_USERNAME=your_bot_username
TELEGRAM_TOKEN=your_telegram_bot_token

# AI Providers
DEEPSEEK_KEY=your_deepseek_api_key
OPENROUTER_KEY=your_openrouter_api_key

# Database (change if needed)
POSTGRES_DB=ai_bot
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_secure_password
```

> **Note**: `.env` is in `.gitignore` and will not be committed.

## Step 5: Verify configuration

Ensure the following are set correctly:
- **[prometheus.yml](prometheus.yml)** — must include target `aibot-app:8080`
- **[aibot-app/src/main/resources/application.yml](aibot-app/src/main/resources/application.yml)** — must use environment variables for DB connection
 - **User access configuration** — Telegram access and priority are configured via `TELEGRAM_ACCESS_*_IDS` и `TELEGRAM_ACCESS_*_CHANNELS` переменные (см. `.env.example` и `aibot-app/src/main/resources/application.yml`).

## Step 6: Choose docker-compose file

For Ubuntu Server, use `docker-compose.ubuntu.yml`, which:
- ✅ Uses default Docker Compose network (standard approach)
- ✅ Enables healthchecks for all services
- ✅ Is tuned for server use (resource limits)
- ✅ Includes tuned PostgreSQL settings

**On Ubuntu Server:**

**Option 1: Use the script (recommended — fixes iptables automatically)**

```bash
# Make script executable
chmod +x docker-compose-up-ubuntu.sh

# Run (script fixes iptables if needed)
./docker-compose-up-ubuntu.sh
```

**Option 2: Manual (if script is not suitable)**

```bash
# 1. Fix iptables (if you see an error)
sudo iptables -t filter -N DOCKER-ISOLATION-STAGE-2
sudo systemctl restart docker

# 2. Start docker-compose
docker-compose -f docker-compose.ubuntu.yml up -d

# Check status
docker-compose -f docker-compose.ubuntu.yml ps
```

**For local development (macOS/Windows):**

```bash
docker-compose up -d
```

> **Note**: If you have iptables issues on Ubuntu Server, see "iptables issue" below.

## Step 7: Run the application

### First run

```bash
# Ubuntu Server
docker-compose -f docker-compose.ubuntu.yml up -d

# Or local development
docker-compose up -d

# Check status
docker-compose ps

# Application logs
docker-compose logs -f aibot-app

# All services logs
docker-compose logs -f
```

### Volumes (data storage)

**Important**: On first run Docker creates volumes for data:
- `postgres-data` — PostgreSQL database
- `grafana-storage` — Grafana dashboards and settings
- `elasticsearch-data` — Elasticsearch indices
- `prometheus-data` — Prometheus metrics

**Inspect volumes:**
```bash
# List volumes
docker volume ls

# Inspect a volume
docker volume inspect ai-bot_postgres-data

# Physical location (usually /var/lib/docker/volumes/)
docker volume inspect ai-bot_postgres-data | grep Mountpoint
```

**Production notes:**
- ✅ Volumes are created on first `docker-compose up -d`
- ✅ Data persists across container restarts
- ✅ Data persists across server reboots
- ✅ Volumes are not removed by `docker-compose down` (only by `docker-compose down -v`)
- ⚠️ Removing a volume deletes all its data!

**Volume management:**
```bash
# Disk usage
docker system df -v

# Backup volume (example for postgres)
docker run --rm -v ai-bot_postgres-data:/data -v $(pwd):/backup alpine tar czf /backup/postgres-backup.tar.gz /data

# Remove volume (CAUTION! Deletes all data!)
docker volume rm ai-bot_postgres-data
```

### Automatic restart

All services in [docker-compose.yml](docker-compose.yml) use `restart: unless-stopped`, so:
- ✅ Containers restart on failure
- ✅ Containers start after server reboot (if Docker daemon is enabled)

**Ensure Docker starts on boot:**
```bash
# Check status
sudo systemctl status docker

# Enable Docker (if not already)
sudo systemctl enable docker
```

> **Note**: For production, configuring a systemd service (Step 9) is also recommended for reliability and control.

### Verify it works

```bash
# Check application is up
curl http://localhost:8080/actuator/health

# Check metrics
curl http://localhost:8080/actuator/prometheus

# Swagger UI (if REST API is enabled)
# Open in browser: http://your-server-ip:8080/swagger-ui/index.html
```

## Step 7: Monitoring setup

### Prometheus
- URL: `http://your-server-ip:9090`
- Check targets: `http://your-server-ip:9090/targets`

### Grafana
- URL: `http://your-server-ip:3000`
- Login: `admin`
- Password: `admin123456`
- Add Prometheus as data source: `http://prometheus:9090`

### Kibana
- URL: `http://your-server-ip:5601`
- Configure index pattern for logs

## Step 8: Updating the application

```bash
# Stop application
docker-compose stop aibot-app

# Rebuild JAR (if changed)
mvn clean package -DskipTests

# Rebuild Docker image
docker-compose build aibot-app

# Start again
docker-compose up -d aibot-app

# Check logs
docker-compose logs -f aibot-app
```

## Step 9: systemd autostart (optional but recommended)

> **Note**: With `restart: unless-stopped` in docker-compose.yml and Docker enabled at boot, containers will start after reboot. A systemd service adds an extra layer of control and monitoring.

Create `/etc/systemd/system/ai-bot.service`:

```ini
[Unit]
Description=AI Bot Application
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/path/to/ai-bot
ExecStart=/usr/bin/docker-compose up -d
ExecStop=/usr/bin/docker-compose down
User=your-user

[Install]
WantedBy=multi-user.target
```

Enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable ai-bot.service
sudo systemctl start ai-bot.service
```

## Useful commands

```bash
# View logs
docker-compose logs -f aibot-app
docker-compose logs -f postgres

# Restart a service
docker-compose restart aibot-app

# Stop all services
docker-compose down

# Stop and remove volumes (CAUTION!)
docker-compose down -v

# Resource usage
docker stats

# Shell into container
docker exec -it ai-bot-app sh
docker exec -it ai-bot-postgres psql -U postgres -d ai_bot
```

## Troubleshooting

### Application does not start

```bash
# Check logs
docker-compose logs aibot-app

# Verify DB is reachable
docker-compose exec postgres psql -U postgres -d ai_bot -c "SELECT 1;"

# Check environment variables
docker-compose exec aibot-app env | grep -E "TELEGRAM|DEEPSEEK|OPENROUTER"
```

### Database connection issues

```bash
# Check postgres is running
docker-compose ps postgres

# Check network
docker network inspect ai-bot_ai-bot-network

# Check postgres logs
docker-compose logs postgres
```

### Prometheus metrics issues

```bash
# Check targets in Prometheus UI
# Ensure aibot-app is reachable by name on the network

# Check prometheus.yml
docker-compose exec prometheus cat /etc/prometheus/prometheus.yml
```

### iptables issue on Linux server (DOCKER-ISOLATION-STAGE-2 error)

If you see this error when running `docker-compose up -d`:
```
failed to create network ai-bot_ai-bot-network: Error response from daemon: 
add inter-network communication rule: (iptables failed: iptables --wait -t filter 
-A DOCKER-ISOLATION-STAGE-1 -i br-xxx ! -o br-xxx -j DOCKER-ISOLATION-STAGE-2: 
iptables v1.8.10 (nf_tables): Chain 'DOCKER-ISOLATION-STAGE-2' does not exist
```

**Solution 1: Restart Docker daemon (recommended)**

```bash
# Restart Docker
sudo systemctl restart docker

# Try starting again
docker-compose up -d
```

**Solution 2: Configure Docker to use iptables (official approach, recommended)**

Per [Docker docs](https://docs.docker.com/engine/network/packet-filtering-firewalls/), you can set Docker to use `iptables` instead of `nftables` via `firewall-backend`:

```bash
# Create or edit /etc/docker/daemon.json
sudo nano /etc/docker/daemon.json
```

Add or update:
```json
{
  "firewall-backend": "iptables"
}
```

Then restart Docker:
```bash
sudo systemctl restart docker

# Try starting again
docker-compose up -d
```

> **Note**: This is the official way to make Docker use iptables. Docker supports both backends (iptables and nftables); iptables is more stable on Ubuntu Server.

**Solution 3: Switch to iptables-legacy (if solution 2 did not help)**

If `firewall-backend` did not help, switch to `iptables-legacy` at system level:

```bash
# Switch to iptables-legacy
sudo update-alternatives --set iptables /usr/sbin/iptables-legacy
sudo update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

# Restart Docker
sudo systemctl restart docker

# Try again
docker-compose up -d
```

**Solution 4: Fix iptables manually (if above did not help)**

```bash
# Create missing chain
sudo iptables -t filter -N DOCKER-ISOLATION-STAGE-2

# Restart Docker
sudo systemctl restart docker

# Try again
docker-compose up -d
```

**Solution 5: Use default Docker Compose network**

If the above did not help, comment out the custom network in `docker-compose.yml`:

1. Open `docker-compose.yml`
2. Comment out the `networks:` section at the end:
   ```yaml
   # networks:
   #   ai-bot-network:
   #     driver: bridge
   ```
3. Remove all `networks: - ai-bot-network` from services
4. Docker Compose will create a default network named after the project

**Solution 6: Configure Docker to use iptables (official approach)**

Per [Docker docs](https://docs.docker.com/engine/network/packet-filtering-firewalls/), set Docker to use `iptables` instead of `nftables`:

```bash
# Create or edit /etc/docker/daemon.json
sudo nano /etc/docker/daemon.json
```

Add or update:
```json
{
  "firewall-backend": "iptables"
}
```

Then restart Docker:
```bash
sudo systemctl restart docker
```

> **Note**: This is the official way to use iptables. iptables is more stable on Ubuntu Server.

**Solution 7: Disable iptables management in Docker (not recommended)**

If nothing else worked, you can disable iptables in Docker:

```bash
# Create or edit /etc/docker/daemon.json
sudo nano /etc/docker/daemon.json
```

Add:
```json
{
  "iptables": false
}
```

Then restart Docker:
```bash
sudo systemctl restart docker
```

> **Note**: Disabling iptables may require manual firewall rules. Use only if other solutions failed.

## Security

1. **Change default passwords** in `.env`
2. **Configure firewall** on the server (open only required ports)
3. **Use HTTPS** for external access (e.g. nginx reverse proxy)
4. **Keep Docker images updated**

## Scaling (multiple app instances)

To run multiple instances:

1. **Remove `container_name`** from `aibot-app` in [docker-compose.yml](docker-compose.yml):
   ```yaml
   aibot-app:
     # container_name: ai-bot-app  # Comment out for scaling
     ports:
       - "8080:8080"  # Or use range: "8080-8090:8080"
   ```

2. **Run with scaling**:
   ```bash
   # Run 3 instances
   docker-compose up -d --scale aibot-app=3
   
   # Check status
   docker-compose ps
   
   # Logs from all instances
   docker-compose logs -f aibot-app
   ```

4. **Use a load balancer** (nginx, traefik) to distribute load across instances

> **Note**: For a Telegram bot one instance is usually enough; scaling is useful for REST API or high load.

## Production recommendations

1. **Volumes for data** — all data services use volumes:
   - ✅ **postgres** — `postgres-data` (DB data persists)
   - ✅ **grafana** — `grafana-storage` (dashboards and settings persist)
   - ✅ **elasticsearch** — `elasticsearch-data` (indices persist)
   - ✅ **prometheus** — `prometheus-data` (metrics persist)
   
   > **Important**: Volumes are created on first run. Data persists across restarts and reboots.

2. **Set up regular DB backups**:
   ```bash
   # DB backup (run regularly)
   docker-compose exec postgres pg_dump -U postgres ${POSTGRES_DB:-ai_bot} > backup-$(date +%Y%m%d-%H%M%S).sql
   
   # Restore DB
   docker-compose exec -T postgres psql -U postgres ${POSTGRES_DB:-ai_bot} < backup.sql
   
   # Cron example (add to crontab)
   # 0 2 * * * cd /path/to/ai-bot && docker-compose exec -T postgres pg_dump -U postgres ai_bot > /backups/ai-bot-$(date +\%Y\%m\%d).sql
   ```

3. **Backup volumes (optional, for full backup)**:
   ```bash
   # Backup all volumes
   docker run --rm -v ai-bot_postgres-data:/data -v $(pwd):/backup alpine tar czf /backup/postgres-volume-$(date +%Y%m%d).tar.gz /data
   docker run --rm -v ai-bot_grafana-storage:/data -v $(pwd):/backup alpine tar czf /backup/grafana-volume-$(date +%Y%m%d).tar.gz /data
   ```

4. **Use a reverse proxy** (nginx) for HTTPS, rate limiting and load balancing when scaling

   Example nginx config for multiple instances:
   ```nginx
   upstream aibot {
       least_conn;
       server localhost:8080;
       server localhost:8081;
       server localhost:8082;
   }
   
   server {
       listen 80;
       server_name your-domain.com;
       
       location / {
           proxy_pass http://aibot;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
       }
   }
   ```

5. **Configure monitoring** and alerts in Grafana

6. **Use secrets management** (Docker Secrets, HashiCorp Vault)

