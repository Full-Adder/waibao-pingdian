-- =====================================================================
-- 外包点评 · XXL-Job 2.4.1 初始化数据
-- 依赖：已执行同目录 tables_xxl_job.sql 建库建表。
-- 执行：mysql -h127.0.0.1 -P3306 -uxxl -pxxl_dev_password xxl_job < infra/xxl-job/jobs.sql
-- 脚本可重复执行：执行器组与任务均按 app_name / executor_handler 判重。
--
-- 说明：
-- 1) trigger_next_time 单位是 UNIX 毫秒，这里按本机时区（Asia/Shanghai）预计算下一次触发时间，
--    避免插值为 0 时 admin 判定「已过期」并立即补偿执行；首次调度后由 admin 自动重算。
-- 2) 5 分钟任务按当前时间向上取整到下一个 5 分钟边界。
-- =====================================================================

-- 执行器组：自动注册（address_type=0），执行器上线后写入 xxl_job_registry
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'waibao-executor', '外包点评执行器', 0, NULL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name = 'waibao-executor');

SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'waibao-executor' LIMIT 1);

-- 1) 榜单快照重算：每日 03:00
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '榜单快照重算（每日 03:00）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 0 3 * * *', 'FIRE_ONCE_NOW', 'FIRST', 'rankingSnapshotJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '榜单快照重算', NOW(), '', 1, 0,
    IF(NOW() < CONCAT(CURDATE(), ' 03:00:00'),
       UNIX_TIMESTAMP(CONCAT(CURDATE(), ' 03:00:00')) * 1000,
       UNIX_TIMESTAMP(CONCAT(DATE_ADD(CURDATE(), INTERVAL 1 DAY), ' 03:00:00')) * 1000)
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'rankingSnapshotJob');

-- 2) 材料保留清理：每日 03:10
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '材料保留清理（每日 03:10）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 10 3 * * *', 'FIRE_ONCE_NOW', 'FIRST', 'retentionJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '材料保留清理', NOW(), '', 1, 0,
    IF(NOW() < CONCAT(CURDATE(), ' 03:10:00'),
       UNIX_TIMESTAMP(CONCAT(CURDATE(), ' 03:10:00')) * 1000,
       UNIX_TIMESTAMP(CONCAT(DATE_ADD(CURDATE(), INTERVAL 1 DAY), ' 03:10:00')) * 1000)
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'retentionJob');

-- 3) 看板指标快照：每日 02:30
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '看板指标快照（每日 02:30）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 30 2 * * *', 'FIRE_ONCE_NOW', 'FIRST', 'dashboardSnapshotJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '看板指标快照', NOW(), '', 1, 0,
    IF(NOW() < CONCAT(CURDATE(), ' 02:30:00'),
       UNIX_TIMESTAMP(CONCAT(CURDATE(), ' 02:30:00')) * 1000,
       UNIX_TIMESTAMP(CONCAT(DATE_ADD(CURDATE(), INTERVAL 1 DAY), ' 02:30:00')) * 1000)
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'dashboardSnapshotJob');

-- 4) 投诉举证时限扫描：每 5 分钟
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '投诉举证时限扫描（每 5 分钟）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 0/5 * * * ?', 'FIRE_ONCE_NOW', 'FIRST', 'complaintDeadlineJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '投诉举证时限扫描', NOW(), '', 1, 0,
    (UNIX_TIMESTAMP(NOW()) DIV 300 + 1) * 300000
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'complaintDeadlineJob');

-- 5) 短信失败重试兜底：每 5 分钟
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '短信失败重试兜底（每 5 分钟）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 0/5 * * * ?', 'FIRE_ONCE_NOW', 'FIRST', 'notificationRetryJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '短信失败重试兜底', NOW(), '', 1, 0,
    (UNIX_TIMESTAMP(NOW()) DIV 300 + 1) * 300000
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'notificationRetryJob');

-- 6) 点评草稿清理：每日 04:15
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '点评草稿清理（每日 04:15）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 15 4 * * *', 'FIRE_ONCE_NOW', 'FIRST', 'reviewDraftCleanupJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '点评草稿清理', NOW(), '', 1, 0,
    IF(NOW() < CONCAT(CURDATE(), ' 04:15:00'),
       UNIX_TIMESTAMP(CONCAT(CURDATE(), ' 04:15:00')) * 1000,
       UNIX_TIMESTAMP(CONCAT(DATE_ADD(CURDATE(), INTERVAL 1 DAY), ' 04:15:00')) * 1000)
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'reviewDraftCleanupJob');

-- 7) 评价聚合校准：每日 03:30
INSERT INTO xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email,
    schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler,
    executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count,
    glue_type, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '评价聚合校准（每日 03:30）', NOW(), NOW(), 'waibao', '',
    'CRON', '0 30 3 * * *', 'FIRE_ONCE_NOW', 'FIRST', 'reviewAggregateCalibrationJob',
    '', 'SERIAL_EXECUTION', 0, 0,
    'BEAN', '评价聚合校准', NOW(), '', 1, 0,
    IF(NOW() < CONCAT(CURDATE(), ' 03:30:00'),
       UNIX_TIMESTAMP(CONCAT(CURDATE(), ' 03:30:00')) * 1000,
       UNIX_TIMESTAMP(CONCAT(DATE_ADD(CURDATE(), INTERVAL 1 DAY), ' 03:30:00')) * 1000)
WHERE @group_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE job_group = @group_id AND executor_handler = 'reviewAggregateCalibrationJob');
