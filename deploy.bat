@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Clinic Flow 2026 - Deployment Manager

echo ================================================================
echo           CLINIC FLOW 2026 - AUTOMATED DEPLOYMENT
echo ================================================================
echo.

:: Check Docker
docker --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Docker is not installed or not in PATH.
    echo Please install Docker Desktop and try again.
    pause
    exit /b 1
)

docker info >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Docker daemon is not running.
    echo Please start Docker Desktop and run this script again.
    pause
    exit /b 1
)

echo [OK] Docker is running.
echo.

:: Handle CLI argument if provided
if "%1"=="1" goto DEPLOY_DOCKER
if "%1"=="--docker" goto DEPLOY_DOCKER
if "%1"=="2" goto DEPLOY_DEV
if "%1"=="--dev" goto DEPLOY_DEV
if "%1"=="3" goto REBUILD_DOCKER
if "%1"=="--build" goto REBUILD_DOCKER
if "%1"=="4" goto STOP_SERVICES
if "%1"=="--stop" goto STOP_SERVICES

echo Select deployment mode:
echo   [1] Fast Launch (Full Docker Stack: DB, Redis, API, Web) [Instant]
echo   [2] Fast Local Dev Mode (Docker DB + Local Spring Boot and Angular)
echo   [3] Rebuild and Launch Docker Stack (Fresh build from source)
echo   [4] Stop and Clean All Running Services
echo.
set /p MODE="Enter choice (1-4) [default: 1]: "
set MODE=%MODE: =%
if "%MODE%"=="" set MODE=1

if "%MODE%"=="1" goto DEPLOY_DOCKER
if "%MODE%"=="2" goto DEPLOY_DEV
if "%MODE%"=="3" goto REBUILD_DOCKER
if "%MODE%"=="4" goto STOP_SERVICES
goto INVALID_CHOICE

:DEPLOY_DOCKER
echo.
echo ================================================================
echo [1/3] Starting Full Docker Production Stack...
echo ================================================================
:: Check if images exist, build only if missing
docker image inspect clinicflow-backend:latest >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Pre-built images not found. Performing initial build...
    docker compose -f docker-compose.yml build
)

docker compose -f docker-compose.yml up -d

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Docker deployment failed.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ================================================================
echo [2/3] Waiting for services to become healthy...
echo ================================================================
ping 127.0.0.1 -n 6 >nul 2>&1

echo.
echo ================================================================
echo [3/3] Deployment Successful!
echo ================================================================
echo.
echo Application URLs:
echo   - Web Workstation:  http://localhost
echo   - Backend REST API: http://localhost:4000
echo   - Actuator Health:  http://localhost:4101/actuator/health
echo   - PostgreSQL Port:  5435
echo   - Redis Port:       6381
echo.
echo Default Credentials:
echo   - Super Admin: admin@clinicflow.io / Password123^^!
echo   - Platform:    platform@clinicflow.io / Password123^^!
echo.
start http://localhost
goto END

:DEPLOY_DEV
echo.
echo ================================================================
echo [1/4] Preparing Local Dev Environment...
echo ================================================================
:: Free port 4000 if Docker backend is running
docker stop clinicflow-backend clinicflow-frontend >nul 2>&1

:: Start database and redis in Docker
docker compose -f docker-compose.yml up -d postgres redis

echo.
echo ================================================================
echo [2/4] Starting Spring Boot Backend in a separate window...
echo ================================================================
set WEB_ORIGIN=http://localhost:4200,http://localhost
set SESSION_SECRET=wVj2K9yNqL7xP3sT6uB1eR5aZ8dF0mC4hI9gO2vY1nU=
set ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5435/firstelement
set SPRING_DATA_REDIS_PORT=6381
start "ClinicFlow Backend (Spring Boot)" cmd /k "gradlew.bat :backend:app-bootstrap:bootRun"

echo.
echo ================================================================
echo [3/4] Starting Angular Frontend in a separate window...
echo ================================================================
start "ClinicFlow Frontend (Angular)" cmd /k "cd frontend && npm start"

echo.
echo ================================================================
echo [4/4] Waiting for services to initialize...
echo ================================================================
ping 127.0.0.1 -n 6 >nul 2>&1
start http://localhost:4200
goto END

:REBUILD_DOCKER
echo.
echo ================================================================
echo Rebuilding and Deploying Docker Stack from Scratch...
echo ================================================================
docker compose -f docker-compose.yml up -d --build
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Docker build failed.
    pause
    exit /b %ERRORLEVEL%
)
ping 127.0.0.1 -n 6 >nul 2>&1
start http://localhost
goto END

:STOP_SERVICES
echo.
echo Stopping all Clinic Flow Docker containers...
docker compose -f docker-compose.yml down
echo [OK] All services stopped.
goto END

:INVALID_CHOICE
echo [ERROR] Invalid choice. Please run the script again and select 1, 2, 3, or 4.
pause
exit /b 1

:END
echo.
echo Deployment script completed.
if "%1"=="" pause
