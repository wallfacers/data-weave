package sync

import (
	"encoding/json"
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/dataweave/dw/client"
)

// DiffOpts dw diff 选项。
type DiffOpts struct {
	WorkDir string // 工作副本根（默认 cwd）
}

// RunDiff 执行 dw diff（FR-003，只读）：读本地文件树 + state.baseline → POST /api/projects/{id}/diff
// → 呈现 added/modified/removed + stale 提示。对服务器零写入（diff 端点不落库）。
func RunDiff(opts DiffOpts, cfg client.Config) error {
	workDir := resolveWorkDir(opts.WorkDir)
	state, err := LoadState(workDir)
	if err != nil {
		return err
	}
	files, err := TreeToFiles(workDir)
	if err != nil {
		return err
	}
	cmd := pushCommand{
		Files:    files,
		Baseline: state.Baseline,
		// 意图文件数 = 本次实际文件数（同 push；diff 只读、服务端当前不强校验，
		// 但保持与 push 一致语义，避免 state.FileCount 陈旧基线数的同源隐患）。
		ExpectedFileCount: len(files),
	}
	body, _ := json.Marshal(cmd)
	data, err := client.Do(cfg, "POST", fmt.Sprintf("/api/projects/%d/diff", state.ProjectID), body)
	if err != nil {
		return err
	}
	var dp diffPreview
	if err := json.Unmarshal(data, &dp); err != nil {
		return client.UsageError("解析 diff 响应失败：%v", err)
	}
	printDiff(dp)
	if dp.Stale {
		fmt.Fprintln(os.Stderr, "提示：服务器侧自上次 pull 后已变更（基线过期），建议先 dw pull 或 dw push --force")
	}
	return nil
}

// printDiff 以表格呈现三态差异（added/modified/removed）；无差异时打印一致提示。
func printDiff(dp diffPreview) {
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "状态\t类型\t标识\t名称")
	printDiffRows(w, "新增", dp.Added)
	printDiffRows(w, "修改", dp.Modified)
	printDiffRows(w, "删除", dp.Removed)
	_ = w.Flush()
	if len(dp.Added)+len(dp.Modified)+len(dp.Removed) == 0 {
		fmt.Println("（本地与服务器一致，无差异）")
	}
}

func printDiffRows(w *tabwriter.Writer, label string, rows []entityRef) {
	for _, r := range rows {
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", label, r.EntityType, r.Identity, r.DisplayName)
	}
}
