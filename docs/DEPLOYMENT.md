# Workflow Platform Container Deployment & Operations Guide

This guide describes containerization, deployment, and health verification for local, staging, and production environments using Docker and Docker Compose.

---

## 1. Prerequisites

- Docker Engine 24.0+ / Docker Desktop with Compose v2
- PostgreSQL 17 client (optional, for direct inspection)
- Minimum System Resources: 4 CPU cores, 8 GB RAM

---

## 2. Image Architecture

Both services employ multi-stage Docker builds to ensure minimal production image sizes and high security:

### Backend (`backend/Dockerfile`)
- **Build Stage**: Maven 3.9 + Eclipse Temurin JDK 21 on Alpine Linux.
- **Runtime Stage**: Eclipse Temurin JRE 21 on Alpine Linux.
- **Security**: Runs under dedicated non-root user `workflow:workflow` (UID/GID 10001).
- **Timezone**: Forced to UTC (`TZ=UTC`, JVM `-Duser.timezone=UTC`).
- **Healthcheck**: Actuator probe (`curl -f http://localhost:8080/actuator/health`).
- **Signal Handling**: Exec-form `ENTRYPOINT ["java", "-jar", "app.jar"]` enables graceful SIGTERM reception and connection draining.

### Frontend (`frontend/Dockerfile`)
- **Build Stage**: Node 22 Alpine installing dependencies via `npm ci` and compiling Next.js standalone output.
- **Runtime Stage**: Node 22 Alpine running standalone `server.js`.
- **Security**: Runs under non-root system user `nextjs:nodejs` (UID/GID 1001).
- **Healthcheck**: HTTP check via `wget -qO- http://localhost:3000/`.

---

## 3. Local Development Deployment

Start the full stack with default development credentials:

```bash
# Build images
docker compose -f docker-compose.dev.yml build

# Start services in background
docker compose -f docker-compose.dev.yml up -d

# Verify logs and migrations
docker compose -f docker-compose.dev.yml logs -f backend

# Verify health status
docker compose -f docker-compose.dev.yml ps
```

### Access Endpoints
- **Frontend Web UI**: `http://localhost:3000`
- **Backend API**: `http://localhost:8080/api/v1`
- **Actuator Health**: `http://localhost:8080/actuator/health`
- **PostgreSQL Database**: `localhost:5433` (user: `workflow`, db: `workflow_platform`)

---

## 4. Staging Environment Deployment

Staging runs isolated container networks with externalized credentials and automated Flyway schema migrations on startup.

```bash
# 1. Provide staging environment variables (or rely on .env file)
export STAGING_POSTGRES_PASSWORD="strong_staging_db_secret_2026"
export STAGING_CALLBACK_SIGNING_SECRET="a9b8c7d6e5f4a9b8c7d6e5f4a9b8c7d6e5f4a9b8c7d6e5f4a9b8c7d6e5f4a9b8"
export STAGING_CORS_ALLOWED_ORIGINS="http://localhost:3000,https://staging.workflow.internal"

# 2. Build and launch staging services
docker compose -f docker-compose.staging.yml up -d --build

# 3. Verify service health and Flyway migration completion
docker compose -f docker-compose.staging.yml ps
curl -s http://localhost:8080/actuator/health
```

---

## 5. Verification Checklist

1. **Database Migration**: Ensure backend logs indicate `Successfully applied 34 migrations to schema "public"`.
2. **Backend Health**: `GET /actuator/health` returns `{"status":"UP", ...}`.
3. **Frontend Access**: `GET http://localhost:3000/catalog` renders HTTP 200.
4. **Inter-Service Communication**: Next.js proxy route `/api/v1/request-types` transparently retrieves backend data without CORS or 404/502 errors.
5. **Graceful Shutdown**: Issuing `docker compose stop` initiates graceful connection draining and preserves state integrity.
