# Deploy the site to Cloudflare (Workers static assets; free plan).
# Needs the user environment variable CLOUDFLARE_API_TOKEN (Account > Workers Scripts > Edit),
# optionally CLOUDFLARE_ACCOUNT_ID. Node.js and Wrangler live on E:\tools (drive C: is full).
param([string]$Name = 'investment-hub')
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:PATH = "E:\tools\node;$env:PATH"
$env:TEMP = 'E:\tools\tmp'; $env:TMP = 'E:\tools\tmp'; $env:npm_config_cache = 'E:\tools\npm-cache'
foreach ($n in 'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_ACCOUNT_ID') {
  if (-not [Environment]::GetEnvironmentVariable($n, 'Process')) {
    $v = [Environment]::GetEnvironmentVariable($n, 'User')
    if (-not $v) {
      if ($n -eq 'CLOUDFLARE_API_TOKEN') { throw "$n is not set. See README: Deploy to Cloudflare." }
      continue  # account id is optional when the token sees a single account
    }
    [Environment]::SetEnvironmentVariable($n, $v, 'Process')
  }
}
$wrangler = 'E:\tools\wrangler\node_modules\.bin\wrangler.cmd'

# Site code (pages, css, js, images) comes from the last git commit, so a half-edited page in the
# working folder is never published by the 09:00 job. Only the freshly built data comes from the folder.
$dist = 'E:\tools\tmp\site-dist'
$pub = "$dist\public"
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory $pub | Out-Null
$zip = "$dist\head.zip"
& git -C $here archive --format=zip -o $zip HEAD -- '*.html' assets
if ($LASTEXITCODE -ne 0) { throw "git archive failed ($LASTEXITCODE)" }
Expand-Archive $zip $pub
Remove-Item $zip
Get-ChildItem $pub -Recurse -Filter *.md | Remove-Item
New-Item -ItemType Directory "$pub\data" -Force | Out-Null
Copy-Item "$here\data\*.json", "$here\data\*.js" "$pub\data"

@"
{
  "name": "$Name",
  "compatibility_date": "2026-11-01",
  "assets": { "directory": "./public", "html_handling": "auto-trailing-slash","not_found_handling": "404-page" },
  "workers_dev": true
}
"@ | Set-Content "$dist\wrangler.jsonc" -Encoding utf8

Push-Location $dist
try {
  & $wrangler deploy --config "$dist\wrangler.jsonc"
  if ($LASTEXITCODE -ne 0) { throw "wrangler deploy failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
