@echo off
chcp 65001 >nul
title Compile TYPUI Database

echo Compiling TYPUI Database...
echo.

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "%~dp0"

gradlew.bat build

echo.
echo Compilation complete!
pause
