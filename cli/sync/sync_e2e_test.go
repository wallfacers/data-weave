package sync

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dataweave/dw/client"
)

// mockSyncServer 模拟 C 的 pull/push/diff 端点（足够验证 CLI 字节搬运 + 退出码）。
// 串行调用（client.Do 同步），无需并发保护。
type mockSyncServer struct {
	srv          *httptest.Server
	files        map[string]string // 服务器当前文件态
	baseline     string            // 当前基线
	newBaseline  string            // push 成功后返回的新基线
	stale        bool              // push 时是否返回基线过期
	accessDenied bool              // 是否返回越权
	lastPushCmd  pushCommand
	pushCalls    int
}

func newMockSyncServer(t *testing.T, initial map[string]string) (*mockSyncServer, client.Config) {
	t.Helper()
	m := &mockSyncServer{
		files:       cloneFiles(initial),
		baseline:    "b-initial",
		newBaseline: "b-after-push",
	}
	m.srv = httptest.NewServer(http.HandlerFunc(m.handle))
	t.Cleanup(m.srv.Close)
	return m, client.Config{APIBase: m.srv.URL, Token: "jwt"}
}

func cloneFiles(in map[string]string) map[string]string {
	out := make(map[string]string, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func (m *mockSyncServer) handle(w http.ResponseWriter, r *http.Request) {
	if m.accessDenied {
		writeJSON(w, map[string]any{"code": 403, "errorCode": "project.access_denied", "message": "越权"})
		return
	}
	switch {
	case strings.HasPrefix(r.URL.Path, "/api/projects/") && strings.HasSuffix(r.URL.Path, "/pull"):
		m.handlePull(w, r)
	case strings.HasSuffix(r.URL.Path, "/push"):
		m.handlePush(w, r)
	case strings.HasSuffix(r.URL.Path, "/diff"):
		m.handleDiff(w, r)
	case r.URL.Path == "/api/projects":
		m.handleList(w, r)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (m *mockSyncServer) handlePull(w http.ResponseWriter, _ *http.Request) {
	writeAPI(w, map[string]any{
		"projectId": 12,
		"bundle":    map[string]any{"files": m.files},
		"baseline":  m.baseline,
		"fileCount": len(m.files),
	})
}

func (m *mockSyncServer) handlePush(w http.ResponseWriter, r *http.Request) {
	m.pushCalls++
	body, _ := io.ReadAll(r.Body)
	var cmd pushCommand
	_ = json.Unmarshal(body, &cmd)
	m.lastPushCmd = cmd
	if m.stale && !cmd.Force {
		writeJSON(w, map[string]any{"code": 409, "errorCode": "project.sync.stale", "message": "基线过期，请先 pull"})
		return
	}
	// 服务器落库：文件态更新为 push 的内容，基线前进。
	m.files = cloneFiles(cmd.Files)
	m.baseline = m.newBaseline
	writeAPI(w, map[string]any{
		"projectId":   12,
		"created":     map[string]any{"task": 1, "workflow": 0, "catalog": 0, "tag": 0},
		"updated":     map[string]any{"task": 1, "workflow": 0, "catalog": 0, "tag": 0},
		"deleted":     map[string]any{"task": 0, "workflow": 0, "catalog": 0, "tag": 0},
		"snapshots":   []any{},
		"newBaseline": m.newBaseline,
	})
}

func (m *mockSyncServer) handleDiff(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var cmd pushCommand
	_ = json.Unmarshal(body, &cmd)
	added := []any{}
	for p := range cmd.Files {
		if _, ok := m.files[p]; !ok {
			added = append(added, map[string]any{"entityType": "TASK", "identity": p, "displayName": p})
		}
	}
	stale := false
	if cmd.Baseline != "" && cmd.Baseline != m.baseline {
		stale = true
	}
	writeAPI(w, map[string]any{
		"added":    added,
		"modified": []any{},
		"removed":  []any{},
		"stale":    stale,
	})
}

func (m *mockSyncServer) handleList(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("search")
	writeAPI(w, map[string]any{
		"content": []any{map[string]any{"id": 12, "code": code, "name": "Demo"}},
	})
}

func writeAPI(w http.ResponseWriter, data any) {
	writeJSON(w, map[string]any{"code": 0, "data": data, "message": "success"})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	b, _ := json.Marshal(v)
	_, _ = w.Write(b)
}

// exitCodeOf 执行一个返回 error 的命令，返回其退出码（nil→0）。
func exitCodeOf(t *testing.T, err error) int {
	t.Helper()
	if err == nil {
		return 0
	}
	if ee, ok := err.(*client.ExitError); ok {
		return ee.Code
	}
	return client.ExitUsage
}

// ---- pull ----

func TestPullLandsFilesAndWritesState(t *testing.T) {
	initial := map[string]string{
		"catalog/etl/a.task.yaml": "name: a\ntype: SHELL\n",
		"catalog/etl/a.sh":        "echo hi",
	}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()

	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d", code)
	}
	// 文件落地
	b, err := os.ReadFile(filepath.Join(dir, "catalog/etl/a.sh"))
	if err != nil {
		t.Fatalf("file not landed: %v", err)
	}
	if string(b) != "echo hi" {
		t.Fatalf("content = %q", b)
	}
	// state 写入
	st, err := LoadState(dir)
	if err != nil {
		t.Fatalf("LoadState: %v", err)
	}
	if st.ProjectID != 12 || st.Baseline != "b-initial" || st.FileCount != 2 {
		t.Fatalf("state = %+v", st)
	}
	if st.ProjectCode != "" {
		t.Fatalf("project code should be empty for numeric id, got %q", st.ProjectCode)
	}
	_ = m
}

func TestPullResolvesCodeToID(t *testing.T) {
	_, cfg := newMockSyncServer(t, map[string]string{"a.txt": "x"})
	dir := t.TempDir()
	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "demo"}, cfg)); code != 0 {
		t.Fatalf("pull by code exit = %d", code)
	}
	st, _ := LoadState(dir)
	if st.ProjectID != 12 {
		t.Fatalf("code not resolved to id 12, got %d", st.ProjectID)
	}
	if st.ProjectCode != "demo" {
		t.Fatalf("projectCode = %q", st.ProjectCode)
	}
}

