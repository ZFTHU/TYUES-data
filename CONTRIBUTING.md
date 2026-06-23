# 贡献指南

感谢您对 TYUES-data 项目的兴趣！我们欢迎各种形式的贡献。

## 如何贡献

### 报告问题

- 使用 GitHub Issues 报告 Bug
- 提供详细的问题描述和复现步骤
- 附上相关日志和配置

### 提交代码

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 遵循 Google Java Style Guide
- 添加适当的注释和 Javadoc
- 确保代码通过现有测试
- 添加新功能的测试用例

### 测试要求

- 所有新增代码必须有测试覆盖
- 运行测试: `./gradlew test`
- 确保所有测试通过后再提交

## 开发环境

### 必需工具

- JDK 17 或更高版本
- Gradle 8.5+
- Git

### 构建项目

```bash
git clone https://github.com/YOUR_USERNAME/TYUES-data.git
cd TYUES-data
./gradlew build
```

### 运行测试

```bash
./gradlew test
```

### IDE 配置

推荐使用 IntelliJ IDEA:
1. 导入项目为 Gradle 项目
2. 安装 CheckStyle-IDEA 插件
3. 启用 Google Java Style 格式化

## 分支管理

- `main` - 主分支，稳定版本
- `develop` - 开发分支
- `feature/*` - 特性分支
- `hotfix/*` - 热修复分支

## 许可证

通过贡献代码，您同意您的贡献将采用相同的 MIT 许可证。

## 行为准则

- 尊重其他贡献者
- 保持讨论建设性
- 关注项目最佳利益

## 联系方式

- GitHub Issues: [点击这里](https://github.com/YOUR_USERNAME/TYUES-data/issues)
- 邮箱: dev@tyues.cn

感谢您的贡献！
