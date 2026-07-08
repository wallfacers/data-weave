// Package authctx 渲染 058 数据开发 LSP 的创作上下文/依赖视图（dw context / dw deps）。
// 目录未用 "context" 以避与 stdlib 同名包冲突。纯格式化 + DTO，HTTP 由 main.go 薄壳承担，
// 便于对输出契约做单测（T017）。
package authctx

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Context 对齐后端 AuthoringContext（application/authoring/AuthoringContext.java）。
type Context struct {
	TaskRef       string       `json:"taskRef"`
	Reads         []TableFact  `json:"reads"`
	Writes        []TableFact  `json:"writes"`
	ColumnLineage []ColumnEdge `json:"columnLineage"`
	DepthUsed     int          `json:"depthUsed"`
	Truncated     []TruncNote  `json:"truncated"`
	Partial       []MissNote   `json:"partial"`
}

type TableFact struct {
	Table          string    `json:"table"`
	Datasource     string    `json:"datasource"`
	Direction      string    `json:"direction"`
	Neighbors      []NodeRef `json:"neighbors"`
	GroundingState string    `json:"groundingState"`
	Source         string    `json:"source"`
}

type NodeRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
	Hop  int    `json:"hop"`
}

type ColumnEdge struct {
	SrcTable  string `json:"srcTable"`
	SrcColumn string `json:"srcColumn"`
	DstTable  string `json:"dstTable"`
	DstColumn string `json:"dstColumn"`
}

type TruncNote struct {
	At     string `json:"at"`
	Reason string `json:"reason"`
}

type MissNote struct {
	Source string `json:"source"`
	Reason string `json:"reason"`
}

// FormatContext 把创作上下文 JSON 渲染为人读摘要（供 agent 编码前速览意图链路）。
func FormatContext(raw []byte) (string, error) {
	var c Context
	if err := json.Unmarshal(raw, &c); err != nil {
		return "", fmt.Errorf("解析创作上下文失败：%w", err)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "任务 %s（深度 %d）\n", c.TaskRef, c.DepthUsed)

	fmt.Fprintf(&b, "读表（%d）：\n", len(c.Reads))
	for _, t := range c.Reads {
		writeTableFact(&b, t, "上游")
	}
	fmt.Fprintf(&b, "写表（%d）：\n", len(c.Writes))
	for _, t := range c.Writes {
		writeTableFact(&b, t, "下游")
	}

	fmt.Fprintf(&b, "列血缘（%d 条）\n", len(c.ColumnLineage))
	for _, e := range c.ColumnLineage {
		fmt.Fprintf(&b, "  %s.%s → %s.%s\n", e.SrcTable, e.SrcColumn, e.DstTable, e.DstColumn)
	}

	for _, n := range c.Truncated {
		fmt.Fprintf(&b, "⚠ 截断 %s：%s\n", n.At, n.Reason)
	}
	for _, n := range c.Partial {
		fmt.Fprintf(&b, "⚠ 缺失 %s：%s\n", n.Source, n.Reason)
	}
	return b.String(), nil
}

func writeTableFact(b *strings.Builder, t TableFact, nbLabel string) {
	fmt.Fprintf(b, "  %s [%s]", t.Table, t.GroundingState)
	if len(t.Neighbors) > 0 {
		parts := make([]string, len(t.Neighbors))
		for i, n := range t.Neighbors {
			parts[i] = fmt.Sprintf("%s(%d)", n.Name, n.Hop)
		}
		fmt.Fprintf(b, "  %s: %s", nbLabel, strings.Join(parts, ", "))
	}
	b.WriteString("\n")
}

// Deps 对齐后端 TaskDependencyView。
type Deps struct {
	TaskRef    string     `json:"taskRef"`
	Upstream   []DepEdge  `json:"upstream"`
	Downstream []DepEdge  `json:"downstream"`
}

type DepEdge struct {
	FromTaskRef string `json:"fromTaskRef"`
	ToTaskRef   string `json:"toTaskRef"`
	Hop         int    `json:"hop"`
	Origin      string `json:"origin"`
}

// FormatDeps 渲染依赖视图（声明/推导带 origin，揭示一致或背离）。
func FormatDeps(raw []byte) (string, error) {
	var d Deps
	if err := json.Unmarshal(raw, &d); err != nil {
		return "", fmt.Errorf("解析依赖视图失败：%w", err)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "任务 %s 依赖\n", d.TaskRef)
	fmt.Fprintf(&b, "上游（%d）：\n", len(d.Upstream))
	for _, e := range d.Upstream {
		fmt.Fprintf(&b, "  %s → %s [%s] (跳距 %d)\n", e.FromTaskRef, e.ToTaskRef, e.Origin, e.Hop)
	}
	fmt.Fprintf(&b, "下游（%d）：\n", len(d.Downstream))
	for _, e := range d.Downstream {
		fmt.Fprintf(&b, "  %s → %s [%s] (跳距 %d)\n", e.FromTaskRef, e.ToTaskRef, e.Origin, e.Hop)
	}
	return b.String(), nil
}
