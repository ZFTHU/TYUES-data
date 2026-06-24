# 更新日志

所有重要的项目更新都将记录在此文件中。

## [3.0.40] - 2026-06-24

### 全面测试体系建设完成（新增6大测试套件）

#### 🧪 测试套件概览（150+项测试）

新增 `tests/` 目录，包含6大测试套件，全面覆盖数据库各维度测试：

| 测试套件 | 测试项数 | 覆盖范围 |
|---------|---------|---------|
| 核心功能测试 | 49项 | MongoDB 15项 + MySQL 16项 + 边界10项 + 安全8项 |
| 模型集成测试 | 35项 | User/Category/Article/Comment/Settings |
| 边界异常测试 | 31项 | 连接层 + 数据层 + 查询层 + 事务隔离 |
| 性能测试套件 | 20+项 | 连接速度/QPS/吞吐量/聚合/索引/并发/内存 |
| 安全测试套件 | 41项 | 密码/SQL注入/XSS/会话/文件上传/权限 |
| 文件上传测试 | 18项 | 大文件/小文件/并发/图片处理/类型验证 |
| 安装迁移测试 | 20项 | 表创建/数据迁移/配置/兼容性 |
| 稳定性测试 | 12项 | 连接保持/数据一致性/循环/事务/内存 |

#### 🔬 各测试套件详细内容

