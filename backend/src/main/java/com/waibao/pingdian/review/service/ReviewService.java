package com.waibao.pingdian.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.domain.UserAccount;
import com.waibao.pingdian.account.repo.AnonymousIdentityRepository;
import com.waibao.pingdian.common.BusinessException;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.common.PageResult;
import com.waibao.pingdian.company.repo.CompanyRepository;
import com.waibao.pingdian.complaint.domain.EnterpriseResponseStatus;
import com.waibao.pingdian.complaint.repo.EnterpriseResponseRepository;
import com.waibao.pingdian.complaint.web.dto.EnterpriseResponseView;
import com.waibao.pingdian.config.RabbitConfig;
import com.waibao.pingdian.mq.MqEventPublisher;
import com.waibao.pingdian.mq.MqProperties;
import com.waibao.pingdian.mq.messages.AiReviewMessage;
import com.waibao.pingdian.review.config.ReviewProperties;
import com.waibao.pingdian.review.domain.DimensionCode;
import com.waibao.pingdian.review.domain.EvidenceType;
import com.waibao.pingdian.review.domain.Review;
import com.waibao.pingdian.review.domain.ReviewActorType;
import com.waibao.pingdian.review.domain.ReviewAudit;
import com.waibao.pingdian.review.domain.ReviewDimension;
import com.waibao.pingdian.review.domain.ReviewEvidence;
import com.waibao.pingdian.review.domain.ReviewStatus;
import com.waibao.pingdian.review.repo.ReviewAuditRepository;
import com.waibao.pingdian.review.repo.ReviewDimensionRepository;
import com.waibao.pingdian.review.repo.ReviewEvidenceRepository;
import com.waibao.pingdian.review.repo.ReviewRepository;
import com.waibao.pingdian.review.web.dto.ReviewItemView;
import com.waibao.pingdian.review.web.dto.ReviewUpsertRequest;
import com.waibao.pingdian.security.CurrentUserService;
import com.waibao.pingdian.verification.domain.UserVerification;
import com.waibao.pingdian.verification.domain.VerificationBadge;
import com.waibao.pingdian.verification.service.VerificationBadgeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 点评提交、草稿、编辑、撤回、我的点评与详情。
 */
