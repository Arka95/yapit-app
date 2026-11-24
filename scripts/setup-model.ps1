# Restores the checksum-pinned Android runtime and offline Pocket TTS model.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot

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
Get-VerifiedAsset "$release/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2" "$root\data\models\pocket-tts-int8.tar.bz2" 'f4da7c7bd465f7b91e89191487fe90504396a51d6f0bcf94d0787633e2657293'
& tar -xjf "$root\data\models\pocket-tts-int8.tar.bz2" -C "$root\data\models"
if ($LASTEXITCODE -ne 0) { throw 'Could not extract the Pocket TTS model' }
Write-Output 'Pocket TTS runtime and model ready. The next Android build will bundle them.'
