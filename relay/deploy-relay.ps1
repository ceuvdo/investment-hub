# Deploy the live relay Worker. First run also creates the shared secret:
# stored as the Worker secret RELAY_SECRET and as user env HUB_RELAY_SECRET for the Java backend.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:PATH = "E:\tools\node;$env:PATH"
$env:TEMP = 'E:\tools\tmp'; $env:TMP = 'E:\tools\tmp'
foreach ($n in 'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID') {
  $v = [Environment]::GetEnvironmentVariable($n, 'User')
  if ($v) { [Environment]::SetEnvironmentVariable($n, $v, 'Process') }
}
$wrangler = 'E:\tools\wrangler\node_modules\.bin\wrangler.cmd'
Push-Location $here
try {
  & $wrangler deploy
  if ($LASTEXITCODE -ne 0) { throw "deploy failed" }
  $secret = [Environment]::GetEnvironmentVariable('HUB_RELAY_SECRET', 'User')
  if (-not $secret) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secret = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    [Environment]::SetEnvironmentVariable('HUB_RELAY_SECRET', $secret, 'User')
    $secret | & $wrangler secret put RELAY_SECRET
    if ($LASTEXITCODE -ne 0) { throw "secret put failed" }
    'relay secret created'
  }
  [Environment]::SetEnvironmentVariable('HUB_RELAY_URL', 'https://investment-hub-live.ceuvdo.workers.dev/push', 'User')
} finally { Pop-Location }
