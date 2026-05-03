# Observability Agent Instructions

## Services & Ports

| Service | Port | Container | Log Command |
|---------|------|-----------|-------------|
| opendaimon-app | 8080 | open-daimon-app | `docker logs -f open-daimon-app` |
| postgres | 5432 | open-daimon-postgres | `docker logs -f open-daimon-postgres` |
| elasticsearch | 9200 | open-daimon-elasticsearch | `docker logs -f open-daimon-elasticsearch` |
| kibana | 5601 | open-daimon-kibana | `docker logs -f open-daimon-kibana` |
| logstash | 5044 | open-daimon-logstash | `docker logs -f open-daimon-logstash` |
| prometheus | 9090 | open-daimon-prometheus | `docker logs -f open-daimon-prometheus` |
| grafana | 3000 | open-daimon-grafana | `docker logs -f open-daimon-grafana` |
| minio | 9000/9001 | open-daimon-minio | `docker logs -f open-daimon-minio` |

## Quick Commands

```bash
# All running containers
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# Follow app logs
docker logs -f open-daimon-app

# App logs (last 100 lines)
docker logs --tail 100 -f open-daimon-app

# All services tail
docker-compose logs --tail=50

# Specific service
docker-compose logs -f opendaimon-app

# Search logs
docker logs open-daimon-app 2>&1 | grep -i "exception\|failed"

# Elasticsearch health
curl -s http://localhost:9200/_cluster/health?pretty

# Prometheus targets
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | .labels.job'
```

## Dashboards

- **Grafana**: http://localhost:3000 (admin/admin123456)
- **Kibana**: http://localhost:5601
- **Prometheus**: http://localhost:9090