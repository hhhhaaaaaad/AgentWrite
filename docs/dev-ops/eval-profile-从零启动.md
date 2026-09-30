# eval profile 从零启动完整步骤

评测实例（`--spring.profiles.active=eval` / `SPRING_PROFILES_ACTIVE=eval`）跑在专用库
`sutone_agent_bok_eval` + 专用 Qdrant collection `agent_memory_eval` 上，与业务环境物理隔离。

## 0. 前置条件

- 本机 Docker 已启动（或至少 MySQL 13306 / Qdrant 6333 可用）
- JDK 21（项目构建要求）
- 一个**真实**的 SiliconFlow API key（用于 embedding；占位 key 会导致向量化静默失败）

## 1. 启动基础设施

```bash
cd docs/dev-ops
docker compose -f docker-compose-environment.yml up -d mysql redis qdrant rocketmq-namesrv rocketmq-broker
```

> 只需这 5 个；phpmyadmin / redis-admin / rocketmq-dashboard 是可选工具。
> Qdrant 容器显示 `unhealthy` 是假报警（镜像内没有 healthcheck 命令），服务本身正常。

## 2. 初始化 eval 库

`docs/dev-ops/mysql/sql/21-sutone-agent-bok-eval-database.sql` 负责建库 + 复制业务库表结构。

**方式 A（推荐，docker 首次初始化）**：若 MySQL 数据目录为空，`mysql/sql/` 下的脚本会按字母序
自动执行，`21-` 脚本在 `01~20`（业务库建表）之后运行，无需手动干预。

**方式 B（手动，对已存在的 MySQL）**：

```bash
docker exec -i mysql mysql -uroot -p123456 < docs/dev-ops/mysql/sql/21-sutone-agent-bok-eval-database.sql
```

> 注意：脚本用 `CREATE TABLE ... LIKE` 复制表结构，**不复制数据、不复制外键**。
> 对评测场景可接受——评测只读写 `memory_*` 与 `user` 表，不依赖业务外键链路。

## 3. 配置环境变量

```bash
export SPRING_PROFILES_ACTIVE=eval
export MEMORY_EMBEDDING_API_KEY=<你的 SiliconFlow key>
```

**不再需要** `MEMORY_QDRANT_VECTOR_SIZE`：向量维度已显式内置在
`application-eval.yml`（`memory.qdrant.vector-size: 1024`，即 bge-large-zh-v1.5），
启动不再依赖调 embedding API 探测维度。

> `SPRING_PROFILES_ACTIVE` 用环境变量而非命令行参数：
> 项目启动类此前 `SpringApplication.run(Application.class)` 漏传 `args`，命令行
> `--spring.profiles.active=eval` 不生效（已修复为 `run(Application.class, args)`，
> 命令行参数现也可用，但环境变量仍是最稳的推荐方式）。

## 4. 构建 + 启动

```bash
# 构建（本机内存紧张，Maven 必须限堆；-Dmaven.test.skip=true 跳过测试，
# 因为 -DskipTests 对本项目 app 模块曾被硬编码 skipTests=false 屏蔽——已修复）
MAVEN_OPTS="-Xmx640m -XX:MaxMetaspaceSize=320m" \
  mvn -B package -Dmaven.test.skip=true

java -jar sutone-agent-bok-app/target/sutone-agent-bok-app.jar
```

> eval 端口为 **8092**（`application-eval.yml` 的 `server.port`）。

## 5. 签发评测凭据（role=EVAL token）

`/api/v1/eval/**` 要求 `hasRole("EVAL")`，而**系统里没有在线签发 EVAL token 的路径**
（`AuthController.login` 调的是二参 `generateToken`，role 恒为 null）。
评测平台（eval-platform）需要一个长期凭据，用这个离线 CLI 签：

```bash
cd E:/java/AgentWrite
MAVEN_OPTS="-Xmx640m -XX:MaxMetaspaceSize=320m \
  -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8" \
  mvn -B -q -pl sutone-agent-bok-trigger exec:java \
    -Dexec.mainClass=cn.sutone.ai.trigger.security.EvalTokenCli \
    -Dexec.args="--expires-days 30 --format env"
```

输出形如 `JAVA_EVAL_TOKEN=eyJ...`，把它写进 **eval-platform 的 `backend/.env`**：

```
JAVA_EVAL_TOKEN=<上一步的输出>
```

要点：

- **`-Dfile.encoding=UTF-8` 必须带**：Windows 控制台默认 GBK，缺这个参数时
  CLI 的中文提示会输出成乱码（token 本身是 ASCII，不受影响）。
- **不是 HTTP 端点**：签发工具做成 CLI 是刻意的——一个「按需签发任意角色 token」
  的端点等于在认证边界上开后门，它得再做一套鉴权；而 EVAL 凭据的使用者是运维本人、
  频率是「部署时签一次」，CLI 用零攻击面换到同样的能力。
- **不要写死 token 到代码或提交进仓库**：它等同密码。`.env` 已被 `.gitignore` 排除。
- **轮换**：重新签一个 → 更新 `.env` → 重启平台进程。旧 token 会随过期自然失效，
  无需吊销。
- **密钥必须一致**：CLI 读 `JWT_SECRET` 环境变量，未设置时用配置文件里的默认值
  `sutone-agent-bok-jwt-secret-key-2026`。用默认密钥时 CLI 会打印告警——
  该密钥在仓库里公开可读，据此签出的 token 对任何读过仓库的人都有效，仅限本地。

## 6. 验证

```bash
# 无 token 应返回 403（Spring Security 拦截）
curl --noproxy '*' -i http://127.0.0.1:8092/api/v1/eval/params

# 带 token 应返回 0000 + 业务数据。
# 两条通道都支持：Cookie（浏览器）与 Authorization: Bearer（机器调用方，eval-platform 走这条）
curl --noproxy '*' -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8092/api/v1/eval/params
```

> 提示：本机系统代理（注册表指向本机 7897）会劫持 localhost 请求返回 502，
> curl 必须加 `--noproxy '*'`，或用 `httpx.Client(trust_env=False)`。

## 附：常见坑（均已修复，留档）

| 现象 | 根因 | 状态 |
|---|---|---|
| 占位 API key 导致应用起不来 | 启动时调 embedding 探测维度，失败→size=0→Qdrant 400 | 已内置 vector-size=1024 |
| `--spring.profiles.active=eval` 不生效 | 启动类漏传 args | 已修复 |
| `-DskipTests` 无效 | app 模块 pom 硬编码 skipTests=false | 已修复 |
| 全新环境无 eval 库 | 无建库脚本 | 已补 21- 脚本 |
| 评测平台带合法 token 仍 403（缺口 1） | `JwtAuthenticationFilter` 只读 `token` Cookie，不认 `Authorization: Bearer` | 已支持 Bearer（Cookie 优先） |
| 任何 token 都过不了 `hasRole("EVAL")`（缺口 2） | 全仓唯一签发点 `AuthController.login` 调二参 `generateToken`，role 恒为 null | 已补 `EvalTokenCli` 离线签发 |

> **这两个缺口是同一类问题**：单看每一侧都「正常」——浏览器登录一直好用、
> 评测端点权限配置也写对了——问题只在**跨进程调用**时才合流暴露。
> 纯 mock 的测试看不见（两个缺口都在真实 HTTP 与真实 Spring Security 里），
> 这也是「必须真跑一次端到端」最直接的论据。
