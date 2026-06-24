package com.dataweave.api.interfaces.dto;

import java.util.List;

/**
 * 分页结果容器。
 */
public record Page<T>(List<T> items, long total, int page, int size) {}
