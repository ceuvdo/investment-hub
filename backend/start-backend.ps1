# Starts PostgreSQL (if not running) and the Java backend. Used by the "investment-hub backend" logon task.
# Paths default to the main PC (drive E:). Another PC sets HUB_PG_BIN, HUB_PG_DATA, HUB_LOG_DIR, HUB_TMP, HUB_JAVA
# (and HUB_SITE_DIR, HUB_BACKUP_DIR, HUB_DEPLOY for the backend itself) as user environment variables.
$ErrorActionPreference = 'Stop'

function Setting($name, $default) {
  $v = [Environment]::GetEnvironmentVariable($name, 'User')
  if ($v) { return $v } else { return $default }
}
$pgBin  = Setting 'HUB_PG_BIN'  'E:\tools\pgsql\bin'
$pgData = Setting 'HUB_PG_DATA' 'E:\data\pg'
$logs   = Setting 'HUB_LOG_DIR' 'E:\tools\logs'
$tmp    = Setting 'HUB_TMP'     'E:\tools\tmp'
$java   = Setting 'HUB_JAVA'    'E:\tools\jdk\bin\java.exe'
$port   = Setting 'HUB_PG_PORT' '5432'
$env:TEMP = $tmp; $env:TMP = $tmp
New-Item -ItemType Directory -Force $logs, $tmp | Out-Null
$jar = Join-Path $PSScriptRoot 'target\hub-backend.jar'

& "$pgBin\pg_isready.exe" -h 127.0.0.1 -p $port -q
if ($LASTEXITCODE -ne 0) {
  Start-Process -FilePath "$pgBin\pg_ctl.exe" -ArgumentList '-D', "`"$pgData`"", '-l', "`"$logs\postgres.log`"", '-o', "`"-p $port`"", 'start' -WindowStyle Hidden
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep 2
    & "$pgBin\pg_isready.exe" -h 127.0.0.1 -p $port -q
    if ($LASTEXITCODE -eq 0) { break }
  }
}

foreach ($n in 'HUB_DB_PASSWORD', 'HUB_RELAY_URL', 'HUB_RELAY_SECRET', 'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID', 'ANTHROPIC_API_KEY',
               'HUB_SITE_DIR', 'HUB_LOG_DIR', 'HUB_BACKUP_DIR', 'HUB_PG_BIN', 'HUB_PG_PORT', 'HUB_DEPLOY') {
  $v = [Environment]::GetEnvironmentVariable($n, 'User')
  if ($v) { [Environment]::SetEnvironmentVariable($n, $v, 'Process') }
}
# runs in the foreground so Task Scheduler sees it alive and can restart it if it dies
& $java -Xmx512m "-Djava.io.tmpdir=$tmp" -jar $jar *> "$logs\backend-console.log"
exit $LASTEXITCODE
