# 外包点评 · 一期

外包点评一期（M0 工程骨架 + 后续 P0 全链路）的单仓库工程。评价以公司统一社会信用代码（USCC）为锚，发评前需实名与在职佐证核验。

## 环境要求

- JDK 21+（本机 JDK 25 可编译，Maven target 21）
- Maven 3.9+
- Node.js 20+（建议 24）与 npm
- 本机 PostgreSQL 16+（本机 PostgreSQL 18.6 已验证；无需 Docker）
- 本机 Redis 8+（本机 Redis 8.10.2 已验证；无需 Docker）
- 本机 RabbitMQ 4+（本机 RabbitMQ 4.4.0 已验证；无需 Docker）
- MySQL 8+ / 9.x（仅 XXL-Job 调度中心使用，本机 MySQL 9.7 已验证）与 JDK17（运行 admin 2.4.1）
- 端口：后端 8080、前台 3000、后台 5173、数据库 5432、Redis 6379、RabbitMQ 5672（管理台 15672）、XXL-Job admin 8081、执行器 9999

## 启动

```bash
brew services start postgresql@18
brew services start redis
brew services start rabbitmq
./scripts/start-xxl-job-admin.sh   # 另开终端，XXL-Job admin 8081（JDK17）
./scripts/dev-up.sh                # 检查 PostgreSQL / Redis / RabbitMQ 并创建 waibao_dev
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

首次运行 `dev-up.sh` 会从 `.env.example` 复制 `.env`，请把 `DB_USER` 改成本机用户名（`whoami`）；Homebrew PostgreSQL 默认 trust 认证，密码留空即可。
后台：`cd admin && npm install && npm run dev`（http://localhost:5173）。
健康检查：`curl -s http://localhost:8080/api/health`（DB 与 Redis 均正常 200，任一异常 503，仅返回 UP/DOWN，不暴露连接串）。

若本机 PostgreSQL 未启动：`brew services start postgresql@18`；Redis 未启动：`brew services start redis`；RabbitMQ 未启动：`brew services start rabbitmq`。

## 目录

```
backend/            Spring Boot 3.5 / Java 21 / JPA + Flyway + Security + Redis
  src/main/resources/db/migration/               V1 核心表 + V2 投诉举证 + V3 反馈 + V4 实名哈希
frontend/           Nuxt 4 SSR（Vue 3 + TS），协议三页
admin/              Vite + Vue 3 + TS 管理后台骨架
infra/              docker-compose.yml（可选，默认不使用）、xxl-job/（调度中心 SQL 与 admin jar）
spec/openapi/       OpenAPI 3.1 全量契约
scripts/            dev-up / build-all / check-contract / backup / restore / bench-search / start-xxl-job-admin
docs/               acceptance.md（AC 验收映射）/ runbook.md（运行手册）
```

## 数据库

默认使用本机原生 PostgreSQL（PostgreSQL 18 已验证），不依赖 Docker：

```bash
./scripts/dev-up.sh          # 检查服务并创建 waibao_dev
psql -d waibao_dev -c "\dt"
psql -d waibao_dev -c "select extname from pg_extension;"
```

后端启动时 Flyway 自动执行 `V1__init.sql`、`V2__complaint_support.sql`，并创建 `pg_trgm` 扩展。迁移文件冻结，后续任务不得修改表结构。
`infra/docker-compose.yml` 仅作为可选方案保留，默认流程不会启动它。

数据备份与恢复（读取根目录 `.env`，默认保留最近 30 份）：

```bash
./scripts/backup.sh                              # 生成 backups/waibao_dev_YYYYmmdd_HHmmss.dump
./scripts/restore.sh backups/<dumpfile> --yes    # pg_restore --clean --if-exists 到同名库
```


## Redis

验证码、IP 注册限频、单设备会话 jti、看板/榜单缓存、埋点去重与定时任务分布式锁均存放在 Redis（本机原生 Redis，不依赖 Docker）：

```bash
brew services start redis
redis-cli ping                     # PONG
redis-cli -n 0 keys 'waibao:*'      # 开发库 key 统一 waibao: 前缀
```

环境变量（见 `.env.example`）：

