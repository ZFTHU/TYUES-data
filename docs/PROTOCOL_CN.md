# TYUES-data 协议说明

## MySQL 协议

### 支持版本

- MySQL 5.7.x
- MySQL 8.0.x (8.0, 8.1, 8.2, 8.3, 8.4, 8.5)
- MySQL 9.0.x

### 连接参数

| 参数 | 值 |
|------|-----|
| Host | localhost |
| Port | 3306 |
| Database | typui_db |
| Username | root |
| Password | ZM135790.. |
| Protocol | TCP/IP |
| Charset | utf8mb4 |

### 支持的 SQL 语句

#### 数据查询
```sql
-- 显示数据库
SHOW DATABASES;
SHOW SCHEMAS;

-- 显示表
SHOW TABLES;
SHOW TABLES FROM database_name;

-- 查询数据
SELECT * FROM collection_name;
SELECT col1, col2 FROM collection_name WHERE condition;
SELECT * FROM collection_name LIMIT 10;
```

#### 数据操作
```sql
-- 插入数据
INSERT INTO collection_name (field1, field2) VALUES ('value1', 'value2');

-- 更新数据
UPDATE collection_name SET field1 = 'new_value' WHERE condition;

-- 删除数据
DELETE FROM collection_name WHERE condition;
```

#### 系统查询
```sql
SELECT VERSION();
SELECT CURRENT_USER();
SELECT DATABASE();
SELECT @@version;
```

### JDBC 连接示例

```java
String url = "jdbc:mysql://localhost:3306/typui_db";
String user = "root";
String password = "ZM135790..";

Connection conn = DriverManager.getConnection(url, user, password);
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM users");
```

### 认证方式

| MySQL 版本 | 默认认证插件 |
|------------|--------------|
| 5.7 | mysql_native_password |
| 8.0+ | caching_sha2_password |

---

## MongoDB 协议

### 支持版本

- MongoDB 3.6.x
- MongoDB 4.0.x - 4.4.x
- MongoDB 5.0.x
- MongoDB 6.0.x
- MongoDB 7.0.x
- MongoDB 8.0.x

### 连接参数

| 参数 | 值 |
|------|-----|
| Host | localhost |
| Port | 27017 |
| Database | typui_db |
| Username | root |
| Password | ZM135790.. |
| Auth Source | admin |

### 支持的命令

#### 数据库操作
```javascript
// 切换数据库
use typui_db

// 显示集合
show collections

// 查询文档
db.users.find()
db.users.find({name: "John"})
db.users.find().limit(10)
```

#### 插入操作
```javascript
db.users.insertOne({
  name: "John",
  email: "john@example.com",
  age: 30
})

db.users.insertMany([
  {name: "Alice", age: 25},
  {name: "Bob", age: 28}
])
```

#### 更新操作
```javascript
db.users.updateOne(
  {name: "John"},
  {$set: {age: 31}}
)
```

#### 删除操作
```javascript
db.users.deleteOne({name: "John"})
db.users.deleteMany({age: {$lt: 18}})
```

### Wire Protocol

| 版本 | Wire Version | Port |
|------|-------------|------|
| 3.6 | 6 | 27017 |
| 4.0 | 8 | 27017 |
| 4.2 | 8 | 27017 |
| 4.4 | 9 | 27017 |
| 5.0 | 13 | 27017 |
| 6.0 | 20 | 27017 |
| 7.0 | 21 | 27017 |
| 8.0 | 21 | 27017 |

---

## S3 协议

### 端点

| 端点 | 地址 |
|------|------|
| S3 API | http://localhost:9000 |
| Region | us-east-1 |

### 认证

| 参数 | 值 |
|------|-----|
| Access Key | root |
| Secret Key | ZM135790.. |
| Signature | AWS Signature V4 |

### 支持的操作

#### 桶操作
```bash
# 列出桶
aws s3 ls --endpoint-url http://localhost:9000

# 创建桶
aws s3 mb s3://my-bucket --endpoint-url http://localhost:9000

# 删除桶
aws s3 rb s3://my-bucket --endpoint-url http://localhost:9000
```

#### 对象操作
```bash
# 上传文件
aws s3 cp file.txt s3://my-bucket/ --endpoint-url http://localhost:9000

# 下载文件
aws s3 cp s3://my-bucket/file.txt . --endpoint-url http://localhost:9000

# 列出对象
aws s3 ls s3://my-bucket/ --endpoint-url http://localhost:9000

# 删除对象
aws s3 rm s3://my-bucket/file.txt --endpoint-url http://localhost:9000
```

### MinIO Client 配置

```bash
mc alias set local http://localhost:9000 root ZM135790..
mc ls local/
```

---

[← 返回主文档](README_CN.md)
