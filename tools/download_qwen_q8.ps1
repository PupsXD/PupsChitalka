param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\.research\model-cache\q8_0')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$files = @(
    @{
        Name = 'qwen-tokenizer-12hz-Q8_0.gguf'
        Bytes = 291150624L
        Sha256 = '1883beeed99348fc35e23dd225e9082f93f6f8c109330a33d935baa8acdbfd94'
    },
    @{
        Name = 'qwen-talker-0.6b-base-Q8_0.gguf'
        Bytes = 992615488L
        Sha256 = 'd54dbaf10591421fa764ed630d764efa717ae40cd959bd48c66d4eb1af226426'
    }
)

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

foreach ($file in $files) {
    $target = Join-Path $OutputDirectory $file.Name
    $verified = (Test-Path -LiteralPath $target) -and
        ((Get-Item -LiteralPath $target).Length -eq $file.Bytes) -and
        ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ieq $file.Sha256)
    if ($verified) {
        Write-Output "Already verified: $($file.Name)"
        continue
    }

    $part = "$target.part"
    $uri = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$($file.Name)?download=true"
    Write-Output "Downloading $($file.Name) ($($file.Bytes) bytes)"
    Invoke-WebRequest -Uri $uri -OutFile $part -MaximumRedirection 10 -TimeoutSec 0

    $actualBytes = (Get-Item -LiteralPath $part).Length
    if ($actualBytes -ne $file.Bytes) {
        throw "Size mismatch for $($file.Name): $actualBytes instead of $($file.Bytes)"
    }
    $actualHash = (Get-FileHash -LiteralPath $part -Algorithm SHA256).Hash
    if ($actualHash -ine $file.Sha256) {
        throw "SHA-256 mismatch for $($file.Name): $actualHash"
    }
    Move-Item -LiteralPath $part -Destination $target -Force
    Write-Output "Verified: $($file.Name)"
}