@Service
public class ReviewService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<ReviewStatus> PERIOD_BLOCKING = EnumSet.of(
            ReviewStatus.DRAFT, ReviewStatus.MACHINE_REVIEWING, ReviewStatus.PENDING_MANUAL,
            ReviewStatus.PUBLISHED, ReviewStatus.EDITING, ReviewStatus.DISPUTED, ReviewStatus.FOLDED);
    private static final Set<ReviewStatus> COUNTED = EnumSet.of(
            ReviewStatus.PUBLISHED, ReviewStatus.DISPUTED, ReviewStatus.FOLDED);
    private static final Set<String> OUTSOURCING_TYPES = Set.of(
            "FULL_OUTSOURCING", "PARTIAL_OUTSOURCING", "DISPATCH", "OTHER");
    private static final Set<String> PROJECT_TYPES = Set.of("WEB", "APP", "DATA", "AI", "OTHER");
    private static final int MAX_CONTENT = 2000;
    private static final int MIN_CONTENT = 20;

    private final CurrentUserService currentUserService;
    private final VerificationBadgeService badgeService;
    private final AnonymousIdentityRepository identityRepository;
    private final CompanyRepository companyRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewDimensionRepository dimensionRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditRepository auditRepository;
    private final ReviewStateMachine stateMachine;
    private final ReviewAiProcessor aiProcessor;
    private final MqEventPublisher eventPublisher;
    private final MqProperties mqProperties;
    private final ReviewProperties reviewProperties;
    private final EnterpriseResponseRepository enterpriseResponseRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(CurrentUserService currentUserService,
                         VerificationBadgeService badgeService,
                         AnonymousIdentityRepository identityRepository,
                         CompanyRepository companyRepository,
                         ReviewRepository reviewRepository,
                         ReviewDimensionRepository dimensionRepository,
                         ReviewEvidenceRepository evidenceRepository,
                         ReviewAuditRepository auditRepository,
                         ReviewStateMachine stateMachine,
                         ReviewAiProcessor aiProcessor,
                         MqEventPublisher eventPublisher,
                         MqProperties mqProperties,
                         ReviewProperties reviewProperties,
                         EnterpriseResponseRepository enterpriseResponseRepository,
                         ObjectMapper objectMapper) {
        this.currentUserService = currentUserService;
        this.badgeService = badgeService;
        this.identityRepository = identityRepository;
        this.companyRepository = companyRepository;
        this.reviewRepository = reviewRepository;
        this.dimensionRepository = dimensionRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditRepository = auditRepository;
        this.stateMachine = stateMachine;
        this.aiProcessor = aiProcessor;
        this.eventPublisher = eventPublisher;
        this.mqProperties = mqProperties;
        this.reviewProperties = reviewProperties;
        this.enterpriseResponseRepository = enterpriseResponseRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建并立即提交点评（POST /api/reviews）。
     */
    @Transactional
    public ReviewItemView createAndSubmit(ReviewUpsertRequest request, String ip, String device) {
        UserAccount user = currentUserService.getUser();
        Validated validated = validateForSubmit(request);
        requireCompany(validated.uscc());
        badgeService.assertCanReview(user.getId(), validated.uscc());
        checkDuplicates(user.getId(), validated.uscc(), validated.periodStart(),
                validated.periodEnd(), null);

        Review review = new Review();
        fillReview(review, user.getId(), validated);
        reviewRepository.saveAndFlush(review);
        replaceDimensions(review.getId(), validated.dimensions());
        replaceEvidence(review.getId(), validated.evidenceKeys());
        stateMachine.transition(review, ReviewStatus.MACHINE_REVIEWING, ReviewActorType.USER,
                user.getId(), "SUBMIT", "SUBMIT", null, null);
        submitForAiReview(review, ip, device, false);
        return toView(review);
    }

    /**
     * 草稿保存/更新（POST /api/reviews/drafts）。草稿允许不完整。
     */
    @Transactional
    public ReviewItemView saveDraft(ReviewUpsertRequest request) {
        UUID userId = currentUserService.getUserId();
        Validated validated = validateForDraft(request);
        requireCompany(validated.uscc());

        Review review;
        if (request.id() != null) {
            review = reviewRepository.findById(request.id())
                    .filter(r -> r.getUserId().equals(userId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在"));
            if (review.getStatus() != ReviewStatus.DRAFT) {
                throw new BusinessException(ErrorCode.CONFLICT, "该点评已提交，不能作为草稿保存");
            }
        } else {
            review = findDraft(userId, validated.uscc(), validated.periodStart(), validated.periodEnd());
            if (review == null) {
                review = new Review();
            }
        }
        JsonNode previousTags = review.getTagJson();
        fillReview(review, userId, validated);
        if (request.tags() == null && request.periodStart() == null && request.periodEnd() == null
                && previousTags != null) {
            review.setTagJson(previousTags);
        }
        reviewRepository.saveAndFlush(review);
        if (request.dimensions() != null) {
            replaceDimensions(review.getId(), validated.dimensions());
        }
        if (request.evidenceFileKey() != null || request.evidenceFileKeys() != null) {
            replaceEvidence(review.getId(), validated.evidenceKeys());
        }
        return toView(review);
    }

    /**
     * 草稿提交进入机器审核（POST /api/reviews/{id}/submit）。
     */
    @Transactional
    public ReviewItemView submitDraft(UUID reviewId, String ip, String device) {
        UserAccount user = currentUserService.getUser();
        Review review = requireAuthorReview(reviewId, user.getId());
        if (review.getStatus() != ReviewStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅草稿可以提交");
        }
        Validated validated = validateForSubmit(toRequest(review));
        badgeService.assertCanReview(user.getId(), review.getUscc());
        checkDuplicates(user.getId(), review.getUscc(), validated.periodStart(),
                validated.periodEnd(), review.getId());
        applyEditableFields(review, validated);
        reviewRepository.saveAndFlush(review);
        stateMachine.transition(review, ReviewStatus.MACHINE_REVIEWING, ReviewActorType.USER,
                user.getId(), "SUBMIT", "SUBMIT", null, null);
        submitForAiReview(review, ip, device, false);
        return toView(review);
    }

    /**
     * 编辑（PATCH /api/reviews/{id}）：仅 PUBLISHED、7 天内、最多 2 次；转 EDITING 重审。
     */
    @Transactional
    public ReviewItemView update(UUID reviewId, ReviewUpsertRequest request, String ip, String device) {
        UserAccount user = currentUserService.getUser();
        Review review = requireAuthorReview(reviewId, user.getId());
        ReviewStatus status = review.getStatus();
        if (status == ReviewStatus.DISPUTED || status == ReviewStatus.FOLDED || status == ReviewStatus.REMOVED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "争议中、已折叠或已下架的点评不可编辑");
        }
        if (status != ReviewStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前状态不可编辑");
        }
        Instant deadline = review.getPublishedAt().plus(reviewProperties.getEditWindowDays(), ChronoUnit.DAYS);
        if (Instant.now().isAfter(deadline)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "已超过 " + reviewProperties.getEditWindowDays() + " 天编辑窗口");
        }
        if (review.getEditCount() >= reviewProperties.getMaxEdits()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "编辑次数已用完");
        }

        Validated validated = validateForSubmit(mergeForUpdate(request, review));
        applyEditableFields(review, validated);
        if (request.dimensions() != null) {
            replaceDimensions(review.getId(), validated.dimensions());
        }
        if (request.evidenceFileKey() != null || request.evidenceFileKeys() != null) {
            replaceEvidence(review.getId(), validated.evidenceKeys());
        }
        review.setEditCount(review.getEditCount() + 1);
        if (review.getEditDeadline() == null) {
            review.setEditDeadline(deadline);
        }
        reviewRepository.saveAndFlush(review);

        stateMachine.transition(review, ReviewStatus.EDITING, ReviewActorType.USER, user.getId(),
                "EDIT", "EDIT", null, null);
        stateMachine.transition(review, ReviewStatus.MACHINE_REVIEWING, ReviewActorType.USER, user.getId(),
                "RESUBMIT_EDIT", "EDIT_SUBMIT", null, null);
        submitForAiReview(review, ip, device, true);
        return toView(review);
    }

    /**
     * 撤回（POST /api/reviews/{id}/withdraw）：仅 PUBLISHED。
     */
    @Transactional
    public void withdraw(UUID reviewId) {
        UUID userId = currentUserService.getUserId();
        Review review = requireAuthorReview(reviewId, userId);
        if (review.getStatus() != ReviewStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅已发布评价可以撤回");
        }
        stateMachine.transition(review, ReviewStatus.WITHDRAWN, ReviewActorType.USER, userId,
                "WITHDRAW", "WITHDRAW", null, null);
    }

    /**
     * 驳回申诉（POST /api/reviews/{id}/appeal）：仅作者、仅非红线驳回、只能申诉一次。
     */
    @Transactional
    public ReviewItemView appeal(UUID reviewId, String statement) {
        UserAccount user = currentUserService.getUser();
        Review review = requireAuthorReview(reviewId, user.getId());
        if (review.getStatus() != ReviewStatus.REJECTED) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅已驳回的点评可以申诉");
        }
        if (noAppealOf(review)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该点评属于红线违规，不支持申诉");
        }
        if (auditRepository.existsByReviewIdAndAction(reviewId, "APPEAL")) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一评价只能申诉一次");
        }
        String detail = null;
        if (statement != null && !statement.isBlank()) {
            String trimmed = statement.trim();
            if (trimmed.length() > 500) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "申诉说明不能超过 500 字");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("statement", trimmed);
            try {
                detail = objectMapper.writeValueAsString(map);
            } catch (Exception ignored) {
                detail = null;
            }
        }
        stateMachine.transition(review, ReviewStatus.PENDING_MANUAL, ReviewActorType.USER, user.getId(),
                "APPEAL", "APPEAL", null, detail);
        return toView(review);
    }

    /**
     * 我的点评（GET /api/reviews/mine）。
     */
    public PageResult<ReviewItemView> mine(String status, int page, int size) {
        UUID userId = currentUserService.getUserId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<Review> result;
        if (status == null || status.isBlank()) {
            result = reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        } else {
            ReviewStatus filter;
            try {
                filter = ReviewStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "点评状态不正确");
            }
            result = reviewRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, filter, pageable);
        }
        List<ReviewItemView> items = result.getContent().stream().map(this::toView).toList();
        return PageResult.of(items, safePage, safeSize, result.getTotalElements());
    }

    /**
     * 首页最新已发布评价（GET /api/reviews/latest）。
     */
    public List<ReviewItemView> latest(int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 20);
        Page<Review> result = reviewRepository.findByStatusOrderByPublishedAtDesc(
                ReviewStatus.PUBLISHED, PageRequest.of(0, safeLimit));
        return result.getContent().stream().map(this::toView).toList();
    }

    /**
     * 点评详情：作者可见全部，其他人仅公开状态。
     */
    public ReviewItemView detail(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
        Optional<UUID> current = currentUserService.findUserId();
        boolean author = current.map(id -> id.equals(review.getUserId())).orElse(false);
        if (!author && !ReviewVisibility.isPubliclyVisible(review.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "点评不存在");
        }
        return toView(review);
    }

    public ReviewItemView toView(Review review) {
        List<ReviewDimension> dimensions = dimensionRepository.findByReviewIdOrderByDimensionCode(review.getId());
        List<ReviewEvidence> evidence = evidenceRepository.findByReviewId(review.getId()).stream()
                .filter(e -> "ACTIVE".equals(e.getMaterialStatus()))
                .toList();
        String alias = identityRepository.findById(review.getIdentityId())
                .map(AnonymousIdentity::getAlias).orElse(null);
        String companyName = companyRepository.findById(review.getUscc())
                .map(c -> c.getName()).orElse(null);

        JsonNode tags = review.getTagJson();
        List<String> labels = new ArrayList<>();
        if (tags != null && tags.has("labels")) {
            tags.path("labels").forEach(node -> labels.add(node.asText()));
        }
        String periodStart = tags == null ? null : tags.path("periodStart").asText(null);
        String periodEnd = tags == null ? null : tags.path("periodEnd").asText(null);
        String city = tags == null ? null : tags.path("city").asText(null);

        List<String> aiFlags = new ArrayList<>();
        boolean noAppeal = false;
        if (review.getAiFlags() != null) {
            review.getAiFlags().path("flags").forEach(node -> aiFlags.add(node.asText()));
            noAppeal = review.getAiFlags().path("noAppeal").asBoolean(false);
        }

        RejectInfo reject = rejectInfo(review);
        boolean editable = review.getStatus() == ReviewStatus.PUBLISHED
                && review.getPublishedAt() != null
                && !Instant.now().isAfter(review.getPublishedAt()
                .plus(reviewProperties.getEditWindowDays(), ChronoUnit.DAYS))
                && review.getEditCount() < reviewProperties.getMaxEdits();
        boolean canAppeal = review.getStatus() == ReviewStatus.REJECTED
                && !noAppeal
                && !auditRepository.existsByReviewIdAndAction(review.getId(), "APPEAL");
        int remainingEdits = review.getStatus() == ReviewStatus.PUBLISHED
                ? Math.max(0, reviewProperties.getMaxEdits() - review.getEditCount())
                : 0;

        return new ReviewItemView(
                review.getId(),
                review.getUscc(),
                companyName,
                alias,
                review.getVerificationBadge(),
                review.getTotalScore(),
                review.getContent(),
                dimensions.stream().map(d -> new ReviewItemView.DimensionView(
                        d.getDimensionCode().name(), d.getScore())).toList(),
                labels,
                periodStart,
                periodEnd,
                city,
                evidence.stream().map(e -> new ReviewItemView.EvidenceView(
                        e.getType().name(), e.getMaterialStatus(), e.getMaskedAt())).toList(),
                review.getStatus(),
                review.getAiConfidence(),
                aiFlags,
                review.getAiRulesVersion(),
                review.getPublishedAt(),
                review.getEditDeadline(),
                review.getEditCount(),
                review.getDisputeDeadline(),
                review.isEverDisputed(),
                reject == null ? null : reject.reasonCode(),
                reject == null ? null : reject.message(),
                reject == null ? null : reject.ruleVersion(),
                review.getWithdrawnAt(),
                review.getCreatedAt(),
                review.getStatus() == ReviewStatus.FOLDED,
                review.getStatus() == ReviewStatus.FOLDED
                        ? (review.isEverDisputed()
                        ? "争议未决，双方举证不足，评价已折叠" : "该评价证据不足，已折叠")
                        : null,
                review.getStatus() == ReviewStatus.DISPUTED,
                remainingEdits,
                canAppeal,
                editable,
                approvedResponse(review.getId()));
    }

    /**
     * 点评详情/公司列表展示审核通过的企业回应（F26）。
     */
    private EnterpriseResponseView approvedResponse(UUID reviewId) {
        return enterpriseResponseRepository
                .findFirstByReviewIdAndStatusOrderByCreatedAtDesc(
                        reviewId, EnterpriseResponseStatus.APPROVED)
                .map(response -> EnterpriseResponseView.approved(response.getId(), response.getReviewId(),
                        response.getContent(), response.getCreatedAt()))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 提交链路
    // ------------------------------------------------------------------

    /**
     * 提交机器审核：异步模式事务提交后发 MQ（立即返回 MACHINE_REVIEWING），
     * 同步模式直接调用处理器，保持测试与原接口的同步返回行为。
     */
    private void submitForAiReview(Review review, String ip, String device, boolean isEdit) {
        if (mqProperties.isAsync()) {
            eventPublisher.publishAfterCommit(RabbitConfig.ROUTING_AI_REVIEW,
                    new AiReviewMessage(review.getId(), ip, device, isEdit));
        } else {
            aiProcessor.process(review.getId(), ip, device, isEdit);
        }
    }

    private void fillReview(Review review, UUID userId, Validated validated) {
        AnonymousIdentity identity = identityRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId,
                        com.waibao.pingdian.account.domain.IdentityStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "匿名身份不存在"));
        UserVerification verification = badgeService.latestApprovedFor(userId, validated.uscc()).orElse(null);
        VerificationBadge badge = verification == null ? null : badgeService.badgeOf(verification);

        review.setUscc(validated.uscc());
        review.setUserId(userId);
        if (review.getIdentityId() == null) {
            review.setIdentityId(identity.getId());
        }
        review.setVerificationId(verification == null ? null : verification.getId());
        review.setVerificationBadge(badge);
        applyEditableFields(review, validated);
    }

    private void applyEditableFields(Review review, Validated validated) {
        review.setTotalScore(validated.totalScore());
        review.setContent(validated.content());
        review.setTagJson(validated.tagJson());
    }

    private void replaceDimensions(UUID reviewId, List<DimensionInput> dimensions) {
        dimensionRepository.deleteByReviewId(reviewId);
        // Hibernate 默认先 insert 后 delete，显式 flush 保证旧维度先落库删除，避免 (review_id, code) 唯一约束冲突。
        dimensionRepository.flush();
        for (DimensionInput input : dimensions) {
            ReviewDimension dimension = new ReviewDimension();
            dimension.setReviewId(reviewId);
            dimension.setDimensionCode(input.code());
            dimension.setScore(input.score());
            dimensionRepository.save(dimension);
        }
    }

    private void replaceEvidence(UUID reviewId, List<String> keys) {
        evidenceRepository.deleteByReviewId(reviewId);
        evidenceRepository.flush();
        for (String key : keys) {
            ReviewEvidence evidence = new ReviewEvidence();
            evidence.setReviewId(reviewId);
            evidence.setFileKey(key);
            evidence.setType(EvidenceType.OTHER);
            evidence.setMaterialStatus("ACTIVE");
            evidenceRepository.save(evidence);
        }
    }

    // ------------------------------------------------------------------
    // 重复规则
    // ------------------------------------------------------------------

    private void checkDuplicates(UUID userId, String uscc, String periodStart, String periodEnd, UUID excludeId) {
        List<Review> mine = reviewRepository.findByUserIdAndUsccAndStatusIn(userId, uscc,
                EnumSet.copyOf(PERIOD_BLOCKING));
        for (Review review : mine) {
            if (review.getId().equals(excludeId)) {
                continue;
            }
            if (periodStart.equals(periodOf(review, "periodStart"))
                    && periodEnd.equals(periodOf(review, "periodEnd"))) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reviewId", review.getId());
                details.put("hint", "该在职时间段已发布过评价，请编辑原评价");
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该在职时间段已发布过评价，请编辑原评价", details);
            }
        }

        Instant banUntil = Instant.now().minus(reviewProperties.getWithdrawBanDays(), ChronoUnit.DAYS);
        reviewRepository.findFirstByUserIdAndUsccAndStatusAndWithdrawnAtAfterOrderByWithdrawnAtDesc(
                        userId, uscc, ReviewStatus.WITHDRAWN, banUntil)
                .ifPresent(withdrawn -> {
                    long remaining = remainingDays(
                            withdrawn.getWithdrawnAt().plus(
                            reviewProperties.getWithdrawBanDays(), ChronoUnit.DAYS), Instant.now());
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("remainingDays", remaining);
                    throw new BusinessException(ErrorCode.FORBIDDEN,
                            "撤回后 " + reviewProperties.getWithdrawBanDays() + " 天内不能对同一公司再次发评，还需 "
                                    + remaining + " 天", details);
                });

        List<Review> counted = reviewRepository.findByUserIdAndUsccAndStatusIn(userId, uscc,
                EnumSet.copyOf(COUNTED));
        if (counted.size() >= reviewProperties.getCompanyCap()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "对同一公司累计最多 " + reviewProperties.getCompanyCap() + " 条评价");
        }
        long minRemaining = Long.MAX_VALUE;
        for (Review review : counted) {
            if (review.getPublishedAt() == null) {
                continue;
            }
            Instant allowedAt = review.getPublishedAt()
                    .plus(reviewProperties.getMinIntervalDays(), ChronoUnit.DAYS);
            long remaining = remainingDays(allowedAt, Instant.now());
            if (remaining > 0) {
                minRemaining = Math.min(minRemaining, remaining);
            }
        }
        if (minRemaining != Long.MAX_VALUE) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("remainingDays", minRemaining);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "距上一条评价发布时间不足 " + reviewProperties.getMinIntervalDays()
                            + " 天，还需 " + minRemaining + " 天", details);
        }
    }

    private Review findDraft(UUID userId, String uscc, String periodStart, String periodEnd) {
        if (periodStart == null || periodEnd == null) {
            return null;
        }
        for (Review review : reviewRepository.findByUserIdAndUsccAndStatusIn(userId, uscc,
                EnumSet.of(ReviewStatus.DRAFT))) {
            if (periodStart.equals(periodOf(review, "periodStart"))
                    && periodEnd.equals(periodOf(review, "periodEnd"))) {
                return review;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 校验与解析
    // ------------------------------------------------------------------

    private Validated validateForSubmit(ReviewUpsertRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        String uscc = normalizeUscc(request.uscc());
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            addError(errors, "uscc", "USCC 格式不正确");
        }
        String[] periods = validatePeriod(request.periodStart(), request.periodEnd(), errors);
        int totalScore = request.totalScore() == null ? 0 : request.totalScore();
        if (request.totalScore() == null || totalScore < 1 || totalScore > 5) {
            addError(errors, "totalScore", "总分必须为 1-5 的整数");
        }
        String content = request.content() == null ? "" : request.content();
        int length = codePointLength(content);
        if (length < MIN_CONTENT) {
            addError(errors, "content", "正文至少 " + MIN_CONTENT + " 字");
        } else if (length > MAX_CONTENT) {
            addError(errors, "content", "正文不能超过 " + MAX_CONTENT + " 字");
        }
        List<DimensionInput> dimensions = validateDimensions(request.dimensions(), true, errors);
        List<String> evidenceKeys = validateEvidence(request, errors);
        List<String> labels = parseLabels(request.tags(), errors);
        String city = parseCity(request.tags(), errors);
        throwIfErrors(errors);

        ObjectNode tagJson = buildTagJson(periods[0], periods[1], labels, city);
        return new Validated(uscc, periods[0], periods[1], totalScore, content,
                dimensions, evidenceKeys, tagJson);
    }

    private Validated validateForDraft(ReviewUpsertRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        String uscc = normalizeUscc(request.uscc());
        if (uscc == null || !uscc.matches("^[0-9A-Z]{18}$")) {
            addError(errors, "uscc", "USCC 格式不正确");
        }
        String[] periods = new String[]{null, null};
        if (request.periodStart() != null || request.periodEnd() != null) {
            periods = validatePeriod(request.periodStart(), request.periodEnd(), errors);
        }
        int totalScore = request.totalScore() == null ? 0 : request.totalScore();
        // review.total_score 为 NOT NULL 且限定 1-5，草稿也必须带总分。
        if (request.totalScore() == null || totalScore < 1 || totalScore > 5) {
            addError(errors, "totalScore", "总分必须为 1-5 的整数");
        }
        String content = request.content() == null ? "" : request.content();
        if (codePointLength(content) > MAX_CONTENT) {
            addError(errors, "content", "正文不能超过 " + MAX_CONTENT + " 字");
        }
        List<DimensionInput> dimensions = validateDimensions(request.dimensions(), false, errors);
        List<String> evidenceKeys = validateEvidence(request, errors);
        List<String> labels = parseLabels(request.tags(), errors);
        String city = parseCity(request.tags(), errors);
        throwIfErrors(errors);

        ObjectNode tagJson = buildTagJson(periods[0], periods[1], labels, city);
        return new Validated(uscc, periods[0], periods[1], totalScore, content,
                dimensions, evidenceKeys, tagJson);
    }

    private String[] validatePeriod(String start, String end, List<Map<String, String>> errors) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            addError(errors, "periodStart", "在职时间段不能为空");
            return new String[]{null, null};
        }
        try {
            YearMonth startMonth = YearMonth.parse(start.trim());
            YearMonth endMonth = YearMonth.parse(end.trim());
            if (endMonth.isBefore(startMonth)) {
                addError(errors, "periodEnd", "结束月份不能早于开始月份");
            }
            return new String[]{startMonth.toString(), endMonth.toString()};
        } catch (DateTimeParseException ex) {
            addError(errors, "periodStart", "月份格式应为 YYYY-MM");
            return new String[]{null, null};
        }
    }

    private List<DimensionInput> validateDimensions(List<ReviewUpsertRequest.DimensionRequest> raw,
                                                    boolean required, List<Map<String, String>> errors) {
        if (raw == null || raw.isEmpty()) {
            if (required) {
                addError(errors, "dimensions", "至少选择 3 个维度");
            }
            return List.of();
        }
        if (raw.size() > 6) {
            addError(errors, "dimensions", "维度不能超过 6 项");
        }
        if (required && raw.size() < 3) {
            addError(errors, "dimensions", "至少选择 3 个维度");
        }
        List<DimensionInput> result = new ArrayList<>();
        Set<DimensionCode> seen = new LinkedHashSet<>();
        for (ReviewUpsertRequest.DimensionRequest dim : raw) {
            if (dim == null) {
                continue;
            }
            try {
                DimensionCode code = DimensionCode.from(dim.code());
                if (!seen.add(code)) {
                    addError(errors, "dimensions", "维度重复：" + code.name());
                    continue;
                }
                if (dim.score() == null || dim.score() < 1 || dim.score() > 5) {
                    addError(errors, "dimensions", "维度分值必须为 1-5");
                    continue;
                }
                result.add(new DimensionInput(code, dim.score()));
            } catch (BusinessException ex) {
                addError(errors, "dimensions", ex.getMessage());
            }
        }
        return result;
    }

    private List<String> validateEvidence(ReviewUpsertRequest request, List<Map<String, String>> errors) {
        List<String> keys = new ArrayList<>();
        if (request.evidenceFileKey() != null && !request.evidenceFileKey().isBlank()) {
            keys.add(request.evidenceFileKey().trim());
        }
        if (request.evidenceFileKeys() != null) {
            request.evidenceFileKeys().stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::trim)
                    .forEach(keys::add);
        }
        if (keys.size() > 5) {
            addError(errors, "evidenceFileKeys", "佐证材料最多 5 个");
        }
        for (String key : keys) {
            if (key.length() > 512) {
                addError(errors, "evidenceFileKeys", "佐证文件标识过长");
            }
        }
        return keys;
    }

    private List<String> parseLabels(JsonNode tags, List<Map<String, String>> errors) {
        List<String> labels = new ArrayList<>();
        if (tags == null || tags.isNull()) {
            return labels;
        }
        if (tags.isArray()) {
            tags.forEach(node -> addLabel(node.asText(), labels, errors));
            return labels;
        }
        if (tags.isObject()) {
            String outsourcing = tags.path("outsourcingType").asText(null);
            if (outsourcing != null) {
                if (!OUTSOURCING_TYPES.contains(outsourcing)) {
                    addError(errors, "tags.outsourcingType", "外包类型不在允许范围");
                } else {
                    addLabel(outsourcing, labels, errors);
                }
            }
            String projectType = tags.path("projectType").asText(null);
            if (projectType != null) {
                if (!PROJECT_TYPES.contains(projectType)) {
                    addError(errors, "tags.projectType", "项目类型不在允许范围");
                } else {
                    addLabel(projectType, labels, errors);
                }
            }
            if (tags.has("labels")) {
                tags.path("labels").forEach(node -> addLabel(node.asText(), labels, errors));
            }
            return labels;
        }
        addError(errors, "tags", "标签格式不正确");
        return labels;
    }

    private String parseCity(JsonNode tags, List<Map<String, String>> errors) {
        if (tags == null || !tags.isObject()) {
            return null;
        }
        String city = tags.path("city").asText(null);
        if (city == null) {
            return null;
        }
        city = city.trim();
        if (city.length() > 20) {
            addError(errors, "tags.city", "城市名称过长");
        }
        return city.isEmpty() ? null : city;
    }

    private void addLabel(String value, List<String> labels, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 20) {
            addError(errors, "tags", "标签过长");
            return;
        }
        if (trimmed.contains("项目")) {
            addError(errors, "tags", "标签不能包含具体项目名称");
            return;
        }
        labels.add(trimmed);
    }

    private ObjectNode buildTagJson(String periodStart, String periodEnd, List<String> labels, String city) {
        ObjectNode node = objectMapper.createObjectNode();
        if (periodStart != null) {
            node.put("periodStart", periodStart);
        }
        if (periodEnd != null) {
            node.put("periodEnd", periodEnd);
        }
        if (city != null) {
            node.put("city", city);
        }
        ArrayNode labelArray = node.putArray("labels");
        if (labels != null) {
            labels.forEach(labelArray::add);
        }
        return node;
    }

    private ReviewUpsertRequest mergeForUpdate(ReviewUpsertRequest request, Review review) {
        return new ReviewUpsertRequest(
                review.getId(),
                review.getUscc(),
                periodOf(review, "periodStart"),
                periodOf(review, "periodEnd"),
                request.totalScore(),
                request.content(),
                request.dimensions(),
                request.evidenceFileKey(),
                request.evidenceFileKeys(),
                request.tags() != null ? request.tags() : review.getTagJson());
    }

    private ReviewUpsertRequest toRequest(Review review) {
        List<ReviewUpsertRequest.DimensionRequest> dimensions =
                dimensionRepository.findByReviewIdOrderByDimensionCode(review.getId()).stream()
                        .map(d -> new ReviewUpsertRequest.DimensionRequest(
                                d.getDimensionCode().name(), d.getScore()))
                        .toList();
        List<String> evidenceKeys = evidenceRepository.findByReviewId(review.getId()).stream()
                .filter(e -> "ACTIVE".equals(e.getMaterialStatus()))
                .map(ReviewEvidence::getFileKey)
                .toList();
        return new ReviewUpsertRequest(
                review.getId(), review.getUscc(),
                periodOf(review, "periodStart"), periodOf(review, "periodEnd"),
                review.getTotalScore(), review.getContent(), dimensions,
                null, evidenceKeys, review.getTagJson());
    }

    private Review requireAuthorReview(UUID reviewId, UUID userId) {
        return reviewRepository.findById(reviewId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "点评不存在"));
    }

    private void requireCompany(String uscc) {
        if (!companyRepository.existsById(uscc)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "公司档案不存在");
        }
    }

    private RejectInfo rejectInfo(Review review) {
        if (review.getStatus() != ReviewStatus.REJECTED) {
            return null;
        }
        for (ReviewAudit audit : auditRepository.findByReviewIdOrderByCreatedAtDesc(review.getId())) {
            if (audit.getAction() != null && audit.getAction().endsWith("REJECT")) {
                String code = audit.getReasonCode();
                String message = null;
                String rule = null;
                if (audit.getDetail() != null) {
                    try {
                        JsonNode detail = objectMapper.readTree(audit.getDetail());
                        code = detail.path("reasonCode").asText(code);
                        message = detail.path("message").asText(null);
                        rule = detail.path("ruleVersion").asText(null);
                    } catch (Exception ignored) {
                        // detail 非 JSON 时保留 reasonCode。
                    }
                }
                return new RejectInfo(code, message, rule);
            }
        }
        return new RejectInfo(null, null, null);
    }

    /**
     * 从最近一次 REJECT 审计 detail 判定是否不可申诉；缺失时回退到 ai_flags。
     */
    private boolean noAppealOf(Review review) {
        for (ReviewAudit audit : auditRepository.findByReviewIdOrderByCreatedAtDesc(review.getId())) {
            if (audit.getAction() == null || !audit.getAction().endsWith("REJECT")) {
                continue;
            }
            String reasonCode = audit.getReasonCode();
            if (audit.getDetail() != null) {
                try {
                    JsonNode detail = objectMapper.readTree(audit.getDetail());
                    if (detail.has("noAppeal")) {
                        return detail.path("noAppeal").asBoolean(false);
                    }
                    if (detail.has("reasonCode")) {
                        reasonCode = detail.path("reasonCode").asText(reasonCode);
                    }
                } catch (Exception ignored) {
                    // detail 非 JSON 时回退到 reason_code。
                }
            }
            if (reasonCode != null) {
                String code = reasonCode.trim().toUpperCase();
                if ("PRIVACY".equals(code) || "ADVERTISING".equals(code) || "AD".equals(code)) {
                    return true;
                }
            }
            break;
        }
        return review.getAiFlags() != null && review.getAiFlags().path("noAppeal").asBoolean(false);
    }

    private String periodOf(Review review, String key) {
        return review.getTagJson() == null ? null : review.getTagJson().path(key).asText(null);
    }

    private String normalizeUscc(String uscc) {
        return uscc == null ? null : uscc.trim().toUpperCase();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static long remainingDays(Instant allowedAt, Instant now) {
        long seconds = Duration.between(now, allowedAt).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (seconds + 86399) / 86400;
    }

    private static void addError(List<Map<String, String>> errors, String field, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("message", message);
        errors.add(error);
    }

    private static void throwIfErrors(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("errors", errors);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求参数错误", details);
        }
    }

    private record DimensionInput(DimensionCode code, int score) {
    }

    private record Validated(String uscc, String periodStart, String periodEnd, int totalScore,
                             String content, List<DimensionInput> dimensions,
                             List<String> evidenceKeys, ObjectNode tagJson) {
    }

    private record RejectInfo(String reasonCode, String message, String ruleVersion) {
    }
}
