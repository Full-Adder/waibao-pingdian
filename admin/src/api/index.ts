import { http, request } from './http'
import type {
  AdminLoginResult,
  AdminProfile,
  AdminResponseItem,
  AdminUserDetail,
  AdminUserListItem,
  AuditLogItem,
  CompanyCandidateItem,
  CompanySummary,
  ComplaintDetailView,
  ComplaintEvidenceView,
  DashboardResponse,
  FeedbackItemView,
  ImportJobView,
  PageResult,
  ReasonTemplateBundle,
  ReportItemView,
  ReviewBatchDispositionResult,
  ReviewQueueDetail,
  ReviewQueueItem,
  RulingResult,
  VerificationItem
} from './types'

export const authApi = {
  login(account: string, password: string) {
    return http.post<AdminLoginResult>('/api/admin/auth/login', { account, password })
  },
  me() {
    return http.get<AdminProfile>('/api/admin/auth/me')
  }
}

export const reviewApi = {
  queue(query: { source?: string; keyword?: string; page?: number; size?: number }) {
    return http.get<PageResult<ReviewQueueItem>>('/api/admin/reviews/queue', query)
  },
  detail(id: string) {
    return http.get<ReviewQueueDetail>(`/api/admin/reviews/queue/${id}`)
  },
  approve(id: string, reasonCode: string, note: string) {
    return http.post<void>(`/api/admin/reviews/${id}/approve`, { reasonCode, note })
  },
  fold(id: string, reasonCode: string, note: string) {
    return http.post<void>(`/api/admin/reviews/${id}/fold`, { reasonCode, note })
  },
  reject(id: string, reasonCode: string, note: string) {
    return http.post<void>(`/api/admin/reviews/${id}/reject`, { reasonCode, note })
  },
  batch(ids: string[], action: string, reasonCode: string, note: string) {
    return http.post<ReviewBatchDispositionResult>('/api/admin/reviews/batch', {
      ids,
      action,
      reasonCode,
      note
    })
  },
  reasonTemplates() {
    return http.get<ReasonTemplateBundle>('/api/admin/reviews/reason-templates')
  }
}

export const verificationApi = {
  list(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<VerificationItem>>('/api/admin/verifications', query)
  },
  approve(id: string) {
    return http.post<void>(`/api/admin/verifications/${id}/approve`)
  },
  reject(id: string, reasonCode: string, note?: string) {
    return http.post<void>(`/api/admin/verifications/${id}/reject`, { reasonCode, note })
  }
}

export const complaintApi = {
  list(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<ComplaintDetailView>>('/api/admin/complaints', query)
  },
  detail(id: string) {
    return http.get<ComplaintDetailView>(`/api/admin/complaints/${id}`)
  },
  verify(id: string, verified: boolean, note?: string) {
    return http.post<void>(`/api/admin/complaints/${id}/verify`, { verified, note })
  },
  addEvidence(
    id: string,
    payload: { party: string; grade: string; note: string; file: File }
  ) {
    const form = new FormData()
    form.append('party', payload.party)
    if (payload.grade) form.append('grade', payload.grade)
    if (payload.note) form.append('note', payload.note)
    form.append('file', payload.file)
    return request<ComplaintEvidenceView>('POST', `/api/admin/complaints/${id}/evidence`, {
      formData: form
    })
  },
  ruling(
    id: string,
    payload: {
      result: string
      enterpriseGrade: string
      userGrade: string
      reasonText: string
      citedMaterialIds: string[]
      ruleClause?: string
      requiresDualReview?: boolean
    }
  ) {
    return http.post<RulingResult>(`/api/admin/complaints/${id}/ruling`, payload)
  },
  blocklist(payload: { uscc: string; reason?: string; evidenceFile?: string; until?: string }) {
    return http.post<{ id: string; uscc: string }>('/api/admin/complaints/blocklist', payload)
  }
}

