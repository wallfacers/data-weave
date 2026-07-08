// Command dw 是 DataWeave 的运维 CLI 单二进制（薄壳调 master REST，本地不做权限）。
//
// 子命令树（M1）：
//
//	dw task list                列出任务定义
//	dw task show <id>           看任务定义详情
//	dw task instances <taskId>  看任务的运行实例
//	dw task rerun <instanceId>  重跑实例（写类，经平台 PolicyEngine 闸门，需 token）
//	dw logs cat <instanceId>    看实例日志
//
// 全子命令支持 --json（结构化输出，字段与 master REST 同构）；默认人类可读表格。
// 配置：环境变量 DW_API（默认 http://localhost:8000）、DW_TOKEN（写类操作的 X-DW-Token）。
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/dataweave/dw/authctx"
	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/run"
	"github.com/dataweave/dw/sync"
)

func apiBase() string {
	if v := os.Getenv("DW_API"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return "http://localhost:8000"
}

func token() string { return os.Getenv("DW_TOKEN") }

func main() {
	args := os.Args[1:]
	if len(args) == 0 || args[0] == "--help" || args[0] == "-h" || args[0] == "help" {
		usageRoot()
		return
	}
	switch args[0] {
	case "task":
		cmdTask(args[1:])
	case "logs":
		cmdLogs(args[1:])
	case "pull":
		cmdPull(args[1:])
	case "push":
		cmdPush(args[1:])
	case "diff":
		cmdDiff(args[1:])
	case "run":
		cmdRun(args[1:])
	case "context":
		cmdContext(args[1:])
	case "deps":
		cmdDeps(args[1:])
	case "--version", "version":
		fmt.Println("dw 0.0.1 (DataWeave agent-fabric-m1)")
	default:
		fmt.Fprintf(os.Stderr, "未知命令：%s\n\n", args[0])
		usageRoot()
		os.Exit(2)
	}
}

// ---- context / deps 子命令（058 数据开发 LSP，只读接地事实）----

func cmdContext(args []string) {
	if len(args) == 0 || isHelp(args[0]) {
		fmt.Println("用法：dw context <taskId> [--depth N] [--json]")
		return
	}
	jsonOut, rest := popJSONFlag(args)
	depth, rest := popDepthFlag(rest)
	id := mustArg(rest, "context <taskId> [--depth N]")
	path := "/api/authoring-context/" + id
	if depth > 0 {
		path += "?depth=" + strconv.Itoa(depth)
	}
	body := mustGet(path)
	if jsonOut {
		printJSON(body)
		return
	}
	out, err := authctx.FormatContext(body)
	if err != nil {
		fail("%v", err)
	}
	fmt.Print(out)
}

func cmdDeps(args []string) {
	if len(args) == 0 || isHelp(args[0]) {
		fmt.Println("用法：dw deps <taskId> [--json]")
		return
	}
	jsonOut, rest := popJSONFlag(args)
	id := mustArg(rest, "deps <taskId>")
	body := mustGet("/api/authoring-context/" + id + "/deps")
	if jsonOut {
		printJSON(body)
		return
	}
	out, err := authctx.FormatDeps(body)
	if err != nil {
		fail("%v", err)
	}
	fmt.Print(out)
}

// popDepthFlag 摘出 --depth N（缺省 0=服务端多跳）。
func popDepthFlag(args []string) (int, []string) {
	var rest []string
	depth := 0
	for i := 0; i < len(args); i++ {
		if args[i] == "--depth" && i+1 < len(args) {
			if d, err := strconv.Atoi(args[i+1]); err == nil {
				depth = d
			}
			i++
			continue
		}
		rest = append(rest, args[i])
	}
	return depth, rest
}

// ---- task 子命令 ----

func cmdTask(args []string) {
	if len(args) == 0 || isHelp(args[0]) {
		usageTask()
		return
	}
	jsonOut, rest := popJSONFlag(args[1:])
	switch args[0] {
	case "list":
		body := mustGet("/api/cli/tasks")
		if jsonOut {
			printJSON(body)
			return
		}
		rows := decodeArray(body)
		printTable(rows, []col{{"id", "ID"}, {"name", "任务名"}, {"type", "类型"},
			{"status", "状态"}, {"currentVersionNo", "版本"}})
	case "show":
		id := mustArg(rest, "task show <id>")
		body := mustGet("/api/cli/tasks/" + id)
		if jsonOut {
			printJSON(body)
			return
		}
		printObject(decodeObject(body), []col{{"id", "ID"}, {"name", "任务名"}, {"type", "类型"},
			{"status", "状态"}, {"content", "执行内容"}, {"currentVersionNo", "版本"}})
	case "instances":
		taskID := mustArg(rest, "task instances <taskId>")
		body := mustGet("/api/cli/tasks/" + taskID + "/instances")
		if jsonOut {
			printJSON(body)
			return
		}
		rows := decodeArray(body)
		printTable(rows, []col{{"id", "实例ID"}, {"state", "状态"}, {"workerNodeCode", "节点"},
			{"attempt", "尝试"}, {"finishedAt", "完成时间"}})
	case "rerun":
		instID := mustArg(rest, "task rerun <instanceId>")
		body := mustPost("/api/cli/instances/" + instID + "/rerun")
		if jsonOut {
			printJSON(body)
			return
		}
		o := decodeObject(body)
		fmt.Printf("裁决：%v（%v）\n%v\n", o["outcome"], o["level"], o["message"])
	default:
		fmt.Fprintf(os.Stderr, "未知 task 子命令：%s\n\n", args[0])
		usageTask()
		os.Exit(2)
	}
}

// ---- logs 子命令 ----

func cmdLogs(args []string) {
	if len(args) == 0 || isHelp(args[0]) {
		usageLogs()
		return
	}
	jsonOut, rest := popJSONFlag(args[1:])
	switch args[0] {
	case "cat":
		instID := mustArg(rest, "logs cat <instanceId>")
		body := mustGet("/api/cli/instances/" + instID + "/logs")
		if jsonOut {
			printJSON(body)
			return
		}
		o := decodeObject(body)
		fmt.Printf("实例 #%v [%v] 节点=%v\n%s\n", o["instanceId"], o["state"], o["workerNodeCode"], strOf(o["log"]))
	default:
		fmt.Fprintf(os.Stderr, "未知 logs 子命令：%s\n\n", args[0])
		usageLogs()
		os.Exit(2)
	}
}

// ---- HTTP ----

func httpClient() *http.Client { return &http.Client{Timeout: 15 * time.Second} }

func mustGet(path string) []byte {
	req, _ := http.NewRequest(http.MethodGet, apiBase()+path, nil)
	if t := token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
	return do(req)
}

func mustPost(path string) []byte {
	req, _ := http.NewRequest(http.MethodPost, apiBase()+path, bytes.NewReader([]byte("{}")))
	req.Header.Set("Content-Type", "application/json")
	if t := token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
	return do(req)
}

func do(req *http.Request) []byte {
	resp, err := httpClient().Do(req)
	if err != nil {
		fail("请求失败：%v（DW_API=%s）", err, apiBase())
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode == http.StatusUnauthorized {
		fail("401 未授权：写类操作需设置环境变量 DW_TOKEN")
	}
	if resp.StatusCode >= 400 {
		fail("HTTP %d：%s", resp.StatusCode, string(body))
	}

	// 解包 ApiResponse：{code, data, message} → 直接返回 data 字段的原始 JSON
	var apiResp struct {
		Code    int             `json:"code"`
		Data    json.RawMessage `json:"data"`
		Message string          `json:"message"`
	}
	if err := json.Unmarshal(body, &apiResp); err == nil && apiResp.Data != nil {
		if apiResp.Code == 0 {
			return []byte(apiResp.Data)
		}
		fail("业务错误(%d)：%s", apiResp.Code, apiResp.Message)
	}
	// 非 ApiResponse 格式（特殊协议端点），返回原始 body
	return body
}

// ---- 渲染 ----

type col struct{ key, header string }

func decodeArray(body []byte) []map[string]any {
	var rows []map[string]any
	if err := json.Unmarshal(body, &rows); err != nil {
		fail("解析响应失败：%v", err)
	}
	return rows
}

func decodeObject(body []byte) map[string]any {
	var o map[string]any
	if err := json.Unmarshal(body, &o); err != nil {
		fail("解析响应失败：%v", err)
	}
	return o
}

func printJSON(body []byte) {
	var v any
	if err := json.Unmarshal(body, &v); err != nil {
		fmt.Println(string(body))
		return
	}
	out, _ := json.MarshalIndent(v, "", "  ")
	fmt.Println(string(out))
}

func printTable(rows []map[string]any, cols []col) {
	if len(rows) == 0 {
		fmt.Println("(无数据)")
		return
	}
	headers := make([]string, len(cols))
	for i, c := range cols {
		headers[i] = c.header
	}
	fmt.Println(strings.Join(headers, "\t"))
	for _, r := range rows {
		cells := make([]string, len(cols))
		for i, c := range cols {
			cells[i] = strOf(r[c.key])
		}
		fmt.Println(strings.Join(cells, "\t"))
	}
}

func printObject(o map[string]any, cols []col) {
	for _, c := range cols {
		fmt.Printf("%-12s %s\n", c.header+"：", strOf(o[c.key]))
	}
}

func strOf(v any) string {
	if v == nil {
		return "-"
	}
	if f, ok := v.(float64); ok && f == float64(int64(f)) {
		return fmt.Sprintf("%d", int64(f))
	}
	return fmt.Sprintf("%v", v)
}

// ---- 参数/帮助 ----

func popJSONFlag(args []string) (bool, []string) {
	var rest []string
	jsonOut := false
	for _, a := range args {
		if a == "--json" {
			jsonOut = true
		} else {
			rest = append(rest, a)
		}
	}
	return jsonOut, rest
}

func isHelp(a string) bool { return a == "--help" || a == "-h" || a == "help" }

func mustArg(rest []string, usage string) string {
	if len(rest) == 0 {
		fail("缺少参数。用法：dw %s [--json]", usage)
	}
	return rest[0]
}

func fail(format string, a ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", a...)
	os.Exit(1)
}

func usageRoot() {
	fmt.Print(`dw —— Weft 运维 CLI（薄壳调后端 REST；权限由平台 PolicyEngine 统一裁决）

用法：
  dw <command> [subcommand] [args] [--json]

命令（任务运维）：
  task    任务定义与实例（list / show / instances / rerun）
  logs    实例日志（cat）

命令（本地工作副本往返 + 本地真跑，子特性 D）：
  pull    拉取项目为本地文件树（dw pull <project>）
  push    推回服务器（幂等覆盖 + 版本快照）
  diff    只读差异预览（对服务器零写入）
  run     本机真跑任务（dw run <task>）；TEST 提交服务器（dw run --test <task>）

命令（血缘接地创作上下文，数据开发 LSP 058）：
  context 任务创作上下文（读写表→上下游 + 表/列血缘 + 三态接地）：dw context <taskId> [--depth N]
  deps    任务依赖视图（声明 DAG + 推导血缘带 origin）：dw deps <taskId>

  version 版本
  help    本帮助

环境变量：
  DW_API        后端地址（默认 http://localhost:8000）
	  DW_TOKEN      统一 Bearer 凭据（所有命令统一使用 Authorization: Bearer）
  DW_WORKER_CP  dw run 的 Java runtime classpath 或 worker fat jar 路径（缺省自动探测）

	退出码：0 成功 / 2 用法错误 / 3 越权 / 4 服务端业务错误 / 5 网络错误 / 6 任务执行失败 / 7 环境错误（缺 JVM/worker classpath）

示例：
  dw task list --json
  DW_TOKEN=<jwt> dw pull demo
  dw run etl/daily/foo.task.yaml
  dw diff && dw push
`)
}

func usageTask() {
	fmt.Print(`dw task —— 任务定义与实例

用法：
  dw task list                     列出全部任务定义
  dw task show <id>                看任务定义详情
  dw task instances <taskId>       看某任务的运行实例
  dw task rerun <instanceId>       重跑实例（写类，经平台闸门，需 DW_TOKEN）

全部支持 --json。
`)
}

func usageLogs() {
	fmt.Print(`dw logs —— 实例日志

用法：
  dw logs cat <instanceId>         看实例日志（支持 --json）
`)
}

// ---- pull / push / diff（子特性 D，直打 C 的 /api/projects/** 端点） ----

// exitOnErr 统一错误退出：*client.ExitError 用其 Code（区分用法/越权/业务/网络），
// 其余 error 按用法错误(exit 2)。供所有 D 子命令复用。
func exitOnErr(err error) {
	if err == nil {
		return
	}
	fmt.Fprintln(os.Stderr, "dw:", err)
	if ee, ok := err.(*client.ExitError); ok {
		os.Exit(ee.Code)
	}
	os.Exit(client.ExitUsage)
}

func cmdPull(args []string) {
	opts, err := parsePullArgs(args)
	if err != nil {
		exitOnErr(err)
	}
	exitOnErr(sync.RunPull(opts, client.LoadConfig()))
}

func cmdPush(args []string) {
	opts, err := parsePushArgs(args)
	if err != nil {
		exitOnErr(err)
	}
	exitOnErr(sync.RunPush(opts, client.LoadConfig()))
}

func cmdDiff(args []string) {
	opts, err := parseDiffArgs(args)
	if err != nil {
		exitOnErr(err)
	}
	exitOnErr(sync.RunDiff(opts, client.LoadConfig()))
}

// nextValue 取 args[i] 的下一个值（用于 --flag value 形式），缺失报错。
func nextValue(args []string, i int, flag string) (string, int, error) {
	if i+1 >= len(args) {
		return "", i, client.UsageError("%s 缺少值", flag)
	}
	return args[i+1], i + 1, nil
}

func parsePullArgs(args []string) (sync.PullOpts, error) {
	var opts sync.PullOpts
	var positional []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--force":
			opts.Force = true
		case a == "--clean":
			opts.Clean = true
		case a == "--dir":
			v, ni, err := nextValue(args, i, "--dir")
			if err != nil {
				return opts, err
			}
			opts.WorkDir = v
			i = ni
		case strings.HasPrefix(a, "--dir="):
			opts.WorkDir = strings.TrimPrefix(a, "--dir=")
		case isHelp(a):
			fmt.Print(usagePullText)
			os.Exit(0)
		case strings.HasPrefix(a, "-") && a != "-":
			return opts, client.UsageError("未知参数 %s", a)
		default:
			positional = append(positional, a)
		}
	}
	if len(positional) == 0 {
		return opts, client.UsageError("缺少项目参数。用法：dw pull <project> [--force|--clean] [--dir DIR]")
	}
	if len(positional) > 1 {
		return opts, client.UsageError("多余参数 %v", positional[1:])
	}
	opts.Project = positional[0]
	return opts, nil
}

