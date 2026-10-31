package com.waibao.pingdian.company.web.dto;

import java.time.Instant;

public record SitemapEntry(String uscc, String name, Instant updatedAt) {
}
