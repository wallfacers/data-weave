package com.dataweave.master.application;

import java.util.List;
import java.util.Map;

/**
 * 通用查询结果：列名 + 行（每行是有序 map）。供前端做表格渲染。
 */
public record QueryResult(List<String> columns, List<Map<String, Object>> rows) {
}