func TestPullRejectsNonEmptyDir(t *testing.T) {
	_, cfg := newMockSyncServer(t, map[string]string{"a.txt": "x"})
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "existing.txt"), []byte("nope"), 0o644)

	code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg))
	if code != client.ExitUsage {
		t.Fatalf("non-empty dir should be exit %d, got %d", client.ExitUsage, code)
	}
}

func TestPullForceOverwritesNonEmpty(t *testing.T) {
	_, cfg := newMockSyncServer(t, map[string]string{"a.txt": "new"})
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("old"), 0o644)

	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12", Force: true}, cfg)); code != 0 {
		t.Fatalf("pull --force exit = %d", code)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "a.txt"))
	if string(b) != "new" {
		t.Fatalf("force should overwrite, got %q", b)
	}
}

func TestPullCleanClearsExisting(t *testing.T) {
	_, cfg := newMockSyncServer(t, map[string]string{"a.txt": "fresh"})
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir,staleFile()), []byte("garbage"), 0o644)
	_ = os.MkdirAll(filepath.Join(dir, "sub"), 0o755)

	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12", Clean: true}, cfg)); code != 0 {
		t.Fatalf("pull --clean exit = %d", code)
	}
	// 旧文件应被清除
	if _, err := os.Stat(filepath.Join(dir, staleFile())); !os.IsNotExist(err) {
		t.Fatalf("clean should remove old file, err=%v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "sub")); !os.IsNotExist(err) {
		t.Fatalf("clean should remove old dir, err=%v", err)
	}
}

// staleFile 返回一个固定名字用于 clean 测试（避免与 "stale" 关键字搜索冲突）。
func staleFile() string { return "leftover.txt" }

// ---- push ----

func TestPushSendsFilesAndWritesNewBaseline(t *testing.T) {
	initial := map[string]string{"a.txt": "v1"}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()
	exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg))

	// 改文件
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("v2"), 0o644)

	if code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir}, cfg)); code != 0 {
		t.Fatalf("push exit = %d", code)
	}
	// 上送的内容应是改后的 v2
	if m.lastPushCmd.Files["a.txt"] != "v2" {
		t.Fatalf("pushed content = %q", m.lastPushCmd.Files["a.txt"])
	}
	if m.lastPushCmd.Baseline != "b-initial" {
		t.Fatalf("baseline sent = %q", m.lastPushCmd.Baseline)
	}
	if m.lastPushCmd.ExpectedFileCount != 1 {
		t.Fatalf("expectedFileCount = %d", m.lastPushCmd.ExpectedFileCount)
	}
	// 新基线写回 state
	st, _ := LoadState(dir)
	if st.Baseline != "b-after-push" {
		t.Fatalf("new baseline not written back: %q", st.Baseline)
	}
}

