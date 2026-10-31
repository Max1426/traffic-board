param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$isccCandidates = @($env:ISCC_PATH, (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'), 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe', 'C:\Program Files\Inno Setup 6\ISCC.exe')
$iscc = $isccCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $iscc) { throw 'Install Inno Setup 6 or set ISCC_PATH to ISCC.exe.' }
if (-not $SkipTests) {
    & dotnet test tests/TrafficBoard.Tests/TrafficBoard.Tests.csproj -c Release --logger 'trx;LogFileName=core-tests.trx' --results-directory artifacts/tests --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
}
$publish = Join-Path $root 'artifacts\publish'
& dotnet publish src/TrafficBoard.App/TrafficBoard.App.csproj -c Release -p:Platform=x64 -r win-x64 --self-contained true -o $publish --nologo
if ($LASTEXITCODE -ne 0) { throw 'WinUI publish failed' }
foreach ($resource in @('App.xbf', 'MainWindow.xbf', 'TrafficBoard.pri', 'Assets\traffic-board.ico')) {
    if (-not (Test-Path -LiteralPath (Join-Path $publish $resource))) { throw "Missing WinUI publish resource: $resource" }
}
Copy-Item -LiteralPath (Join-Path (Split-Path $root -Parent) 'LICENSE') -Destination (Join-Path $publish 'LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $root 'THIRD_PARTY_NOTICES.md') -Destination $publish -Force
$output = Join-Path $root 'dist'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $iscc "/DPublishDir=$publish" "/DOutputDir=$output" 'installer\TrafficBoard.iss'
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed' }
$installer = Join-Path $output 'TrafficBoard-Windows-Setup-1.0.0-x64.exe'
"$((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash)  $(Split-Path $installer -Leaf)" | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Installer: $installer"
