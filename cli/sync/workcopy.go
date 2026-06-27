// Package sync 实现 dw CLI 的本地工作副本往返同步（pull/push/diff）。
//
// 工作副本约定：开发者本地目录，承载 C 的文件契约文件集 + 一份 git-ignored 的
// `.weft/` 隐藏状态目录。CLI 只搬运原始字节（files{相对路径→UTF-8 content}），
// 不解析 B 的文件契约格式——序列化/反序列化全在服务器 C 侧（D2 边界）。
//
// 状态目录（一个工作副本根一份，全量 git-ignored）：
//
//	<workDir>/.weft/state.json            上次 pull 的 projectId + 基线令牌（push 乐观并发）
//	<workDir>/.weft/datasources.local.yaml 本地数据源连接配置（凭据，绝不上行，见 run 包）
package sync

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// 本地工作副本约定路径（相对工作副本根）。
const (
	WeftDirName        = ".weft"
	StateFileName      = "state.json"
	DatasourceFileName = "datasources.local.yaml"
)

// State 对应 `.weft/state.json`：记录上次 pull 的基线令牌与 projectId。
// git-ignored，不入服务器契约；仅供 push 乐观并发与后续命令定位目标项目。
type State struct {
	APIBase     string `json:"apiBase"`               // 拉取时的服务器地址（DW_API）
	ProjectID   int64  `json:"projectId"`             // push/diff/run --test 的目标项目 id
	ProjectCode string `json:"projectCode,omitempty"` // 项目 code（人类可读，可空）
	Baseline    string `json:"baseline"`              // C PullResult.baseline（SHA256 前 16 hex）
	PulledAt    string `json:"pulledAt"`              // 拉取时刻 RFC3339（UTC）
	FileCount   int    `json:"fileCount"`             // C PullResult.fileCount，push 作 expectedFileCount 校验
}

// WeftDir 返回工作副本根下的 `.weft` 绝对路径。
func WeftDir(workDir string) string { return filepath.Join(workDir, WeftDirName) }

// StatePath 返回 `.weft/state.json` 绝对路径。
func StatePath(workDir string) string { return filepath.Join(WeftDir(workDir), StateFileName) }

// DatasourcePath 返回 `.weft/datasources.local.yaml` 绝对路径。
func DatasourcePath(workDir string) string { return filepath.Join(WeftDir(workDir), DatasourceFileName) }

// LoadState 读取 `.weft/state.json`。文件缺失或损坏返回可定位错误（调用方据此提示先 pull）。
func LoadState(workDir string) (*State, error) {
	b, err := os.ReadFile(StatePath(workDir))
	if err != nil {
		return nil, fmt.Errorf("读取工作副本状态失败（%s）：%w；请先 dw pull <project>", StatePath(workDir), err)
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("解析工作副本状态失败（%s）：%w", StatePath(workDir), err)
	}
	if s.ProjectID == 0 {
		return nil, fmt.Errorf("工作副本状态无效（%s）：缺少 projectId", StatePath(workDir))
	}
	return &s, nil
}

// SaveState 写 `.weft/state.json`（必要时创建 `.weft/`）。原子写：先写临时文件再 rename。
func SaveState(workDir string, s *State) error {
	dir := WeftDir(workDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("创建状态目录失败（%s）：%w", dir, err)
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return fmt.Errorf("序列化状态失败：%w", err)
	}
	tmp, err := os.CreateTemp(dir, ".state-*.json")
	if err != nil {
		return fmt.Errorf("写状态临时文件失败：%w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // rename 成功后此 remove 命中已不存在的文件，无副作用
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return fmt.Errorf("写状态文件失败：%w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭状态临时文件失败：%w", err)
	}
	if err := os.Rename(tmpName, StatePath(workDir)); err != nil {
		return fmt.Errorf("落盘状态文件失败：%w", err)
	}
	return nil
}

// FilesToTree 把 `files{相对路径→content}` 落地为工作副本文件树（pull 用）。
// 相对路径用 `/` 分隔（POSIX 契约）；自动创建中间目录。拒绝路径逃逸（`..`/绝对路径）。
func FilesToTree(workDir string, files map[string]string) error {
	absRoot, err := filepath.Abs(workDir)
	if err != nil {
		return fmt.Errorf("解析工作目录失败：%w", err)
	}
	// 按 path 排序，便于确定性 + 早期发现逃逸。
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		if p == "" {
			continue
		}
		// 规范化并校验落在 workDir 内（防 ../ 越狱）。
		cleaned := filepath.Clean(filepath.FromSlash(p))
		if filepath.IsAbs(cleaned) || strings.HasPrefix(cleaned, ".."+string(filepath.Separator)) || cleaned == ".." {
			return fmt.Errorf("非法文件路径（逃逸工作目录）：%q", p)
		}
		abs := filepath.Join(absRoot, cleaned)
		if !within(absRoot, abs) {
			return fmt.Errorf("非法文件路径（逃逸工作目录）：%q", p)
		}
		if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
			return fmt.Errorf("创建目录失败（%s）：%w", filepath.Dir(abs), err)
		}
		if err := os.WriteFile(abs, []byte(files[p]), 0o644); err != nil {
			return fmt.Errorf("写文件失败（%s）：%w", abs, err)
		}
	}
	return nil
}

// TreeToFiles 收集工作副本文件树为 `files{相对路径→content}`（push/diff 用），排除 `.weft/`。
// 相对路径用 `/` 分隔（POSIX 契约）。空目录不产生条目。
func TreeToFiles(workDir string) (map[string]string, error) {
	absRoot, err := filepath.Abs(workDir)
	if err != nil {
		return nil, fmt.Errorf("解析工作目录失败：%w", err)
	}
	out := make(map[string]string)
	err = filepath.WalkDir(absRoot, func(path string, d os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if d.IsDir() {
			if path == absRoot {
				return nil
			}
			// 整棵跳过 .weft/
			if d.Name() == WeftDirName {
				return filepath.SkipDir
			}
			return nil
		}
		rel, err := filepath.Rel(absRoot, path)
		if err != nil {
			return err
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return fmt.Errorf("读文件失败（%s）：%w", path, err)
		}
		out[filepath.ToSlash(rel)] = string(b)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// within 报告 target 是否在 root 目录树内（含 root 自身）。
func within(root, target string) bool {
	rel, err := filepath.Rel(root, target)
	if err != nil {
		return false
	}
	if rel == "." {
		return true
	}
	return !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != ".."
}