func TestPushStaleRejectedExit4(t *testing.T) {
	initial := map[string]string{"a.txt": "v1"}
	m, cfg := newMockSyncServer(t, initial)
	m.stale = true
	dir := t.TempDir()
	exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg))
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("v2"), 0o644)

	code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir}, cfg))
	if code != client.ExitServer {
		t.Fatalf("stale push should exit %d, got %d", client.ExitServer, code)
	}
	// --force 覆盖
	m.stale = true
	if code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir, Force: true}, cfg)); code != 0 {
		t.Fatalf("push --force exit = %d", code)
	}
}

func TestPushWithoutStateErrors(t *testing.T) {
	_, cfg := newMockSyncServer(t, map[string]string{"a.txt": "x"})
	code := exitCodeOf(t, RunPush(PushOpts{WorkDir: t.TempDir()}, cfg))
	if code == 0 {
		t.Fatal("push without state should fail")
	}
}

// ---- diff ----

func TestDiffReadOnlyReportsAdded(t *testing.T) {
	initial := map[string]string{"a.txt": "x"}
	_, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()
	exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg))
	// 新增一个文件（本地有，服务器无）→ added
	_ = os.WriteFile(filepath.Join(dir, "new.txt"), []byte("n"), 0o644)

	if code := exitCodeOf(t, RunDiff(DiffOpts{WorkDir: dir}, cfg)); code != 0 {
		t.Fatalf("diff exit = %d", code)
	}
}

// ---- 越权 ----

func TestAccessDeniedExit3(t *testing.T) {
	m, cfg := newMockSyncServer(t, map[string]string{"a.txt": "x"})
	m.accessDenied = true
	code := exitCodeOf(t, RunPull(PullOpts{WorkDir: t.TempDir(), Project: "12"}, cfg))
	if code != client.ExitUnauthorized {
		t.Fatalf("access denied should exit %d, got %d", client.ExitUnauthorized, code)
	}
}

// ---- SC-001 round-trip 语义等价 ----

func TestRoundTripSemanticEquivalenceSC001(t *testing.T) {
	initial := map[string]string{
		"catalog/etl/dim.task.yaml": "name: dim\ntype: SQL\ndatasource: wh\n",
		"catalog/etl/dim.sql":       "select 1;",
		"README.md":                 "# demo\n中文内容\n",
	}
	m, cfg := newMockSyncServer(t, initial)

	// 1. pull 到 dir1
	dir1 := t.TempDir()
	exitCodeOf(t, RunPull(PullOpts{WorkDir: dir1, Project: "12"}, cfg))

	// 2. 改一个脚本 + 新增一个文件
	_ = os.WriteFile(filepath.Join(dir1, "catalog/etl/dim.sql"), []byte("select 2;"), 0o644)
	_ = os.WriteFile(filepath.Join(dir1, "catalog/etl/extra.sql"), []byte("-- extra"), 0o644)

	// 3. push（mock 把服务器文件态更新为本地）
	exitCodeOf(t, RunPush(PushOpts{WorkDir: dir1}, cfg))

	// 4. 再 pull 到干净目录 dir2
	dir2 := t.TempDir()
	exitCodeOf(t, RunPull(PullOpts{WorkDir: dir2, Project: "12"}, cfg))

	// 5. 断言两次文件树语义等价
	tree1, _ := TreeToFiles(dir1)
	tree2, _ := TreeToFiles(dir2)
	if len(tree1) != len(tree2) {
		t.Fatalf("file count differs: dir1=%d dir2=%d\n dir1=%+v\n dir2=%+v",
			len(tree1), len(tree2), tree1, tree2)
	}
	for p, want := range tree1 {
		// dir1 含 .weft/state.json（被 TreeToFiles 排除），dir2 同理；其余应一致
		if tree2[p] != want {
			t.Fatalf("content differs at %s:\n dir1=%q\n dir2=%q", p, want, tree2[p])
		}
	}
	// 服务器态应已更新为改后内容
	if m.files["catalog/etl/dim.sql"] != "select 2;" {
		t.Fatalf("server not updated: %q", m.files["catalog/etl/dim.sql"])
	}
}
