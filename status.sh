#!/bin/bash
# TYPUI Database 状态检查脚本

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================"
echo "  TYPUI Database 状态检查"
echo "========================================"
echo ""

# 检查 PID 文件
PID_FILE=".typui_db.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    echo "📄 PID 文件：$PID"
    if ps -p $PID > /dev/null; then
        echo "✅ 进程运行中：$PID"
        echo ""
        # 检查端口
        echo "🌐 检查端口..."
        if command -v netstat &> /dev/null; then
            netstat -tuln 2>/dev/null | grep -E "(27018|9001|9002)"
        elif command -v ss &> /dev/null; then
            ss -tuln 2>/dev/null | grep -E "(27018|9001|9002)"
        fi
        echo ""
        echo "📝 日志文件："
        if [ -f "typui_db.log" ]; then
            echo "  $(ls -lh typui_db.log)"
            echo "  最近 5 行："
            tail -n 5 typui_db.log | sed 's/^/  /'
        fi
    else
        echo "❌ 进程未运行（PID 文件残留）"
        echo "清理 PID 文件..."
        rm -f "$PID_FILE"
    fi
else
    echo "❌ 未找到 PID 文件"
fi

echo ""
echo "========================================"