func parsePushArgs(args []string) (sync.PushOpts, error) {
	var opts sync.PushOpts
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--force":
			opts.Force = true
		case a == "--remark":
			v, ni, err := nextValue(args, i, "--remark")
			if err != nil {
				return opts, err
			}
			opts.Remark = v
			i = ni
		case strings.HasPrefix(a, "--remark="):
			opts.Remark = strings.TrimPrefix(a, "--remark=")
		case a == "--dir":
			v, ni, err := nextValue(args, i, "--dir")
			if err != nil {
				return opts, err
			}
			opts.WorkDir = v
			i = ni
		case strings.HasPrefix(a, "--dir="):
			opts.WorkDir = strings.TrimPrefix(a, "--dir=")
		case isHelp(a):
			fmt.Print(usagePushText)
			os.Exit(0)
		case strings.HasPrefix(a, "-") && a != "-":
			return opts, client.UsageError("未知参数 %s（dw push 不接收位置参数）", a)
		default:
			return opts, client.UsageError("dw push 不接收位置参数 %q（工作副本由当前目录或 --dir 决定）", a)
		}
	}
	return opts, nil
}

func parseDiffArgs(args []string) (sync.DiffOpts, error) {
	var opts sync.DiffOpts
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--dir":
			v, ni, err := nextValue(args, i, "--dir")
			if err != nil {
				return opts, err
			}
			opts.WorkDir = v
			i = ni
		case strings.HasPrefix(a, "--dir="):
			opts.WorkDir = strings.TrimPrefix(a, "--dir=")
		case isHelp(a):
			fmt.Print(usageDiffText)
			os.Exit(0)
		case strings.HasPrefix(a, "-") && a != "-":
			return opts, client.UsageError("未知参数 %s", a)
		default:
			return opts, client.UsageError("dw diff 不接收位置参数 %q", a)
		}
	}
	return opts, nil
}