- `REDIS_HOST` / `REDIS_PORT`（默认 `localhost` / `6379`）
- `REDIS_PASSWORD`（默认空）
- `REDIS_DB`（默认 `0`；测试固定使用 `15`）
- `REDIS_KEY_PREFIX`（默认 `waibao:`，多环境/多实例共用 Redis 时建议区分）

key 约定：`waibao:sms:code:{phone}`、`waibao:sms:cooldown:{phone}`、`waibao:reg:ip:{ip}:{yyyyMMdd}`、`waibao:session:user:{userId}`、`waibao:dashboard:{from}:{to}:{city}:{industry}`、`waibao:ranking:{city}:{industry}:{type}:{limit}`、`waibao:visit:dedupe:{visitorId}:{sha256(path)}`、`waibao:lock:{job}:{date}`。
看板/榜单/埋点去重读取异常时会降级为数据库或实时计算；但验证码、限频、单设备会话依赖 Redis，Redis 不可用时登录/发码/注册会失败，`/api/health` 返回 503。

## RabbitMQ

工商导入、通知发送/重试、AI 初审默认走 RabbitMQ（`APP_MQ_ASYNC=true`），事务提交后才发布消息；消息体只携带业务 id，不放大对象。

```bash
brew services start rabbitmq
rabbitmqctl list_queues name messages consumers   # 或访问 http://localhost:15672（guest/guest）
curl -s -u guest:guest http://localhost:15672/api/queues | jq '.items[].name'
```

环境变量（见 `.env.example`）：

- `RABBITMQ_HOST` / `RABBITMQ_PORT`（默认 `localhost` / `5672`）
- `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST`（默认 `guest` / `guest` / `/`）
- `APP_MQ_ASYNC`（默认 `true`；设为 `false` 时退回进程内同步执行，集成测试固定为 `false`）

交换机与队列：

| 交换机 | 队列 | routing key | 用途 |
| --- | --- | --- | --- |
| `waibao.events`（topic, durable） | `waibao.import.queue` | `import.run` | 工商导入任务 |
| `waibao.events` | `waibao.notification.queue` | `notification.send` | 站内/短信通知发送 |
| `waibao.events` | `waibao.ai-review.queue` | `ai-review.process` | AI 初审 |
| `waibao.events`（DLX） | `waibao.notification.delay.queue` | 死信回到 `notification.send` | 短信失败延迟重试（TTL 5 分钟） |

消费失败由 Spring AMQP 重试 3 次；仍失败时导入任务置 `FAILED`、AI 初审置 `PENDING_MANUAL` 并写审计，通知业务失败自行落库，不会无限重投。`NotificationRetryJob` 经 XXL-Job 每 5 分钟兜底重试（Redis 锁防重），详见下文。

## XXL-Job 分布式定时任务

原 Spring `@Scheduled` 已全部迁移至 XXL-Job 2.4.1：执行器 `waibao-executor` 通过方法级 `@XxlJob` 注册 7 个 Handler，调度策略由调度中心统一管理；各任务入口的 Redis 分布式锁保留，作为多实例与重复调度的二次防护。

### 初始化（首次）

```bash
mysql -h127.0.0.1 -P3306 -uxxl -pxxl_dev_password -e "CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARSET utf8mb4;"
mysql -h127.0.0.1 -P3306 -uxxl -pxxl_dev_password xxl_job < infra/xxl-job/tables_xxl_job.sql   # 官方 8 张表
mysql -h127.0.0.1 -P3306 -uxxl -pxxl_dev_password xxl_job < infra/xxl-job/jobs.sql            # 执行器组 + 7 个任务
```

### 启动调度中心

```bash
./scripts/start-xxl-job-admin.sh      # 前台运行（JDK17），日志 /tmp/xxl-job-admin.log
```

- 地址：http://localhost:8081/xxl-job-admin
- 账号：`admin / 123456`
- accessToken：`waibao_xxl_token`（须与后端 `XXL_JOB_ACCESS_TOKEN` 一致）
- admin jar：`infra/xxl-job/xxl-job-admin-2.4.1.jar`（本地交付，`*.jar` 已在 `.gitignore` 中忽略；缺失时脚本会打印构建/下载说明）

