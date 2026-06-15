# TYUES-data 配置说明

## 配置文件位置

主配置文件: `config.json`

## 配置结构

### 基础配置

```json
{
  "databaseName": "typui_db",
  "encryptionEnabled": true,
  "version": "3.4.2567.29"
}
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| databaseName | string | typui_db | 数据库名称 |
| encryptionEnabled | boolean | true | 是否启用加密 |
| version | string | 3.4.2567.29 | 版本号 |

### HTTP API 配置

```json
"documentDatabase": {
  "port": 27016,
  "host": "0.0.0.0",
  "dataDir": "db",
  "maxConnections": 100
}
```

### 文件存储配置

```json
"fileStorage": {
  "port": 9001,
  "host": "0.0.0.0",
  "dataDir": "tec",
  "maxFileSize": 104857600
}
```

### 认证配置

```json
"auth": {
  "enabled": true,
  "tokenExpireHours": 24,
  "secretKey": "your-secret-key"
}
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| enabled | boolean | true | 是否启用认证 |
| tokenExpireHours | int | 24 | Token 过期时间（小时） |
| secretKey | string | - | JWT 密钥（生产环境必须修改） |

### 管理员配置

```json
"admin": {
  "username": "root",
  "password": "ZM135790.."
}
```

⚠️ **重要**: 生产环境必须修改默认密码！

### MySQL 协议配置

```json
"mysqlProtocol": {
  "enabled": true,
  "host": "0.0.0.0",
  "port": 3306,
  "defaultVersion": "8.0",
  "serverVersion": "8.0.36-TYPUI",
  "charset": "utf8mb4",
  "maxConnections": 200,
  "allowPublicKeyRetrieval": true,
  "useSSL": false,
  "defaultAuthPlugin": "mysql_native_password"
}
```

### MongoDB 协议配置

```json
"mongodbProtocol": {
  "enabled": true,
  "host": "0.0.0.0",
  "port": 27017,
  "defaultVersion": "6.0",
  "serverVersion": "6.0.12-TYPUI",
  "wireVersion": 21,
  "maxConnections": 200
}
```

### S3 协议配置

```json
"s3Protocol": {
  "enabled": true,
  "host": "0.0.0.0",
  "port": 9000,
  "accessKey": "root",
  "secretKey": "ZM135790..",
  "region": "us-east-1",
  "signatureVersion": "v4",
  "pathStyleAccess": true
}
```

## MySQL 版本兼容配置

```json
"mysqlVersions": {
  "5.7": {
    "sqlMode": "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES...",
    "defaultCharset": "latin1",
    "authPlugin": "mysql_native_password",
    "maxAllowedPacket": 4194304
  },
  "8.0": {
    "sqlMode": "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES...",
    "defaultCharset": "utf8mb4",
    "authPlugin": "caching_sha2_password",
    "maxAllowedPacket": 67108864
  }
}
```

## MongoDB 版本兼容配置

```json
"mongodbVersions": {
  "3.6": {
    "wireVersion": 6,
    "authMechanism": "SCRAM-SHA-1"
  },
  "6.0": {
    "wireVersion": 20,
    "authMechanism": "SCRAM-SHA-256"
  }
}
```

## 备份配置

```json
"backup": {
  "enabled": true,
  "intervalMinutes": 60,
  "maxBackups": 24,
  "incremental": true,
  "compression": true
}
```

## 快照配置

```json
"snapshot": {
  "enabled": true,
  "intervalSeconds": 600,
  "maxSnapshots": 5,
  "async": true,
  "compression": true
}
```

## 环境变量覆盖

可以通过环境变量覆盖配置:

| 环境变量 | 对应配置 |
|----------|----------|
| TYUES_DB_PORT | documentDatabase.port |
| TYUES_ADMIN_USER | admin.username |
| TYUES_ADMIN_PASS | admin.password |
| TYUES_SECRET_KEY | auth.secretKey |

---

[← 返回主文档](README_CN.md) | [安装指南 ←](INSTALL_CN.md) | [安全指南 →](SECURITY_CN.md)
