# TYPUI Database 编译脚本
$ErrorActionPreference = "Stop"

Write-Host "Compiling TYPUI Database..." -ForegroundColor Cyan
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Set UTF-8 encoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Find Java compiler
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javac) {
    Write-Host "ERROR: javac not found" -ForegroundColor Red
    Write-Host "Please install JDK" -ForegroundColor Yellow
    exit 1
}

Write-Host "javac version: $(javac -version 2>&1)" -ForegroundColor Green

# Clean and create directories
$classesDir = "build\classes\java\main"
if (Test-Path $classesDir) {
    Remove-Item $classesDir -Recurse -Force
}
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

# Find all JAR dependencies
$libs = Get-ChildItem -Path "build\libs" -Filter "*.jar" -ErrorAction SilentlyContinue
$classpath = ($libs | ForEach-Object { $_.FullName }) -join ";"

# Compile
Write-Host ""
Write-Host "Compiling Main.java..." -ForegroundColor Yellow

$srcDir = "src\main\java"
$mainFile = "$srcDir\com\typui\database\Main.java"

if (-not (Test-Path $mainFile)) {
    Write-Host "ERROR: Main.java not found at $mainFile" -ForegroundColor Red
    exit 1
}

# Use UTF-8 encoding for javac
& javac -encoding UTF-8 -d $classesDir -cp $classpath $mainFile 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "SUCCESS: Main.class compiled" -ForegroundColor Green
    Write-Host "Location: $classesDir\com\typui\database\Main.class"
} else {
    Write-Host ""
    Write-Host "FAILED: Compilation errors" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Compilation complete!" -ForegroundColor Cyan
