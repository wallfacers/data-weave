package run

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/sync"
)

// TestOpts dw run --test 选项。
type TestOpts struct {
	WorkDir string
	Task    string // 任务相对路径或任务名
	BizDate string
}

// gateResult 对应 C 的 GateResult（TaskController.run 返回）。
type gateResult struct {
	Outcome          string `json:"outcome"`           // EXECUTED / PENDING_APPROVAL / REJECTED
	ActionID         *int64 `json:"actionId"`          // 审批单 id（PENDING_APPROVAL）
	Message          string `json:"message"`
	ResultInstanceID string `json:"resultInstanceId"`  // 实例 UUID（EXECUTED）
}

// runRequest 对应 TaskController.RunRequest。
type runRequest struct {
	BizDate    string `json:"bizDate,omitempty"`
	Content    string `json:"content,omitempty"`
	ParamsJSON string `json:"paramsJson,omitempty"`
	Type       string `json:"type,omitempty"`
}

// taskSearchItem GET /api/tasks 列表项子集（按名+projectId 解析 id）。
type taskSearchItem struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	ProjectID int64  `json:"projectId"`
	Type      string `json:"type"`
}

// RunTest 执行 dw run --test（FR-009/010，US3）：按名解析 server task id →
// gated TEST_RUN 提交（POST /api/tasks/{id}/run）→ 消费日志 SSE 至终态 → 退出码反映实例成败。
// 越权/闸门拒 → 透传非0退出码（FR-010）。
func RunTest(opts TestOpts, stdout, stderr io.Writer) error {
	workDir := resolveRunWorkDir(opts.WorkDir)
	state, err := sync.LoadState(workDir)
	if err != nil {
		return err
	}
	taskPath, err := LocateTask(workDir, opts.Task)
	if err != nil {
		return err
	}
	meta, err := ParseTaskMeta(taskPath)
	if err != nil {
		return err
	}
	// 本地脚本内容（DRAFT 的 TEST_RUN 跑本地内容；ONLINE 时服务器忽略 content）
	content, _ := os.ReadFile(ScriptForTask(taskPath, strings.ToUpper(meta.Type)))

	cfg := client.LoadConfig()

	taskID, err := resolveServerTaskID(cfg, state.ProjectID, meta.Name)
	if err != nil {
		return err
	}

	body, _ := json.Marshal(runRequest{BizDate: opts.BizDate, Content: string(content), Type: meta.Type})
	data, err := client.Do(cfg, "POST", fmt.Sprintf("/api/tasks/%d/run", taskID), body)
	if err != nil {
		// 越权 / 闸门拒（access_denied / REJECTED）→ 透传（FR-010）
		return err
	}
	var gr gateResult
	if err := json.Unmarshal(data, &gr); err != nil {
		return client.UsageError("解析 TEST 提交响应失败：%v", err)
	}

	switch gr.Outcome {
	case "EXECUTED":
		if gr.ResultInstanceID == "" {
			return &client.ExitError{Code: client.ExitServer, Message: "TEST 已提交但未返回实例 id"}
		}
		fmt.Fprintf(stdout, "TEST 实例已提交：%s\n", gr.ResultInstanceID)
		return consumeLogsSSE(cfg, gr.ResultInstanceID, stdout)
	case "PENDING_APPROVAL":
		fmt.Fprintf(stdout, "TEST 提交需审批（actionId=%v）：%s\n", gr.ActionID, orStr(gr.Message, "待审批"))
		return nil // 待审批不视为失败
	case "REJECTED":
		return &client.ExitError{Code: client.ExitUnauthorized,
			Message: orStr(gr.Message, "TEST 提交被闸门拒绝")}
	default:
		return &client.ExitError{Code: client.ExitServer,
			Message: fmt.Sprintf("未知闸门结果 %q：%s", gr.Outcome, orStr(gr.Message, ""))}
	}
}

// resolveServerTaskID 在项目内按任务名解析 server task id（GET /api/tasks?keyword=<name>）。
func resolveServerTaskID(cfg client.Config, projectID int64, name string) (int64, error) {
	path := "/api/tasks?keyword=" + url.QueryEscape(name) + "&size=100"
	data, err := client.Do(cfg, "GET", path, nil)
	if err != nil {
		return 0, err
	}
	var page struct {
		Content []taskSearchItem `json:"content"`
	}
	if err := json.Unmarshal(data, &page); err != nil {
		return 0, client.UsageError("解析任务列表失败：%v", err)
	}
	var hits []int64
	for _, t := range page.Content {
		if t.Name == name && t.ProjectID == projectID {
			hits = append(hits, t.ID)
		}
	}
	switch len(hits) {
	case 0:
		return 0, client.UsageError("项目 #%d 内未找到任务 %q（dw run --test 需任务已存在于服务器，通常先 dw push）",
			projectID, name)
	case 1:
		return hits[0], nil
	default:
		return 0, client.UsageError("任务名 %q 在项目 #%d 内匹配 %d 个（歧义）", name, projectID, len(hits))
	}
}

// consumeLogsSSE 消费 GET /api/ops/instances/{id}/logs/stream（text/event-stream）：
// log 事件逐行输出到 stdout；end 事件据 state 决定退出码；流断开报错（Edge Case，不静默挂死）。
func consumeLogsSSE(cfg client.Config, instanceID string, stdout io.Writer) error {
	u := cfg.APIBase + "/api/ops/instances/" + instanceID + "/logs/stream"
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return client.UsageError("构造日志流请求失败：%v", err)
	}
	if cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.Token)
	}
	req.Header.Set("Accept", "text/event-stream")
	cl := &http.Client{Timeout: 30 * time.Minute} // 长连接；实例通常秒级结束
	resp, err := cl.Do(req)
	if err != nil {
		return &client.ExitError{Code: client.ExitNetwork, Message: fmt.Sprintf("连接日志流失败：%v", err)}
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return &client.ExitError{Code: client.ExitServer,
			Message: fmt.Sprintf("日志流返回 HTTP %d", resp.StatusCode)}
	}

	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	var event string
	for scanner.Scan() {
		line := scanner.Text()
		switch {
		case strings.HasPrefix(line, "event:"):
			event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			if event == "log" {
				fmt.Fprintln(stdout, data)
			} else if event == "end" {
				state := parseEndState(data)
				if state == "SUCCESS" {
					return nil
				}
				return &client.ExitError{Code: client.ExitRunFailed,
					Message: fmt.Sprintf("实例终态 %s（非成功，退出码 %d）", state, client.ExitRunFailed)}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return &client.ExitError{Code: client.ExitNetwork, Message: fmt.Sprintf("日志流中断：%v", err)}
	}
	return &client.ExitError{Code: client.ExitNetwork, Message: "日志流在终态前断开（未见 end 事件）"}
}

// parseEndState 解析 end 事件 data（形如 {"state":"SUCCESS"}），失败原样返回。
func parseEndState(data string) string {
	var obj struct {
		State string `json:"state"`
	}
	if json.Unmarshal([]byte(data), &obj) == nil && obj.State != "" {
		return obj.State
	}
	return data
}
