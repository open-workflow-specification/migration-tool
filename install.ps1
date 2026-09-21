# Installs swf-migrate to $Env:USERPROFILE\bin and adds it to the user PATH.
# Usage: irm https://raw.githubusercontent.com/skavgou/spec-convert/main/install.ps1 | iex
$ErrorActionPreference = 'Stop'

$Repo   = "skavgou/spec-convert"
$Asset  = "swf-migrate-windows.exe"
$BinDir = "$Env:USERPROFILE\bin"

# Resolve latest release tag
$Release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest"
$Tag     = $Release.tag_name

if (-not $Tag) {
    Write-Error "Could not determine latest release tag."
    exit 1
}

$Url  = "https://github.com/$Repo/releases/download/$Tag/$Asset"
$Dest = "$BinDir\swf-migrate.exe"

Write-Host "Downloading swf-migrate $Tag for Windows..."
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
Invoke-WebRequest -Uri $Url -OutFile $Dest

# Add $BinDir to user PATH if not already present
$CurrentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($CurrentPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$CurrentPath;$BinDir", "User")
    Write-Host "Added $BinDir to your PATH (restart your terminal to apply)."
}

Write-Host "Installed to $Dest"
Write-Host "Run: swf-migrate --help"