const usagePullText = `dw pull —— 拉取项目为本地文件树（US1）

用法：
  dw pull <project> [--force|--clean] [--dir DIR]
    <project>   项目 id（数字）或 code（按 code 精确解析）
    --force     目标目录非空时覆盖同名文件（不清除多余文件）
    --clean     先清空目标目录所有内容（含 .weft/）再拉取
    --dir DIR   工作副本根（默认当前目录）

写入 <DIR>/文件树 + <DIR>/.weft/state.json（基线令牌）。
`

const usagePushText = `dw push —— 推回服务器（幂等覆盖 + 版本快照，US1）

用法：
  dw push [--force] [--remark R] [--dir DIR]
    --force     基线过期时强制覆盖
    --remark R  快照备注（默认 "dw push"）
    --dir DIR   工作副本根（默认当前目录）

读取 <DIR>/.weft/state.json 的基线做乐观并发；成功后写回新基线。
`

const usageDiffText = `dw diff —— 只读差异预览（US1，对服务器零写入）

用法：
  dw diff [--dir DIR]
    --dir DIR   工作副本根（默认当前目录）

列出 added/modified/removed；基线过期时提示。
`

// ---- run（子特性 D / US2 本地真跑 + US3 TEST 提交） ----

func cmdRun(args []string) {
	for _, a := range args {
		if a == "--test" {
			cmdRunTest(args)
			return
		}
	}
	opts, err := parseRunArgs(args)
	if err != nil {
		exitOnErr(err)
	}
	exitOnErr(run.RunLocal(opts, os.Stdout, os.Stderr))
}