export const responseApi = {
  list(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<AdminResponseItem>>('/api/admin/responses', query)
  },
  approve(id: string) {
    return http.post<void>(`/api/admin/responses/${id}/approve`)
  },
  reject(id: string, reason: string, note?: string) {
    return http.post<void>(`/api/admin/responses/${id}/reject`, { reason, note })
  }
}

export const reportApi = {
  list(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<ReportItemView>>('/api/admin/reports', query)
  },
  resolve(id: string, result: string, note?: string) {
    return http.post<void>(`/api/admin/reports/${id}/resolve`, { result, note })
  }
}

export const companyApi = {
  list(query: {
    keyword?: string
    status?: string
    city?: string
    industry?: string
    page?: number
    size?: number
  }) {
    return http.get<PageResult<CompanySummary>>('/api/admin/companies', query)
  },
  update(
    uscc: string,
    payload: {
      name?: string
      shortName?: string
      city?: string
      industry?: string
      companyType?: string
      status?: string
      reason?: string
      note?: string
      ticketId?: string
    }
  ) {
    return http.put<void>(`/api/admin/companies/${uscc}`, payload)
  },
  merge(payload: {
    sourceUscc: string
    targetUscc: string
    reason: string
    ticketId?: string
  }) {
    return http.post<void>('/api/admin/companies/merge', payload)
  },
  candidates(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<CompanyCandidateItem>>('/api/admin/company-candidates', query)
  },
  matchCandidate(id: string, payload: { uscc: string; note?: string; ticketId?: string }) {
    return http.post<void>(`/api/admin/company-candidates/${id}/match`, payload)
  },
  createCandidate(
    id: string,
    payload: { name: string; city?: string; industry?: string; note?: string; ticketId?: string }
  ) {
    return http.post<string>(`/api/admin/company-candidates/${id}/create`, payload)
  },
  rejectCandidate(id: string, payload: { note?: string; ticketId?: string }) {
    return http.post<void>(`/api/admin/company-candidates/${id}/reject`, payload)
  }
}

export const importApi = {
  list(query: { page?: number; size?: number }) {
    return http.get<PageResult<ImportJobView>>('/api/admin/imports', query)
  },
  detail(id: string) {
    return http.get<ImportJobView>(`/api/admin/imports/${id}`)
  },
  create(payload: { city: string; industry: string; triggerType?: string }) {
    return request<ImportJobView>('POST', '/api/admin/imports', { body: payload })
  }
}

export const userApi = {
  list(query: { keyword?: string; status?: string; page?: number; size?: number }) {
    return http.get<PageResult<AdminUserListItem>>('/api/admin/users', query)
  },
  detail(id: string) {
    return http.get<AdminUserDetail>(`/api/admin/users/${id}`)
  },
  ban(id: string, ban: boolean, reason: string) {
    return http.post<void>(`/api/admin/users/${id}/ban`, { ban, reason })
  },
  adjustContribution(id: string, delta: number, reason: string, refId?: string) {
    return http.post<void>(`/api/admin/users/${id}/contribution`, { delta, reason, refId })
  }
}

export const auditApi = {
  list(query: {
    actorId?: string
    action?: string
    targetType?: string
    targetId?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) {
    return http.get<PageResult<AuditLogItem>>('/api/admin/audit-logs', query)
  }
}

export const feedbackApi = {
  list(query: { status?: string; page?: number; size?: number }) {
    return http.get<PageResult<FeedbackItemView>>('/api/admin/feedback', query)
  },
  reply(id: string, responseNote: string, close: boolean) {
    return http.post<void>(`/api/admin/feedback/${id}/reply`, { responseNote, close })
  }
}

export const dashboardApi = {
  get(query: { from?: string; to?: string; city?: string; industry?: string; refresh?: boolean }) {
    return http.get<DashboardResponse>('/api/admin/dashboard', query)
  }
}

export const materialApi = {
  signedUrl(type: string, id: string, ticketId: string) {
    return http.get<{ url: string; expiresAt: string }>(
      `/api/admin/materials/${type}/${id}/url`,
      { ticketId }
    )
  }
}
