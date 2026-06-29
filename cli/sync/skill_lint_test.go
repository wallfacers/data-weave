package sync

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// skillLintTest 校验 SKILL.md 引用的 dw 子命令/flag 在 CLI 中真实存在（FR-027）。
// 不一致即 test fail —— 防止文档-实现漂移。

// dw 实际命令表（来自 cli/main.go switch + 子命令分支）。
var dwCommands = map[string]bool{
	// 顶层命令
	"task":    true,
	"logs":    true,
	"pull":    true,
	"push":    true,
	"diff":    true,
	"run":     true,
	"version": true,
	"help":    true,
}

// dw 子命令表（subcommand → parent）。
var dwSubcommands = map[string]string{
	"list":      "task",
	"show":      "task",
	"instances": "task",
	"rerun":     "task",
	"cat":       "logs",
}

// dw flag 表（flag → 适用的命令）。
var dwFlags = map[string]bool{
	"--json":     true,
	"--force":    true,
	"--clean":    true,
	"--dir":      true,
	"--timeout":  true,
	"--remark":   true,
	"--biz-date": true,
	"--test":     true,
	"--help":     true,
	"-h":         true,
	"--version":  true,
}

// TestSkillLintCommandsAndFlags 解析 SKILL.md 引用，比对实际 CLI 命令表。
func TestSkillLintCommandsAndFlags(t *testing.T) {
	// 定位 SKILL.md（相对于仓库根）
	skillPath := filepath.Join(repoRoot(t), ".claude", "skills", "weft-task-authoring", "SKILL.md")
	content, err := os.ReadFile(skillPath)
	if err != nil {
		t.Skipf("SKILL.md not found at %s; skipping lint (run from repo root)", skillPath)
	}

	text := string(content)

	// 提取所有 `dw <subcommand>` 引用（反引号包裹的命令行）。
	backtickRe := regexp.MustCompile("`dw ([a-z][a-z-]*(?: [a-z][a-z-]*)*)`")
	var failures []string

	for _, match := range backtickRe.FindAllStringSubmatch(text, -1) {
		cmdLine := match[1] // 如 "pull demo" 或 "run --test task"
		parts := strings.Fields(cmdLine)
		if len(parts) == 0 {
			continue
		}

		cmd := parts[0]
		// 检查顶层命令
		if !dwCommands[cmd] {
			failures = append(failures, "SKILL.md 引用了不存在的 dw 命令: dw "+cmd)
			continue
		}

		// 检查后续 token
		for i := 1; i < len(parts); i++ {
			tok := parts[i]
			// 跳过占位符 <xxx> 和参数值示例
			if strings.HasPrefix(tok, "<") || tok == "demo" || tok == "task" ||
				tok == "etl/daily/foo.task.yaml" || tok == "12" {
				continue
			}
			// 子命令
			if parent, ok := dwSubcommands[tok]; ok && parent == cmd {
				continue
			}
			// flag
			if strings.HasPrefix(tok, "-") {
				if !dwFlags[tok] {
					failures = append(failures, "SKILL.md 引用了不存在的 flag: "+tok+" (在 `dw "+cmdLine+"` 中)")
				}
				continue
			}
			// 其他 token（如任务路径参数等）放过
		}
	}

	if len(failures) > 0 {
		t.Fatalf("Skill 一致性 lint 失败（FR-027）:\n%s", strings.Join(failures, "\n"))
	}
}

// repoRoot 向上查找包含 .claude/ 的仓库根目录。
func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, ".claude")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("cannot find repo root (no .claude/ found)")
		}
		dir = parent
	}
}
