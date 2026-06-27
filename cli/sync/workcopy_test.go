package sync

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestPathsUnderWorkDir(t *testing.T) {
	if got := WeftDir("demo"); got != filepath.Join("demo", ".weft") {
		t.Fatalf("WeftDir = %q", got)
	}
	if got := StatePath("demo"); got != filepath.Join("demo", ".weft", "state.json") {
		t.Fatalf("StatePath = %q", got)
	}
	if got := DatasourcePath("demo"); got != filepath.Join("demo", ".weft", "datasources.local.yaml") {
		t.Fatalf("DatasourcePath = %q", got)
	}
}

func TestSaveLoadStateRoundTrip(t *testing.T) {
	dir := t.TempDir()
	in := &State{
		APIBase:     "http://localhost:8000",
		ProjectID:   12,
		ProjectCode: "demo",
		Baseline:    "a1b2c3d4e5f60718",
		PulledAt:    "2026-06-27T10:00:00Z",
		FileCount:   37,
	}
	if err := SaveState(dir, in); err != nil {
		t.Fatalf("SaveState: %v", err)
	}
	// .weft/ 与 state.json 落盘
	if _, err := os.Stat(StatePath(dir)); err != nil {
		t.Fatalf("state.json not written: %v", err)
	}
	got, err := LoadState(dir)
	if err != nil {
		t.Fatalf("LoadState: %v", err)
	}
	if *got != *in {
		t.Fatalf("round-trip mismatch:\n got=%+v\nwant=%+v", *got, *in)
	}
}

func TestLoadStateMissingFileErrors(t *testing.T) {
	_, err := LoadState(t.TempDir())
	if err == nil {
		t.Fatal("expected error for missing state.json")
	}
	if !strings.Contains(err.Error(), "dw pull") {
		t.Fatalf("error should hint `dw pull`, got: %v", err)
	}
}

func TestLoadStateCorruptErrors(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(WeftDir(dir), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(StatePath(dir), []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadState(dir); err == nil {
		t.Fatal("expected error for corrupt state.json")
	}
}

func TestLoadStateMissingProjectIDErrors(t *testing.T) {
	dir := t.TempDir()
	// projectId 缺失（=0）→ 无效
	if err := SaveState(dir, &State{Baseline: "x"}); err != nil {
		t.Fatal(err)
	}
	// 手动抹掉 projectId 校验：直接写 projectId=0 的 json
	if err := os.WriteFile(StatePath(dir), []byte(`{"baseline":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadState(dir); err == nil {
		t.Fatal("expected error for missing projectId")
	}
}

func TestFilesToTreeCreatesNestedDirs(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		"etl/daily/build_dim.task.yaml": "name: build_dim\ntype: SQL\n",
		"etl/daily/build_dim.sql":       "select 1;",
		"README.md":                    "# demo\n",
	}
	if err := FilesToTree(dir, files); err != nil {
		t.Fatalf("FilesToTree: %v", err)
	}
	for p, want := range files {
		b, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(p)))
		if err != nil {
			t.Fatalf("read %s: %v", p, err)
		}
		if string(b) != want {
			t.Fatalf("content mismatch %s:\n got=%q\nwant=%q", p, string(b), want)
		}
	}
}

func TestFilesToTreeRejectsPathEscape(t *testing.T) {
	dir := t.TempDir()
	evil := map[string]string{
		"../escape.txt":      "pwned",
		"a/../../escape2.txt": "pwned2",
	}
	if err := FilesToTree(dir, evil); err == nil {
		t.Fatal("expected error for path-escaping file")
	}
	// 确认没有越狱文件落盘
	if _, err := os.Stat(filepath.Join(dir, "..", "escape.txt")); err == nil {
		t.Fatal("escape.txt should NOT exist outside workDir")
	}
}

func TestFilesToTreeRejectsAbsolutePath(t *testing.T) {
	dir := t.TempDir()
	abs := "/tmp/dw-absolute-test"
	if runtime.GOOS == "windows" {
		abs = `C:\tmp\dw-absolute-test`
	}
	if err := FilesToTree(dir, map[string]string{abs: "x"}); err == nil {
		t.Fatal("expected error for absolute path")
	}
}

func TestTreeToFilesExcludesWeftDir(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		"tasks/a.task.yaml": "x",
		"scripts/run.sh":    "echo hi",
	}
	if err := FilesToTree(dir, files); err != nil {
		t.Fatal(err)
	}
	// .weft/ 里有内容，绝不能被收集
	if err := SaveState(dir, &State{ProjectID: 1, Baseline: "b"}); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(DatasourcePath(dir), []byte("secret: pw"), 0o644)

	got, err := TreeToFiles(dir)
	if err != nil {
		t.Fatalf("TreeToFiles: %v", err)
	}
	if len(got) != len(files) {
		t.Fatalf("expected %d files, got %d: %+v", len(files), len(got), got)
	}
	for p := range got {
		if strings.HasPrefix(p, ".weft/") || strings.Contains(p, "/.weft/") {
			t.Fatalf(".weft/ file leaked into tree: %s", p)
		}
	}
}

func TestFilesTreeRoundTripEquivalence(t *testing.T) {
	dir := t.TempDir()
	original := map[string]string{
		"catalog/etl/task.yaml":      "name: t\ntype: SHELL\n",
		"catalog/etl/t.sh":           "#!/bin/bash\necho ok\n",
		"catalog/etl/sub/deep.txt":   "deep\n",
		"top.md":                     "# top",
		"with space and 中文.txt":     "unicode 内容\n",
	}
	if err := FilesToTree(dir, original); err != nil {
		t.Fatalf("FilesToTree: %v", err)
	}
	got, err := TreeToFiles(dir)
	if err != nil {
		t.Fatalf("TreeToFiles: %v", err)
	}
	if len(got) != len(original) {
		t.Fatalf("count mismatch: got=%d want=%d\n got=%+v", len(got), len(original), got)
	}
	for p, want := range original {
		if got[p] != want {
			t.Fatalf("content mismatch %s:\n got=%q\nwant=%q", p, got[p], want)
		}
	}
}

func TestTreeToFilesEmptyDir(t *testing.T) {
	got, err := TreeToFiles(t.TempDir())
	if err != nil {
		t.Fatalf("TreeToFiles empty: %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("expected empty map, got %+v", got)
	}
}
