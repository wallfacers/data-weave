package run

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/sync"
)

// writeState 写 .weft/state.json（RunTest 需要 projectId）。
func writeState(t *testing.T, dir string, projectID int64) {
	t.Helper()
	if err := sync.SaveState(dir, &sync.State{
		APIBase: "x", ProjectID: projectID, Baseline: "b", FileCount: 1,
	}); err != nil {
		t.Fatal(err)
	}
}

func writeAPITest(w http.ResponseWriter, data string) {
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"code":0,"data":%s,"message":"success"}`, data)
}

// ---- resolveServerTaskID ----

func TestResolveServerTaskID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeAPITest(w, `{"content":[{"id":5,"name":"foo","projectId":12,"type":"SHELL"},
		                              {"id":6,"name":"foo","projectId":99,"type":"SHELL"},
		                              {"id":7,"name":"foobar","projectId":12,"type":"SHELL"}]}`)
	}))
	defer srv.Close()
	cfg := client.Config{APIBase: srv.URL, Token: "jwt"}
	id, err := resolveServerTaskID(cfg, 12, "foo")
	if err != nil {
		t.Fatal(err)
	}
	if id != 5 {
		t.Fatalf("id = %d, want 5（name==foo && projectId==12）", id)
	}
}

func TestResolveServerTaskIDNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeAPITest(w, `{"content":[{"id":5,"name":"other","projectId":12}]}`)
	}))
	defer srv.Close()
	_, err := resolveServerTaskID(client.Config{APIBase: srv.URL}, 12, "foo")
	if err == nil || !strings.Contains(err.Error(), "未找到") {
		t.Fatalf("expected not-found, got: %v", err)
	}
}

// ---- consumeLogsSSE ----

func TestConsumeLogsSSESuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		f := w.(http.Flusher)
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "event: log\ndata: running\n\n")
		f.Flush()
		fmt.Fprintf(w, "event: log\ndata: done\n\n")
		f.Flush()
		fmt.Fprintf(w, "event: end\ndata: {\"state\":\"SUCCESS\"}\n\n")
		f.Flush()
	}))
	defer srv.Close()

	var out bytes.Buffer
	if err := consumeLogsSSE(client.Config{APIBase: srv.URL}, "inst-1", &out); err != nil {
		t.Fatalf("expected nil for SUCCESS, got %v", err)
	}
	if !strings.Contains(out.String(), "running") || !strings.Contains(out.String(), "done") {
		t.Fatalf("stdout missing log lines: %q", out.String())
	}
}

func TestConsumeLogsSSEFailedNonZero(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		f := w.(http.Flusher)
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "event: log\ndata: oops\n\n")
		f.Flush()
		fmt.Fprintf(w, "event: end\ndata: {\"state\":\"FAILED\"}\n\n")
		f.Flush()
	}))
	defer srv.Close()

	err := consumeLogsSSE(client.Config{APIBase: srv.URL}, "inst-1", &bytes.Buffer{})
	ee, ok := err.(*client.ExitError)
	if !ok || ee.Code == 0 {
		t.Fatalf("expected non-zero exit for FAILED, got %v", err)
	}
}

func TestConsumeLogsSSEBrokenStream(t *testing.T) {
	// 流在 end 前关闭 → ExitNetwork
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		f := w.(http.Flusher)
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "event: log\ndata: partial\n\n")
		f.Flush()
		// 不发 end，直接返回（关连接）
	}))
	defer srv.Close()

	err := consumeLogsSSE(client.Config{APIBase: srv.URL}, "inst-1", &bytes.Buffer{})
	ee, ok := err.(*client.ExitError)
	if !ok || ee.Code != client.ExitNetwork {
		t.Fatalf("expected ExitNetwork for broken stream, got %v", err)
	}
}

// ---- RunTest 全链路 ----

func TestRunTestFullExecuted(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/tasks" && r.Method == "GET":
			writeAPITest(w, `{"content":[{"id":5,"name":"foo","projectId":12,"type":"SHELL"}]}`)
		case strings.HasPrefix(r.URL.Path, "/api/tasks/") && strings.HasSuffix(r.URL.Path, "/run"):
			writeAPITest(w, `{"outcome":"EXECUTED","resultInstanceId":"inst-uuid","message":"ok"}`)
		case strings.Contains(r.URL.Path, "/logs/stream"):
			f := w.(http.Flusher)
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, "event: log\ndata: hello-test\n\n")
			f.Flush()
			fmt.Fprintf(w, "event: end\ndata: {\"state\":\"SUCCESS\"}\n\n")
			f.Flush()
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	dir := t.TempDir()
	writeState(t, dir, 12)
	// task.yaml + script + 覆盖 state 的 apiBase 指向 mock
	_ = os.MkdirAll(filepath.Join(dir, "etl"), 0o755)
	_ = os.WriteFile(filepath.Join(dir, "etl", "foo.task.yaml"),
		[]byte("name: foo\ntype: SHELL\n"), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "etl", "foo.sh"), []byte("echo hi"), 0o644)

	// RunTest 用 client.LoadConfig()（读 DW_API）。临时设 DW_API 指向 mock。
	t.Setenv("DW_API", srv.URL)
	t.Setenv("DW_TOKEN", "jwt")

	var out bytes.Buffer
	if err := RunTest(TestOpts{WorkDir: dir, Task: "foo"}, &out, &out); err != nil {
		t.Fatalf("RunTest: %v", err)
	}
	if !strings.Contains(out.String(), "hello-test") {
		t.Fatalf("stdout missing streamed log: %q", out.String())
	}
}

func TestRunTestGateRejected(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/tasks" {
			writeAPITest(w, `{"content":[{"id":5,"name":"foo","projectId":12,"type":"SHELL"}]}`)
			return
		}
		// /run → access_denied（越权 / 闸门拒）
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"code":403,"errorCode":"project.access_denied","message":"越权"}`)
	}))
	defer srv.Close()

	dir := t.TempDir()
	writeState(t, dir, 12)
	_ = os.WriteFile(filepath.Join(dir, "foo.task.yaml"), []byte("name: foo\ntype: SHELL\n"), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "foo.sh"), []byte("echo hi"), 0o644)

	t.Setenv("DW_API", srv.URL)
	t.Setenv("DW_TOKEN", "jwt")

	err := RunTest(TestOpts{WorkDir: dir, Task: "foo"}, &bytes.Buffer{}, &bytes.Buffer{})
	ee, ok := err.(*client.ExitError)
	if !ok || ee.Code != client.ExitUnauthorized {
		t.Fatalf("expected ExitUnauthorized for access_denied, got %v", err)
	}
}
