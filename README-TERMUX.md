# TYPUI Database - Termux/Linux 使用指南

## 📋 支持的命令行参数

TYPUI Database 内置支持以下参数：

| 参数 | 说明 |
|------|------|
| `-YC` 或 `--background` | 后台运行模式 |
| `-h` 或 `--help` | 显示帮助信息 |
| `-v` 或 `--version` | 显示版本信息 |

### 查看帮助
```bash
java -jar build/libs/typui-database.jar -h
```

## 🚀 快速启动

### 方法一：使用脚本（推荐）

```bash
# 1. 赋予执行权限
chmod +x start.sh stop.sh status.sh

# 2. 后台启动
./start.sh

# 3. 查看状态
./status.sh

# 4. 停止服务
./stop.sh
```

### 方法二：纯 Java 命令

#### 前台运行
```bash
cd /path/to/sjk
java -jar build/libs/typui-database.jar
```

#### 后台运行（标准 Linux 方式）
```bash
cd /path/to/sjk

# 方式1：nohup（推荐）
nohup java -jar build/libs/typui-database.jar -YC > typui_db.log 2>&1 < /dev/null &

# 方式2：使用系统自带参数
java -jar build/libs/typui-database.jar -YC

# 方式3：screen（终端复用）
screen -S typuidb java -jar build/libs/typui-database.jar
# Ctrl+A, D 断开；screen -r typuidb 重新连接

# 方式4：tmux（终端复用）
tmux new-session -d -s typuidb "java -jar build/libs/typui-database.jar"
# tmux attach -t typuidb 重新连接
```

## 📱 Termux 特别说明

### 安装 Java
```bash
pkg update
pkg install openjdk-17
```

### 验证安装
```bash
java -version
```

### 在 Termux 中运行

```bash
# 进入目录
cd ~/TAC/sjk  # 或您的实际路径

# 赋予执行权限
chmod +x start.sh stop.sh status.sh

# 启动
./start.sh

# 查看日志
tail -f typui_db.log
```

## 🔍 管理命令

### 查看进程
```bash
# 方法1：使用 PID 文件
cat .typui_db.pid

# 方法2：查找进程
ps aux | grep typui-database

# 方法3：pgrep
pgrep -f "typui-database.jar"
```

### 停止进程
```bash
# 方法1：使用脚本
./stop.sh

# 方法2：使用 PID 文件
kill -15 $(cat .typui_db.pid)  # 优雅停止
kill -9 $(cat .typui_db.pid)   # 强制停止

# 方法3：pkill
pkill -f "typui-database.jar"
```

### 查看日志
```bash
# 实时查看
tail -f typui_db.log

# 查看最近 100 行
tail -n 100 typui_db.log

# 查看完整日志
cat typui_db.log
```

### 检查端口
```bash
# 方法1：netstat（如果可用）
netstat -tuln | grep -E "(27018|9001|9002)"

# 方法2：ss（推荐）
ss -tuln | grep -E "(27018|9001|9002)"

# 方法3：lsof（如果可用）
lsof -i :27018
lsof -i :9001
lsof -i :9002
```

## 📊 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| 文档数据库 API | 27018 | MongoDB 兼容接口 |
| 文件存储 API | 9001 | MinIO 兼容接口 |
| Web 管理面板 | 9002 | 管理界面 |

## 📁 文件说明

```
sjk/
├── start.sh           # 启动脚本
├── stop.sh            # 停止脚本
├── status.sh          # 状态检查脚本
├── typui_db.log       # 运行日志
├── .typui_db.pid      # PID 文件（自动生成）
├── db/                # 数据库文件
├── tec/               # 存储文件
├── logs/              # 日志归档
├── .snapshots/        # 快照文件
└── build/libs/typui-database.jar  # JAR 文件
```

## 🎯 实用命令组合

### 一键重启
```bash
./stop.sh && sleep 2 && ./start.sh
```

### 后台运行并查看日志
```bash
./start.sh && tail -f typui_db.log
```

### 检查并自动重启（脚本）
```bash
#!/bin/bash
if ! ./status.sh | grep -q "运行中"; then
    echo "服务未运行，正在重启..."
    ./stop.sh 2>/dev/null
    ./start.sh
fi
```

## 🔧 故障排查

### 端口被占用
```bash
# 查找占用进程
ss -tuln | grep 27018

# 或使用 lsof
lsof -i :27018

# 终止进程
kill -9 <PID>
```

### 权限问题
```bash
# 检查 Java 权限
ls -l /data/data/com.termux/files/usr/bin/java

# 修复权限
chmod +x build/libs/typui-database.jar
chmod +x start.sh stop.sh status.sh
```

### 内存不足
```bash
# 增加 JVM 内存参数
java -Xmx512m -jar build/libs/typui-database.jar
```

## 📝 前台运行时的交互命令

在前台模式下运行后，可以使用以下命令：

| 命令 | 说明 |
|------|------|
| `status` | 显示服务器状态 |
| `stats` | 显示系统统计信息 |
| `logs` | 显示最近日志 |
| `help` | 显示可用命令 |
| `exit` / `quit` / `q` | 停止服务器 |
| `clear` / `cls` | 清空控制台 |
