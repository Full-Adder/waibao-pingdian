export type AdminRole = 'CONTENT_ADMIN' | 'SUPER_ADMIN'

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface AdminLoginResult {
  token: string
  role: AdminRole
  expiresAt: string
}

export interface AdminProfile {
  id: string
  account: string
  role: AdminRole
  status: string
  lastLoginAt?: string
}

export interface DimensionView {
  code: string
  score: number
}

export interface ReviewQueueItem {
  reviewId: string
  uscc: string
  companyName: string
  identityAlias: string
  totalScore: number
  dimensions: DimensionView[]
  content: string
  hasEvidence: boolean
  aiFlags: string[]
  aiConfidence: number
  rulesVersion?: string
  source: string
  enteredQueueAt: string
  waitingHours: number
  overdue: boolean
  noAppeal: boolean
  canAppeal: boolean
}

export interface ReviewAuditView {
  stage: string
  action: string
  fromStatus?: string
  toStatus?: string
  actorType: string
  actorId?: string
  reasonCode?: string
  detail?: string
  createdAt: string
}

export interface ReviewEvidenceMaterialView {
  evidenceId: string
  type: string
  materialStatus: string
  url?: string
}

export interface ReviewQueueDetail {
  reviewId: string
  uscc: string
  companyName: string
  identityAlias: string
  totalScore: number
  dimensions: DimensionView[]
  content: string
  hasEvidence: boolean
  aiFlags: string[]
  aiConfidence: number
  rulesVersion?: string
  status: string
  source: string
  enteredQueueAt: string
  waitingHours: number
  overdue: boolean
  noAppeal: boolean
  canAppeal: boolean
  auditHistory: ReviewAuditView[]
  materials: ReviewEvidenceMaterialView[]
}

export interface ReasonTemplateView {
  reasonCode: string
  text: string
}

export interface ReasonTemplateBundle {
  approve: ReasonTemplateView[]
  fold: ReasonTemplateView[]
  reject: ReasonTemplateView[]
}

export interface ReviewBatchDispositionResult {
  total: number
  succeeded: number
  failed: number
  results: Array<{
    reviewId: string
    success: boolean
    status?: string
    code?: number
    message?: string
  }>
}

export interface VerificationItem {
  id: string
  userId: string
  proofType: string
  companyUscc: string
  companyName?: string
  periodStart: string
  periodEnd: string
  badge?: string
  status: string
  rejectReasonCode?: string
  rejectNote?: string
  materialStatus: string
  createdAt: string
  reviewedAt?: string
}

export interface ReviewItemView {
  id: string
  uscc: string
  companyName: string
  identityAlias: string
  verificationBadge?: string
  totalScore: number
  content: string
  dimensions: DimensionView[]
  tags?: string[]
  periodStart?: string
  periodEnd?: string
  city?: string
  status: string
  aiConfidence?: number
  aiFlags?: string[]
  aiRulesVersion?: string
  publishedAt?: string
  createdAt?: string
  collapsed?: boolean
  disputed?: boolean
}

export interface ComplaintEvidenceView {
  id: string
  side: string
  grade?: string
  note?: string
  fileName?: string
  materialStatus?: string
  createdAt: string
}

export interface ComplaintMaterialView {
  materialType: string
  id: string
  url?: string
}

export interface EnterpriseResponseView {
  id: string
  reviewId?: string
  content: string
  createdAt: string
  label?: string
}

export interface ComplaintDetailView {
  id: string
  reviewId: string
  review?: ReviewItemView
  claimantUscc: string
  subjectVerified: boolean
  type?: string
  amountFlag: boolean
  safetyFlag: boolean
  status: string
  deadline?: string
  remainingSeconds: number
  overdue: boolean
  enterpriseEvidenceNote?: string
  userEvidenceNote?: string
  resolution?: string
  createdAt: string
  dualReviewRequired: boolean
  enterpriseGrade?: string
  userGrade?: string
  rulingReason?: string
  citedMaterials?: string
  ruleClause?: string
  proposedResolution?: string
  proposedBy?: string
  proposedAt?: string
  evidences: ComplaintEvidenceView[]
  materials: ComplaintMaterialView[]
  responses: EnterpriseResponseView[]
}

