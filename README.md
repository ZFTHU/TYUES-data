# TYUES-data 数据库

<div align="center">

![TYUES-data Logo](docs/images/logo.png)

**高性能自研文档数据库 · 多协议适配 · 全链路加密**

[简体中文](docs/README_CN.md) · [English](docs/README_EN.md) · [日本語](docs/README_JA.md) · [한국어](docs/README_KO.md)

[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-3.0.31-orange.svg)]()

</div>

---

## 项目简介

TYUES-data 是一款高性能自研文档数据库系统，支持 MySQL、MongoDB、S3/MinIO 等多种原生协议适配，让业务系统无需修改代码即可实现数据库迁移。

### 核心特性

| 特性 | 说明 |
|------|------|
| 🔐 **全链路加密** | AES-256-GCM 数据加密存储 |
| 🌐 **多协议兼容** | MySQL 5.7-9.0 / MongoDB 3.6-8.0 / S3 |
| ⚡ **高性能** | 异步 I/O + 连接池 + 索引优化 |
| 🔧 **零配置迁移** | 兼容原生 SQL/BSON 语法 |
| 📦 **开箱即用** | 内置 Admin 管理界面 |

### 技术架构

```
┌──────────────────────────────────────────────────────────────┐
│                     客户端层                                  │
│   MySQL Client │ MongoDB Driver │ AWS SDK │ HTTP REST        │
└────────────────┬────────────────┬─────────────┬─────────────┘
                 │                │             │
        ┌────────▼────┐  ┌────────▼────┐  ┌───▼────────┐
        │ MySQL 协议  │  │ MongoDB 协议 │  │ S3 协议    │
        │  3306/TCP   │  │  27017/TCP   │  │ 9000/HTTP  │
        └─────┬───────┘  └─────┬───────┘  └─────┬───────┘
              │                │                │
              └────────────────┼────────────────┘
                               │
                    ┌───────────▼───────────┐
                    │    统一数据库层        │
                    │  DocumentDatabase      │
                    │  StorageServer        │
                    └───────────┬───────────┘
                                │
                    ┌───────────▼───────────┐
                    │    加密存储层          │
                    │  AES-256-GCM          │
                    │  文件系统              │
                    └───────────────────────┘
```

---

## 快速开始

### 环境要求

- Java 17 或更高版本
- 内存 512MB 以上
- 磁盘空间 1GB 以上

### 下载与构建

```bash
# 克隆项目
git clone https://github.com/YOUR_USERNAME/TYUES-data.git
cd TYUES-data

# 构建
./gradlew clean jar

# 运行
java -jar build/libs/typui-database-*.jar
```

### 默认配置

| 服务 | 端口 | 默认账户 | 默认密码 |
|------|------|----------|----------|
| HTTP API | 27016 | root | ZM135790.. |
| MySQL | 3306 | root | ZM135790.. |
| MongoDB | 27017 | root | ZM135790.. |
| S3/MinIO | 9000 | root | ZM135790.. |
| Admin UI | 9002 | root | ZM135790.. |

---

## 连接示例

### MySQL 连接

```bash
# 使用 mysql 客户端
mysql -h localhost -P 3306 -u root -pZM135790..

# 使用 JDBC
Connection conn = DriverManager.getConnection(
    "jdbc:mysql://localhost:3306/typui_db",
    "root", "ZM135790.."
);
```

### MongoDB 连接

```javascript
// 使用 mongosh
mongosh mongodb://root:ZM135790..@localhost:27017

// 使用 Java 驱动
MongoClient client = MongoClients.create(
    "mongodb://root:ZM135790..@localhost:27017"
);
```

### S3 连接

```bash
# 使用 AWS CLI
aws configure
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1

# 使用 MinIO 客户端
mc alias set local http://localhost:9000 root ZM135790..
```

---

## 文档导航

| 文档 | 说明 |
|------|------|
| [安装指南](docs/INSTALL_CN.md) | 详细安装步骤 |
| [配置说明](docs/CONFIG_CN.md) | 配置文件详解 |
| [API 文档](docs/API_CN.md) | REST API 参考 |
| [协议说明](docs/PROTOCOL_CN.md) | MySQL/MongoDB/S3 协议详情 |
| [安全指南](docs/SECURITY_CN.md) | 安全配置与最佳实践 |

---

## 项目结构

```
TYUES-data/
├── src/main/java/com/typui/database/
│   ├── adapter/          # 协议适配器
│   │   ├── MysqlProtocolServer.java    # MySQL 协议
│   │   ├── MongoWireProtocolServer.java # MongoDB 协议
│   │   └── S3CompatibleServer.java     # S3 兼容
│   ├── auth/             # 认证服务
│   ├── document/        # 文档数据库核心
│   ├── security/        # 加密模块
│   │   └── DataEncryptor.java          # AES-256-GCM
│   ├── storage/         # 文件存储
│   └── Main.java        # 入口
├── docs/                # 多语言文档
├── config.json          # 配置文件
└── build.gradle         # 构建配置
```

---

## 版本历史

详见 [CHANGELOG.md](CHANGELOG.md)

---

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 联系方式

- 提交 Issue: [GitHub Issues](https://github.com/YOUR_USERNAME/TYUES-data/issues)
- 邮箱: support@tyues.cn

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给我们一个 Star！**

</div>
