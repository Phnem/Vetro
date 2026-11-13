<#
  Incremental release of a NEW catalogue build (the bucket, the database and the Worker already exist: see deploy-data.ps1).
  The live API keeps answering throughout: D1 is loaded into works_next/fts_next next to the live tables and swapped in at the end.

  Build the inputs first (book-dictionary/v2/README.md): build_index.py, then
    build_server_data.py ... --suffix _next          -> out/server/{schema,data-*,post-import,swap}.sql
    tools/new_covers.py  ...                          -> out/server/covers-new/covers-new-{small,large}.json

  Usage (from tools/books-api), in this order; -Covers and -Load can run at the same time:
    .\scripts\refresh-data.ps1 -Covers      # only covers the bucket does not have yet (resumable chunks)
    .\scripts\refresh-data.ps1 -Load        # D1: schema + data + FTS into works_next/fts_next (resumable per file)
    .\scripts\refresh-data.ps1 -Publish     # phone-index files -> swap D1 tables -> manifest (last, always)

  Every wrangler call is retried: the REST API answers 429/5xx now and then. Markers of finished chunks/files carry the file's
  size and timestamp, so a rebuilt file is never skipped by mistake.
#>
param(
    [string]$Out = (Join-Path $PSScriptRoot "..\..\..\book-dictionary\v2\out"),
    [switch]$Covers,
    [switch]$Load,
    [switch]$Publish,
    # wrangler talks to the Cloudflare REST API, which answers 429 above a few requests per second.
    [int]$Concurrency = 3,
    [int]$ChunkSize = 2500,
    [int]$Retries = 4
)
$ErrorActionPreference = "Stop"
$Out = (Resolve-Path $Out).Path
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Bucket = "vetro-books"
$Db = "vetro-books"
Set-Location $Root
if (-not ($Covers -or $Load -or $Publish)) { throw "Pick at least one of -Covers, -Load, -Publish (see the header)." }

# npx.cmd + `$args`, not npx.ps1 + `@args`: in Windows PowerShell 5.1 splatting through the .ps1 wrapper drops every argument.
function Wr {
    $attempt = 0
    while ($true) {
        # wrangler writes warnings to stderr; under "Stop" that would abort the script.
        $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        & npx.cmd --yes wrangler@latest $args
        $code = $LASTEXITCODE; $ErrorActionPreference = $prev
        if ($code -eq 0) { return }
        $attempt++
        if ($attempt -ge $Retries) { throw "wrangler $($args -join ' ') failed ($code) after $attempt attempts" }
        Write-Host "  wrangler failed ($code), retry $attempt/$($Retries - 1) in $(15 * $attempt)s" -ForegroundColor Yellow
        Start-Sleep -Seconds (15 * $attempt)
    }
}

# Marker name = file name + size + last-write ticks: a rebuilt file gets a new marker and is uploaded again.
function Marker([string]$dir, [System.IO.FileInfo]$file) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    return Join-Path $dir ("{0}.{1}.{2}.done" -f $file.Name, $file.Length, $file.LastWriteTimeUtc.Ticks)
}

$server = Join-Path $Out "server"
$noBom = New-Object System.Text.UTF8Encoding $false

if ($Covers) {
    Write-Host "== new covers" -ForegroundColor Cyan
    $dir = Join-Path $server "covers-new"
    foreach ($size in "small", "large") {
        $listFile = Get-Item (Join-Path $dir "covers-new-$size.json")
        # -InputObject, not a pipe: Windows PowerShell 5.1 would hand a piped JSON array over as ONE object.
        $items = ConvertFrom-Json -InputObject (Get-Content $listFile.FullName -Raw -Encoding UTF8)
        $n = 0
        for ($i = 0; $i -lt $items.Count; $i += $ChunkSize) {
            $n++
            $last = [math]::Min($i + $ChunkSize - 1, $items.Count - 1)
            $path = Join-Path $dir ("chunk-{0}-{1:D3}.json" -f $size, $n)
            [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject @($items[$i..$last]) -Compress), $noBom)
            $marker = Marker (Join-Path $dir "done") (Get-Item $path)
            if (Test-Path $marker) { continue }
            Write-Host ("  {0} chunk {1}: {2}/{3}" -f $size, $n, ($last + 1), $items.Count)
            Wr r2 bulk put $Bucket --remote --force --filename $path --content-type "image/avif" --concurrency $Concurrency
            New-Item $marker -ItemType File | Out-Null
        }
    }
}

if ($Load) {
    Write-Host "== D1: load works_next / fts_next" -ForegroundColor Cyan
    $done = Join-Path $server "load-next"
    $files = @(Get-Item (Join-Path $server "schema.sql")) + @(Get-ChildItem (Join-Path $server "data-*.sql") | Sort-Object Name) +
        @(Get-Item (Join-Path $server "post-import.sql"))
    # schema.sql drops and recreates works_next: if it has to run again, every data file after it must run again too.
    $rerun = $false
    foreach ($f in $files) {
        $marker = Marker $done $f
        if ((Test-Path $marker) -and -not $rerun) { continue }
        if ($f.Name -eq "schema.sql") { $rerun = $true; Get-ChildItem $done -Filter "*.done" | Remove-Item }
        Write-Host "  $($f.Name)"
        Wr d1 execute $Db --remote --yes --file $f.FullName
        New-Item (Marker $done $f) -ItemType File | Out-Null
    }
}

if ($Publish) {
    Write-Host "== phone-index files" -ForegroundColor Cyan
    $manifest = Get-Content (Join-Path $Out "manifest.json") -Raw | ConvertFrom-Json
    $rels = @($manifest.base.url) + @($manifest.deltas | ForEach-Object { $_.url })
    foreach ($rel in $rels) {
        Wr r2 object put "$Bucket/$rel" --remote --file (Join-Path $Out ($rel -replace '/', '\')) --content-type "application/zstd" --cache-control "public, max-age=31536000, immutable"
    }
    Write-Host "== D1: swap works_next/fts_next in" -ForegroundColor Cyan
    Wr d1 execute $Db --remote --yes --file (Join-Path $server "swap.sql")
    # The manifest goes last: clients must never see a manifest that points at files not uploaded yet.
    Write-Host "== manifest" -ForegroundColor Cyan
    Wr r2 object put "$Bucket/index/manifest.json" --remote --file (Join-Path $Out "manifest.json") --content-type "application/json"
}
Write-Host "Done." -ForegroundColor Green
