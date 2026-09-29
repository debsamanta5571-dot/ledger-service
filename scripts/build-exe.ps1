# Builds dist\Ledger\Ledger.exe: the API + bundled UI + a private Java runtime (no Java install needed to run it).
# Requires: JDK 21 (for jpackage), Maven, Node. Run from the repo root:  powershell -File scripts\build-exe.ps1
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host '1/4 Building UI (same-origin API)'
Push-Location ui
npm install --no-audit --no-fund
$env:VITE_API_BASE = '.'
npm run build
Remove-Item Env:VITE_API_BASE
Pop-Location

Write-Host '2/4 Bundling UI into the service'
Remove-Item -Recurse -Force src\main\resources\static -ErrorAction SilentlyContinue
Copy-Item -Recurse ui\dist src\main\resources\static

Write-Host '3/4 Packaging the jar'
mvn -B -q package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
$jar = (Get-ChildItem target\ledger-service-*.jar | Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1).Name

Write-Host '4/4 Creating the exe with jpackage'
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
jpackage --type app-image --name Ledger --input target --main-jar $jar `
  --dest dist --win-console `
  --java-options '-Dledger.open-browser=true' `
  --java-options '-Dledger.auth.bootstrap-api-key=dev-local-key'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
Write-Host 'Done: dist\Ledger\Ledger.exe  (needs Postgres: docker compose up -d db)'
