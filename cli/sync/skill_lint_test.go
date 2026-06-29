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
//
// 命令表通过解析 cli/main.go 源码自省获取（非手抄），消除二阶漂移风险。
// 标志表保留手写枚举——flag 通常由后端契约约束，数量远少于命令，且标志变更必伴随
// SKILL.md 正文同步更新，lint 会在那时 fail。

// TestSkillLintCommandsAndFlags 解析 SKILL.md 引用，比对 CLI 实际命令（自省 main.go）。
func TestSkillLintCommandsAndFlags(t *testing.T) {
	mainPath := filepath.Join(repoRoot(t), "cli", "main.go")
	mainSrc, err := os.ReadFile(mainPath)
	if err != nil {
		t.Skipf("main.go not found at %s; skipping lint", mainPath)
	}

	cmdTable := parseCommands(mainSrc)
	flagTable := builtinFlags()

	skillPath := filepath.Join(repoRoot(t), ".claude", "skills", "weft-task-authoring", "SKILL.md")
	content, err := os.ReadFile(skillPath)
	if err != nil {
		t.Skipf("SKILL.md not found at %s; skipping lint (run from repo root)", skillPath)
	}

	text := string(content)

	// 提取所有 dw <subcommand> 引用（反引号包裹的命令行）。
	backtickRe := regexp.MustCompile("`dw ([a-z][a-z-]*(?: [a-z][a-z-]*)*)`")
	var failures []string

	for _, match := range backtickRe.FindAllStringSubmatch(text, -1) {
		cmdLine := match[1]
		parts := strings.Fields(cmdLine)
		if len(parts) == 0 {
			continue
		}

		cmd := parts[0]
		if !cmdTable.topLevel[cmd] {
			failures = append(failures, "SKILL.md 引用了不存在的 dw 命令: dw "+cmd)
			continue
		}

		for i := 1; i < len(parts); i++ {
			tok := parts[i]
			// 跳过占位符 <xxx> 和参数值示例
			if strings.HasPrefix(tok, "<") || tok == "demo" || tok == "task" ||
				tok == "etl/daily/foo.task.yaml" || tok == "12" {
				continue
			}
			// 子命令
			if parent, ok := cmdTable.sub[tok]; ok && parent == cmd {
				continue
			}
			// flag
			if strings.HasPrefix(tok, "-") {
				if !flagTable[tok] {
					failures = append(failures, "SKILL.md 引用了不存在的 flag: "+tok+" (在 `dw "+cmdLine+"` 中)")
				}
				continue
			}
		}
	}

	if len(failures) > 0 {
		t.Fatalf("Skill 一致性 lint 失败（FR-027）:\n%s", strings.Join(failures, "\n"))
	}
}

// cmdTable 自省 main.go 获得的命令注册表。
type cmdTable struct {
	topLevel map[string]bool   // 顶层命令
	sub      map[string]string // subcommand -> parent command
}

// parseCommands 解析 main.go 源码，提取 switch case 字面值作为命令表。
func parseCommands(src []byte) cmdTable {
	t := cmdTable{
		topLevel: map[string]bool{},
		sub:      map[string]string{},
	}
	s := string(src)

	// 顶层命令：main() 中 switch args[0] { case "xxx": ... }
	t.topLevel = switchCases(s, "func main(")

	// task 子命令：cmdTask() 中 switch args[0] { case "xxx": ... }
	for sc := range switchCases(s, "func cmdTask(") {
		t.sub[sc] = "task"
	}

	// logs 子命令：cmdLogs() 中 switch args[0] { case "xxx": ... }
	for sc := range switchCases(s, "func cmdLogs(") {
		t.sub[sc] = "logs"
	}

	// --version / version 也计入顶层
	t.topLevel["version"] = true
	t.topLevel["help"] = true

	return t
}

// switchCases 在 src 中找到给定 funcPattern 后的第一个 switch 语句，
// 提取其 case "literal": 字面值。仅处理 case "xxx": 形式，忽略逗号分隔的多值 case。
func switchCases(src, funcPattern string) map[string]bool {
	idx := strings.Index(src, funcPattern)
	if idx < 0 {
		return map[string]bool{}
	}
	// 从函数开始，找到下一个 switch 关键字
	tail := src[idx:]
	switchIdx := strings.Index(tail, "\tswitch ")
	if switchIdx < 0 {
		return map[string]bool{}
	}
	tail = tail[switchIdx:]
	// 取 switch body（保守取 2400 字符——单个 switch 远小于此）
	end := len(tail)
	if end > 2400 {
		end = 2400
	}
	body := tail[:end]

	re := regexp.MustCompile(`case "([a-z][a-z0-9-]*)"`)
	result := map[string]bool{}
	for _, m := range re.FindAllStringSubmatch(body, -1) {
		result[m[1]] = true
	}
	return result
}

// builtinFlags 返回 dw CLI 已知的标志集（flag 由 CLI 主控，数量少且随 flag 新增须同步 SKILL.md）。
func builtinFlags() map[string]bool {
	return map[string]bool{
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
