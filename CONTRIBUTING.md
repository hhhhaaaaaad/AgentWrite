# Contributing to AgentWrite

[English](#english) | [中文](#中文)

---

## English

Thanks for your interest in contributing to AgentWrite! Here's how you can help.

### Reporting Issues

- Search existing issues before creating a new one
- Use the issue template and include:
  - Steps to reproduce
  - Expected vs actual behavior
  - Environment info (JDK version, OS, Docker version)

### Pull Requests

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Follow the existing code style and conventions
4. Commit with clear messages
5. Push and open a PR against `main`

### Development Setup

```bash
# 1. Start infrastructure
cd docs/dev-ops
docker compose -f docker-compose-environment.yml up -d

# 2. Set environment variables
export MEMORY_EMBEDDING_API_KEY="your_key"
export SUTONE_WRITING_MODEL_API_KEY="your_key"

# 3. Build
cd ../..
mvn -pl sutone-agent-bok-app -am clean package -Dmaven.test.skip=true
```

### Code Style

- Java 17 features are welcome
- Follow existing DDD layer conventions
- Keep domain logic in `sutone-agent-bok-domain`
- Infrastructure adapters go in `sutone-agent-bok-infrastructure`

---

## 中文

感谢你对 AgentWrite 的关注！以下是参与贡献的方式。

### 提交 Issue

- 提交前请先搜索已有 Issue
- 使用 Issue 模板，并包含：
  - 复现步骤
  - 期望行为与实际行为
  - 环境信息（JDK 版本、操作系统、Docker 版本）

### 提交 Pull Request

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 遵循现有代码风格
4. 使用清晰的 commit message
5. 推送并向 `main` 分支发起 PR

### 开发环境搭建

```bash
# 1. 启动基础设施
cd docs/dev-ops
docker compose -f docker-compose-environment.yml up -d

# 2. 配置环境变量
export MEMORY_EMBEDDING_API_KEY="your_key"
export SUTONE_WRITING_MODEL_API_KEY="your_key"

# 3. 编译
cd ../..
mvn -pl sutone-agent-bok-app -am clean package -Dmaven.test.skip=true
```

### 代码规范

- 欢迎使用 Java 17 特性
- 遵循现有 DDD 分层约定
- 领域逻辑放在 `sutone-agent-bok-domain`
- 基础设施适配放在 `sutone-agent-bok-infrastructure`
