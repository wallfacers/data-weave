// Package client 提供 dw CLI 同步/TEST 命令共享的 HTTP 客户端与退出码语义。
//
// 认证：pull/push/diff 与 run --test 直打 C 的 `/api/projects/**`、`/api/tasks/**`、
// `/api/ops/**` 端点，这些走标准 JWT（JwtAuthFilter 白名单仅 `/api/cli`），故用
// `Authorization: Bearer <DW_TOKEN>`，其中 DW_TOKEN 为登录获取的 JWT。
// （现有 `dw task/logs` 仍打 `/api/cli` 用 `X-DW-Token`，互不干扰。）
//
// 退出码（FR-013 区分用法错误 vs 服务端/网络错误）：
//
//	0  成功
//	2  用法错误（本地参数/文件/缺配置）
//	3  鉴权/越权（HTTP 401 或业务 access_denied/403）
//	4  服务端业务错误（stale/incomplete/unknown_datasource/not_found/…）
//	5  网络不可达 / HTTP 5xx
//	6  dw run 任务执行失败（透传 runner 非0退出码）
//	7  环境/前置错误（缺 JVM/worker classpath）
package client

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

// 进程退出码（FR-013）。
const (
	ExitOK          = 0
	ExitUsage       = 2
	ExitUnauthorized = 3
	ExitServer      = 4
	ExitNetwork     = 5
	ExitRunFailed   = 6 // 任务执行失败（透传 runner 非零退出码）
	ExitEnvironment = 7 // 环境/前置错误（缺 JVM/worker classpath）
)

// DefaultAPIBase 与现有 main.go 一致：DW_API 缺省 http://localhost:8000。
const DefaultAPIBase = "http://localhost:8000"

// Config 携带一次 HTTP 调用所需的服务器地址与令牌。
type Config struct {
	APIBase string // 不带尾斜杠
	Token   string // JWT（DW_TOKEN）
}

// LoadConfig 从环境变量 DW_API/DW_TOKEN 读取，缺省回落。
func LoadConfig() Config {
	base := strings.TrimRight(os.Getenv("DW_API"), "/")
	if base == "" {
		base = DefaultAPIBase
	}
	return Config{APIBase: base, Token: os.Getenv("DW_TOKEN")}
}

// ExitError 携带进程退出码的错误。命令返回它，main 据此 os.Exit；普通 error 视为用法错误(2)。
type ExitError struct {
	Code      int    // 进程退出码
	ErrorCode string // 稳定业务错误码（如 project.sync.stale），可空
	Message   string // 面向用户的可定位信息
}

func (e *ExitError) Error() string {
	if e.ErrorCode != "" {
		return fmt.Sprintf("[%s] %s", e.ErrorCode, e.Message)
	}
	return e.Message
}

// UsageError 构造用法错误（退出码 2）。
func UsageError(format string, a ...any) *ExitError {
	return &ExitError{Code: ExitUsage, Message: fmt.Sprintf(format, a...)}
}

// apiResp 对应 C 的 ApiResponse{code,data,message,errorCode}（HTTP 恒 200，业务态在 code）。
type apiResp struct {
	Code      int             `json:"code"`
	Data      json.RawMessage `json:"data"`
	Message   string          `json:"message"`
	ErrorCode string          `json:"errorCode"`
}

// Do 发送一次 JSON 请求并解包 ApiResponse，返回 data 字段的原始 JSON。
// 成功（HTTP 200 且 ApiResponse.code==0）返回 data；失败返回 *ExitError。
//
//	method: GET/POST；path: 形如 /api/projects/12/pull；body: nil 或 JSON 字节。
func Do(cfg Config, method, path string, body []byte) (json.RawMessage, error) {
	url := cfg.APIBase + path
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, url, reader)
	if err != nil {
		return nil, &ExitError{Code: ExitUsage, Message: fmt.Sprintf("构造请求失败：%v", err)}
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.Token)
	}
	req.Header.Set("Accept", "application/json")

	cl := &http.Client{Timeout: 60 * time.Second}
	resp, err := cl.Do(req)
	if err != nil {
		return nil, &ExitError{Code: ExitNetwork,
			Message: fmt.Sprintf("无法连接服务器（DW_API=%s）：%v", cfg.APIBase, err)}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)

	// 鉴权层（JwtAuthFilter）失败 → HTTP 401（非 ApiResponse）。
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, &ExitError{Code: ExitUnauthorized,
			Message: "未授权（401）：DW_TOKEN 缺失或不是有效 JWT，请登录后设置 DW_TOKEN=<jwt>"}
	}

	// 非 200 且非 ApiResponse 信封（路由错误 / 5xx）。
	var ar apiResp
	parseErr := json.Unmarshal(raw, &ar)
	if resp.StatusCode >= 300 {
		if parseErr == nil && ar.Code != 0 {
			return nil, bizError(resp.StatusCode, &ar)
		}
		return nil, &ExitError{Code: serverExitForHTTP(resp.StatusCode),
			Message: fmt.Sprintf("服务器返回 HTTP %d：%s", resp.StatusCode, truncate(raw))}
	}

	// HTTP 200：靠 ApiResponse.code 判定。
	if parseErr != nil {
		// 非 ApiResponse 信封（特殊协议端点），透传原始 body。
		return raw, nil
	}
	if ar.Code == 0 {
		return ar.Data, nil
	}
	return nil, bizError(resp.StatusCode, &ar)
}

// bizError 把 ApiResponse 业务错误映射为 *ExitError（403/access_denied → 越权；其余 → 服务端）。
func bizError(httpStatus int, ar *apiResp) *ExitError {
	ec := ar.ErrorCode
	// 越权类：HTTP 403 或显式 access_denied 错误码。
	if httpStatus == http.StatusForbidden || ec == "project.access_denied" || ec == "access_denied" {
		return &ExitError{Code: ExitUnauthorized, ErrorCode: ec,
			Message: orMsg(ar.Message, "越权：当前令牌无权访问该项目")}
	}
	return &ExitError{Code: ExitServer, ErrorCode: ec,
		Message: orMsg(ar.Message, fmt.Sprintf("服务器业务错误（code=%d）", ar.Code))}
}

// serverExitForHTTP 把 5xx 归为网络/服务器错误，其余非 2xx 归服务端业务。
func serverExitForHTTP(status int) int {
	if status >= 500 {
		return ExitNetwork
	}
	return ExitServer
}

func orMsg(msg, fallback string) string {
	if strings.TrimSpace(msg) != "" {
		return msg
	}
	return fallback
}

func truncate(b []byte) string {
	const max = 500
	s := string(b)
	if len(s) > max {
		return s[:max] + "…"
	}
	return s
}