### 启动执行器（后端）

`XXL_JOB_ENABLED=true`（默认）时，后端启动会自动注册到调度中心，执行器端口 9999；`XXL_JOB_ENABLED=false` 时完全不启动执行器。调度中心不可用时后端仍能正常启动（仅打印注册失败日志），`/api/health` 不受影响。

在 admin「执行器管理」中可看到 `waibao-executor` 及其在线地址（`http://<本机IP>:9999/`），对应 MySQL `xxl_job_registry`；「任务管理」中可见 7 条任务且状态为运行中。

### 任务列表

| Handler | 任务说明 | Cron | 原 Spring 配置 |
| --- | --- | --- | --- |
| `rankingSnapshotJob` | 榜单快照重算 | `0 0 3 * * *` | `0 0 3 * * *` |
| `retentionJob` | 材料保留清理 | `0 10 3 * * *` | `0 10 3 * * *` |
| `dashboardSnapshotJob` | 看板指标快照 | `0 30 2 * * *` | `0 30 2 * * *` |
| `complaintDeadlineJob` | 投诉举证时限扫描 | `0 0/5 * * * ?` | fixedDelay 5 分钟 |
| `notificationRetryJob` | 短信失败重试兜底 | `0 0/5 * * * ?` | fixedDelay 5 分钟 |
| `reviewDraftCleanupJob` | 点评草稿清理 | `0 15 4 * * *` | `0 15 4 * * *` |
| `reviewAggregateCalibrationJob` | 评价聚合校准 | `0 30 3 * * *` | `0 30 3 * * *` |

调度中心侧统一配置：`misfire_strategy=FIRE_ONCE_NOW`、`executor_route_strategy=FIRST`、`executor_block_strategy=SERIAL_EXECUTION`、`glue_type=BEAN`。

手动触发（页面「任务管理 → 执行一次」，或 API）：

```bash
curl -s -c /tmp/xxl_cookie.txt -X POST http://localhost:8081/xxl-job-admin/login -d "userName=admin&password=123456&ifRemember=on"
curl -s -b /tmp/xxl_cookie.txt -X POST "http://localhost:8081/xxl-job-admin/jobinfo/trigger?id=<任务ID>"
```

执行结果见 admin「调度日志」或 MySQL `xxl_job_log`（`trigger_code=200`、`handle_code=200` 为成功），执行器明细日志在 `XXL_JOB_LOG_PATH`（默认 `/tmp/xxl-job-executor/YYYY-MM-DD/<日志ID>.log`）。

环境变量（见 `.env.example`）：`XXL_JOB_ENABLED`、`XXL_JOB_ADMIN_ADDRESSES`、`XXL_JOB_ACCESS_TOKEN`、`XXL_JOB_EXECUTOR_APPNAME`、`XXL_JOB_EXECUTOR_ADDRESS`、`XXL_JOB_EXECUTOR_IP`、`XXL_JOB_EXECUTOR_PORT`、`XXL_JOB_LOG_PATH`、`XXL_JOB_LOG_RETENTION_DAYS`，以及 admin 自用库 `XXL_DB_HOST` / `XXL_DB_PORT` / `XXL_DB_NAME` / `XXL_DB_USER` / `XXL_DB_PASSWORD` / `XXL_JOB_ADMIN_PORT`。

## 测试

```bash
cd backend && mvn test
```

集成测试基类会在每次运行前自动创建独立测试库 `waibao_test_<timestamp>`，结束后删除，不需要 Docker。Redis 固定使用 `localhost:6379` 的 DB 15，并叠加本次运行随机前缀 `waibao:test:<runId>:`，`@BeforeEach` 只清理该前缀，不会污染开发库 DB 0。请确保测试前本机 Redis 已启动。

测试固定 `app.mq.async=false`（同步路径），并关闭 RabbitMQ 监听器与自动声明（`spring.rabbitmq.listener.simple.auto-startup=false`、`spring.rabbitmq.dynamic=false`），因此不需要 RabbitMQ 也能跑全量测试。测试同时固定 `xxl.job.enabled=false`，不启动执行器、不连调度中心。

若使用非默认连接，可设置：

