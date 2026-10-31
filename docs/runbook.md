# 运行手册（外包点评一期）

适用版本：Task 1–17 完成后的单仓库版本。默认本机原生 PostgreSQL 18，不依赖 Docker。

## 1. 本机环境与数据库

```bash
# 启动 PostgreSQL 18（Homebrew）
brew services start postgresql@18
pg_isready -h localhost -p 5432

# Redis 8（验证码、限频、会话、缓存、分布式锁）
brew services start redis
redis-cli ping

# RabbitMQ 4（导入 / 通知 / AI 初审）
brew services start rabbitmq
curl -s -u guest:guest http://localhost:15672/api/overview | head -c 80

# MySQL 9（仅 XXL-Job 调度中心元数据）
mysql -h127.0.0.1 -P3306 -uxxl -pxxl_dev_password -D xxl_job -e "select 1"

# 一键检查并创建开发库（读取根目录 .env，默认 waibao_dev）
./scripts/dev-up.sh

# 手工建库（等价）
createdb -h localhost -p 5432 -U "$(whoami)" -O "$(whoami)" waibao_dev
psql -d waibao_dev -c '\dt'
```

- 连接配置来自根目录 `.env`：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`。首次可从 `.env.example` 复制，并把 `DB_USER` 改成 `whoami`。
- Homebrew PostgreSQL 默认 trust 认证，本地密码可留空；生产环境必须设置独立账号与强密码。
- Flyway 在后端启动时自动执行 `backend/src/main/resources/db/migration/V1__init.sql` ~ `V4__realname_id_hash.sql`。**迁移文件已冻结，不得修改**；新增字段须走新的版本迁移。
- Flyway 11.7.2 会对 PostgreSQL 18 输出 “upgrade recommended” 警告，属已知提示，不影响迁移、启动与测试。
- `pg_trgm` 扩展随 V1 迁移创建；搜索依赖其 GIN 索引。

## 2. 启动命令与端口

| 服务 | 命令 | 端口 |
| --- | --- | --- |
| 后端 | `cd backend && mvn -q spring-boot:run` | 8080 |
| 前台 | `cd frontend && npm install && npm run dev` | 3000 |
| 后台 | `cd admin && npm install && npm run dev` | 5173 |
| PostgreSQL | `brew services start postgresql@18` | 5432 |
| Redis | `brew services start redis` | 6379 |
| RabbitMQ | `brew services start rabbitmq` | 5672 / 15672 |
| MySQL（XXL-Job） | `mysql -uxxl -p` | 3306 |
| XXL-Job admin | `./scripts/start-xxl-job-admin.sh`（JDK17） | 8081 |
| XXL-Job 执行器 | 随后端启动 | 9999 |

健康检查：

```bash
curl -s http://localhost:8080/api/health
# DB 与 Redis 正常：200；任一异常：503，仅返回 UP/DOWN，不暴露连接串
```

管理员账号由 `ADMIN_SEED_ACCOUNT` / `ADMIN_SEED_PASSWORD` / `ADMIN_SEED_ROLE` 注入，仅当账号与密码都存在时写入 `admin_user`（BCrypt）。仓库不保存真实凭据。

## 3. 测试

```bash
cd backend && mvn -q test
```

- 集成测试基类 `AbstractIntegrationTest` 每次启动自动创建独立库 `waibao_test_<timestamp>`，JVM 退出时 `DROP DATABASE ... WITH (FORCE)`，不需要 Docker。
- 测试固定使用 Redis `DB 15` 与随机 key 前缀；`APP_MQ_ASYNC=false`、`XXL_JOB_ENABLED=false`，确保同步、无外部调度依赖。
- 如需复用外部测试库，设置 `TEST_DB_URL`；连接参数可用 `TEST_DB_HOST` / `TEST_DB_PORT` / `TEST_DB_USER` / `TEST_DB_PASSWORD` 覆盖 `DB_*`。
- 当前基线：113 个测试，全绿（含 Redis/RabbitMQ/XXL-Job 改造后的回归）。验收映射见 `docs/acceptance.md`。
- 搜索压测：

```bash
./scripts/bench-search.sh
# 读取 .env 管理员账号 → 登录 → 公司数不足 5000 时补 6 城市 × 2 行业导入
# → 100 次搜索 → 输出平均 / P50 / P95 / 失败率；P95 > 300ms 或失败率 > 0 退出码 1
```

## 4. 备份与恢复

```bash
# 备份（pg_dump -Fc，默认保留最近 30 份到 backups/）
./scripts/backup.sh

