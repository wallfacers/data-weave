package sync

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dataweave/dw/client"
)

// TestGoldenPathPullAuthorDiffPush 验证 golden path 核心链路（FR-001/FR-002/FR-025）：
// pull → 写任务文件 → diff 显示新增 → push 落库。
// 使用 mock server，无外部依赖，进 CI。
func TestGoldenPathPullAuthorDiffPush(t *testing.T) {
	// 初始服务器态：含一个已存在的任务
	initial := map[string]string{
		"catalog/etl/existing.task.yaml": "name: existing\ntype: SHELL\n",
		"catalog/etl/existing.sh":        "echo hi",
	}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()
	_ = m

	// Step 1: dw pull <project>
	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "demo"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d, want 0", code)
	}
	st, err := LoadState(dir)
	if err != nil {
		t.Fatalf("LoadState after pull: %v", err)
	}
	if st.Baseline != "b-initial" {
		t.Fatalf("baseline = %q, want b-initial", st.Baseline)
	}

	// Step 2: 模拟 agent 创作 —— 写新任务文件（对应 "写/改" 步骤）
	newTaskYAML := "name: 我的新任务\ntype: SQL\ndatasource: warehouse_pg\nscript: new_task.sql\n"
	newTaskSQL := "SELECT * FROM orders WHERE dt = '{{bizdate}}';\n"
	taskDir := filepath.Join(dir, "catalog", "etl", "new")
	if err := os.MkdirAll(taskDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(taskDir, "new-task.task.yaml"), []byte(newTaskYAML), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(taskDir, "new-task.sql"), []byte(newTaskSQL), 0o644); err != nil {
		t.Fatal(err)
	}

	// Step 3: 验证本地文件结构 —— 新文件可被 TreeToFiles 收集
	localFiles, err := TreeToFiles(dir)
	if err != nil {
		t.Fatalf("TreeToFiles: %v", err)
	}
	if localFiles["catalog/etl/new/new-task.task.yaml"] != newTaskYAML {
		t.Fatalf("new task yaml not collected correctly")
	}
	if localFiles["catalog/etl/new/new-task.sql"] != newTaskSQL {
		t.Fatalf("new task sql not collected correctly")
	}

	// Step 4: dw diff —— 验证差异预览（应显示新增文件）
	if code := exitCodeOf(t, RunDiff(DiffOpts{WorkDir: dir}, cfg)); code != 0 {
		t.Fatalf("diff exit = %d, want 0", code)
	}

	// Step 5: dw push —— 推回服务器
	if code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir, Remark: "golden-path-test"}, cfg)); code != 0 {
		t.Fatalf("push exit = %d, want 0", code)
	}

	// Step 6: 验证 push 后服务器态包含新文件
	if _, ok := m.files["catalog/etl/new/new-task.task.yaml"]; !ok {
		t.Fatalf("new task not found on server after push")
	}
	if m.files["catalog/etl/new/new-task.sql"] != newTaskSQL {
		t.Fatalf("new task sql mismatch on server")
	}
	if m.baseline != "b-after-push" {
		t.Fatalf("server baseline = %q, want b-after-push", m.baseline)
	}

	// Step 7: 验证 push 后本地基线已更新
	st2, err := LoadState(dir)
	if err != nil {
		t.Fatalf("LoadState after push: %v", err)
	}
	if st2.Baseline != "b-after-push" {
		t.Fatalf("local baseline = %q, want b-after-push", st2.Baseline)
	}
}

