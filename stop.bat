@echo off
title TYPUI Database Stop

echo Stopping TYPUI Database...
echo.

echo Finding TYPUI Database process...
tasklist /FI "IMAGENAME eq javaw.exe" /FO LIST | findstr "PID"

echo.
echo Ending process...
taskkill /IM javaw.exe /F 2>nul

if %errorlevel% equ 0 (
    echo.
    echo TYPUI Database stopped successfully
) else (
    echo.
    echo TYPUI Database is not running
)

pause
