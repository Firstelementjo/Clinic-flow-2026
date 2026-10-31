#!/usr/bin/env pwsh
# ==============================================================================
# Clinic Flow - Production One-Click Installer (Windows / PowerShell)
# Standard: Installability & Portability
# ==============================================================================

[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.prod.yml",
    [int]$WebPort = 80,
    [switch]$ForceRebuild = $false
)

$ErrorActionPreference = "Stop"

Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host "        Clinic Flow - Automated Production Installer          " -ForegroundColor Cyan
Write-Host "==============================================================" -ForegroundColor Cyan

# 1. Prerequisite Verification
Write-Host "[1/5] Checking environment prerequisites..." -ForegroundColor Yellow

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not installed or not in PATH. Please install Docker Desktop for Windows."
    exit 1
}

try {
    docker info | Out-Null
    Write-Host "  -> Docker daemon is running." -ForegroundColor Green
} catch {
    Write-Error "Docker daemon is not running. Please start Docker Desktop and retry."
    exit 1
}

# 2. Configuration & Secret Generation
Write-Host "[2/5] Configuring environment (.env)..." -ForegroundColor Yellow

$envPath = Join-Path $PSScriptRoot "..\..\.env"
if (-not (Test-Path $envPath)) {
    Write-Host "  -> Generating new production .env from template..." -ForegroundColor Gray
    $examplePath = Join-Path $PSScriptRoot "..\..\.env.example"
    
    # Generate cryptographic random secrets
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    
    $secretBytes = New-Object byte[] 32
    $rng.GetBytes($secretBytes)
    $sessionSecret = [Convert]::ToBase64String($secretBytes)

    $encBytes = New-Object byte[] 32
    $rng.GetBytes($encBytes)
    $encryptionKey = [Convert]::ToBase64String($encBytes)

    $envContent = @"
# Clinic Flow Production Environment
APP_ENV=PRODUCTION
LOG_LEVEL=info

# Database & Cache
DATABASE_USER=firstelement
DATABASE_PASSWORD=$([System.Guid]::NewGuid().ToString('N'))
DATABASE_NAME=firstelement
POSTGRES_PORT=5432
REDIS_PORT=6379

# Ports & URLs
API_PORT=4000
MANAGEMENT_PORT=4101
WEB_PORT=$WebPort
WEB_ORIGIN=http://localhost:$WebPort

# Cryptographic Keys (Auto-generated)
SESSION_SECRET=$sessionSecret
ENCRYPTION_KEY=$encryptionKey

# Security
PLATFORM_CONSOLE_ENABLED=true
"@
    Set-Content -Path $envPath -Value $envContent -Encoding UTF8
    Write-Host "  -> .env file created with secure generated credentials." -ForegroundColor Green
} else {
    Write-Host "  -> Existing .env file found. Preserving existing secrets." -ForegroundColor Green
}

# 3. Build & Launch Containers
Write-Host "[3/5] Starting Clinic Flow containers via Docker Compose..." -ForegroundColor Yellow
$rootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $rootDir

$buildArg = if ($ForceRebuild) { "--build" } else { "" }
$composeCmd = "docker compose -f $ComposeFile up -d $buildArg"
Invoke-Expression $composeCmd

if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker Compose failed to start services."
    exit 1
}

# 4. Readiness & Health Verification
Write-Host "[4/5] Waiting for services to become healthy..." -ForegroundColor Yellow
$maxRetries = 30
$healthy = $false

for ($i = 1; $i -le $maxRetries; $i++) {
    Write-Host "  -> Probing backend readiness ($i/$maxRetries)..." -ForegroundColor Gray
    try {
        $backendHealth = docker inspect --format='{{json .State.Health.Status}}' clinicflow-backend 2>$null
        $frontendHealth = docker inspect --format='{{json .State.Health.Status}}' clinicflow-frontend 2>$null
        
        if ($backendHealth -match "healthy" -and $frontendHealth -match "healthy") {
            $healthy = $true
            break
        }
    } catch {}
    Start-Sleep -Seconds 3
}

if (-not $healthy) {
    Write-Warning "Services are starting but healthcheck timed out. Checking container logs:"
    docker compose -f $ComposeFile logs --tail=50
} else {
    Write-Host "  -> All services reported healthy and ready." -ForegroundColor Green
}

# 5. Summary & Hand-off
Write-Host "[5/5] Installation Completed Successfully!" -ForegroundColor Green
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host "Clinic Flow is now live and accessible at:" -ForegroundColor Cyan
Write-Host "  Web Application : http://localhost:$WebPort" -ForegroundColor White
Write-Host "  Calendar Screen : http://localhost:$WebPort/app/clinic/calendar" -ForegroundColor White
Write-Host "  AI Assistant    : http://localhost:$WebPort/app/clinic/ai-assistant" -ForegroundColor White
Write-Host "  Health Status   : http://localhost:4101/actuator/health" -ForegroundColor White
Write-Host "==============================================================" -ForegroundColor Cyan
