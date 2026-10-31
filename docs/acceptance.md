# 验收报告（Task 17）

- 验收日期：2026-10-31
- 代码基线：`backend/` Task 1–17 完成，并完成 Redis / RabbitMQ / XXL-Job 基础设施升级；只改 backend/spec/scripts/docs，未改 frontend/admin。
- 数据库：本机原生 PostgreSQL 18，开发库 `waibao_dev`，测试用临时库由 `AbstractIntegrationTest` 自动创建/删除。
- 自动化测试：`cd backend && mvn -q test`，**113 个测试，0 失败、0 错误、0 跳过**（含 Redis、RabbitMQ、XXL-Job 改造后的回归）。
- 基础设施：本机 Redis 8.10（验证码/限频/会话/缓存/锁）、RabbitMQ 4.4（导入/通知/AI 初审异步）、XXL-Job 2.4.1（7 个定时任务，admin 8081，执行器 9999）。
- 契约校验：`npx @redocly/cli lint spec/openapi/openapi.yaml`，**valid，0 warning**（`redocly.yaml` 关闭了两条与实际路由设计相关的规则，见文件注释）。
- 搜索压测：`./scripts/bench-search.sh`，5004 家 ACTIVE 公司，100/100 成功，平均 52.8ms，**P95 55.1ms**（阈值 300ms）。

## AC-01 ~ AC-22 映射

| 编号 | 场景 | 验证方式 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| AC-01 | 游客发评 403，数据库无点评记录 | `ReviewIntegrationTest#guestCannotSubmitReviewAndNoRecordIsCreated` | 通过 | 同时由 `AuthFlowIntegrationTest#guestWithoutAuthorizationGetsForbidden` 覆盖游客访问受保护接口 |
| AC-02 | 已实名未核验发评 403 + 佐证引导 | `ReviewIntegrationTest#realnameVerifiedUserWithoutVerificationIsForbidden`、`#unverifiedUserCannotSubmitReview` | 通过 | 校验 `realname_status=VERIFIED` 后仍 403，且 `review` 表无记录 |
| AC-03 | 维度仅 2 项被拒 | `ReviewIntegrationTest#fewerThanThreeDimensionsRejectedWithFieldError` | 通过 | 接口 400，字段错误含「至少选择 3 个维度」 |
| AC-04 | 2 条评价不显示综合分，评价正文可见 | `ReviewIntegrationTest#twoPublishedReviewsShowScoreGateAndBothContents` | 通过 | 公司详情 `reviewCount=2`、`scoreVisible=false`、`scoreNote=评价不足，暂不评分`；列表返回 2 条正文 |
| AC-05 | 第 3 条过审事务内重算，校准一致 | `ReviewIntegrationTest#thirdPublishedReviewRecomputesAggregateAndCompanyCapEnforced` | 通过 | 校准任务后 `review_count`/`avg_score`/`score_visible` 与同步结果一致，无 `AGGREGATE_DRIFT` |
| AC-06 | 具体指控无佐证转人工 | `ReviewIntegrationTest#privacyRedlineRejectedWithoutAppealAndSpecificAllegationGoesToManual`、`ReviewModerationIntegrationTest#specificClaimEntersQueueAndApprovePublishesWithAggregateAndContribution` | 通过 | AI flags 标记 `specificAllegation`，状态 `PENDING_MANUAL` |
| AC-07 | 隐私红线直接驳回且不可申诉 | `ReviewIntegrationTest#privacyRedlineRejectedWithoutAppealAndSpecificAllegationGoesToManual`、`ReviewModerationIntegrationTest#redlineCannotAppealAndNonRedlineAppealsOnce` | 通过 | `rejectReasonCode=PRIVACY`、`canAppeal=false` |
| AC-08 | 换身份后历史点评显示旧别名 | `ReviewIntegrationTest#identityChangeKeepsHistoricalReviewAlias` | 通过 | 我的点评与公开列表均保留旧 `identityAlias` |
| AC-09 | 未上传营业执照不生成工单 | `ComplaintIntegrationTest#unverifiedAppealNeverChangesReviewStatus` | 通过 | 点评状态不变 |
| AC-10 | 投诉转争议、降权、移榜、48h 倒计时 | `ComplaintIntegrationTest#verifiedAppealTransfersReviewToDisputedWithDeadlineAndAggregate` | 通过 | `DISPUTED`、`deadline`、聚合分排除、榜单剔除 |
| AC-11 | 双方均未举证超时转折叠「争议未决」 | `ComplaintIntegrationTest#bothSidesNoEvidenceRulingFoldsReview`、`NotificationIntegrationTest#deadlineWithNoEvidenceAutoFoldsAndNotifiesOnce` | 通过 | 定时任务与裁定两条路径均覆盖 |
| AC-12 | 二次投诉被拒并记录异常 | `ComplaintIntegrationTest#duplicateComplaintRejectedAndAnomalyAudited` | 通过 | 409，异常写审计 |
| AC-13 | 重复导入幂等无重复公司 | `CompanyIntegrationTest#importRunsTwiceIdempotentlyByUscc` | 通过 | 两次 417 条，公司总数不变，两个 `import_job` 可查 |
| AC-14 | 关闭 JS 公司页正文可读 | `curl -s http://localhost:3000/company/{uscc} \| grep ...`（见下方命令） | 通过 | 实测 HTML 含公司名、`评价不足，暂不评分`、评价正文、`Organization`/`Review` JSON-LD；1 条评价时无 `aggregateRating` |
| AC-15 | 管理员无删除接口，仅折叠/下架并填理由 | `ReviewModerationIntegrationTest#noDeleteEndpointAndDispositionRequiresReasonAndNote` | 通过 | `DELETE` 返回 405；驳回缺 `reasonCode/note` 返回 400 |
| AC-16 | 第 9 天编辑被拒 | `ReviewIntegrationTest#editingAfterNineDaysRejected` | 通过 | 403，提示「已超过 7 天编辑窗口」 |
| AC-17 | 编辑转「编辑中」并重审、不计分 | `ReviewIntegrationTest#editingMovesToEditingThenRestoresPublishedWithoutDoubleContribution` | 通过 | 编辑后 `review_audit` 记录 `EDIT`/`RESUBMIT_EDIT`/`AI_RE_REVIEW_PUBLISH`，贡献值不重复发放 |
| AC-18 | 同时间段重复发评被拒 | `ReviewIntegrationTest#duplicateSamePeriodRejectedWithOriginalReviewId` | 通过 | 409，返回原 `reviewId` 与编辑入口提示 |
| AC-19 | 24h 提醒站内 + 短信，失败重试 3 次 | `NotificationIntegrationTest#evidenceReminderIsIdempotentAndSkipsSubmittedSide`、`#smsFailureRetriesThreeTimesAndRecordsError` | 通过 | 已举证方不重复发送，调度幂等；短信失败 3 次并留记录 |
| AC-20 | 下架 >30 天内容需不同超管二次确认 | `ComplaintIntegrationTest#removalOfOldPublishedReviewRequiresSecondSuperAdmin` | 通过 | 首次 202 待复核，另一超管确认后才下架，同一人确认被拒 |
| AC-21 | 合并后 301、旧 USCC 映射、sitemap 剔除 | `CompanyIntegrationTest#mergeMigratesRelationsWritesRedirectAndExcludesSitemap` + 下方 `curl` 命令 | 通过 | 实测旧 URL 返回 `301 -> /company/{newUscc}`；sitemap 接口不含旧 USCC |
| AC-22 | 撤回立即移出评分与索引，30 天禁发 | `ReviewIntegrationTest#withdrawRemovesFromScoreAndIndexAndBlocksThirtyDays` | 通过 | 撤回后 `review_count=0`、公开列表为空；30 天内 403，40 天后释放名额 |

