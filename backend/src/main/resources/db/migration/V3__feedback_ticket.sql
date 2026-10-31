-- =====================================================================
-- 外包点评 一期 M0 · 帮助与反馈工单（F28 / R20）
-- =====================================================================

CREATE TABLE feedback_ticket (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category varchar(30) NOT NULL,
  contact_cipher text,
  content text NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',
  response_note text,
  responded_by uuid REFERENCES admin_user(id),
  responded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_feedback_ticket_category CHECK (category IN ('ACCOUNT','VERIFICATION','APPEAL','REVIEW','OTHER')),
  CONSTRAINT ck_feedback_ticket_status CHECK (status IN ('PENDING','REPLIED','CLOSED'))
);
CREATE INDEX idx_feedback_ticket_status ON feedback_ticket (status, created_at DESC);
