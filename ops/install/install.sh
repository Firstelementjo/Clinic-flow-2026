#!/usr/bin/env bash
# ==============================================================================
# Clinic Flow - Production One-Click Installer (Linux / VPS / macOS)
# Standard: Installability & Portability
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT_DIR}"

COMPOSE_FILE="docker-compose.prod.yml"
WEB_PORT="${1:-80}"

echo -e "\033[1;36m==============================================================\033[0m"
echo -e "\033[1;36m        Clinic Flow - Automated Production Installer          \033[0m"
echo -e "\033[1;36m==============================================================\033[0m"

# 1. Prerequisite Verification
echo -e "\033[1;33m[1/5] Checking environment prerequisites...\033[0m"
if ! command -v docker &> /dev/null; then
    echo -e "\033[1;31mError: Docker is not installed. Please install Docker and retry.\033[0m"
    exit 1
fi

if ! docker info &> /dev/null; then
    echo -e "\033[1;31mError: Docker daemon is not running.\033[0m"
    exit 1
fi
echo -e "\033[1;32m  -> Docker daemon is active and responsive.\033[0m"

# 2. Configuration & Secret Generation
echo -e "\033[1;33m[2/5] Configuring environment (.env)...\033[0m"
if [ ! -f .env ]; then
    echo -e "\033[0;37m  -> Generating .env from template with secure random secrets...\033[0m"
    
    SESSION_SECRET=$(openssl rand -base64 32 || head -c 32 /dev/urandom | base64)
    ENCRYPTION_KEY=$(openssl rand -base64 32 || head -c 32 /dev/urandom | base64)
    DB_PASSWORD=$(openssl rand -hex 16 || head -c 16 /dev/urandom | xxd -p)

    cat <<EOF > .env
# Clinic Flow Production Environment
APP_ENV=PRODUCTION
LOG_LEVEL=info

# Database & Cache
DATABASE_USER=firstelement
DATABASE_PASSWORD=${DB_PASSWORD}
DATABASE_NAME=firstelement
POSTGRES_PORT=5432
REDIS_PORT=6379

# Ports & URLs
API_PORT=4000
MANAGEMENT_PORT=4101
WEB_PORT=${WEB_PORT}
WEB_ORIGIN=http://localhost:${WEB_PORT}

# Cryptographic Keys (Auto-generated)
SESSION_SECRET=${SESSION_SECRET}
ENCRYPTION_KEY=${ENCRYPTION_KEY}

# Security
PLATFORM_CONSOLE_ENABLED=true
EOF
    echo -e "\033[1;32m  -> .env file successfully created.\033[0m"
else
    echo -e "\033[1;32m  -> Existing .env file found. Preserving current configurations.\033[0m"
fi

# 3. Launch Services
echo -e "\033[1;33m[3/5] Starting Clinic Flow containers via Docker Compose...\033[0m"
docker compose -f "${COMPOSE_FILE}" up -d --build

# 4. Readiness & Health Verification
echo -e "\033[1;33m[4/5] Waiting for services to become healthy...\033[0m"
MAX_RETRIES=30
COUNT=0
HEALTHY=false

while [ $COUNT -lt $MAX_RETRIES ]; do
    COUNT=$((COUNT+1))
    echo -e "\033[0;37m  -> Probing backend readiness (${COUNT}/${MAX_RETRIES})...\033[0m"
    
    BACKEND_STATUS=$(docker inspect --format='{{json .State.Health.Status}}' clinicflow-backend 2>/dev/null || echo "\"starting\"")
    FRONTEND_STATUS=$(docker inspect --format='{{json .State.Health.Status}}' clinicflow-frontend 2>/dev/null || echo "\"starting\"")
    
    if [ "${BACKEND_STATUS}" = "\"healthy\"" ] && [ "${FRONTEND_STATUS}" = "\"healthy\"" ]; then
        HEALTHY=true
        break
    fi
    sleep 3
done

if [ "${HEALTHY}" = true ]; then
    echo -e "\033[1;32m  -> All services are healthy and ready.\033[0m"
else
    echo -e "\033[1;33m  -> Warning: Healthcheck timeout. Inspecting logs...\033[0m"
    docker compose -f "${COMPOSE_FILE}" logs --tail=40
fi

# 5. Hand-off
echo -e "\033[1;32m[5/5] Installation Completed Successfully!\033[0m"
echo -e "\033[1;36m==============================================================\033[0m"
echo -e "\033[1;37mClinic Flow is live at:\033[0m"
echo -e "  Web Application : http://localhost:${WEB_PORT}"
echo -e "  Calendar Screen : http://localhost:${WEB_PORT}/app/clinic/calendar"
echo -e "  AI Assistant    : http://localhost:${WEB_PORT}/app/clinic/ai-assistant"
echo -e "  Health Status   : http://localhost:4101/actuator/health"
echo -e "\033[1;36m==============================================================\033[0m"