1. **边界条件和异常场景测试** [boundary-exception-test.php](file:///c:/TYUES/ACCZH/tests/boundary-exception-test.php)
   - 连接层：连接建立、多次连接稳定性、空闲连接复用、长查询不中断
   - 数据层：大数据量写入(5000条)、超长字符串(64KB)、超大文本(1MB)
   - 数据层：特殊字符处理、空值NULL、空字符串vs NULL、整数边界值
   - 数据层：布尔值处理、并发写入一致性、并发读写一致性
   - 查询层：超大结果集、深分页、多条件复杂查询、ORDER BY多字段
   - 查询层：GROUP BY聚合、子查询嵌套、LIKE模糊查询、正则表达式
   - 查询层：IN子句多值、空结果集、单字段NULL、LIMIT 0边界、全表扫描
   - 事务隔离：事务提交、事务回滚、批量事务性能、嵌套事务兼容

2. **性能测试套件** [performance-test.php](file:///c:/TYUES/ACCZH/tests/performance-test.php)
   - 连接建立速度（50次连接统计）
   - 简单查询QPS（1000次查询）
   - 主键查询QPS
   - 写入吞吐量（事务1000条）
   - 更新性能（500条更新）
   - 聚合性能（COUNT/GROUP BY/SUM/ORDER BY）
   - 索引效率对比（带索引vs无索引）
   - 混合并发性能（600次混合操作）
   - 内存增长测试（100行/1000行对比）
   - 大数据传输速率（2000行数据）

3. **安全测试套件** [security-test.php](file:///c:/TYUES/ACCZH/tests/security-test.php)
   - 密码安全：bcrypt哈希、弱密码检测、哈希不可逆、相同密码不同哈希
   - SQL注入防护：OR 1=1、DROP TABLE、UNION查询、注释绕过、数字型注入、堆叠查询
   - NoSQL注入防护：$gt操作符、$regex绕过、数组类型检测、参数化查询
   - XSS注入防护：script标签、img onerror、javascript协议、事件处理器、Unicode编码
   - 会话安全：httponly、use_only_cookies、会话ID长度
   - 路径遍历防护：../绕过、绝对路径、编码绕过、扩展名验证
   - 文件上传安全：MIME验证、大小限制、文件名随机化、双重扩展名防护
   - 权限控制：角色区分、密码验证、错误密码失败
   - 信息泄露防护：错误信息、密码哈希、敏感路径
   - CSRF防护：token生成、随机性

4. **文件上传和媒体处理测试** [file-upload-test.php](file:///c:/TYUES/ACCZH/tests/file-upload-test.php)
   - 文件上传：小文件(1KB)、中等文件(1MB)、大文件(10MB)
   - 并发上传模拟（10个文件）
   - 分块上传模拟（5MB分5块）
   - 图片处理：JPG/PNG/GIF/WebP支持检测
   - 图片尺寸调整（800x600→200x150）
   - 文件类型验证：扩展名白名单、MIME类型、getimagesize真实性
   - 文件大小限制、文件名安全处理
   - 元数据提取：EXIF信息、文件哈希、时间戳

5. **安装程序和数据迁移测试** [install-migration-test.php](file:///c:/TYUES/ACCZH/tests/install-migration-test.php)
   - 表创建：users/categories/articles/comments/settings
   - 默认数据：管理员用户、默认设置、默认分类
   - 数据迁移：添加新字段、创建索引、插入测试数据
   - 配置测试：读取、更新、JSON导出、热加载
   - 兼容性：版本号格式、连接兼容、UTF-8字符集、外键约束模拟

6. **长时间稳定性测试** [stability-test.php](file:///c:/TYUES/ACCZH/tests/stability-test.php)
   - 连接稳定性：100次ping、长连接不中断
   - 数据一致性：计数准确性(1000次递增)、插入数据完整性、随机读取一致性
   - 循环操作稳定性：1000次CRUD循环、批量插入稳定性
   - 事务完整性：提交完整性、回滚完整性、嵌套事务安全
   - 内存稳定性：内存增长可控、大数据集内存释放

#### 🔧 修复和优化

7. **SQLite驱动路径处理优化**
   - 修复绝对路径识别问题
   - 支持Windows绝对路径（`C:\path\to\db`）
   - 支持Unix/Linux绝对路径（`/path/to/db`）
   - 相对路径仍基于ROOT_PATH解析

8. **User模型功能完善**
   - 新增 `delete()` 方法
   - 新增 `getList()` 分页方法
   - 新增 `getCount()` 统计方法

9. **Settings模型解耦优化**
   - 移除对全局 `generateUUID()` 函数的依赖
   - 新增私有 `generateUUID()` 方法
   - 提升模型独立性和可测试性

#### 📊 测试结果（SQLite基准）

| 测试套件 | 通过/总计 | 通过率 |
|---------|----------|--------|
| 核心功能测试 | 48/49 | 98% |
| 模型集成测试 | 33/35 | 94.3% |
| 边界异常测试 | 30/31 | 96.8% |
| 安全测试 | 41/41 | 100% |
| 文件上传测试 | 18/18 | 100% |
| 安装迁移测试 | 20/20 | 100% |
| 稳定性测试 | 12/12 | 100% |

#### 🚀 使用方法

```bash
# 核心功能测试
php tests/db-core-test.php sqlite all

# 边界条件测试
php tests/boundary-exception-test.php sqlite

# 性能测试
php tests/performance-test.php sqlite

# 安全测试
php tests/security-test.php sqlite

# 文件上传测试
php tests/file-upload-test.php

# 安装迁移测试
php tests/install-migration-test.php sqlite

# 稳定性测试
php tests/stability-test.php sqlite

# 模型集成测试
php tests/model-integration-test.php sqlite
```

---

## [3.0.39] - 2026-06-24

### 核心协议深度测试与模型完整性修复

#### 🧪 测试体系建设

1. **创建数据库核心功能测试套件**
   - 新增 `tests/db-core-test.php` 综合测试文件
   - MongoDB 风格 15 项核心功能测试
   - MySQL 风格 16 项核心功能测试
   - 10 项边界条件和异常场景测试
   - 8 项安全相关测试
   - 总计 49 项全面测试

2. **创建业务模型集成测试套件**
   - 新增 `tests/model-integration-test.php`
   - User 模型 8 项测试
   - Category 模型 6 项测试
   - Article 模型 11 项测试
   - Comment 模型 4 项测试
   - Settings 模型 2 项测试
   - 边界条件 4 项测试
   - 总计 35 项集成测试

#### 🔧 模型完整性修复

3. **User 模型功能完善**
   - 新增 `delete()` 方法，支持用户删除
   - 新增 `getList()` 方法，支持分页用户列表
   - 新增 `getCount()` 方法，支持用户总数统计
   - 提升模型完整性，与其他模型保持一致

4. **Settings 模型解耦优化**
   - 移除对全局函数 `generateUUID()` 的依赖
   - 新增私有方法 `generateUUID()`，实现自包含
   - 提高模型独立性，便于单元测试

5. **SQLite 驱动路径处理增强**
   - 修复绝对路径识别问题
   - 支持 Windows 绝对路径（如 `C:\path\to\db`）
   - 支持 Unix/Linux 绝对路径（如 `/path/to/db`）
   - 相对路径仍基于 ROOT_PATH 解析

#### 📊 测试验证结果

6. **MongoDB 15项核心功能**
   - 文档插入、查询、更新、删除
   - 聚合管道、索引管理、集合管理
   - 数据库管理、用户认证、复制集协议
   - 分片协议、游标管理、写关注
   - 读关注、批量写
   - **通过率: 15/15 (100%)**

7. **MySQL 16项核心功能**
   - SQL查询、数据插入、更新、删除
   - 事务控制、预编译语句、存储过程
   - 用户管理、数据库管理、表管理
   - 索引管理、字符集支持、连接管理
   - 权限验证、二进制日志、握手协议
   - **通过率: 16/16 (100%)**

8. **安全测试**
   - SQL注入防护、NoSQL注入防护
   - XSS数据存储安全、密码哈希验证
   - 路径遍历防护、文件类型验证
   - 会话安全配置、错误信息防护
   - **通过率: 8/8 (100%)**

9. **业务模型集成测试**
   - User/Category/Article/Comment/Settings
   - 增删改查完整验证
   - 边界条件测试
   - **通过率: 33/35 (94.3%)**

#### 📝 使用说明

10. **测试套件使用方法**
    - 核心功能测试: `php tests/db-core-test.php sqlite all`
    - 模型集成测试: `php tests/model-integration-test.php sqlite`
    - 支持 sqlite/mysql/mongodb 三种数据库类型
    - 支持单独运行某类测试（mongodb/mysql/boundary/security）

---

## [3.0.38] - 2026-06-24

### 第11-18轮深度查缺补漏（15+问题修复）

#### 🔐 安全性增强

1. **前端XSS防护全面完善**
   - 修复 `header.php` 背景图片路径未转义的XSS漏洞
   - 修复导航栏分类slug未转义的XSS漏洞
   - 修复侧边栏分类名称未转义的XSS漏洞
   - 所有用户输出统一使用 `htmlspecialchars()` 转义

2. **会话安全全面升级**
   - 登录后调用 `session_regenerate_id(true)` 防止会话固定攻击
   - 注册后自动调用会话再生，提升安全性
   - 记录登录时间和IP地址，便于审计
   - 登录后自动生成CSRF令牌

3. **登出流程安全加固**
   - 彻底清理 `$_SESSION` 数组
   - 删除会话Cookie（包含path、domain、secure、httponly参数）
   - 销毁会话数据
   - 支持AJAX请求返回JSON响应

4. **输入验证全面加强**
   - 文章标题限制200字符
   - 文章摘要限制500字符
   - 文章别名限制100字符
   - 评论内容限制2000字符
   - 所有输入都有最小长度验证

#### 📄 前端页面修复

5. **分类详情页分页功能**
   - 新增分类详情页的文章列表分页
   - 支持页码导航（上一页/下一页/数字页码）
   - 自动处理页码边界（最多显示5个页码）
   - 保留分类slug参数

6. **MySQL建表脚本修复**
   - 修复 `schema_mysql.sql` 中users表role枚举缺少`user`角色
   - 默认角色从 `admin` 改为 `user`
   - 与安装程序和注册功能保持一致

#### 🔧 系统优化

7. **分类列表安全性**
   - 所有分类链接的slug参数都经过htmlspecialchars转义
   - 分类名称输出都经过安全转义
   - 防止XSS注入攻击

8. **多页面统一安全标准**
   - index.php、article.php、category.php、about.php、announcement.php
   - 所有页面的用户输出都经过安全处理
   - 统一的安全编码标准

---

## [3.0.37] - 2026-06-24

### 第七八九轮深度查缺补漏（20+问题修复）

#### 🔐 安全性修复

1. **管理员权限检查完善**
   - 修复 `admin/auth.php` 仅检查登录未检查管理员权限的严重漏洞
   - 所有管理操作 API 统一添加 `isAdmin()` 权限检查
   - 涉及：文章创建/更新/删除、分类创建/更新/删除、评论删除、设置更新

2. **文章创建 API 权限绕过漏洞修复**
   - 移除自动获取第一个管理员登录的危险逻辑
   - 严格验证当前登录用户的管理员权限
   - 移除调试代码（`display_errors`、`error_reporting`）

#### 🔧 核心功能修复

3. **版本号统一**
   - `config.php` 中 `APP_VERSION` 从 1.0.0 升级到 3.0.37
   - 与 `SJK/version.json` 版本号保持一致

4. **维护模式排除路径修复**
   - 修复排除路径缺少 `.php` 后缀导致登录/注册/安装页面被拦截的问题
   - 正确匹配 `/login.php`、`/register.php`、`/install.php`
   - 简化路径判断逻辑

5. **Session 变量完整性修复**
   - `login.php` 登录后完整设置所有 Session 变量
   - 添加 `$_SESSION['user_name']` 和 `$_SESSION['is_admin']`
   - 与 API 登录逻辑保持一致

6. **注册页面自动登录**
   - `register.php` 注册成功后自动登录
   - 设置完整的 Session 变量
   - 提升用户体验

#### 📊 数据库模型修复

7. **Article::getCount() 方法修复**
   - 修复 `$published` 参数默认值和判断逻辑错误
   - 支持 `null` 值（不过滤状态）
   - 修复返回值为 null 时的默认值

8. **Settings::update() INSERT 逻辑修复**
   - 修复新记录插入时 SQL 语句构建混乱的问题
   - 重构 INSERT 语句构建逻辑
   - 确保参数正确对应

9. **MySQL users 表 role 枚举修复**
   - 添加 `'user'` 角色到枚举值
   - 默认角色从 `'admin'` 改为 `'user'`
   - 与注册功能保持一致

10. **SQLite fetchOne 返回值统一**
    - 修复 SQLite 返回 `false` 而 MySQL 返回 `null` 的不一致问题
    - 统一使用 `null` 表示无结果
    - 提高跨数据库兼容性

#### 📄 页面完善

11. **公告详情页创建**
    - 新增 `announcement.php` 公告详情页面
    - 支持 Markdown 内容渲染
    - 包含左中右三栏布局
    - 右侧显示最新公告列表
    - 支持图片灯箱效果

#### 🧮 管理后台修复

12. **admin/index.php 参数调用修复**
    - 修复 `getCount(false)` 参数传递错误
    - 正确调用 `getCount(null, null)` 获取全部文章数
    - 正确调用 `getCount(null, true)` 获取已发布文章数

---

## [3.0.36] - 2026-06-24

### 第六轮查缺补漏

#### 🔍 系统检查

1. **安装状态 API 检查**
   - 确认安装检查逻辑正常
   - 确认版本号获取正常

2. **数据库配置检查**
   - 确认数据库配置文件正常
   - 确认数据库连接逻辑正常

---

## [3.0.35] - 2026-06-24

### 第五轮查缺补漏

#### 🔍 代码审查

1. **图片上传 API 检查**
   - 确认文件类型检查正常
   - 确认文件大小限制正常
   - 确认文件名生成逻辑正常

2. **注册登录流程检查**
   - 确认注册页面正常
   - 确认登录页面正常
   - 确认 Session 管理正常

---

## [3.0.34] - 2026-06-24

### 第四轮查缺补漏

#### 🔧 代码质量修复

1. **Settings 类 update 方法修复**
   - 添加 WHERE 条件，确保只更新单条记录
   - 防止 UPDATE 语句无 WHERE 条件导致更新所有记录
   - 添加空表时自动插入新记录的逻辑

---

## [3.0.33] - 2026-06-24

### 第二轮 + 第三轮查缺补漏

#### 🔧 API 文件完善

1. **创建缺失的 API 文件**
   - `api/categories/list.php` - 获取分类列表
   - `api/categories/update.php` - 更新分类
   - `api/articles/list.php` - 获取文章列表
   - `api/articles/read.php` - 获取单篇文章
   - `api/categories/read.php` - 获取单个分类
   - `api/comments/list.php` - 获取评论列表
   - `api/comments/delete.php` - 删除评论

2. **修复公告 API 兼容性**
   - 移除 MySQL 特有的 `SHOW TABLES` 命令
   - 改为直接使用 try-catch 捕获异常
   - 确保兼容 SQLite 和 MySQL

3. **文档更新**
   - 更新所有多语言文档版本号（中文、英文、日文、韩文）

---

## [3.0.32] - 2026-06-24

### 第一轮查缺补漏

#### 🔧 博客系统兼容性修复

1. **文章列表查询修复**
   - 修复 SQLite LIMIT/OFFSET 参数绑定问题
   - 改用直接拼接方式，避免 PDO 类型转换问题
   - 修复热门文章和相关文章查询

2. **维护模式逻辑完善**
   - 添加文件 + 数据库双存储机制
   - 维护模式检查提前到 config.php 全局生效
   - 修复维护页面显示问题
   - 管理员例外规则调整

3. **代码优化**
   - 简化 getList 方法参数处理
   - 移除不必要的检查调用
   - 统一代码风格

### 🐛 Bug 修复

4. **数据库操作问题修复**
   - 修复文章创建失败问题
   - 修复文章删除失败问题
   - 修复评论操作失败问题

---

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

