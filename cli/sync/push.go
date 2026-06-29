package sync

import (
	"encoding/json"
	"fmt"

	"github.com/dataweave/dw/client"
)

// PushOpts dw push 选项。
type PushOpts struct {
	WorkDir string // 工作副本根（默认 cwd）
	Force   bool   // 基线过期时强制覆盖（FR-004）
	Remark  string // 快照备注（默认 "dw push"）
}

// RunPush 执行 dw push（FR-002/004）：读本地文件树 + state.baseline → POST /api/projects/{id}/push
// （携带 baseline 做乐观并发）→ 报告 created/updated/deleted 统计，把 newBaseline 写回 state。
// 基线过期且无 --force → C 返回 project.sync.stale → 非0退出（ExitServer）；--force 覆盖。
// 校验失败（未知数据源、删除 ONLINE 引用等）→ 透传 C 可定位错误码，不部分落库。
func RunPush(opts PushOpts, cfg client.Config) error {
	workDir := resolveWorkDir(opts.WorkDir)
	state, err := LoadState(workDir)
	if err != nil {
		return err
	}
	files, err := TreeToFiles(workDir)
	if err != nil {
		return err
	}
	remark := opts.Remark
	if remark == "" {
		remark = "dw push"
	}
	cmd := pushCommand{
		Files:             files,
		Baseline:          state.Baseline,
		Force:             opts.Force,
		ExpectedFileCount: state.FileCount,
		Remark:            remark,
	}
	body, err := json.Marshal(cmd)
	if err != nil {
		return client.UsageError("序列化 push 请求失败：%v", err)
	}
	data, err := client.Do(cfg, "POST", fmt.Sprintf("/api/projects/%d/push", state.ProjectID), body)
	if err != nil {
		// FR-018：基线过期 → 可读提示（说明原因 + 推荐处置），服务端检测不动
		if ee, ok := err.(*client.ExitError); ok && ee.ErrorCode == "project.sync.stale" {
			return &client.ExitError{Code: ee.Code, ErrorCode: ee.ErrorCode,
				Message: fmt.Sprintf("本地基线过期（%s）：他人可能已推送变更。"+
					"推荐处置：① dw pull 拉取最新后再 dw push；② 若确认覆盖他人变更，dw push --force。", ee.Message)}
		}
		// 其他错误 → 直接透传
		return err
	}
	var pr pushResult
	if err := json.Unmarshal(data, &pr); err != nil {
		return client.UsageError("解析 push 响应失败：%v", err)
	}
	// 成功：写回新基线，刷新 fileCount 为当前文件数。
	state.Baseline = pr.NewBaseline
	state.FileCount = len(files)
	if err := SaveState(workDir, state); err != nil {
		return err
	}
	fmt.Printf("已推送项目 #%d：新增 %d / 更新 %d / 删除 %d，生成 %d 个版本快照，新基线 %s\n",
		pr.ProjectID, pr.Created.Total(), pr.Updated.Total(), pr.Deleted.Total(),
		len(pr.Snapshots), pr.NewBaseline)
	return nil
}
