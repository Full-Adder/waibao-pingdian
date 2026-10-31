-- =====================================================================
-- Task 9：投诉举证材料、企业回应与裁定字段
-- 仅新增表/列/索引，不修改 V1 已有结构。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 投诉双方举证材料（side 区分企业/用户；文件只存私有 file_key）
-- ---------------------------------------------------------------------
CREATE TABLE complaint_evidence (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    complaint_id        uuid        NOT NULL REFERENCES complaint (id),
    side                varchar(10) NOT NULL,
    file_key            text        NOT NULL,
    file_hash           varchar(64),
    file_name           varchar(255),
    evidence_level      varchar(1),
    note                text,
    material_status     varchar(20) NOT NULL DEFAULT 'ACTIVE',
    material_deleted_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_complaint_evidence_side CHECK (side IN ('ENTERPRISE', 'USER')),
    CONSTRAINT ck_complaint_evidence_level CHECK (evidence_level IS NULL OR evidence_level IN ('A', 'B', 'C')),
    CONSTRAINT ck_complaint_evidence_material_status CHECK (material_status IN ('ACTIVE', 'DELETED'))
);

-- ---------------------------------------------------------------------
-- 企业回应（凭投诉工单提交，审核通过后公开展示）
-- ---------------------------------------------------------------------
CREATE TABLE enterprise_response (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id     uuid        NOT NULL REFERENCES review (id),
    complaint_id  uuid        REFERENCES complaint (id),
    uscc          varchar(18) NOT NULL,
    content       varchar(500) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'PENDING',
    reject_reason text,
    reviewer_id   uuid        REFERENCES admin_user (id),
    reviewed_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_enterprise_response_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- ---------------------------------------------------------------------
-- 投诉工单扩展：双人复核、证据分级、裁定、提案字段
-- ---------------------------------------------------------------------
ALTER TABLE complaint
    ADD COLUMN dual_review_required boolean NOT NULL DEFAULT false,
    ADD COLUMN dual_review_by       uuid REFERENCES admin_user (id),
    ADD COLUMN dual_review_at       timestamptz,
    ADD COLUMN enterprise_grade     varchar(1),
    ADD COLUMN user_grade           varchar(1),
    ADD COLUMN ruling_reason        text,
    ADD COLUMN cited_materials      text,
    ADD COLUMN rule_clause          varchar(200),
    ADD COLUMN proposed_resolution  varchar(30),
    ADD COLUMN proposed_by          uuid REFERENCES admin_user (id),
    ADD COLUMN proposed_at          timestamptz;

ALTER TABLE complaint
    ADD CONSTRAINT ck_complaint_enterprise_grade CHECK (enterprise_grade IS NULL OR enterprise_grade IN ('A', 'B', 'C')),
    ADD CONSTRAINT ck_complaint_user_grade CHECK (user_grade IS NULL OR user_grade IN ('A', 'B', 'C'));

-- ---------------------------------------------------------------------
-- 索引
-- ---------------------------------------------------------------------
CREATE INDEX idx_complaint_evidence_complaint_side ON complaint_evidence (complaint_id, side);
CREATE INDEX idx_enterprise_response_review_status ON enterprise_response (review_id, status);
