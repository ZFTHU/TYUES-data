# TYUES-data 安全指南

## 安全概述

TYUES-data 采用多层安全策略保护数据安全。

## 数据加密

### 加密算法

- **算法**: AES-256-GCM
- **密钥长度**: 256 位
- **IV**: 12 字节随机向量
- **认证标签**: 16 字节

### 加密存储

| 数据类型 | 存储格式 | 加密状态 |
|----------|----------|----------|
| 文档数据 | .json.enc | ✅ 已加密 |
| 文件数据 | .enc | ✅ 已加密 |
| 元数据 | .json | ⚠️ 部分明文 |
| 日志文件 | .log | ⚠️ 可配置加密 |

### 密钥管理

密钥通过 `config.json` 的 `auth.secretKey` 配置:

```json
"auth": {
  "secretKey": "your-256-bit-secret-key-here"
}
```

⚠️ **重要安全建议**:
1. 生产环境必须修改默认密钥
2. 密钥长度至少 32 字符
3. 建议使用随机生成的强密钥
4. 定期更换密钥

## 认证机制

### MySQL 认证

支持两种认证插件:
- `mysql_native_password` (MySQL 5.7)
- `caching_sha2_password` (MySQL 8.0+)

### MongoDB 认证

支持 SCRAM 认证:
- `SCRAM-SHA-1` (MongoDB 3.6-4.0)
- `SCRAM-SHA-256` (MongoDB 4.0+)

### JWT Token

认证成功后返回 JWT Token:

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 86400
}
```

Token 有效期可在配置中调整 (默认 24 小时)。

## 网络安全

### 绑定地址

生产环境建议绑定到 localhost 或内网 IP:

```json
"documentDatabase": {
  "host": "127.0.0.1"  // 仅本地访问
}
```

### 防火墙配置

| 端口 | 服务 | 建议 |
|------|------|------|
| 27016 | HTTP API | 仅内网 |
| 27017 | MongoDB | 仅内网 |
| 3306 | MySQL | 仅内网 |
| 9000 | S3 | 可公网 |
| 9002 | Admin UI | 仅内网 |

## 最佳实践

### 开发环境

```json
{
  "auth": {
    "secretKey": "dev-secret-key"
  },
  "admin": {
    "username": "admin",
    "password": "admin123"
  }
}
```

### 生产环境

```json
{
  "auth": {
    "enabled": true,
    "secretKey": "生成的32位随机密钥"
  },
  "admin": {
    "username": "自定义用户名",
    "password": "强密码（12位+大小写+数字+特殊字符）"
  }
}
```

### 密码强度要求

| 等级 | 长度 | 要求 |
|------|------|------|
| 低 | 8+ | 仅字母数字 |
| 中 | 12+ | 大小写+数字 |
| 高 | 16+ | 大小写+数字+特殊字符 |

## 安全检查清单

- [ ] 修改默认管理员密码
- [ ] 设置强密钥 (32位+)
- [ ] 启用防火墙
- [ ] 禁用不必要的端口
- [ ] 启用 SSL/TLS (如有)
- [ ] 定期备份数据
- [ ] 监控访问日志
- [ ] 定期更新版本

## 漏洞报告

发现安全漏洞请通过以下方式联系:
- GitHub Issues (Private)
- 邮箱: security@tyues.cn

---

[← 返回主文档](README_CN.md) | [配置说明 ←](CONFIG_CN.md)