// cmdRunTest 处理 dw run --test（US3，T022-T025 实现）。
func cmdRunTest(args []string) {
	opts, err := parseRunTestArgs(args)
	if err != nil {
		exitOnErr(err)
	}
	exitOnErr(run.RunTest(opts, os.Stdout, os.Stderr))
}

func parseRunArgs(args []string) (run.LocalOpts, error) {
	var opts run.LocalOpts
	var positional []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--dir":
			v, ni, err := nextValue(args, i, "--dir")
			if err != nil {
				return opts, err
			}
			opts.WorkDir = v
			i = ni
		case strings.HasPrefix(a, "--dir="):
			opts.WorkDir = strings.TrimPrefix(a, "--dir=")
		case a == "--timeout":
			v, ni, err := nextValue(args, i, "--timeout")
			if err != nil {
				return opts, err
			}
			n, perr := strconv.Atoi(v)
			if perr != nil {
				return opts, client.UsageError("--timeout 需为整数：%q", v)
			}
			opts.Timeout = n
			i = ni
		case strings.HasPrefix(a, "--timeout="):
			v := strings.TrimPrefix(a, "--timeout=")
			n, perr := strconv.Atoi(v)
			if perr != nil {
				return opts, client.UsageError("--timeout 需为整数：%q", v)
			}
			opts.Timeout = n
		case isHelp(a):
			fmt.Print(usageRunText)
			os.Exit(0)
		case strings.HasPrefix(a, "-") && a != "-":
			return opts, client.UsageError("未知参数 %s", a)
		default:
			positional = append(positional, a)
		}
	}
	if len(positional) == 0 {
		return opts, client.UsageError("缺少任务参数。用法：dw run <task> [--dir DIR] [--timeout N]")
	}
	if len(positional) > 1 {
		return opts, client.UsageError("多余参数 %v", positional[1:])
	}
	opts.Task = positional[0]
	return opts, nil
}

