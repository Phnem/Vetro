<#
  One-shot (re-runnable) provisioning + data upload + deploy for api.vetrostudio.bid.

  Prerequisite, once:   npx wrangler login     (browser OAuth; no tokens are typed into this script)

  Usage (from tools/books-api):
    .\scripts\deploy-data.ps1                 # everything: bucket, D1, catalogue, covers, index, Worker
    .\scripts\deploy-data.ps1 -IndexOnly      # only the phone-index files + manifest (after a new build_index run)
    .\scripts\deploy-data.ps1 -SkipCovers     # everything except the ~7.5 GB of covers

  Build the inputs first (book-dictionary/v2/README.md): out/manifest.json, out/index/*, out/server/*.
#>
param(
    [string]$Out = (Join-Path $PSScriptRoot "..\..\..\book-dictionary\v2\out"),
    [switch]$IndexOnly,
    [switch]$SkipCovers
)
$ErrorActionPreference = "Stop"
$Out = (Resolve-Path $Out).Path
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Bucket = "vetro-books"
$Db = "vetro-books"
Set-Location $Root

function Wr { & npx --yes wrangler@latest @args; if ($LASTEXITCODE -ne 0) { throw "wrangler $($args -join ' ') failed ($LASTEXITCODE)" } }

Write-Host "== account" -ForegroundColor Cyan
Wr whoami

if (-not $IndexOnly) {
    Write-Host "== R2 bucket $Bucket" -ForegroundColor Cyan
    try { Wr r2 bucket create $Bucket } catch { Write-Host "(bucket exists - fine)" }

    Write-Host "== D1 database $Db" -ForegroundColor Cyan
    try { Wr d1 create $Db } catch { Write-Host "(database exists - fine)" }
    $list = (& npx --yes wrangler@latest d1 list --json) | ConvertFrom-Json
    $id = ($list | Where-Object { $_.name -eq $Db }).uuid
    if (-not $id) { throw "database $Db not found" }
    $toml = Get-Content "wrangler.toml" -Raw
    $toml = [regex]::Replace($toml, 'database_id\s*=\s*"[^"]*"', "database_id = `"$id`"")
    Set-Content "wrangler.toml" $toml -NoNewline -Encoding utf8
    Write-Host "wrangler.toml -> database_id $id"

    Write-Host "== D1 schema + data (268k works)" -ForegroundColor Cyan
    Wr d1 execute $Db --remote --yes --file "$Out\server\schema.sql"
    foreach ($f in (Get-ChildItem "$Out\server\data-*.sql" | Sort-Object Name)) {
        Write-Host "  $($f.Name)"
        Wr d1 execute $Db --remote --yes --file $f.FullName
    }
    Wr d1 execute $Db --remote --yes --file "$Out\server\post-import.sql"
}

Write-Host "== phone index files" -ForegroundColor Cyan
$manifest = Get-Content "$Out\manifest.json" -Raw | ConvertFrom-Json
$files = @($manifest.base.url) + @($manifest.deltas | ForEach-Object { $_.url })
foreach ($rel in $files) {
    Wr r2 object put "$Bucket/$rel" --remote --file "$Out\$($rel -replace '/', '\')" --content-type "application/zstd" --cache-control "public, max-age=31536000, immutable"
}
# The manifest goes last: clients must never see a manifest that points at files not uploaded yet.
Wr r2 object put "$Bucket/index/manifest.json" --remote --file "$Out\manifest.json" --content-type "application/json"

if (-not $IndexOnly -and -not $SkipCovers) {
    Write-Host "== covers (re-running uploads everything again, ~176k objects)" -ForegroundColor Cyan
    foreach ($size in "small", "large") {
        Wr r2 bulk put $Bucket --remote --force --filename "$Out\server\covers-$size.json" --content-type "image/avif" --concurrency 50
    }
}

if (-not $IndexOnly) {
    Write-Host "== deploy Worker" -ForegroundColor Cyan
    Wr deploy
}
Write-Host "Done. Check: https://api.vetrostudio.bid/v1/health  and  /v1/manifest" -ForegroundColor Green
