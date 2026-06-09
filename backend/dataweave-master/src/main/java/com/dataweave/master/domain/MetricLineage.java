package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 指标血缘边：指标 -> 下游（物理表 / SQL / 报表）。MVP 至少支持「指标 -> 来源物理表」。
 */
@Table("metric_lineage")
public class MetricLineage {

    @Id
    private Long id;
    private Long metricId;
    private String downstreamType;
    private String downstreamId;

    public MetricLineage() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMetricId() {
        return metricId;
    }

    public void setMetricId(Long metricId) {
        this.metricId = metricId;
    }

    public String getDownstreamType() {
        return downstreamType;
    }

    public void setDownstreamType(String downstreamType) {
        this.downstreamType = downstreamType;
    }

    public String getDownstreamId() {
        return downstreamId;
    }

    public void setDownstreamId(String downstreamId) {
        this.downstreamId = downstreamId;
    }
}
