package com.waibao.pingdian.ranking.web;

import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.ranking.service.RankingService;
import com.waibao.pingdian.ranking.web.dto.RankingPageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开双榜与历史快照查询。
 */
@RestController
@RequestMapping("/api/rankings")
public class RankingController {

    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping
    public ApiResponse<RankingPageView> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(rankingService.rank(city, industry, type, date, limit));
    }

    @GetMapping("/history")
    public ApiResponse<RankingPageView> history(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String date) {
        return ApiResponse.ok(rankingService.history(city, industry, type, date));
    }
}
