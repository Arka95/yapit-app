# Restores the pinned Android runtime and exports the current English Pocket checkpoint.
param(
    [string]$Python = 'python',
    [string]$CacheDirectory = '',
    [switch]$InstallExportDependencies
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $CacheDirectory) { $CacheDirectory = "$root\data\models\hub" }

function Get-VerifiedAsset {
    param([string]$Url, [string]$Path, [string]$Sha256)
    if ((Test-Path -LiteralPath $Path) -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) { return }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $pending = "$Path.$([Guid]::NewGuid().ToString('N')).pending"
    try {
        Invoke-WebRequest -Uri $Url -OutFile $pending -TimeoutSec 300
        if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Path" }
        Move-Item -LiteralPath $pending -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending }
    }
}

$release = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
Get-VerifiedAsset "$release/v2.0.3/sherpa-onnx-2.0.3.aar" "$root\app\libs\sherpa-onnx-2.0.3.aar" '4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50'
$model = "$root\data\models\pocket-tts-english-2026-10-fp32"
$manifestPath = "$model\manifest.json"
if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $valid = $manifest.revision -eq 'db04f0e653473e5c7333c69686ac92b7460b0c1d' -and $manifest.precision -eq 'float32'
    foreach ($entry in $manifest.files.PSObject.Properties) {
        $path = Join-Path $model $entry.Name
        if (-not (Test-Path -LiteralPath $path) -or
                (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.Value.sha256) { $valid = $false; break }
    }
    if ($valid) { Write-Output 'Verified existing October 2026 FP32 model and Android runtime.'; return }
}
if ($InstallExportDependencies) {
    & $Python -m pip install -r "$root\config\pocket-export-requirements.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Could not install export dependencies' }
}
$license = "$root\data\models\pocket-tts-LICENSE"
Get-VerifiedAsset 'https://huggingface.co/kyutai/pocket-tts/resolve/db04f0e653473e5c7333c69686ac92b7460b0c1d/LICENSE' $license 'fe7b4ce83b8381cc5b216bbb4af73c570688d1b819c73bbaed8ca401f4677cd6'
& $Python -X utf8 "$root\scripts\export-pocket.py" --output $model --cache-directory $CacheDirectory --license $license
if ($LASTEXITCODE -ne 0) { throw 'Pocket export failed. Use an existing CPU environment with config\pocket-export-requirements.txt installed.' }
Write-Output 'Pocket TTS runtime and model ready. The next Android build will bundle them.'