# 恢复（默认交互确认；--yes 跳过确认）
./scripts/restore.sh backups/waibao_dev_20261031_021500.dump
./scripts/restore.sh backups/<dumpfile> --yes
```

- 备份脚本会校验归档可读（`pg_restore --list`）并设置 `chmod 600`；`BACKUP_KEEP`、`BACKUP_DIR` 可覆盖。
- 恢复使用 `pg_restore --clean --if-exists`，会覆盖同名库现有数据，执行前必须确认目标库与备份时点。
- 目标：10 分钟内恢复。演练步骤：`./scripts/backup.sh` → 记录 dump → 在维护窗口执行 `./scripts/restore.sh <dump> --yes` → `curl /api/health` 200 → 抽查 `company`、`review` 行数。
- WAL 归档为可选生产项（当前仓库只交付每日 `pg_dump`）。若需 PITR，在 `postgresql.conf` 配置：
  `wal_level=replica`、`archive_mode=on`、`archive_command='test ! -f /path/archive/%f && cp %p /path/archive/%f'`，并用 `pgBackRest` 或 `restore_command` 做时间点恢复。归档目录必须与数据库主机分离存放。

## 5. 材料保留与提前删除

每日 03:10（Asia/Shanghai）由 `RetentionJob` 执行 `RetentionService.run()`，三条终点任一触发即删除原件与脱敏副本，仅保留核验结论（状态、时间段、类型）：

1. 核验通过后 12 个月内未对该 USCC 发布任何评价；
2. 相关评价撤回/下架后 6 个月；
3. 材料上传满 24 个月的硬上限。

实现范围：`user_verification`、`review_evidence`、`complaint_evidence`。任务幂等，只处理 `material_status='ACTIVE'`；单文件删除失败只记日志，下次重试。

用户提前删除（不影响已发布评价）：

```bash
POST /api/users/me/verifications/{verificationId}/delete-material
# 需用户 JWT；返回 materialStatus / materialDeletedAt；写审计
```

## 6. 材料访问审计与泄露应急

- 访问入口：`GET /api/admin/materials/{type}/{id}/url?ticketId=`，`type` 支持 `VERIFICATION`、`REVIEW_EVIDENCE`、`COMPLAINT_EVIDENCE`、`COMPLAINT`、`COMPLAINT_LICENSE`、`COMPLAINT_LETTER`。
- 必须携带匹配的工单号 `ticketId`；签名 URL 5 分钟有效；仅管理端 JWT（`aud=admin`）可调用。
- 每次读取写 `admin_audit_log`（action=`MATERIAL_READ`，含 actor、目标、ticketId、IP）。同一管理员单日读取超过 20 份写 `risk_signal`（`MATERIAL_READ_THRESHOLD`）并告警。
- 后台不提供批量导出接口；不得把签名 URL 转发到外部或长期保存。

泄露应急（发现材料或敏感数据外泄时）：

1. **2 小时内止损**：禁用涉事管理员账号、轮换 `JWT_SECRET` 与 `CRYPTO_*`/`STORAGE_MASTER_KEY`（注意密钥轮换需配套数据迁移方案，先在隔离环境验证）、吊销泄露的签名 URL、排查 `admin_audit_log` 的 `MATERIAL_READ` 记录并保全证据。
2. **24 小时内通知**：按影响范围通知受影响用户与安全负责人，说明泄露字段、可能后果与已采取措施；同步法务评估上报义务。
3. **72 小时内报告**：提交事件报告，包含时间线、根因、影响面、止损与修复措施、后续加固计划；补齐审计与监控告警规则。

日志与响应约束：手机号/证件号 AES-GCM 加密存储，手机号另有 HMAC 唯一索引；`SensitiveDataMasker` 与 `RequestLoggingFilter` 对手机号、证件号做掩码；错误响应不返回堆栈（`server.error.include-stacktrace=never`）。

## 7. 监控与健康检查

- `/api/health`：DB 正常 200，异常 503 且不泄露连接串。接入外部监控时对 503 与响应时间告警。
- 关键日志：`RequestLoggingFilter`（方法、路径、状态、耗时、traceId）、`AdminAuditService`（后台写操作）、`RetentionService`（每日保留任务结果）、导入任务日志。
- 建议监控项：搜索 P95（`./scripts/bench-search.sh` 定期跑）、5xx 比例、审核队列积压、投诉 48h 超时、`MATERIAL_READ_THRESHOLD` 风险信号、备份成功与归档校验。
- 日志脱敏：不要在生产打开 SQL 参数明文日志；新增日志禁止输出手机号、证件号、材料 URL、JWT。

## 8. Docker（可选）

`infra/docker-compose.yml` 仅作为可选方案保留，默认流程不会启动。若使用 Docker：

```bash
docker compose -f infra/docker-compose.yml up -d
# 之后仍需按 .env 指向该实例，并执行 Flyway（后端启动自动迁移）
```

本机已验证路径是本机原生 PostgreSQL 18；Docker 方案未纳入本轮验收。

## 9. 已知限制

1. **Redis 不是纯缓存**：验证码、手机号/IP 限频、单设备会话 jti 依赖 Redis；Redis 故障时登录、发码、注册会失败，`/api/health` 返回 503。看板/榜单缓存与埋点去重可降级。
2. **企业联系人短信未接入通知重试队列**：R16 的站内 + 短信五类时限通知已覆盖；企业申诉联系人短信目前不写 `notification` 重试链路，失败需人工跟进。
3. **Flyway 对 PostgreSQL 18 打印 upgrade recommended 警告**：不影响迁移与测试，升级 Flyway 后可消除。
4. **MQ 发布失败补偿**：消息在事务提交后发布，发布失败只记录日志；极端情况下导入任务可能停留 RUNNING、通知停留 PENDING，`notificationRetryJob` 每 5 分钟经 XXL-Job 兜底重试，导入任务需人工重跑。
5. **XXL-Job 版本与运行环境**：admin 2.4.1 需 JDK17；`infra/xxl-job/xxl-job-admin-2.4.1.jar` 为本地交付，`*.jar` 不入库。执行器注册地址约 30 秒刷新，刚启动立刻手动触发可能提示地址为空。
6. **材料保留窗口当前写死在 SQL**（12 / 6 / 24 个月），与 PRD 默认值一致；如需可配置化需新增迁移与配置项，不要直接改冻结的 V1–V4。
