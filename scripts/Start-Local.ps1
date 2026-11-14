param([int]$Port=8083)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($Port -lt 1024 -or $Port -gt 65535){throw 'Invalid port.'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw 'Port occupied. No process was stopped.'}
Push-Location -LiteralPath $root
try {
    if(-not (Test-Path -LiteralPath '.local/auth.properties')){throw 'Run scripts/Initialize-Local.ps1 first.'}
    $jar=Join-Path $root 'build/libs/ai-study-planner-0.0.1-SNAPSHOT.jar'
    if(-not (Test-Path -LiteralPath $jar)){throw 'Run .\gradlew.bat bootJar first.'}
    $db=(Join-Path $root '.local/data/planner-public').Replace('\','/')
    # Dedicated H2 URL is pinned; inherited MySQL URLs cannot select the original DB.
    & java -jar $jar '--spring.profiles.active=public-local' "--spring.config.location=classpath:/,optional:file:$root/.local/auth.properties" '--server.address=127.0.0.1' "--server.port=$Port" "--spring.datasource.url=jdbc:h2:file:$db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE" '--spring.datasource.driver-class-name=org.h2.Driver' '--spring.datasource.username=sa' '--spring.datasource.password=' '--spring.jpa.hibernate.ddl-auto=none'
    if($LASTEXITCODE -ne 0){throw 'Server exited unsuccessfully.'}
} finally {Pop-Location}