- `TEST_DB_HOST` / `TEST_DB_PORT`（默认取 `DB_HOST` / `DB_PORT`）
- `TEST_DB_USER` / `TEST_DB_PASSWORD`（默认取 `DB_USER` / `DB_PASSWORD`）
- 或直接设置 `TEST_DB_URL` 复用已有测试库

Flyway 11.7.2 对 PostgreSQL 18 会打印「upgrade recommended」警告，不影响迁移与测试。

## 验收与压测

```bash
cd backend && mvn -q test          # 113 个测试全绿
./scripts/bench-search.sh          # 不足 5000 家自动导入 6 城市 × 2 行业，输出搜索 P95 与失败率
```

- AC-01 ~ AC-22 逐条映射与可复现命令见 `docs/acceptance.md`。
- 启动、备份恢复、材料保留、泄露应急、监控与已知限制见 `docs/runbook.md`。
- 搜索压测脚本要求后端已启动（默认 `http://localhost:8080`）并读取根目录 `.env` 的管理员账号；P95 > 300ms 时退出码 1。

## 测试账号

管理员账号来自环境变量，不在仓库中硬编码：

- `ADMIN_SEED_ACCOUNT` / `ADMIN_SEED_PASSWORD` / `ADMIN_SEED_ROLE`（`SUPER_ADMIN` 或 `CONTENT_ADMIN`）
- 仅当账号与密码变量都存在时，后端启动后 upsert 到 `admin_user`，密码以 BCrypt 存储
- 本地示例值见 `.env.example`（`admin / admin_dev_password`），仅供开发使用

用户端 Mock 短信验证码固定为 `123456`（`providers.mock.sms.fixed-code` 可改），服务端日志会输出验证码。

## Provider 切换

`providers.mode=mock|real`，默认 `mock`。Mock 覆盖短信、实名、AI 初审、工商数据、对象存储；`real` 为占位实现，切换不影响编译。

对象存储主密钥：`STORAGE_MASTER_KEY`（Base64，32 字节）。开发默认值仅在 `.env.example`，代码不写死。
JWT 密钥：`JWT_SECRET`（≥32 字节）。

## 契约与 Apifox

契约文件：`spec/openapi/openapi.yaml`（OpenAPI 3.1）。校验：

```bash
./scripts/check-contract.sh
```

Apifox 导入步骤：

1. 打开 Apifox，进入目标项目（当前「默认模块」为空）。
2. 选择「导入数据」→「OpenAPI / Swagger」。
3. 上传 `spec/openapi/openapi.yaml`，选择 OpenAPI 3.1，导入到「默认模块」。
4. 在「环境管理」新增本地环境，前置 URL 填 `http://localhost:8080`。
5. 管理端接口使用 `adminBearer`，用户端接口使用 `userBearer`；登录接口返回 token 后填入对应鉴权。

## 统一构建

```bash
./scripts/build-all.sh        # 后端 package + 前台 build + 后台 build
```

前端协议页：`/terms`、`/privacy`、`/report-policy`，无需登录即可访问。

## 持续集成

GitHub Actions 配置见 `.github/workflows/ci.yml`，在 push 到 `main`、PR 和手动触发时运行：

| Job | 内容 | 依赖 |
| --- | --- | --- |
| `backend` | `mvn test`（113 个测试） | 服务容器 PostgreSQL 16 + Redis 8 |
| `frontend` | `npm ci && npm run build`（Nuxt SSR） | Node.js 24 |
| `admin` | `npm ci && npm run build`（Vite） | Node.js 24 |
| `contract` | `spec/openapi/openapi.yaml` 校验 | Node.js 24 + Redocly CLI |

部署（CD）尚未接入：待服务器方案确定后，再在同一工作流中增加手动触发的发布任务，服务器密钥统一放 GitHub Secrets，生产环境变量只保存在服务器。

## 说明

- 本仓库已完成后端 Task 1–12、16（账号核验、公司档案、点评、审核、投诉裁定、通知、看板、安全保留）及 Task 17 验收；前台/后台按 Task 13–15 交付。
- 不提供删除点评接口；管理端处置仅有折叠、下架、驳回，且必须写审计。
- `waibao-prd/` 为只读需求文档，不在本仓库改动范围内。
