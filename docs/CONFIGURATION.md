# Workflow Platform Configuration & Environment Hardening

This document defines the configuration architecture, environment separation, secrets management, connection pool tuning, and operational limits across all deployment tiers.

---

## 1. Environment Profiles

The platform uses Spring profiles to cleanly segregate environment configurations:

| Profile | Purpose | Data Persistence | Credentials Strategy |
| :--- | :--- | :--- | :--- |
| `dev` | Local developer workstations | Local PostgreSQL (`localhost:5433`) | Sensible developer defaults |
| `test` | Automated integration testing | Ephemeral Testcontainers (PostgreSQL 17) | Dynamically allocated |
| `staging` | Staging cluster / pre-production | Staging PostgreSQL instance | Externalized via environment variables |
| `prod` | High-availability production | Managed PostgreSQL 17 cluster | Strictly externalized secrets (no defaults) |

---

## 2. Environment Variables & Secrets Reference

| Variable | Required In | Default (dev) | Description |
| :--- | :---: | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Yes | `dev` | Active Spring profile (`dev`, `staging`, `prod`) |
| `DATABASE_URL` | Staging/Prod | `jdbc:postgresql://localhost:5433/workflow_platform` | PostgreSQL 17 JDBC connection URL |
| `DATABASE_USERNAME` | Staging/Prod | `workflow` | Database username |
| `DATABASE_PASSWORD` | Staging/Prod | `workflow123` | Database password (dev default rejected in prod) |
| `BACKEND_PORT` | No | `8080` | HTTP port for backend Spring Boot service |
| `CORS_ALLOWED_ORIGINS` | Staging/Prod | `*` (dev only) | Comma-separated allowed HTTP origins (no `*` in prod) |
| `CALLBACK_SIGNING_SECRET` | Staging/Prod | (Generated) | HMAC-SHA256 secret for webhook/callback signatures |
| `PLATFORM_FILES_ROOT` | No | `${java.io.tmpdir}/workflow-platform-files` | Root directory for local filesystem object storage |
| `MAX_FILE_SIZE` | No | `25MB` | Maximum single file upload size |
| `MAX_REQUEST_SIZE` | No | `50MB` | Maximum multipart request size |
| `SHUTDOWN_TIMEOUT` | No | `30s` | Graceful shutdown drain timeout |
| `NEXT_PUBLIC_API_BASE_URL` | Frontend | `http://localhost:3000/api/v1` | Public API endpoint for browser clients |
| `BACKEND_INTERNAL_URL` | Frontend | `http://localhost:8080` | Upstream backend URL for SSR/API proxy |

---

## 3. Security & Anti-Leakage Rules

1. **Zero Hard-Coded Production Secrets**:
   - Production builds fail fast if `DATABASE_URL`, `DATABASE_USERNAME`, or `DATABASE_PASSWORD` are missing.
   - Known development passwords (`workflow123`, `password`, `admin`, `root`, etc.) trigger immediate startup rejection in `prod`.
2. **Secret Masking**:
   - `ProductionConfigurationValidator` sanitizes URLs and masks database credentials before emitting log statements.
   - Stack traces, authorization tokens, and raw signing secrets are never exposed in logs or actuator endpoints.
3. **UTC Timezone Enforcement**:
   - Platform startup validates and enforces UTC at JVM, Hibernate JDBC, and PostgreSQL session levels (`SET TIME ZONE 'UTC'`).

---

## 4. Connection Pool & HTTP Resource Limits

### HikariCP Connection Pool

| Metric | Dev | Staging | Production | Description |
| :--- | :---: | :---: | :---: | :--- |
| `maximum-pool-size` | 10 | 20 | 30 | Maximum concurrent database connections |
| `minimum-idle` | 2 | 5 | 10 | Minimum idle connections retained |
| `connection-timeout` | 30,000ms | 30,000ms | 15,000ms | Max wait time before connection acquisition failure |
| `idle-timeout` | 600,000ms | 300,000ms | 300,000ms | Max idle duration before releasing idle connection |
| `max-lifetime` | 1,800,000ms | 1,800,000ms | 1,800,000ms | Max connection age before retirement |
| `validation-timeout` | 5,000ms | 5,000ms | 3,000ms | Connection health check timeout |

### Tomcat & Network Configuration
- **Graceful Shutdown**: Enabled (`server.shutdown: graceful`, 30s drain timeout).
- **Timeouts**: Connection timeout 20s, Keep-alive timeout 15s.
- **Worker Threads**: Max 200, Min spare 10.
- **Connection Capacity**: Max 8,192 simultaneous connections.

---

## 5. Production Configuration Checklist

- [x] Spring profile set to `prod` (`SPRING_PROFILES_ACTIVE=prod`).
- [x] Dedicated PostgreSQL 17 user with non-trivial password configured.
- [x] UTC timezone active across OS, database server, and JVM.
- [x] Database migrations managed via Flyway (`flyway.validate-on-migrate=true`, `clean-disabled=true`).
- [x] CORS origins restricted to exact production domain(s) (no wildcard `*`).
- [x] Secret masking verified; credentials not logged to standard out or files.
- [x] Graceful shutdown and health probes active (`/actuator/health/readiness`, `/actuator/health/liveness`).
- [x] Connection pool sized appropriately for database server resources.
- [x] Reverse proxy headers enabled (`server.forward-headers-strategy=framework`).
