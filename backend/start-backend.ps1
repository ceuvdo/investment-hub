# Starts PostgreSQL (if not running) and the Java backend. Used by the "investment-hub backend" logon task.
$ErrorActionPreference = 'Stop'
$env:TEMP = 'E:\tools\tmp'; $env:TMP = 'E:\tools\tmp'
$pgBin = 'E:\tools\pgsql\bin'
$jar = Join-Path $PSScriptRoot 'target\hub-backend.jar'

& "$pgBin\pg_isready.exe" -h 127.0.0.1 -p 5432 -q
if ($LASTEXITCODE -ne 0) {
  Start-Process -FilePath "$pgBin\pg_ctl.exe" -ArgumentList '-D', 'E:\data\pg', '-l', 'E:\tools\logs\postgres.log', 'start' -WindowStyle Hidden
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep 2
    & "$pgBin\pg_isready.exe" -h 127.0.0.1 -p 5432 -q
    if ($LASTEXITCODE -eq 0) { break }
  }
}

foreach ($n in 'HUB_DB_PASSWORD', 'HUB_RELAY_URL', 'HUB_RELAY_SECRET', 'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID', 'ANTHROPIC_API_KEY') {
  $v = [Environment]::GetEnvironmentVariable($n, 'User')
  if ($v) { [Environment]::SetEnvironmentVariable($n, $v, 'Process') }
}
# runs in the foreground so Task Scheduler sees it alive and can restart it if it dies
& 'E:\tools\jdk\bin\java.exe' -Xmx512m '-Djava.io.tmpdir=E:\tools\tmp' -jar $jar *> 'E:\tools\logs\backend-console.log'
exit $LASTEXITCODE
