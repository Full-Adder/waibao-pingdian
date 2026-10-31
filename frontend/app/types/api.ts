export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export type CompanySource = 'BUSINESS_API' | 'USER_REPORT' | 'MANUAL'
export type CompanyStatus = 'ACTIVE' | 'MERGED' | 'OFFLINE'
export type VerificationBadge = 'CURRENT' | 'FORMER'
export type ReviewStatus =
  | 'DRAFT'
  | 'MACHINE_REVIEWING'
  | 'PENDING_MANUAL'
  | 'PUBLISHED'
  | 'EDITING'
  | 'WITHDRAWN'
  | 'DISPUTED'
  | 'FOLDED'
  | 'REJECTED'
  | 'REMOVED'

export interface CompanySummary {
  uscc: string
  name: string
  shortName: string | null
  city: string | null
  industry: string | null
  companyType: string | null
  source: CompanySource | null
  reviewCount: number
  avgScore: number | null
  scoreVisible: boolean
  hasReviews: boolean
}

export interface DimensionScore {
  code: string
  average: number | null
  sampleCount: number
  enoughSample: boolean
}

export interface CompanyDetail {
  uscc: string
  name: string | null
  shortName: string | null
  city: string | null
  industry: string | null
  companyType: string | null
  source: CompanySource | null
  sourceLabel: string | null
  status: CompanyStatus | null
  reviewCount: number
  avgScore: number | null
  scoreVisible: boolean
  hasReviews: boolean
  scoreNote: string | null
  aliases: string[]
  dimensions: DimensionScore[]
  responseCount: number
  redirectTo: string | null
}

export interface ReviewDimension {
  code: string
  score: number
}

export interface ReviewEvidence {
  type: string
  materialStatus: string | null
  maskedAt: string | null
}

export interface EnterpriseResponse {
  id: string
  reviewId: string | null
  content: string
  createdAt: string | null
  label: string
}

export interface ReviewItem {
  id: string
  uscc: string
  companyName: string | null
  identityAlias: string | null
  verificationBadge: VerificationBadge | null
  totalScore: number
  content: string
  dimensions: ReviewDimension[]
  tags: string[]
  periodStart: string | null
  periodEnd: string | null
  city: string | null
  evidence: ReviewEvidence[]
  status: ReviewStatus
  aiConfidence: number | null
  aiFlags: string[]
  aiRulesVersion: string | null
  publishedAt: string | null
  editDeadline: string | null
  editCount: number
  disputeDeadline: string | null
  everDisputed: boolean
  rejectReasonCode: string | null
  rejectMessage: string | null
  rejectRuleVersion: string | null
  withdrawnAt: string | null
  createdAt: string | null
  collapsed: boolean
  collapseNote: string | null
  disputed: boolean
  remainingEdits: number
  canAppeal: boolean
  editable: boolean
  enterpriseResponse: EnterpriseResponse | null
}

export interface RankingItem {
  rank: number
  uscc: string
  name: string
  sampleCount: number
  weightedScore: number | null
  lowScoreConcentration: number | null
  disputeRate: number | null
  score: number | null
  lowScoreRatio: number | null
  disputeRatio: number | null
}

export interface MetricDefinitions {
  sampleSet: string
  weightedScore: string
  lowScoreConcentration: string
  disputeRate: string
}

export interface RankingPage {
  city: string
  industry: string
  type: 'RECOMMEND' | 'IMPROVE'
  algorithmVersion: string
  computedAt: string | null
  threshold: number | null
  params: Record<string, unknown>
  metricDefinitions: MetricDefinitions
  page: number
  size: number
  total: number
  items: RankingItem[]
}

export interface SitemapEntry {
  uscc: string
  name: string
  updatedAt: string | null
}

export type RealnameStatus = 'UNVERIFIED' | 'PENDING' | 'VERIFIED'

export interface IdentityInfo {
  alias: string
  status: string
}

export interface UserProfile {
  id: string
  phone: string | null
  realnameStatus: RealnameStatus
  userState: string | null
  contributionScore: number
  status: string
  identity: IdentityInfo | null
  createdAt: string | null
}

export interface LoginResult {
  token: string
  expiresAt: string | null
  user: UserProfile
}

export interface SmsSendResult {
  resendAfterSeconds: number
  expiresInSeconds: number
}

export interface RealnameResult {
  success: boolean
  realnameStatus: RealnameStatus
  failCount: number
  lockedUntil: string | null
  reasonCode: string | null
  idMasked: string | null
}

export type ProofType = 'LABOR_CONTRACT' | 'BADGE' | 'PAYROLL' | 'COMPANY_EMAIL'
export type VerificationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type MaterialStatus = 'ACTIVE' | 'DELETED' | 'PENDING_DELETE'

export interface VerificationItem {
  id: string
  userId: string
  proofType: ProofType
  companyUscc: string
  companyName: string | null
  periodStart: string | null
  periodEnd: string | null
  badge: VerificationBadge | null
  status: VerificationStatus
  rejectReasonCode: string | null
  rejectNote: string | null
  materialStatus: MaterialStatus | null
  createdAt: string | null
  reviewedAt: string | null
}

export interface NotificationItem {
  id: string
  type: string
  title: string
  body: string
  channel: string | null
  readAt: string | null
  refType: string | null
  refId: string | null
  createdAt: string | null
}

export interface NotificationPage {
  items: NotificationItem[]
  page: number
  size: number
  total: number
  unreadCount: number
}

export type ComplaintStatus =
  | 'PENDING_VERIFY'
  | 'REJECTED'
  | 'EVIDENCE_COLLECTING'
  | 'PENDING_RULING'
  | 'RULED_USER_WIN'
  | 'RULED_COMPANY_WIN'
  | 'RULED_INSUFFICIENT'

export interface MyComplaintView {
  complaintId: string
  reviewId: string
  status: ComplaintStatus
  subjectVerified: boolean
  deadline: string | null
  remainingSeconds: number
  overdue: boolean
  userEvidenceSubmitted: boolean
  enterpriseEvidenceSubmitted: boolean
  userEvidenceNote: string | null
  enterpriseEvidenceNote: string | null
  resolution: string | null
  hasEnterpriseResponse: boolean
  enterpriseResponses: EnterpriseResponse[]
}

export interface ComplaintEvidenceView {
  id: string
  side: string
  evidenceLevel: string | null
  note: string | null
  fileName: string | null
  materialStatus: string | null
  createdAt: string | null
}

export interface ComplaintCreatedResult {
  complaintId: string
  status: ComplaintStatus
}

export interface ReportSubmitView {
  reportId: string
  status: string
  thresholdReached: boolean
}

export interface CompanyReportResult {
  matched: boolean
  pending: boolean
  company: CompanySummary | null
  candidateId: string | null
}

export interface FeedbackSubmitResult {
  ticketId: string
  status: string
}