export interface RulingResult {
  complaintId: string
  status: string
  result: string
  pendingDualReview: boolean
  overdue: boolean
  rulingDeadline?: string
}

export interface AdminResponseItem {
  id: string
  reviewId: string
  complaintId?: string
  uscc?: string
  content: string
  status: string
  rejectReason?: string
  createdAt: string
  reviewedAt?: string
}

export interface ReportItemView {
  id: string
  targetType: string
  targetId: string
  reasonCode: string
  detail?: string
  status: string
  createdAt: string
  thresholdReached: boolean
}

export interface CompanySummary {
  uscc: string
  name: string
  shortName?: string
  city?: string
  industry?: string
  companyType?: string
  source: string
  reviewCount: number
  avgScore?: number
  scoreVisible: boolean
  hasReviews: boolean
}

export interface CompanyCandidateItem {
  id: string
  name: string
  city?: string
  industry?: string
  reporterUserId?: string
  matchedUscc?: string
  status: string
  createdAt: string
}

export interface ImportJobView {
  id: string
  city: string
  industry: string
  triggerType: string
  total: number
  upserted: number
  failed: number
  status: string
  startedAt: string
  finishedAt?: string
  detail?: {
    pages?: number
    failures?: Array<{ uscc?: string; name?: string; attempts?: string; error?: string }>
    error?: string
  }
}

export interface VerificationSummary {
  id: string
  status: string
  companyUscc: string
  periodStart: string
  periodEnd: string
  proofType: string
  reviewedAt?: string
}

export interface AdminUserDetail {
  id: string
  phone: string
  phoneMasked?: string
  realnameStatus: string
  contributionScore: number
  status: string
  createdAt: string
  alias?: string
  approvedVerificationCount: number
  publishedReviewCount: number
  verifications: VerificationSummary[]
  reviewStatusCounts: Record<string, number>
}

export interface AdminUserListItem {
  id: string
  phone: string
  phoneMasked?: string
  realnameStatus: string
  contributionScore: number
  status: string
  createdAt: string
  alias?: string
  approvedVerificationCount: number
  publishedReviewCount: number
}

export interface AuditLogItem {
  id: string
  actorId?: string
  actorRole?: AdminRole
  action: string
  targetType?: string
  targetId?: string
  reasonCode?: string
  detail?: string
  beforeHash?: string
  afterHash?: string
  ip?: string
  ticketId?: string
  createdAt: string
}

export interface FeedbackItemView {
  id: string
  category: string
  contactMasked?: string
  content: string
  status: string
  responseNote?: string
  respondedBy?: string
  respondedAt?: string
  createdAt: string
}

export interface MetricView {
  key: string
  label: string
  value: number
  unit: string
  definition?: string
  formula?: string
  numerator?: number
  denominator?: number
}

export interface DashboardWindow {
  from: string
  to: string
  city?: string
  industry?: string
  timeZone: string
}

export interface FunnelStage {
  stage: string
  count: number
  conversionFromPrevious?: number
}

export interface HealthRedLine {
  key: string
  label: string
  value: number
  threshold: number
  comparison: string
  triggered: boolean
  breached: boolean
  suggestion: string
}

export interface DashboardResponse {
  window: DashboardWindow
  northstar: MetricView
  supply: MetricView[]
  coverage: MetricView[]
  quality: MetricView[]
  demand: MetricView[]
  ecology: MetricView[]
  metrics: MetricView[]
  funnel: FunnelStage[]
  healthRedLines: HealthRedLine[]
  redLines: HealthRedLine[]
  metricDefinitions: Record<string, string>
}