## 可复现命令

```bash
# 1) 全量自动化测试
cd backend && mvn -q test

# 2) 启动（另开终端）
./scripts/dev-up.sh
cd backend && mvn -q spring-boot:run          # :8080
cd frontend && npm install && npm run dev      # :3000

# 3) AC-14：无 JS 读公司页（需先有至少 1 条 PUBLISHED 评价）
curl -s http://localhost:3000/company/<USCC> \
  | grep -E '公司名|评价不足，暂不评分|评价正文|"@type":"Organization"|"@type":"Review"'
# 可用 bench 脚本导入的 mock 公司，例如 USCC=910001010000000001

# 4) AC-21：合并后旧 URL 301 + sitemap 剔除
curl -s -o /dev/null -w '%{http_code} -> %{redirect_url}\n' \
  http://localhost:3000/company/<OLD_USCC>
curl -s 'http://localhost:8080/api/companies/sitemap?city=北京&industry=IT人力外包' \
  | grep -c "<OLD_USCC>"   # 期望 0

# 5) 搜索压测
./scripts/bench-search.sh
```

## 本轮修复的问题

1. `ReviewService#replaceDimensions`：编辑点评时 Hibernate 先执行 INSERT 后执行 DELETE，导致 `(review_id, dimension_code)` 唯一约束冲突、PATCH 返回 500。已显式 flush 删除后再插入；`replaceEvidence` 同样处理。
2. `CompanyProfileService#detail`：`responseCount` 硬编码为 0，企业回应计数始终不展示。已改为统计 `enterprise_response` 中 `APPROVED` 数，并补 `CompanyIntegrationTest#adminCompanyDetailAndApprovedResponseCount`。
3. `GET /api/admin/companies/{uscc}` 缺失：已补管理端公司详情接口（复用 `CompanyDetailResponse`），并在契约登记。

## 未覆盖 / 前端说明

- 浏览器 375px 整页横向滚动、键盘可达性属于 Task 13/14/15 的人工浏览器验收，本轮未改前端，未做像素级回归。
- AC-14、AC-21 的页面侧已在本地 Nuxt dev 服务器实测通过；生产构建结果与部署环境需在发布阶段复验。
- 通知重试仅覆盖站内通知链路；企业联系人短信未接入 `notification` 重试队列（见运行手册「已知限制」）。
