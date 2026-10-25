# Builds the launcher (launcher\WarioLauncher.csproj, .NET Framework 4.8) and copies it to Play\WarioLauncher.exe.
# Uses the portable .NET 8 SDK in toolchain\dotnet (or the system one). Log: build-logs\launcher.log
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'build-logs'
[IO.Directory]::CreateDirectory($logs) | Out-Null
$log = Join-Path $logs 'launcher.log'

$dotnet = Join-Path $root 'toolchain\dotnet\dotnet.exe'
if (-not (Test-Path -LiteralPath $dotnet)) {
    $sys = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($sys) { $dotnet = $sys.Source } else { throw 'dotnet not found: run Build-WarioRecomp.cmd first (it downloads the portable SDK).' }
}
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'
$proj = Join-Path $root 'launcher\WarioLauncher.csproj'
$out = Join-Path $root 'launcher\bin\out'

# Launcher assets from local-assets\: icon and header image.
$assets = Join-Path $root 'local-assets'
$icon = Join-Path $assets 'wario.ico'
if (Test-Path -LiteralPath $icon) { Copy-Item -LiteralPath $icon -Destination (Join-Path $root 'launcher\wario.ico') -Force }
$previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $dotnet build $proj -c Release -o $out 2>&1 | ForEach-Object { "$_" } | Out-File -LiteralPath $log -Encoding utf8
$code = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($code -ne 0) {
    Get-Content -LiteralPath $log -Tail 30
    throw "The launcher did not build (code $code), see build-logs\launcher.log"
}
$play = Join-Path $root 'Play'
New-Item -ItemType Directory -Force -Path $play | Out-Null
Get-Process WarioLauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Copy-Item -LiteralPath (Join-Path $out 'WarioLauncher.exe') -Destination (Join-Path $play 'WarioLauncher.exe') -Force
$cfg = Join-Path $out 'WarioLauncher.exe.config'
if (Test-Path -LiteralPath $cfg) { Copy-Item -LiteralPath $cfg -Destination (Join-Path $play 'WarioLauncher.exe.config') -Force }
foreach ($b in @('launcher_banner.jpg', 'launcher_banner.png')) {
    $src = Join-Path $assets $b
    if (Test-Path -LiteralPath $src) {
        New-Item -ItemType Directory -Force -Path (Join-Path $play 'UserData') | Out-Null
        Copy-Item -LiteralPath $src -Destination (Join-Path $play ('UserData\' + $b)) -Force
    }
}
Write-Host "Launcher ready: $play\WarioLauncher.exe"
