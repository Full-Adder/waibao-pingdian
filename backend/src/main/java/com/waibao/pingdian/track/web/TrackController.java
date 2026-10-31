package com.waibao.pingdian.track.web;

import com.waibao.pingdian.track.service.VisitTrackingService;
import com.waibao.pingdian.track.web.dto.TrackVisitRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开访问埋点（R18）。
 */
@RestController
@RequestMapping("/api/track")
public class TrackController {

    private final VisitTrackingService visitTrackingService;

    public TrackController(VisitTrackingService visitTrackingService) {
        this.visitTrackingService = visitTrackingService;
    }

    @PostMapping("/visit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trackVisit(@Valid @RequestBody TrackVisitRequest request) {
        visitTrackingService.record(request);
    }
}
