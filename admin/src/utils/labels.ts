export const ROLE_LABEL: Record<string, string> = {
  CONTENT_ADMIN: '内容管理员',
  SUPER_ADMIN: '超级管理员'
}

export const REVIEW_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  MACHINE_REVIEWING: '机器审核中',
  PENDING_MANUAL: '待人工',
  PUBLISHED: '已发布',
  EDITING: '编辑中',
  WITHDRAWN: '已撤回',
  DISPUTED: '争议中',
  FOLDED: '已折叠',
  REJECTED: '已驳回',
  REMOVED: '已下架'
}

export const REVIEW_SOURCE_LABEL: Record<string, string> = {
  AI_LOW_CONFIDENCE: '低置信度',
  SPECIFIC_CLAIM: '具体指控',
  REPORTED: '被举报',
  COPY_DETECTED: '疑似抄袭',
  RISK_FLAGGED: '风险标记',
  APPEAL: '申诉复审'
}

export const AI_FLAG_LABEL: Record<string, string> = {
  LOW_CONFIDENCE: '低置信度',
  SPECIFIC_CLAIM: '具体指控无佐证',
  SPECIFIC_CLAIM_WITHOUT_EVIDENCE: '具体指控无佐证',
  PERSONAL_ATTACK: '人身攻击',
  ADVERTISING: '广告与引流',
  PRIVACY_LEAK: '隐私泄露',
  ILLEGAL_FACT: '虚构违法事实',
  AI_GENERATED: 'AI 生成痕迹',
  COPY_DETECTED: '重复内容',
  RED_LINE: '红线违规',
  RISK: '风险内容'
}

export const DIMENSION_LABEL: Record<string, string> = {
  SALARY: '薪资',
  WORK_REALITY: '工作实况',
  CONTRACT: '合同',
  PROJECT_QUALITY: '项目质量',
  MANAGEMENT: '管理',
  RECOMMEND: '推荐度'
}

export const COMPLAINT_TYPE_LABEL: Record<string, string> = {
  CONTENT_DISPUTE: '内容争议'
}

export const EVIDENCE_TYPE_LABEL: Record<string, string> = {
  LABOR_CONTRACT: '劳动合同',
  BADGE: '工牌',
  PAYROLL: '工资流水',
  PAYSLIP: '工资条',
  COMPANY_EMAIL: '企业邮箱',
  CHAT_RECORD: '聊天记录',
  OTHER: '其他'
}

export const PROOF_TYPE_LABEL: Record<string, string> = {
  LABOR_CONTRACT: '劳动合同',
  BADGE: '工牌',
  PAYROLL: '工资流水',
  COMPANY_EMAIL: '企业邮箱'
}

export const MATERIAL_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '可用',
  DELETED: '已删除',
  PENDING_DELETE: '待删除'
}

export const VERIFICATION_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export const VERIFICATION_REJECT_LABEL: Record<string, string> = {
  MATERIAL_BLURRY: '材料模糊不清',
  COMPANY_MISMATCH: '公司与材料不匹配',
  PERIOD_INVALID: '在职时间不合理',
  IDENTITY_MISMATCH: '身份信息不一致',
  OTHER: '其他原因'
}

export const COMPLAINT_STATUS_LABEL: Record<string, string> = {
  PENDING_VERIFY: '待主体核验',
  REJECTED: '主体核验未通过',
  EVIDENCE_COLLECTING: '举证中',
  PENDING_RULING: '待裁定',
  RULED_USER_WIN: '用户胜诉',
  RULED_COMPANY_WIN: '企业胜诉',
  RULED_INSUFFICIENT: '证据不足'
}

export const COMPLAINT_STATUS_TYPE: Record<string, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  PENDING_VERIFY: 'warning',
  REJECTED: 'info',
  EVIDENCE_COLLECTING: 'primary',
  PENDING_RULING: 'warning',
  RULED_USER_WIN: 'success',
  RULED_COMPANY_WIN: 'success',
  RULED_INSUFFICIENT: 'info'
}

export const COMPLAINT_RESULT_LABEL: Record<string, string> = {
  USER_WIN: '用户胜诉',
  COMPANY_WIN: '企业胜诉',
  INSUFFICIENT: '证据不足'
}

export const RESPONSE_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export const REPORT_STATUS_LABEL: Record<string, string> = {
  PENDING: '待处置',
  VALID: '有效',
  INVALID: '无效',
  HANDLED: '已处理'
}

export const REPORT_TARGET_LABEL: Record<string, string> = {
  REVIEW: '点评',
  COMPANY: '公司'
}

export const REPORT_REASON_LABEL: Record<string, string> = {
  FALSE_INFORMATION: '不实信息',
  ABUSIVE: '辱骂攻击',
  ADVERTISING: '广告引流',
  PRIVACY: '隐私泄露',
  DUPLICATE: '重复内容',
  OTHER: '其他'
}

export const USER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '正常',
  BANNED: '已封禁',
  DELETED: '已注销'
}

export const REALNAME_STATUS_LABEL: Record<string, string> = {
  UNVERIFIED: '未实名',
  PENDING: '实名审核中',
  VERIFIED: '已实名'
}

export const COMPANY_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '已上线',
  MERGED: '已合并',
  OFFLINE: '已下线'
}

export const COMPANY_SOURCE_LABEL: Record<string, string> = {
  BUSINESS_API: '工商接口',
  USER_REPORT: '用户上报',
  MANUAL: '人工补录'
}

export const CANDIDATE_STATUS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  MATCHED: '已匹配',
  CREATED: '已补录',
  REJECTED: '已驳回'
}

export const IMPORT_STATUS_LABEL: Record<string, string> = {
  RUNNING: '执行中',
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败'
}

export const IMPORT_TRIGGER_LABEL: Record<string, string> = {
  MANUAL: '手动触发',
  SCHEDULE: '定时任务'
}

export const FEEDBACK_CATEGORY_LABEL: Record<string, string> = {
  ACCOUNT: '账号问题',
  VERIFICATION: '核验问题',
  APPEAL: '申诉受阻',
  REVIEW: '点评问题',
  OTHER: '其他'
}

export const FEEDBACK_STATUS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  REPLIED: '已回复',
  CLOSED: '已关闭'
}

export function labelOf(map: Record<string, string>, key?: string | null): string {
  if (!key) return '—'
  return map[key] ?? key
}

export function fmtDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function fmtDate(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function fmtDuration(seconds?: number | null): string {
  if (seconds === null || seconds === undefined) return '—'
  if (seconds <= 0) return '已到期'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours >= 24) {
    const days = Math.floor(hours / 24)
    return `${days} 天 ${hours % 24} 小时`
  }
  return `${hours} 小时 ${minutes} 分`
}

export function fmtScore(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  return Number(value).toFixed(1)
}
