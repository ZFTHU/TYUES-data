#!/bin/bash
# TYPUI Database 停止脚本 - Termux/Linux 版本

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================"
echo "  停止 TYPUI Database"
echo "========================================"
echo ""

# 读取 PID 文件
PID_FILE=".typui_db.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    echo "📌 读取到 PID：$PID"
    
    # 检查进程
    if ps -p $PID > /dev/null; then
        echo ""
        echo "🛑 正在停止进程 $PID..."
        kill -15 $PID
        
        # 等待进程停止
        sleep 2
        
        if ps -p $PID > /dev/null; then
            echo "⚠️  进程未停止，尝试强制终止..."
            kill -9 $PID
            sleep 1
        fi
        
        # 删除 PID 文件
        rm -f "$PID_FILE"
        
        echo ""
        echo "✅ TYPUI Database 已停止"
    else
        echo ""
        echo "⚠️  进程 $PID 未运行"
        rm -f "$PID_FILE"
    fi
else
    echo "⚠️  未找到 PID 文件"
    echo ""
    echo "查找所有 Java 进程..."
    PIDS=$(pgrep -f "typui-database.jar" 2>/dev/null)
    if [ -n "$PIDS" ]; then
        echo ""
        echo "发现以下进程：$PIDS"
        echo "是否终止？(y/n)"
        read -r answer
        if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
            kill -9 $PIDS 2>/dev/null
            echo "✅ 已终止进程"
        fi
    else
        echo "❌ 未找到运行中的 TYPUI Database"
    fi
fi

echo ""
echo "检查端口占用..."
if command -v netstat &> /dev/null; then
    netstat -tuln 2>/dev/null | grep -E "(27018|9001|9002)" || echo "  端口已释放"
elif command -v ss &> /dev/null; then
    ss -tuln 2>/dev/null | grep -E "(27018|9001|9002)" || echo "  端口已释放"
fi
