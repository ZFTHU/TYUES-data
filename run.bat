@echo off
chcp 65001 >nul
echo ========================================
echo TYPUI 数据库系统
echo ========================================
echo.

echo.
echo 正在启动数据库服务...
echo.

java -jar build\libs\typui-database.jar

pause
