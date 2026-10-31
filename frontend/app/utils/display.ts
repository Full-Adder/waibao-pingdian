import type { CompanySource, ReviewStatus, VerificationBadge } from '~/types/api'

export const CITIES = ['北京', '上海', '深圳', '杭州', '广州', '成都'] as const
export const INDUSTRIES = ['IT人力外包', 'IT项目外包'] as const

export const DIMENSION_ORDER = [
  'SALARY',
  'WORK_REALITY',
  'CONTRACT',
  'PROJECT_QUALITY',
  'MANAGEMENT',
  'RECOMMEND'
] as const

const DIMENSION_LABELS: Record<string, string> = {
  SALARY: '薪资与发放',
  WORK_REALITY: '工作真实性',
  CONTRACT: '合同规范性',
  PROJECT_QUALITY: '项目质量',
  MANAGEMENT: '管理规范',
  RECOMMEND: '推荐意愿'
}

const SOURCE_LABELS: Record<CompanySource, string> = {
  BUSINESS_API: '工商数据',
  USER_REPORT: '用户上报',
  MANUAL: '人工补录'
}

const BADGE_LABELS: Record<VerificationBadge, string> = {
  CURRENT: '在职中',
  FORMER: '曾在职'
}

const STATUS_LABELS: Record<ReviewStatus, string> = {
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

export function dimensionLabel(code: string | null | undefined): string {
  if (!code) return '未评价'
  return DIMENSION_LABELS[code] ?? code
}

export function sourceLabel(source: string | null | undefined): string {
  if (!source) return ''
  return SOURCE_LABELS[source as CompanySource] ?? source
}

export function badgeLabel(badge: string | null | undefined): string {
  if (!badge) return ''
  return BADGE_LABELS[badge as VerificationBadge] ?? badge
}

export function statusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return STATUS_LABELS[status as ReviewStatus] ?? status
}

export const OUTSOURCING_TYPE_LABELS: Record<string, string> = {
  FULL_OUTSOURCING: '整包外包',
  PARTIAL_OUTSOURCING: '部分外包',
  DISPATCH: '人力派遣',
  OTHER: '其他形式'
}

export const PROJECT_TYPE_LABELS: Record<string, string> = {
  WEB: 'Web 项目',
  APP: 'App 项目',
  DATA: '数据项目',
  AI: 'AI 项目',
  OTHER: '其他项目'
}

export const PROOF_TYPE_LABELS: Record<string, string> = {
  LABOR_CONTRACT: '劳动合同',
  BADGE: '工牌',
  PAYROLL: '工资流水',
  COMPANY_EMAIL: '企业邮箱'
}

export const VERIFICATION_STATUS_LABELS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export const VERIFICATION_REJECT_LABELS: Record<string, string> = {
  MATERIAL_BLURRY: '材料不清晰',
  COMPANY_MISMATCH: '公司与档案不匹配',
  PERIOD_INVALID: '在职时间段无效',
  IDENTITY_MISMATCH: '身份信息不一致',
  OTHER: '其他原因'
}

export const NOTIFICATION_TYPE_LABELS: Record<string, string> = {
  DISPUTE_STARTED: '争议开始',
  EVIDENCE_REMINDER: '举证提醒',
  RULING_RESULT: '裁定结果',
  VERIFICATION_REJECTED: '核验驳回',
  APPEAL_RESULT: '申诉结果'
}

export const NOTIFICATION_TYPE_ICONS: Record<string, string> = {
  DISPUTE_STARTED: '争',
  EVIDENCE_REMINDER: '证',
  RULING_RESULT: '裁',
  VERIFICATION_REJECTED: '核',
  APPEAL_RESULT: '诉'
}

export const COMPLAINT_STATUS_LABELS: Record<string, string> = {
  PENDING_VERIFY: '等待主体核验',
  REJECTED: '主体核验未通过',
  EVIDENCE_COLLECTING: '双向举证中',
  PENDING_RULING: '等待裁定',
  RULED_USER_WIN: '用户胜诉',
  RULED_COMPANY_WIN: '企业胜诉',
  RULED_INSUFFICIENT: '争议未决'
}

export const REPORT_REASON_LABELS: Record<string, string> = {
  FALSE_INFO: '虚构违法事实',
  ABUSIVE: '人身攻击',
  ADVERTISING: '广告引流',
  PRIVACY: '泄露隐私',
  SPAM: '垃圾信息',
  OTHER: '其他违规情形'
}

export const REPORT_TARGET_LABELS: Record<string, string> = {
  REVIEW: '点评',
  COMPANY: '公司档案'
}

export const FEEDBACK_CATEGORY_LABELS: Record<string, string> = {
  ACCOUNT: '账号问题',
  VERIFICATION: '实名与核验',
  APPEAL: '申诉与投诉',
  REVIEW: '点评内容',
  OTHER: '其他问题'
}

export function outsourcingTypeLabel(code: string | null | undefined): string {
  if (!code) return ''
  return OUTSOURCING_TYPE_LABELS[code] ?? code
}

export function projectTypeLabel(code: string | null | undefined): string {
  if (!code) return ''
  return PROJECT_TYPE_LABELS[code] ?? code
}

export function proofTypeLabel(code: string | null | undefined): string {
  if (!code) return ''
  return PROOF_TYPE_LABELS[code] ?? code
}

export function verificationStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return VERIFICATION_STATUS_LABELS[status] ?? status
}

export function rejectReasonLabel(code: string | null | undefined): string {
  if (!code) return ''
  return VERIFICATION_REJECT_LABELS[code] ?? code
}

export function notificationTypeLabel(type: string | null | undefined): string {
  if (!type) return ''
  return NOTIFICATION_TYPE_LABELS[type] ?? type
}

export function complaintStatusLabel(status: string | null | undefined): string {
  if (!status) return ''
  return COMPLAINT_STATUS_LABELS[status] ?? status
}

export function reportReasonLabel(code: string | null | undefined): string {
  if (!code) return ''
  return REPORT_REASON_LABELS[code] ?? code
}

export function feedbackCategoryLabel(code: string | null | undefined): string {
  if (!code) return ''
  return FEEDBACK_CATEGORY_LABELS[code] ?? code
}

function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDate(value: string | null | undefined): string {
  const date = parseDate(value)
  if (!date) return ''
  return date.toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
}

export function formatDateTime(value: string | null | undefined): string {
  const date = parseDate(value)
  if (!date) return '—'
  return `${date.toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })} ${date.toLocaleTimeString(
    'zh-CN',
    { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false }
  )}`
}

export function formatScore(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return Number(value).toFixed(digits)
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function companyPath(uscc: string | null | undefined): string {
  return `/company/${encodeURIComponent(uscc ?? '')}`
}

export function reviewPeriod(start: string | null, end: string | null): string {
  if (!start && !end) return ''
  return `${start ?? '—'} 至 ${end ?? '—'}`
}

/**
 * 输出可安全嵌入 <script type="application/ld+json"> 的 JSON 字符串。
 */
export function jsonLdSafe(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029')
}
