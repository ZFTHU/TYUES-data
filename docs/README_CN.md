# TYUES-data 数据库

TYUES-data 是一款高性能自研文档数据库系统，支持 MySQL、MongoDB、S3/MinIO 等多种原生协议适配，让业务系统无需修改代码即可实现数据库迁移。

## 核心特性

| 特性 | 说明 |
|------|------|
| 全链路加密 | AES-256-GCM 数据加密存储 |
| 多协议兼容 | MySQL 5.7-9.0 / MongoDB 3.6-8.0 / S3 |
| 高性能 | 异步 I/O + 连接池 + 索引优化 |
| 零配置迁移 | 兼容原生 SQL/BSON 语法 |
| 开箱即用 | 内置 Admin 管理界面 |

## 快速开始

### 环境要求

- Java 17 或更高版本
- 内存 512MB 以上
- 磁盘空间 1GB 以上

### 构建与运行

```bash
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

## 连接示例

### MySQL 连接

```bash
mysql -h localhost -P 3306 -u root -pZM135790..
```

### MongoDB 连接

```javascript
mongosh mongodb://root:ZM135790..@localhost:27017
```

### S3 连接

```bash
aws s3 ls --endpoint-url http://localhost:9000 --region us-east-1
```

## 文档导航

- [安装指南](INSTALL_CN.md) - 详细安装步骤
- [配置说明](CONFIG_CN.md) - 配置文件详解
- [API 文档](API_CN.md) - REST API 参考
- [协议说明](PROTOCOL_CN.md) - MySQL/MongoDB/S3 协议详情
- [安全指南](SECURITY_CN.md) - 安全配置与最佳实践

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](../LICENSE) 文件
