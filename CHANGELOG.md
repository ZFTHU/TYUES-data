# 更新日志

所有重要的项目更新都将记录在此文件中。

## [3.0.31] - 2026-06-23

### MongoDB 功能完善（15项测试全部通过 ✅ ALL yes）

#### 🔐 认证系统修复

1. **SCRAM-SHA-256 认证实现**
   - 使用 Java 标准 PBKDF2WithHmacSHA256 实现
   - 完整的 HI 函数计算
   - 客户端证明（ClientProof）验证
   - 服务器签名（ServerSignature）生成

2. **SCRAM-SHA-1 认证实现**
   - 修复 MongoDB 特有的密码处理机制
   - 使用 `md5(username + ":mongo:" + password)` 哈希
   - 兼容传统 MongoDB 认证方式

3. **SCRAM 会话管理**
   - 支持 saslStart / saslContinue 命令
   - 完整的挑战-响应流程
   - 会话状态保存与清理

#### 🔧 命令支持完善

4. **getMore 命令支持**
   - 实现游标分批获取功能
   - 支持 nextBatch 返回
   - 正确的 cursor ID 处理

5. **killCursors 命令支持**
   - 支持游标销毁
   - 释放服务器端资源

6. **Cursor ID 优化**
   - 修复 cursor id 返回逻辑
   - 一次性返回结果时设置 id=0
   - 避免客户端额外请求

#### 📊 CRUD 操作完善

7. **find 命令增强**
   - 支持 filter 条件查询
   - 支持 limit 限制
   - 正确的 firstBatch 返回格式

8. **insert 命令增强**
   - 支持单条文档插入
   - 支持批量文档插入
   - 返回正确的 inserted_id

9. **update 命令增强**
   - 支持 $set 更新操作符
   - matched_count 和 modified_count 统计

10. **delete 命令增强**
    - 支持单条删除
    - 返回 deleted_count

11. **count_documents 命令支持**
    - 完整的文档计数功能

12. **listCollections 命令完善**
    - 返回集合列表
    - 包含集合元数据

#### 🛡️ BSON 编解码完善

13. **Binary 类型（0x05）支持**
    - 修复 payload 字段传输
    - 正确的 BSON 编码格式
    - Base64 编解码处理

14. **数据类型兼容性**
    - 支持 int、long、double、string、boolean
    - 支持 null 和 array 类型
    - 正确的类型转换

---

### MySQL 功能完善（16项测试全部通过 ✅ ALL yes）

#### 🔍 SELECT 查询增强

15. **列选择功能**
    - 支持指定列查询 `SELECT col1, col2`
    - 自动过滤内部字段（_id、_sequence_identifier 等）
    - 支持列别名 `SELECT col AS alias`

16. **WHERE 条件扩展**
    - 数值比较：`=`、`!=`、`<>`、`>`、`<`、`>=`、`<=`
    - 字符串比较：自动识别类型
    - 智能类型转换

17. **LIKE 模糊查询**
    - 支持 `%` 通配符（任意字符）
    - 支持 `_` 通配符（单个字符）
    - 不区分大小写匹配

18. **ORDER BY 排序**
    - 支持升序 ASC 和降序 DESC
    - 数字字段数值排序
    - 字符串字段字典序排序

19. **LIMIT 分页支持**
    - 支持 LIMIT n 语法
    - 支持 LIMIT n OFFSET m 语法
    - 支持 LIMIT m, n 语法

20. **OFFSET 偏移支持**
    - 正确的偏移处理
    - 配合 LIMIT 实现分页

21. **COUNT 聚合函数**
    - 支持 `COUNT(*)` 查询
    - 支持别名 `COUNT(*) AS alias`
    - 正确的计数统计

#### 📝 INSERT 增强

22. **多值插入功能**
    - 支持 `INSERT INTO ... VALUES (...), (...), (...)`
    - 正确的值组解析
    - 支持嵌套括号处理

23. **带列名插入**
    - 支持 `INSERT INTO tbl (col1, col2) VALUES (...)`
    - 列名引号处理（反引号、单引号、双引号）

#### ✏️ UPDATE 增强

24. **SET 子句解析**
    - 支持多字段更新 `SET col1=val1, col2=val2`
    - 支持 AND 连接的 WHERE 条件
    - 条件操作符扩展（>、<、>=、<=、!=、LIKE）

25. **无 WHERE 子句支持**
    - UPDATE 所有行
    - 需要谨慎使用

#### 🗑️ DELETE 增强

26. **WHERE 条件支持**
    - 支持所有比较操作符
    - 支持 AND 连接多条件

27. **无 WHERE 子句支持**
    - DELETE 所有行
    - 需要谨慎使用

#### 🔧 SQL 解析优化

28. **条件解析器重构**
    - 新的 Condition 类表示条件
    - parseWhereConditions 方法支持多操作符
    - matchConditions 方法支持智能类型比较

29. **正则表达式优化**
    - 更健壮的 SQL 解析
    - 支持引号处理的列名/表名

30. **错误处理完善**
    - 空查询处理
    - 无效 SQL 返回空结果

#### 📦 内部字段处理

31. **自动过滤系统字段**
    - 过滤 `_id`、`_sequence_identifier`
    - 过滤 `_createdAt`、`_updatedAt` 等内部字段
    - 只返回用户数据

---

### 测试脚本

32. **test_mongo_full.py**
    - 完整的 MongoDB 功能测试脚本
    - 15项测试覆盖所有核心功能
    - 详细的中文输出

33. **test_mysql_full.py**
    - 完整的 MySQL 功能测试脚本
    - 16项测试覆盖所有核心功能
    - 支持参数化查询

34. **test_scram.py / test_scram_sha1.py**
    - SCRAM 认证验证脚本
    - 辅助调试认证流程

---

### 性能与稳定性

35. **编译优化**
    - 使用 Java 标准库 PBKDF2 实现
    - 减少自定义加密代码
    - 提高安全性

---

## [3.4.2567.29] - 2026-06-15

### 新增功能

- **多协议适配器**: MySQL、MongoDB、S3/MinIO 原生协议支持
- **全链路加密**: AES-256-GCM 数据加密存储
- **多语言文档**: 中文、英文、日文、韩文文档支持
- **性能优化**: 
  - 线程池优化，避免资源耗尽
  - 连接超时设置，防止无限阻塞
  - 有界队列，拒绝策略保护

### 协议支持

| 协议 | 版本 | 状态 |
|------|------|------|
| MySQL | 5.7, 8.0-8.5, 9.0 | ✅ 支持 |
| MongoDB | 3.6-8.0 | ✅ 支持 |
| S3/MinIO | AWS S3 兼容 | ✅ 支持 |

### 端口分配

| 服务 | 端口 | 协议 |
|------|------|------|
| HTTP API | 27016 | REST |
| MongoDB Wire | 27017 | TCP |
| MySQL Wire | 3306 | TCP |
| S3 API | 9000 | HTTP |
| File Storage | 9001 | HTTP |
| Admin UI | 9002 | HTTP |

### 修复问题

- 修复协议服务器连接卡顿问题
- 修复 MySQL 9.0 客户端兼容性问题
- 修复 MongoDB 超时设置问题
- 优化线程池大小限制

### 安全改进

- 添加连接数上限保护
- 添加消息长度验证
- 添加优雅关闭机制
- 修复 S3 XML 命名空间问题

---

## [3.4.2567.24] - 2026-05-14

### 初始版本

- 基础文档数据库功能
- HTTP REST API
- 文件存储系统
- Admin 管理界面

