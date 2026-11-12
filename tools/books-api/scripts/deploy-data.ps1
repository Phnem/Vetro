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
    [switch]$SkipCovers,
    [switch]$CoversOnly,
    # wrangler talks to the Cloudflare REST API, which answers 429 above a few requests per second; 50 failed after ~1000 files.
    [int]$Concurrency = 3
)
$ErrorActionPreference = "Stop"
$Out = (Resolve-Path $Out).Path
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Bucket = "vetro-books"
$Db = "vetro-books"
Set-Location $Root

# npx.cmd, not npx.ps1, and `$args`, not `@args`: in Windows PowerShell 5.1 splatting through the .ps1 wrapper drops
# every argument and wrangler just prints its help.
function Wr {
    # wrangler writes warnings to stderr; with $ErrorActionPreference = "Stop" and redirected output that would abort the script.
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    & npx.cmd --yes wrangler@latest $args
    $code = $LASTEXITCODE; $ErrorActionPreference = $prev
    if ($code -ne 0) { throw "wrangler $($args -join ' ') failed ($code)" }
}

Write-Host "== account" -ForegroundColor Cyan
Wr whoami

if ($CoversOnly) { $IndexOnly = $false; $SkipIndexAndData = $true }

if (-not $IndexOnly -and -not $SkipIndexAndData) {
    Write-Host "== R2 bucket $Bucket" -ForegroundColor Cyan
    try { Wr r2 bucket create $Bucket } catch { Write-Host "(bucket exists - fine)" }

    Write-Host "== D1 database $Db" -ForegroundColor Cyan
    try { Wr d1 create $Db } catch { Write-Host "(database exists - fine)" }
    $list = (& npx.cmd --yes wrangler@latest d1 list --json) | ConvertFrom-Json
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

if (-not $SkipIndexAndData) {
    Write-Host "== phone index files" -ForegroundColor Cyan
    $manifest = Get-Content "$Out\manifest.json" -Raw | ConvertFrom-Json
    $files = @($manifest.base.url) + @($manifest.deltas | ForEach-Object { $_.url })
    foreach ($rel in $files) {
        Wr r2 object put "$Bucket/$rel" --remote --file "$Out\$($rel -replace '/', '\')" --content-type "application/zstd" --cache-control "public, max-age=31536000, immutable"
    }
    # The manifest goes last: clients must never see a manifest that points at files not uploaded yet.
    Wr r2 object put "$Bucket/index/manifest.json" --remote --file "$Out\manifest.json" --content-type "application/json"
}

if (-not $IndexOnly -and -not $SkipCovers) {
    # ~176k objects, ~7.5 GB. Uploaded in chunks of 5000; a finished chunk leaves out\server\covers-chunks\<name>.done,
    # so a closed terminal or a dropped connection costs at most one chunk. Delete the folder to upload everything again.
    Write-Host "== covers (resumable)" -ForegroundColor Cyan
    $chunkDir = "$Out\server\covers-chunks"
    New-Item -ItemType Directory -Force $chunkDir | Out-Null
    $noBom = New-Object System.Text.UTF8Encoding $false
    foreach ($size in "small", "large") {
        # -InputObject, not a pipe: Windows PowerShell 5.1 would hand a piped JSON array over as ONE object.
        $items = ConvertFrom-Json -InputObject (Get-Content "$Out\server\covers-$size.json" -Raw -Encoding UTF8)
        $n = 0
        for ($i = 0; $i -lt $items.Count; $i += 5000) {
            $n++
            $name = "{0}-{1:D3}" -f $size, $n
            if (Test-Path "$chunkDir\$name.done") { continue }
            $last = [math]::Min($i + 4999, $items.Count - 1)
            [IO.File]::WriteAllText("$chunkDir\$name.json", (ConvertTo-Json -InputObject @($items[$i..$last]) -Compress), $noBom)
            Write-Host "  $name ($($last + 1)/$($items.Count))"
            Wr r2 bulk put $Bucket --remote --force --filename "$chunkDir\$name.json" --content-type "image/avif" --concurrency $Concurrency
            New-Item "$chunkDir\$name.done" -ItemType File | Out-Null
        }
    }
}

if (-not $IndexOnly -and -not $SkipIndexAndData) {
    Write-Host "== deploy Worker" -ForegroundColor Cyan
    Wr deploy
}
Write-Host "Done. Check: https://api.vetrostudio.bid/v1/health  and  /v1/manifest" -ForegroundColor Green
