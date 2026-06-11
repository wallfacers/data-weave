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
// 配置：环境变量 DW_API（默认 http://localhost:8080）、DW_TOKEN（写类操作的 X-DW-Token）。
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func apiBase() string {
	if v := os.Getenv("DW_API"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return "http://localhost:8080"
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
	case "--version", "version":
		fmt.Println("dw 0.0.1 (DataWeave agent-fabric-m1)")
	default:
		fmt.Fprintf(os.Stderr, "未知命令：%s\n\n", args[0])
		usageRoot()
		os.Exit(2)
	}
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
		req.Header.Set("X-DW-Token", t)
	}
	return do(req)
}

func mustPost(path string) []byte {
	req, _ := http.NewRequest(http.MethodPost, apiBase()+path, bytes.NewReader([]byte("{}")))
	req.Header.Set("Content-Type", "application/json")
	if t := token(); t != "" {
		req.Header.Set("X-DW-Token", t)
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
	fmt.Print(`dw —— DataWeave 运维 CLI（薄壳调 master REST，权限由平台 PolicyEngine 统一裁决）

用法：
  dw <command> [subcommand] [args] [--json]

命令：
  task    任务定义与实例（list / show / instances / rerun）
  logs    实例日志（cat）
  version 版本
  help    本帮助

环境变量：
  DW_API    master 地址（默认 http://localhost:8080）
  DW_TOKEN  写类操作（rerun）的 X-DW-Token

示例：
  dw task list --json
  dw task instances 100
  DW_TOKEN=xxx dw task rerun 100
  dw logs cat 100
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