func parseRunTestArgs(args []string) (run.TestOpts, error) {
	var opts run.TestOpts
	var positional []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--test": // 标志，已识别
		case a == "--dir":
			v, ni, err := nextValue(args, i, "--dir")
			if err != nil {
				return opts, err
			}
			opts.WorkDir = v
			i = ni
		case strings.HasPrefix(a, "--dir="):
			opts.WorkDir = strings.TrimPrefix(a, "--dir=")
		case a == "--biz-date":
			v, ni, err := nextValue(args, i, "--biz-date")
			if err != nil {
				return opts, err
			}
			opts.BizDate = v
			i = ni
		case strings.HasPrefix(a, "--biz-date="):
			opts.BizDate = strings.TrimPrefix(a, "--biz-date=")
		case isHelp(a):
			fmt.Print(usageRunTestText)
			os.Exit(0)
		case strings.HasPrefix(a, "-") && a != "-":
			return opts, client.UsageError("未知参数 %s", a)
		default:
			positional = append(positional, a)
		}
	}
	if len(positional) == 0 {
		return opts, client.UsageError("缺少任务参数。用法：dw run --test <task> [--dir DIR] [--biz-date DATE]")
	}
	if len(positional) > 1 {
		return opts, client.UsageError("多余参数 %v", positional[1:])
	}
	opts.Task = positional[0]
	return opts, nil
}

const usageRunText = `dw run —— 本机真跑任务脚本体（US2，脱机，需本机 JVM）

用法：
  dw run <task> [--dir DIR] [--timeout N]
    <task>      任务相对路径（优先，如 etl/daily/foo.task.yaml）或任务名别名
    --dir DIR   工作副本根（默认当前目录）
    --timeout N 覆盖超时秒数（默认用任务定义的 timeoutSec）

在本机用 Java runtime（复用平台真实执行器）执行 SHELL/SQL/PYTHON 脚本，
stdout/stderr 直出终端，退出码忠实反映执行结果（失败非0）。
SQL/PYTHON 按 datasource 逻辑名读 .weft/datasources.local.yaml（凭据本地，绝不上行）。
`

const usageRunTestText = `dw run --test —— TEST 模式提交服务器执行（US3）

用法：
  dw run --test <task> [--dir DIR] [--biz-date DATE]
    <task>          任务相对路径或任务名（需已存在于服务器，通常先 dw push）
    --dir DIR       工作副本根（默认当前目录）
    --biz-date DATE 业务日期（可选）

按任务名解析服务器 task id → gated TEST_RUN 提交 → 流式日志回传至终态，退出码反映实例成败。
`
