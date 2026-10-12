# Builds the web app and assembles the deployment folder <project>\deploy.
#   powershell -ExecutionPolicy Bypass -File web\build-deploy.ps1
# Needs Node.js, Maven and JDK 21 (set JAVA_HOME or pass -JavaHome).
# An existing deploy\application.properties is kept, so a filled-in password survives rebuilds.
param([string]$JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$web = $PSScriptRoot
$root = Split-Path $web -Parent
$deploy = Join-Path $root 'deploy'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }

Push-Location (Join-Path $web 'client')
try {
    npm ci
    if ($LASTEXITCODE) { throw 'npm ci failed' }
    npm run build
    if ($LASTEXITCODE) { throw 'client build failed' }
} finally { Pop-Location }

Push-Location (Join-Path $web 'server')
try {
    mvn -q clean package
    if ($LASTEXITCODE) { throw 'server build failed' }
} finally { Pop-Location }

New-Item -ItemType Directory -Force $deploy | Out-Null
Copy-Item (Join-Path $web 'server\target\ajwa-web-1.0.0.jar') (Join-Path $deploy 'ajwa-web.jar') -Force
Get-ChildItem (Join-Path $web 'deploy-template') | ForEach-Object {
    $target = Join-Path $deploy $_.Name
    if ($_.Name -ne 'application.properties' -or -not (Test-Path $target)) { Copy-Item $_.FullName $target -Force }
}
$relay = Join-Path $root 'android\relay.properties'
if (Test-Path $relay) { Copy-Item $relay (Join-Path $deploy 'relay.properties') -Force }
else { Write-Warning 'android\relay.properties not found: backups will not be e-mailed.' }

Write-Host "Deployment folder ready: $deploy"