// TestGoldenPathStaleBaseline 验证基线过期时的可读提示与处置建议（FR-018）。
func TestGoldenPathStaleBaseline(t *testing.T) {
	initial := map[string]string{
		"a.task.yaml": "name: a\ntype: SHELL\n",
		"a.sh":        "echo hi",
	}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()

	// pull
	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d", code)
	}

	// 修改文件
	if err := os.WriteFile(filepath.Join(dir, "a.sh"), []byte("echo modified"), 0o644); err != nil {
		t.Fatal(err)
	}

	// 设为 stale 态
	m.stale = true

	// push 无 --force → 应返回基线过期错误
	err := RunPush(PushOpts{WorkDir: dir}, cfg)
	code := exitCodeOf(t, err)
	if code != client.ExitServer {
		t.Fatalf("stale push exit = %d, want %d (ExitServer)", code, client.ExitServer)
	}
	ee, ok := err.(*client.ExitError)
	if !ok {
		t.Fatalf("expected *client.ExitError, got %T", err)
	}
	if ee.ErrorCode != "project.sync.stale" {
		t.Fatalf("errorCode = %q, want project.sync.stale", ee.ErrorCode)
	}
	// 提示应包含 "pull" 或 "force" 引导
	msg := strings.ToLower(ee.Message)
	if !strings.Contains(msg, "pull") && !strings.Contains(msg, "force") && !strings.Contains(msg, "基线") {
		t.Fatalf("stale message should hint pull/force/基线, got: %s", ee.Message)
	}

	// push --force → 应成功
	if code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir, Force: true}, cfg)); code != 0 {
		t.Fatalf("force push exit = %d, want 0", code)
	}
	if m.baseline != "b-after-push" {
		t.Fatalf("force push did not advance baseline")
	}
}

// TestGoldenPathAccessDenied 验证越权场景（FR-011 GateResult 三态）。
func TestGoldenPathAccessDenied(t *testing.T) {
	initial := map[string]string{"a.txt": "x"}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()

	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d", code)
	}

	// 修改文件
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("y"), 0o644)

	// 设越权
	m.accessDenied = true

	err := RunPush(PushOpts{WorkDir: dir}, cfg)
	code := exitCodeOf(t, err)
	if code != client.ExitUnauthorized {
		t.Fatalf("access denied push exit = %d, want %d (ExitUnauthorized)", code, client.ExitUnauthorized)
	}
	ee, ok := err.(*client.ExitError)
	if !ok {
		t.Fatalf("expected *client.ExitError, got %T", err)
	}
	if ee.ErrorCode != "project.access_denied" {
		t.Fatalf("errorCode = %q, want project.access_denied", ee.ErrorCode)
	}
}

// TestGoldenPathDiffNoChanges 验证无变更时 diff 行为正常。
func TestGoldenPathDiffNoChanges(t *testing.T) {
	initial := map[string]string{
		"a.task.yaml": "name: a\ntype: SHELL\n",
		"a.sh":        "echo hi",
	}
	m, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()
	_ = m

	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d", code)
	}
	// 无修改 → diff 应成功（exit 0）
	if code := exitCodeOf(t, RunDiff(DiffOpts{WorkDir: dir}, cfg)); code != 0 {
		t.Fatalf("diff exit = %d, want 0", code)
	}
}

// TestGoldenPathPullClean 验证 --clean 清空后拉取。
func TestGoldenPathPullClean(t *testing.T) {
	initial := map[string]string{
		"tasks/hello.task.yaml": "name: hello\ntype: ECHO\n",
	}
	_, cfg := newMockSyncServer(t, initial)
	dir := t.TempDir()

	// 先在目录中创建额外文件
	extraFile := filepath.Join(dir, "garbage.txt")
	if err := os.WriteFile(extraFile, []byte("trash"), 0o644); err != nil {
		t.Fatal(err)
	}

	// pull --clean
	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12", Clean: true}, cfg)); code != 0 {
		t.Fatalf("pull --clean exit = %d", code)
	}

	// 垃圾文件应被清除
	if _, err := os.Stat(extraFile); err == nil {
		t.Fatal("garbage.txt should be removed by --clean")
	}
	// 任务文件应存在
	if _, err := os.Stat(filepath.Join(dir, "tasks/hello.task.yaml")); err != nil {
		t.Fatalf("task file missing after pull --clean: %v", err)
	}
}
