-- =====================================================================
-- 外包点评 一期 M0 · 初始化迁移
-- 17 张核心表 + 6 张辅助表，一次建好，后续任务不再改表结构。
-- 业务时区 Asia/Shanghai；DB 统一存 timestamptz（UTC）。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- 1. 用户账号
-- ---------------------------------------------------------------------
CREATE TABLE user_account (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_cipher           text        NOT NULL,
    phone_hash             varchar(64) NOT NULL,
    realname_status        varchar(20) NOT NULL DEFAULT 'UNVERIFIED',
    realname_verified_at   timestamptz,
    realname_fail_count    integer     NOT NULL DEFAULT 0,
    realname_locked_until  timestamptz,
    contribution_score     integer     NOT NULL DEFAULT 0,
    status                 varchar(20) NOT NULL DEFAULT 'ACTIVE',
    session_jti            varchar(64),
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_account_phone_hash UNIQUE (phone_hash),
    CONSTRAINT ck_user_account_realname_status CHECK (realname_status IN ('UNVERIFIED', 'PENDING', 'VERIFIED')),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'BANNED', 'DELETED'))
);

-- ---------------------------------------------------------------------
-- 2. 管理员账号（角色仅两级）
-- ---------------------------------------------------------------------
CREATE TABLE admin_user (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account       varchar(64) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(20) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'ACTIVE',
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_admin_user_account UNIQUE (account),
    CONSTRAINT ck_admin_user_role CHECK (role IN ('CONTENT_ADMIN', 'SUPER_ADMIN')),
    CONSTRAINT ck_admin_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- ---------------------------------------------------------------------
-- 3. 在职佐证
-- ---------------------------------------------------------------------
CREATE TABLE user_verification (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid        NOT NULL REFERENCES user_account (id),
    proof_type          varchar(30) NOT NULL,
    file_key            text,
    file_hash           varchar(64),
    masked_file_key     text,
    company_uscc        varchar(18) NOT NULL,
    period_start        date        NOT NULL,
    period_end          date        NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'PENDING',
    reject_reason_code  varchar(40),
    reject_note         text,
    reviewer_id         uuid        REFERENCES admin_user (id),
    reviewed_at         timestamptz,
    material_status     varchar(20) NOT NULL DEFAULT 'ACTIVE',
    material_deleted_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_uv_proof_type CHECK (proof_type IN ('LABOR_CONTRACT', 'BADGE', 'PAYROLL', 'COMPANY_EMAIL')),
    CONSTRAINT ck_uv_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_uv_material_status CHECK (material_status IN ('ACTIVE', 'DELETED', 'PENDING_DELETE')),
    CONSTRAINT ck_uv_period CHECK (period_end >= period_start)
);

-- ---------------------------------------------------------------------
-- 3. 匿名身份
-- ---------------------------------------------------------------------
CREATE TABLE anonymous_identity (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES user_account (id),
    alias           varchar(32) NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'ACTIVE',
    change_count    integer     NOT NULL DEFAULT 0,
    last_changed_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_anonymous_identity_alias UNIQUE (alias),
    CONSTRAINT ck_anonymous_identity_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);

-- ---------------------------------------------------------------------
-- 4. 公司档案（USCC 作主键）
-- ---------------------------------------------------------------------
CREATE TABLE company (
    uscc          varchar(18) PRIMARY KEY,
    name          varchar(255) NOT NULL,
    short_name    varchar(255),
    city          varchar(64),
    industry      varchar(64),
    company_type  varchar(64),
    source        varchar(20) NOT NULL DEFAULT 'MANUAL',
    review_count  integer     NOT NULL DEFAULT 0,
    avg_score     numeric(3, 1),
    score_visible boolean     NOT NULL DEFAULT false,
    status        varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_company_source CHECK (source IN ('BUSINESS_API', 'USER_REPORT', 'MANUAL')),
    CONSTRAINT ck_company_status CHECK (status IN ('ACTIVE', 'MERGED', 'OFFLINE'))
);

-- ---------------------------------------------------------------------
-- 5. 公司别名（简称 / 曾用名）
-- ---------------------------------------------------------------------
CREATE TABLE company_alias (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uscc       varchar(18) NOT NULL REFERENCES company (uscc) ON UPDATE CASCADE,
    alias_name varchar(255) NOT NULL,
    alias_type varchar(20) NOT NULL DEFAULT 'SHORT_NAME'
);

-- ---------------------------------------------------------------------
-- 6. 旧 USCC 永久跳转映射（公司合并）
-- ---------------------------------------------------------------------
CREATE TABLE uscc_redirect (
    old_uscc    varchar(18) PRIMARY KEY,
    new_uscc    varchar(18) NOT NULL,
    merged_at   timestamptz NOT NULL DEFAULT now(),
    reason      text,
    operator_id uuid REFERENCES admin_user (id)
);

-- ---------------------------------------------------------------------
-- 7. 用户上报的待核验公司候选池
-- ---------------------------------------------------------------------
CREATE TABLE company_candidate (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name             varchar(255) NOT NULL,
    city             varchar(64),
    industry         varchar(64),
    reporter_user_id uuid REFERENCES user_account (id),
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    matched_uscc     varchar(18),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_candidate_status CHECK (status IN ('PENDING', 'MATCHED', 'CREATED', 'REJECTED'))
);

-- ---------------------------------------------------------------------
-- 8. 点评（identity_id 为发布时快照）
-- ---------------------------------------------------------------------
CREATE TABLE review (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uscc               varchar(18) NOT NULL REFERENCES company (uscc),
    user_id            uuid        NOT NULL REFERENCES user_account (id),
    identity_id        uuid        NOT NULL REFERENCES anonymous_identity (id),
    verification_id    uuid REFERENCES user_verification (id),
    verification_badge varchar(20),
    total_score        smallint    NOT NULL,
    content            text        NOT NULL,
    tag_json           jsonb,
    status             varchar(30) NOT NULL DEFAULT 'DRAFT',
    ai_confidence      numeric(4, 3),
    ai_flags           jsonb,
    ai_rules_version   varchar(32),
    edit_count         integer     NOT NULL DEFAULT 0,
    edit_deadline      timestamptz,
    published_at       timestamptz,
    withdrawn_at       timestamptz,
    dispute_deadline   timestamptz,
    ever_disputed      boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_review_total_score CHECK (total_score BETWEEN 1 AND 5),
    CONSTRAINT ck_review_status CHECK (status IN (
        'DRAFT', 'MACHINE_REVIEWING', 'PENDING_MANUAL', 'PUBLISHED', 'EDITING',
        'WITHDRAWN', 'DISPUTED', 'FOLDED', 'REJECTED', 'REMOVED'
    )),
    CONSTRAINT ck_review_verification_badge CHECK (
        verification_badge IS NULL OR verification_badge IN ('CURRENT', 'FORMER')
    )
);

-- ---------------------------------------------------------------------
-- 9. 点评维度分（六维度，至少 3 项由应用校验）
-- ---------------------------------------------------------------------
CREATE TABLE review_dimension (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id      uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    dimension_code varchar(32) NOT NULL,
    score          smallint    NOT NULL,
    CONSTRAINT uk_review_dimension UNIQUE (review_id, dimension_code),
    CONSTRAINT ck_review_dimension_score CHECK (score BETWEEN 1 AND 5),
    CONSTRAINT ck_review_dimension_code CHECK (dimension_code IN (
        'SALARY', 'WORK_REALITY', 'CONTRACT', 'PROJECT_QUALITY', 'MANAGEMENT', 'RECOMMEND'
    ))
);

-- ---------------------------------------------------------------------
-- 10. 点评佐证材料
-- ---------------------------------------------------------------------
CREATE TABLE review_evidence (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id       uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    file_key        text        NOT NULL,
    file_hash       varchar(64),
    type            varchar(30) NOT NULL,
    masked_at       timestamptz,
    material_status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_review_evidence_type CHECK (type IN (
        'LABOR_CONTRACT', 'BADGE', 'PAYROLL', 'COMPANY_EMAIL', 'PAYSLIP', 'CHAT_RECORD', 'OTHER'
    )),
    CONSTRAINT ck_review_evidence_material_status CHECK (material_status IN ('ACTIVE', 'DELETED'))
);

-- ---------------------------------------------------------------------
-- 11. 点评状态迁移审计
-- ---------------------------------------------------------------------
CREATE TABLE review_audit (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id   uuid        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    stage       varchar(30) NOT NULL,
    actor_id    uuid,
    actor_type  varchar(20) NOT NULL,
    action      varchar(40) NOT NULL,
    from_status varchar(30),
    to_status   varchar(30),
    reason_code varchar(40),
    detail      text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_review_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN'))
);

-- ---------------------------------------------------------------------
-- 12. 企业投诉（一条点评仅允许一次投诉）
-- ---------------------------------------------------------------------
CREATE TABLE complaint (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id                uuid        NOT NULL REFERENCES review (id),
    claimant_uscc            varchar(18) NOT NULL,
    claimant_contact_cipher  text        NOT NULL,
    license_file_key         text,
    letter_file_key          text,
    subject_verified         boolean     NOT NULL DEFAULT false,
    type                     varchar(40) NOT NULL DEFAULT 'CONTENT_DISPUTE',
    amount_flag              boolean     NOT NULL DEFAULT false,
    safety_flag              boolean     NOT NULL DEFAULT false,
    status                   varchar(30) NOT NULL DEFAULT 'PENDING_VERIFY',
    deadline                 timestamptz,
    enterprise_evidence_note text,
    user_evidence_note       text,
    resolution               varchar(30),
    resolved_by              uuid REFERENCES admin_user (id),
    created_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_complaint_review UNIQUE (review_id),
    CONSTRAINT ck_complaint_status CHECK (status IN (
        'PENDING_VERIFY', 'REJECTED', 'EVIDENCE_COLLECTING', 'PENDING_RULING',
        'RULED_USER_WIN', 'RULED_COMPANY_WIN', 'RULED_INSUFFICIENT'
    ))
);

-- ---------------------------------------------------------------------
-- 13. 举报（24h 限报由应用实现，DB 不强制）
-- ---------------------------------------------------------------------
CREATE TABLE report (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type varchar(20) NOT NULL,
    target_id   uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES user_account (id),
    reason_code varchar(40) NOT NULL,
    detail      text,
    status      varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_target_type CHECK (target_type IN ('REVIEW', 'COMPANY')),
    CONSTRAINT ck_report_status CHECK (status IN ('PENDING', 'VALID', 'INVALID', 'HANDLED'))
);

-- ---------------------------------------------------------------------
-- 14. 工商导入任务
-- ---------------------------------------------------------------------
CREATE TABLE import_job (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city         varchar(64) NOT NULL,
    industry     varchar(64) NOT NULL,
    trigger_type varchar(20) NOT NULL DEFAULT 'MANUAL',
    total        integer     NOT NULL DEFAULT 0,
    upserted     integer     NOT NULL DEFAULT 0,
    failed       integer     NOT NULL DEFAULT 0,
    status       varchar(20) NOT NULL DEFAULT 'RUNNING',
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    detail       jsonb,
    CONSTRAINT ck_import_job_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULE')),
    CONSTRAINT ck_import_job_status CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'))
);

-- ---------------------------------------------------------------------
-- 16. 后台操作审计（所有写操作与材料读取）
-- ---------------------------------------------------------------------
CREATE TABLE admin_audit_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    uuid        NOT NULL,
    actor_role  varchar(20) NOT NULL,
    action      varchar(60) NOT NULL,
    target_type varchar(40) NOT NULL,
    target_id   text,
    reason_code varchar(40),
    detail      text,
    before_hash varchar(64),
    after_hash  varchar(64),
    ip          varchar(64),
    ticket_id   varchar(64),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 17. 风险信号（设备 / IP / 用户）
-- ---------------------------------------------------------------------
CREATE TABLE risk_signal (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type  varchar(20) NOT NULL,
    subject_value varchar(255) NOT NULL,
    signal_type   varchar(40) NOT NULL,
    score         integer     NOT NULL DEFAULT 0,
    hit_count     integer     NOT NULL DEFAULT 1,
    expires_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_signal_subject_type CHECK (subject_type IN ('USER', 'IP', 'DEVICE'))
);

-- ---------------------------------------------------------------------
-- 18. 投诉黑名单
-- ---------------------------------------------------------------------
CREATE TABLE complaint_blocklist (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uscc          varchar(18) NOT NULL,
    reason        text        NOT NULL,
    evidence_file text,
    until         timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- =====================================================================
-- 以下 6 张为辅助表（服务 F24 通知与第 17 章指标）
-- =====================================================================

-- 19. 站内 / 短信通知
CREATE TABLE notification (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES user_account (id),
    type        varchar(40) NOT NULL,
    title       varchar(255) NOT NULL,
    body        text        NOT NULL,
    channel     varchar(20) NOT NULL DEFAULT 'INAPP',
    status      varchar(20) NOT NULL DEFAULT 'PENDING',
    read_at     timestamptz,
    ref_type    varchar(40),
    ref_id      text,
    retry_count integer     NOT NULL DEFAULT 0,
    last_error  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('INAPP', 'SMS', 'BOTH')),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ'))
);

-- 20. 搜索日志
CREATE TABLE search_log (
    id           bigserial PRIMARY KEY,
    keyword      varchar(255),
    result_count integer     NOT NULL DEFAULT 0,
    city         varchar(64),
    industry     varchar(64),
    visitor_id   varchar(64),
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- 21. 访问埋点
CREATE TABLE visit_event (
    id         bigserial PRIMARY KEY,
    visitor_id varchar(64),
    path       varchar(512) NOT NULL,
    user_agent text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 22. 榜单快照（算法参数与结果可回溯）
CREATE TABLE ranking_snapshot (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city              varchar(64) NOT NULL,
    industry          varchar(64) NOT NULL,
    ranking_type      varchar(20) NOT NULL,
    algorithm_version varchar(32) NOT NULL,
    params_json       jsonb,
    payload_json      jsonb,
    computed_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ranking_snapshot_type CHECK (ranking_type IN ('RECOMMEND', 'IMPROVE'))
);

-- 23. 贡献值流水（幂等：user + reason + ref_id 唯一）
CREATE TABLE contribution_ledger (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES user_account (id),
    delta      integer     NOT NULL,
    reason     varchar(40) NOT NULL,
    ref_type   varchar(40),
    ref_id     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_contribution_ledger UNIQUE (user_id, reason, ref_id)
);

-- =====================================================================
-- 索引
-- =====================================================================

-- 公司名 / 简称 / 别名的三元组模糊检索
CREATE INDEX idx_company_name_trgm ON company USING gin (name gin_trgm_ops);
CREATE INDEX idx_company_short_name_trgm ON company USING gin (short_name gin_trgm_ops);
CREATE INDEX idx_company_alias_name_trgm ON company_alias USING gin (alias_name gin_trgm_ops);

-- 城市 + 行业检索
CREATE INDEX idx_company_city_industry ON company (city, industry);

-- 点评列表 / 聚合
CREATE INDEX idx_review_uscc_status_published ON review (uscc, status, published_at DESC);
CREATE INDEX idx_review_user_uscc ON review (user_id, uscc);

-- 核验
CREATE INDEX idx_user_verification_company_status ON user_verification (company_uscc, status);
CREATE INDEX idx_user_verification_user ON user_verification (user_id, created_at DESC);

-- 举报
CREATE INDEX idx_report_target ON report (target_type, target_id);

-- 通知未读
CREATE INDEX idx_notification_user_read ON notification (user_id, read_at);

-- 指标
CREATE INDEX idx_search_log_created_at ON search_log (created_at);
CREATE INDEX idx_visit_event_created_at ON visit_event (created_at);

-- 审计与风控
CREATE INDEX idx_admin_audit_log_created_at ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_log_target ON admin_audit_log (target_type, target_id);
CREATE INDEX idx_risk_signal_subject ON risk_signal (subject_type, subject_value, expires_at);

-- 投诉
CREATE INDEX idx_complaint_status_deadline ON complaint (status, deadline);

-- 候选池
CREATE INDEX idx_company_candidate_status ON company_candidate (status, created_at);
