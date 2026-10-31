package com.waibao.pingdian.common;

import java.util.List;

/**
 * 统一分页结构。
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(items, page, size, total);
    }

    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) ((total + size - 1) / size);
    }
}
