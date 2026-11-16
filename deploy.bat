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
if "%1"=="3" goto STOP_SERVICES
if "%1"=="--stop" goto STOP_SERVICES

echo Select deployment mode:
echo   [1] Full Docker Deployment (Production: DB, Redis, API, Web)
echo   [2] Fast Local Dev Mode (Docker DB + Local Spring Boot and Angular)
echo   [3] Stop and Clean All Running Services
echo.
set /p MODE="Enter choice (1-3) [default: 1]: "
set MODE=%MODE: =%
if "%MODE%"=="" set MODE=1

if "%MODE%"=="1" goto DEPLOY_DOCKER
if "%MODE%"=="2" goto DEPLOY_DEV
if "%MODE%"=="3" goto STOP_SERVICES
goto INVALID_CHOICE

:DEPLOY_DOCKER
echo.
echo ================================================================
echo [1/3] Deploying Full Docker Production Stack...
echo ================================================================
docker compose -f docker-compose.yml up -d --build

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Docker deployment failed.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ================================================================
echo [2/3] Waiting for services to become healthy...
echo ================================================================
timeout /t 8 /nobreak >nul

echo.
echo ================================================================
echo [3/3] Deployment Successful!
echo ================================================================
echo.
echo Application URLs:
echo   - Web Workstation: http://localhost
echo   - Backend REST API: http://localhost:4000
echo   - Actuator Health:  http://localhost:4101/actuator/health
echo.
echo Default Credentials:
echo   - Super Admin: admin@clinicflow.io / Password123!
echo   - Platform:    platform@clinicflow.io / Password123!
echo.
start http://localhost
goto END

:DEPLOY_DEV
echo.
echo ================================================================
echo [1/4] Starting PostgreSQL and Redis via Docker...
echo ================================================================
docker compose -f docker-compose.yml up -d postgres redis

echo.
echo ================================================================
echo [2/4] Starting Spring Boot Backend in a separate window...
echo ================================================================
set WEB_ORIGIN=http://localhost:4200,http://localhost
set SESSION_SECRET=wVj2K9yNqL7xP3sT6uB1eR5aZ8dF0mC4hI9gO2vY1nU=
set ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
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
timeout /t 5 /nobreak >nul
start http://localhost:4200
goto END

:STOP_SERVICES
echo.
echo Stopping all Clinic Flow Docker containers...
docker compose -f docker-compose.yml down
echo [OK] All services stopped.
goto END

:INVALID_CHOICE
echo [ERROR] Invalid choice. Please run the script again and select 1, 2, or 3.
pause
exit /b 1

:END
echo.
echo Deployment script completed.
if "%1"=="" pause
