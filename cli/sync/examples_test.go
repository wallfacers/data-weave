package sync

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// TestExamplesAcceptedByDw 验证 Skill examples/ 中的任务与任务流模板能被 dw 接受（FR-012）。
// 检查：文件结构合法（可被 TreeToFiles 收集）、YAML 可解析、push mock 接受。
func TestExamplesAcceptedByDw(t *testing.T) {
	examplesDir := filepath.Join(repoRoot(t), ".claude", "skills", "weft-task-authoring", "examples")
	if _, err := os.Stat(examplesDir); os.IsNotExist(err) {
		t.Skipf("examples dir not found at %s", examplesDir)
	}

	// 收集所有示例文件
	files := make(map[string]string)
	err := filepath.Walk(examplesDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, err := filepath.Rel(examplesDir, path)
		if err != nil {
			return err
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		files[rel] = string(b)
		return nil
	})
	if err != nil {
		t.Fatalf("walk examples: %v", err)
	}
	if len(files) == 0 {
		t.Fatal("no example files found")
	}

	// 验证文件扩展名和基本结构
	for name, content := range files {
		switch {
		case strings.HasSuffix(name, ".task.yaml"):
			validateTaskYAML(t, name, content)
		case strings.HasSuffix(name, ".flow.yaml"):
			validateFlowYAML(t, name, content)
		case strings.HasSuffix(name, ".sql"):
			if content == "" {
				t.Errorf("SQL script %s is empty", name)
			}
		case strings.HasSuffix(name, ".py"):
			if content == "" {
				t.Errorf("PySpark script %s is empty", name)
			}
		case name == "datasources.local.yaml":
			validateDatasourceYAML(t, name, content)
		default:
			t.Logf("unexpected file in examples/: %s (will be pushed as-is)", name)
		}
	}

	// 模拟 push：验证文件能被 mock server 接受（端到端兼容）
	m, cfg := newMockSyncServer(t, nil)
	dir := t.TempDir()
	_ = m

	// 初始化 state（模拟 pull 后的状态）
	if err := SaveState(dir, &State{ProjectID: 12, Baseline: "b-initial"}); err != nil {
		t.Fatal(err)
	}

	// 将示例文件写入工作副本
	for name, content := range files {
		p := filepath.Join(dir, "catalog", "samples", name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	// push → 应被服务器接受（exit 0）
	if code := exitCodeOf(t, RunPush(PushOpts{WorkDir: dir, Remark: "skill-examples-test"}, cfg)); code != 0 {
		t.Fatalf("push examples exit = %d, want 0", code)
	}

	// 验证服务器端文件完整
	for name, content := range files {
		serverPath := "catalog/samples/" + name
		serverContent, ok := m.files[serverPath]
		if !ok {
			t.Errorf("example file %s not found on server after push", serverPath)
			continue
		}
		if serverContent != content {
			t.Errorf("content mismatch for %s:\n server=%q\n local=%q", serverPath, serverContent, content)
		}
	}
}

func validateTaskYAML(t *testing.T, name, content string) {
	t.Helper()
	var doc map[string]any
	if err := yaml.Unmarshal([]byte(content), &doc); err != nil {
		t.Errorf("task YAML parse error in %s: %v", name, err)
		return
	}
	// 必填字段检查
	if v, ok := doc["name"]; !ok || v == nil {
		t.Errorf("task %s missing required field 'name'", name)
	}
	if v, ok := doc["type"]; !ok || v == nil {
		t.Errorf("task %s missing required field 'type'", name)
	}
	// script 字段可选（无脚本任务），但如果有值应为 string
	if v, ok := doc["script"]; ok && v != nil {
		if _, ok := v.(string); !ok {
			t.Errorf("task %s 'script' should be string, got %T", name, v)
		}
	}
	// datasource 可选但若有值应为 string
	if v, ok := doc["datasource"]; ok && v != nil {
		if _, ok := v.(string); !ok {
			t.Errorf("task %s 'datasource' should be string, got %T", name, v)
		}
	}
}

func validateFlowYAML(t *testing.T, name, content string) {
	t.Helper()
	var doc map[string]any
	if err := yaml.Unmarshal([]byte(content), &doc); err != nil {
		t.Errorf("flow YAML parse error in %s: %v", name, err)
		return
	}
	if v, ok := doc["name"]; !ok || v == nil {
		t.Errorf("flow %s missing required field 'name'", name)
	}
	// nodes 必填
	nodes, ok := doc["nodes"]
	if !ok || nodes == nil {
		t.Errorf("flow %s missing required field 'nodes'", name)
		return
	}
	nodeList, ok := nodes.([]any)
	if !ok {
		t.Errorf("flow %s 'nodes' should be an array", name)
		return
	}
	// edges 必填
	edges, ok := doc["edges"]
	if !ok || edges == nil {
		t.Errorf("flow %s missing required field 'edges'", name)
		return
	}
	edgeList, ok := edges.([]any)
	if !ok {
		t.Errorf("flow %s 'edges' should be an array", name)
		return
	}
	// edges 一致性：收集所有 node key
	nodeKeys := make(map[string]bool)
	for _, n := range nodeList {
		nm, ok := n.(map[string]any)
		if !ok {
			continue
		}
		if k, ok := nm["key"].(string); ok {
			nodeKeys[k] = true
		}
	}
	// 每条 edge 的 from/to 必须存在于 nodeKeys
	for _, e := range edgeList {
		em, ok := e.(map[string]any)
		if !ok {
			continue
		}
		for _, field := range []string{"from", "to"} {
			if ref, ok := em[field].(string); ok {
				if !nodeKeys[ref] {
					t.Errorf("flow %s edge %q references non-existent node %q", name, field, ref)
				}
			}
		}
	}
}

func validateDatasourceYAML(t *testing.T, name, content string) {
	t.Helper()
	var doc map[string]any
	if err := yaml.Unmarshal([]byte(content), &doc); err != nil {
		t.Errorf("datasource YAML parse error in %s: %v", name, err)
		return
	}
	for dsName, v := range doc {
		ds, ok := v.(map[string]any)
		if !ok {
			t.Errorf("datasource %s should be a map", dsName)
			continue
		}
		// 必填字段检查（FR-017）
		if _, ok := ds["typeCode"]; !ok {
			t.Errorf("datasource %s in %s missing required field 'typeCode'", dsName, name)
		}
		// SPARK 数据源不要求 jdbcUrl（提交配置走 sparkHome/master，缺失由执行器判 SKIPPED，与 LoadDatasources 一致）
		tc, _ := ds["typeCode"].(string)
		if strings.ToUpper(tc) != "SPARK" {
			if _, ok := ds["jdbcUrl"]; !ok {
				t.Errorf("datasource %s in %s missing required field 'jdbcUrl'", dsName, name)
			}
		}
	}
}

// TestExamplesDatasourceValidation 验证数据源示例文件的必填字段校验。
func TestExamplesDatasourceValidation(t *testing.T) {
	examplesDir := filepath.Join(repoRoot(t), ".claude", "skills", "weft-task-authoring", "examples")
	dsPath := filepath.Join(examplesDir, "datasources.local.yaml")
	if _, err := os.Stat(dsPath); os.IsNotExist(err) {
		t.Skip("datasources.local.yaml not found")
	}

	content, err := os.ReadFile(dsPath)
	if err != nil {
		t.Fatal(err)
	}

	// 模拟 dw run 加载 datasources 的校验
	var doc map[string]any
	if err := yaml.Unmarshal(content, &doc); err != nil {
		t.Fatalf("datasources.local.yaml parse error: %v", err)
	}

	// 每个数据源必须有 jdbcUrl
	for dsName, v := range doc {
		ds, ok := v.(map[string]any)
		if !ok {
			t.Fatalf("datasource %s should be a map", dsName)
		}
		typeCode, _ := ds["typeCode"].(string)
		if typeCode == "" {
			t.Errorf("datasource %s missing typeCode", dsName)
		}
		// SPARK 数据源不要求 jdbcUrl（与 LoadDatasources 校验一致）
		if strings.ToUpper(typeCode) != "SPARK" {
			if jdbcUrl, ok := ds["jdbcUrl"].(string); !ok || jdbcUrl == "" {
				t.Errorf("datasource %s missing jdbcUrl", dsName)
			}
		}
	}
}

// TestExamplesPushThenPullRoundTrip 验证示例文件 push 后再 pull 内容一致。
func TestExamplesPushThenPullRoundTrip(t *testing.T) {
	examplesDir := filepath.Join(repoRoot(t), ".claude", "skills", "weft-task-authoring", "examples")
	if _, err := os.Stat(examplesDir); os.IsNotExist(err) {
		t.Skip("examples dir not found")
	}

	// 收集示例文件
	files := make(map[string]string)
	filepath.Walk(examplesDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(examplesDir, path)
		b, _ := os.ReadFile(path)
		files[rel] = string(b)
		return nil
	})

	// 将文件映射到 catalog/samples/ 下
	serverFiles := make(map[string]string)
	for name, content := range files {
		serverFiles["catalog/samples/"+name] = content
	}

	m, cfg := newMockSyncServer(t, serverFiles)
	dir := t.TempDir()
	_ = m

	// pull
	if code := exitCodeOf(t, RunPull(PullOpts{WorkDir: dir, Project: "12"}, cfg)); code != 0 {
		t.Fatalf("pull exit = %d", code)
	}

	// 验证拉取的文件与原始示例一致
	for name, want := range files {
		p := filepath.Join(dir, "catalog", "samples", name)
		got, err := os.ReadFile(p)
		if err != nil {
			t.Errorf("pull did not land %s: %v", name, err)
			continue
		}
		if string(got) != want {
			t.Errorf("round-trip mismatch for %s:\n got=%q\nwant=%q", name, string(got), want)
		}
	}
}

