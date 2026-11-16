[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PackZip,
    [string]$RepoRoot = (Get-Location).Path
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path $RepoRoot).Path
$ZipPath = (Resolve-Path $PackZip).Path

if (-not (Test-Path (Join-Path $RepoRoot "settings.gradle"))) {
    throw "RepoRoot must point to the MAQAN-UniverseForge checkout (settings.gradle was not found)."
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$requiredSkins = @("royal", "iron", "grim", "elf")
$requiredTextures = @(
    "top_bar.png", "left_column.png", "right_column.png", "corner.png",
    "minimap.png", "rts_console.png", "window_state.png", "window_diplomacy.png",
    "window_left_blank.png", "window_right_blank.png"
)

$archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
    $entries = @{}
    foreach ($entry in $archive.Entries) {
        $entries[$entry.FullName.Replace("\\", "/")] = $entry
    }

    foreach ($skin in $requiredSkins) {
        foreach ($texture in $requiredTextures) {
            $entryPath = "assets/maqan/textures/gui/$skin/$texture"
            if (-not $entries.ContainsKey($entryPath)) {
                throw "Required texture is missing from pack: $entryPath"
            }
        }
    }
    if (-not $entries.ContainsKey("layout.json")) {
        throw "Required layout.json is missing from pack."
    }

    $resourceRoot = Join-Path $RepoRoot "platform/src/main/resources/assets/maqan"
    foreach ($skin in $requiredSkins) {
        $targetDir = Join-Path $resourceRoot "textures/gui/$skin"
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        foreach ($texture in $requiredTextures) {
            $entryPath = "assets/maqan/textures/gui/$skin/$texture"
            $targetPath = Join-Path $targetDir $texture
            $input = $entries[$entryPath].Open()
            try {
                $output = [System.IO.File]::Open($targetPath, [System.IO.FileMode]::Create,
                    [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                try { $input.CopyTo($output) } finally { $output.Dispose() }
            } finally { $input.Dispose() }
        }
    }

    $layoutDir = Join-Path $resourceRoot "gui"
    New-Item -ItemType Directory -Force -Path $layoutDir | Out-Null
    $layoutTarget = Join-Path $layoutDir "layout.json"
    $layoutInput = $entries["layout.json"].Open()
    try {
        $layoutOutput = [System.IO.File]::Open($layoutTarget, [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $layoutInput.CopyTo($layoutOutput) } finally { $layoutOutput.Dispose() }
    } finally { $layoutInput.Dispose() }
}
finally {
    $archive.Dispose()
}

Write-Host "Imported 40 MAQAN UI textures (4 mandatory skins) and layout.json."
Write-Host "Target: $resourceRoot"
Write-Host "The HTML prototype was intentionally not copied; MAQAN uses its native Minecraft workspace."
