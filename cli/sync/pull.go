package sync

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/dataweave/dw/client"
)

// PullOpts dw pull 选项。
type PullOpts struct {
	WorkDir string // 工作副本根（默认 cwd）
	Project string // 项目 id 或 code（code 经 GET /api/projects?search= 解析）
	Force   bool   // 非空目录允许覆盖同名文件
	Clean   bool   // 先清空工作副本所有内容（含 .weft/）再写
}

// RunPull 执行 dw pull（FR-001）：POST /api/projects/{id}/pull → 落地文件树 + 写 state。
// 目标目录非空默认拒绝（D7 安全默认），--force 覆盖、--clean 清空。越权/无效令牌透传非0退出码。
func RunPull(opts PullOpts, cfg client.Config) error {
	workDir := resolveWorkDir(opts.WorkDir)
	id, code, err := ResolveProjectID(cfg, opts.Project)
	if err != nil {
		return err
	}
	if err := preparePullTarget(workDir, opts.Force, opts.Clean); err != nil {
		return err
	}
	data, err := client.Do(cfg, "POST", fmt.Sprintf("/api/projects/%d/pull", id), nil)
	if err != nil {
		return err
	}
	var pr pullResult
	if err := json.Unmarshal(data, &pr); err != nil {
		return client.UsageError("解析 pull 响应失败：%v", err)
	}
	if pr.Bundle.Files == nil {
		pr.Bundle.Files = map[string]string{}
	}
	if err := FilesToTree(workDir, pr.Bundle.Files); err != nil {
		return err
	}
	state := &State{
		APIBase:     cfg.APIBase,
		ProjectID:   pr.ProjectIDInt(),
		ProjectCode: code,
		Baseline:    pr.Baseline,
		PulledAt:    nowRFC3339(),
		FileCount:   pr.FileCount,
	}
	if err := SaveState(workDir, state); err != nil {
		return err
	}
	fmt.Printf("已拉取项目 #%d（%s）：%d 个文件，基线 %s\n",
		pr.ProjectIDInt(), orStr(code, "code 未知"), pr.FileCount, pr.Baseline)
	return nil
}

// preparePullTarget 落地前的工作副本策略（D7 安全默认，pull 目标非空 Edge Case）：
//   - 默认：工作副本须为空（仅可有 .weft/），否则拒绝；
//   - --force：跳过非空检查，直接覆盖同名文件（不清除多余文件）；
//   - --clean：清空工作副本所有内容（含 .weft/）后再写。
func preparePullTarget(workDir string, force, clean bool) error {
	if clean {
		return cleanWorkDir(workDir)
	}
	if force {
		return nil
	}
	empty, err := isWorkDirEmpty(workDir)
	if err != nil {
		return err
	}
	if !empty {
		return client.UsageError("目标目录非空（%s）：加 --force 覆盖同名文件，或 --clean 清空后拉取", workDir)
	}
	return nil
}

// isWorkDirEmpty 报告目录是否只含 .weft/（或完全为空/不存在）。含其它任何条目即视为非空。
func isWorkDirEmpty(workDir string) (bool, error) {
	entries, err := os.ReadDir(workDir)
	if err != nil {
		if os.IsNotExist(err) {
			return true, nil
		}
		return false, err
	}
	for _, e := range entries {
		if e.Name() == WeftDirName {
			continue
		}
		return false, nil
	}
	return true, nil
}

// cleanWorkDir 删除工作副本下所有条目（含 .weft/），保留目录本身。拒绝清理根 / 或 HOME（防误删）。
func cleanWorkDir(workDir string) error {
	abs, err := filepath.Abs(workDir)
	if err != nil {
		return client.UsageError("解析工作目录失败：%v", err)
	}
	if abs == "/" || abs == "" {
		return client.UsageError("拒绝清空根目录")
	}
	if home, hErr := os.UserHomeDir(); hErr == nil && home != "" && abs == home {
		return client.UsageError("拒绝清空 HOME 目录（%s），请指定子目录", home)
	}
	entries, err := os.ReadDir(abs)
	if err != nil {
		if os.IsNotExist(err) {
			return os.MkdirAll(abs, 0o755)
		}
		return err
	}
	for _, e := range entries {
		if err := os.RemoveAll(filepath.Join(abs, e.Name())); err != nil {
			return fmt.Errorf("清空 %s 失败：%w", e.Name(), err)
		}
	}
	return nil
}
