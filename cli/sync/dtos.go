package sync

import "encoding/json"

// C 端点 DTO 的 Go 镜像（见 backend com.dataweave.master.application.ProjectSyncDtos）。
// 仅本包 pull/push/diff 命令用于 JSON 序列化/反序列化——CLI 不解析 B 的文件契约，
// 这些 DTO 只搬运原始字节（files{path→content}）。

// cBundle PullResult.bundle：相对路径 → UTF-8 内容。
type cBundle struct {
	Files map[string]string `json:"files"`
}

// pullResult 对应 PullResult{projectId, bundle{files}, baseline, fileCount}。
type pullResult struct {
	ProjectID json.Number `json:"projectId"` // 容忍数字/字符串
	Bundle    cBundle     `json:"bundle"`
	Baseline  string      `json:"baseline"`
	FileCount int         `json:"fileCount"`
}

// ProjectIDInt 解析 projectId 为 int64（容忍 JSON number/string）。
func (r pullResult) ProjectIDInt() int64 {
	n, err := r.ProjectID.Int64()
	if err == nil {
		return n
	}
	return 0
}

// pushCommand 对应 PushCommand{files, baseline, force, expectedFileCount, remark}。
type pushCommand struct {
	Files             map[string]string `json:"files"`
	Baseline          string            `json:"baseline"`
	Force             bool              `json:"force"`
	ExpectedFileCount int               `json:"expectedFileCount"`
	Remark            string            `json:"remark"`
}

// counts 对应 Counts{task, workflow, catalog, tag}。
type counts struct {
	Task     int `json:"task"`
	Workflow int `json:"workflow"`
	Catalog  int `json:"catalog"`
	Tag      int `json:"tag"`
}

// Total 实体总数。
func (c counts) Total() int { return c.Task + c.Workflow + c.Catalog + c.Tag }

type snapshotRef struct {
	EntityType string `json:"entityType"`
	EntityID   int64  `json:"entityId"`
	Name       string `json:"name"`
	VersionNo  int    `json:"versionNo"`
}

// pushResult 对应 PushResult{projectId, created, updated, deleted, snapshots, newBaseline}。
type pushResult struct {
	ProjectID   int64         `json:"projectId"`
	Created     counts        `json:"created"`
	Updated     counts        `json:"updated"`
	Deleted     counts        `json:"deleted"`
	Snapshots   []snapshotRef `json:"snapshots"`
	NewBaseline string        `json:"newBaseline"`
}

type entityRef struct {
	EntityType  string `json:"entityType"`
	Identity    string `json:"identity"`
	DisplayName string `json:"displayName"`
}

// diffPreview 对应 DiffPreview{added, modified, removed, stale}（只读，零写入）。
type diffPreview struct {
	Added    []entityRef `json:"added"`
	Modified []entityRef `json:"modified"`
	Removed  []entityRef `json:"removed"`
	Stale    bool        `json:"stale"`
}
