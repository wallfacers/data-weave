package client

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// helper：起一个按 handler 回应的测试服务器，返回其 Config（APIBase 指向它）。
func testServer(t *testing.T, handler http.HandlerFunc) Config {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return Config{APIBase: srv.URL, Token: "test-jwt"}
}

func wantCode(t *testing.T, err error, code int) {
	t.Helper()
	ee, ok := err.(*ExitError)
	if !ok {
		t.Fatalf("expected *ExitError, got %T: %v", err, err)
	}
	if ee.Code != code {
		t.Fatalf("exit code = %d, want %d (err=%v)", ee.Code, code, ee)
	}
}

func TestDoSuccessReturnsData(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"code":0,"data":{"x":1},"message":"success"}`)
	})
	data, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil)
	if err != nil {
		t.Fatalf("Do: %v", err)
	}
	var got map[string]int
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatal(err)
	}
	if got["x"] != 1 {
		t.Fatalf("data = %+v", got)
	}
}

func TestDoSendsBearerToken(t *testing.T) {
	var seen string
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Get("Authorization")
		io.WriteString(w, `{"code":0,"data":null,"message":"ok"}`)
	})
	cfg.Token = "abc.def.ghi"
	if _, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil); err != nil {
		t.Fatal(err)
	}
	if seen != "Bearer abc.def.ghi" {
		t.Fatalf("Authorization = %q, want Bearer JWT", seen)
	}
}

func TestDoUnauthorizedExit3(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	})
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil)
	wantCode(t, err, ExitUnauthorized)
}

func TestDoAccessDeniedMapsToExit3(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"code":403,"errorCode":"project.access_denied","message":"无权访问"}`)
	})
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil)
	wantCode(t, err, ExitUnauthorized)
}

func TestDoStaleMapsToExit4(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"code":409,"errorCode":"project.sync.stale","message":"基线过期"}`)
	})
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/push", []byte(`{}`))
	wantCode(t, err, ExitServer)
	ee := err.(*ExitError)
	if ee.ErrorCode != "project.sync.stale" {
		t.Fatalf("errorCode = %q", ee.ErrorCode)
	}
	if !strings.Contains(ee.Message, "基线过期") {
		t.Fatalf("message = %q", ee.Message)
	}
}

func TestDoUnknownDatasourceExit4(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"code":400,"errorCode":"project.sync.unknown_datasource","message":"未知数据源 wh"}`)
	})
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/push", nil)
	wantCode(t, err, ExitServer)
}

func TestDo5xxExit5(t *testing.T) {
	cfg := testServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		io.WriteString(w, "boom")
	})
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil)
	wantCode(t, err, ExitNetwork)
}

func TestDoNetworkUnreachableExit5(t *testing.T) {
	// 指向一个未监听端口 → 连接失败 → ExitNetwork(5)
	cfg := Config{APIBase: "http://127.0.0.1:1", Token: "x"}
	_, err := Do(cfg, http.MethodPost, "/api/projects/1/pull", nil)
	wantCode(t, err, ExitNetwork)
}

func TestLoadConfigDefaults(t *testing.T) {
	t.Setenv("DW_API", "")
	t.Setenv("DW_TOKEN", "")
	cfg := LoadConfig()
	if cfg.APIBase != DefaultAPIBase {
		t.Fatalf("APIBase = %q, want %q", cfg.APIBase, DefaultAPIBase)
	}
	if cfg.Token != "" {
		t.Fatalf("Token = %q, want empty", cfg.Token)
	}
}

func TestLoadConfigFromEnv(t *testing.T) {
	t.Setenv("DW_API", "http://srv:9000/")
	t.Setenv("DW_TOKEN", "jwt123")
	cfg := LoadConfig()
	if cfg.APIBase != "http://srv:9000" {
		t.Fatalf("APIBase = %q (trailing slash trimmed)", cfg.APIBase)
	}
	if cfg.Token != "jwt123" {
		t.Fatalf("Token = %q", cfg.Token)
	}
}

func TestUsageErrorExit2(t *testing.T) {
	e := UsageError("bad %s", "arg")
	if e.Code != ExitUsage {
		t.Fatalf("code = %d", e.Code)
	}
	if !strings.Contains(e.Message, "bad arg") {
		t.Fatalf("msg = %q", e.Message)
	}
}
