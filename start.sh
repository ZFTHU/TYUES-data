#!/bin/bash
# TYPUI Database 启动脚本 - Termux/Linux 版本

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================"
echo "  TYPUI Database"
echo "========================================"
echo ""

# 检查 Java 是否安装
if ! command -v java &> /dev/null; then
    echo "❌ Java 未安装！"
    echo "请先安装：pkg install openjdk-17"
    exit 1
fi

# 检查 JAR 文件
JAR_FILE="build/libs/typui-database.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 找不到 JAR 文件：$JAR_FILE"
    echo "请先编译：./gradlew build"
    exit 1
fi

echo "✅ Java 版本：$(java -version 2>&1 | head -n1)"
echo "✅ JAR 文件：$JAR_FILE"
echo ""

# 后台启动
echo "🚀 正在后台启动 TYPUI Database..."
echo ""

nohup java -jar "$JAR_FILE" -YC > typui_db.log 2>&1 < /dev/null &
PID=$!

# 保存 PID
echo $PID > .typui_db.pid

# 等待启动
sleep 3

# 检查进程
if ps -p $PID > /dev/null; then
    echo "✅ TYPUI Database 启动成功！"
    echo ""
    echo "📊 服务信息："
    echo "  PID: $PID"
    echo "  文档 API: http://localhost:27018"
    echo "  存储 API: http://localhost:9001"
    echo "  管理面板: http://localhost:9002"
    echo ""
    echo "📝 日志文件：typui_db.log"
    echo "🔄 停止服务：./stop.sh"
    echo "👁️  查看日志：tail -f typui_db.log"
else
    echo "❌ 启动失败，请查看日志："
    echo "   cat typui_db.log"
    exit 1
fi
