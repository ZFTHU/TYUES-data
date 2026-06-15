# TYUES-data 安装指南

## 环境准备

### 系统要求

| 组件 | 最低要求 | 推荐配置 |
|------|----------|----------|
| 操作系统 | Windows 10 / Linux / macOS | Windows 11 / Ubuntu 22.04 |
| Java | JDK 17 | JDK 21 |
| 内存 | 512MB | 2GB+ |
| 磁盘 | 1GB | 10GB+ SSD |

### 安装 Java

**Windows:**
1. 下载 [Adoptium JDK 17+](https://adoptium.net/)
2. 安装并配置环境变量 `JAVA_HOME`
3. 验证: `java -version`

**Linux (Ubuntu/Debian):**
```bash
sudo apt update
sudo apt install openjdk-17-jdk
```

**macOS:**
```bash
brew install openjdk@17
```

## 下载与构建

### 方法一：直接下载 JAR

```bash
# 下载最新版本
curl -O https://github.com/YOUR_USERNAME/TYUES-data/releases/latest/typui-database.jar
java -jar typui-database.jar
```

### 方法二：从源码构建

```bash
# 克隆项目
git clone https://github.com/YOUR_USERNAME/TYUES-data.git
cd TYUES-data

# 构建 JAR
./gradlew clean jar

# 运行
java -jar build/libs/typui-database-*.jar
```

## Windows 启动

### 方式一：双击运行

直接双击 `run.bat` 或 `start.bat`

### 方式二：命令行运行

```cmd
java -jar build\libs\typui-database-*.jar
```

### 方式三：后台运行

```cmd
.\run-background.bat
```

## Linux/macOS 启动

```bash
chmod +x start.sh
./start.sh
```

## Docker 部署（可选）

```dockerfile
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY build/libs/typui-database-*.jar app.jar
COPY config.json config.json

EXPOSE 27016 27017 3306 9000 9001 9002

ENTRYPOINT ["java", "-jar", "app.jar"]
```

构建并运行:
```bash
docker build -t typui-database .
docker run -p 27016:27016 -p 27017:27017 -p 3306:3306 -p 9000:9000 -p 9001:9001 -p 9002:9002 typui-database
```

## 验证安装

启动后，访问 Admin UI 验证:

```
http://localhost:9002
用户名: root
密码: ZM135790..
```

## 卸载

```bash
# 停止服务
./stop.sh  # Linux
stop.bat   # Windows

# 删除数据目录
rm -rf db tec logs backup
rm -rf build/libs/typui-database-*.jar
```

## 常见问题

### Q: 端口被占用？

修改 `config.json` 中的端口配置，或停止占用端口的进程。

### Q: 内存不足？

增加 JVM 堆内存:
```bash
java -Xmx2g -jar typui-database.jar
```

### Q: 无法连接？

检查防火墙设置，确保相关端口已开放。

---

[← 返回主文档](README_CN.md) | [配置说明 →](CONFIG_CN.md)
