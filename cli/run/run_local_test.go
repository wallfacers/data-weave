package run

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/sync"
)

func writeTaskYAML(t *testing.T, dir, rel, body string) {
	t.Helper()
	full := filepath.Join(dir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

// ---- ScriptExtension / ScriptForTask ----

func TestScriptExtension(t *testing.T) {
	cases := map[string]string{
		"SQL": ".sql", "Shell": ".sh", "python": ".py", "DATA_SYNC": ".json", "ECHO": ".txt",
		"SPARK": ".py", "UNKNOWN": ".txt",
	}
	for typ, want := range cases {
		if got := ScriptExtension(typ); got != want {
			t.Errorf("ScriptExtension(%q)=%q want %q", typ, got, want)
		}
	}
}

func TestScriptForTask(t *testing.T) {
	got := ScriptForTask("etl/daily/foo.task.yaml", "SQL")
	if got != "etl/daily/foo.sql" {
		t.Fatalf("ScriptForTask = %q", got)
	}
}

// ---- ParseTaskMeta ----

func TestParseTaskMeta(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "etl/foo.task.yaml", "name: foo\ntype: SHELL\ndatasource: wh\ntimeoutSec: 30\n")
	meta, err := ParseTaskMeta(filepath.Join(dir, "etl/foo.task.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if meta.Name != "foo" || meta.Type != "SHELL" || meta.Datasource != "wh" || meta.TimeoutSec != 30 {
		t.Fatalf("meta = %+v", meta)
	}
}

func TestParseTaskMetaMissingTypeErrors(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "x.task.yaml", "name: x\n")
	if _, err := ParseTaskMeta(filepath.Join(dir, "x.task.yaml")); err == nil {
		t.Fatal("expected error for missing type")
	}
}

// ---- LocateTask ----

func TestLocateTaskByPath(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "etl/foo.task.yaml", "name: foo\ntype: SHELL\n")
	// 完整路径
	p, err := LocateTask(dir, filepath.ToSlash(filepath.Join("etl", "foo.task.yaml")))
	if err != nil {
		t.Fatalf("by full path: %v", err)
	}
	if !strings.HasSuffix(p, "foo.task.yaml") {
		t.Fatalf("located = %q", p)
	}
	// 短路径（自动补 .task.yaml）
	p2, err := LocateTask(dir, filepath.ToSlash(filepath.Join("etl", "foo")))
	if err != nil {
		t.Fatalf("by short path: %v", err)
	}
	if !strings.HasSuffix(p2, "foo.task.yaml") {
		t.Fatalf("short located = %q", p2)
	}
}

func TestLocateTaskByName(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "etl/daily/foo.task.yaml", "name: foo\ntype: SHELL\n")
	p, err := LocateTask(dir, "foo")
	if err != nil {
		t.Fatalf("by name: %v", err)
	}
	if !strings.HasSuffix(p, "foo.task.yaml") {
		t.Fatalf("located = %q", p)
	}
}

func TestLocateTaskAmbiguous(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "a/foo.task.yaml", "name: dup\ntype: SHELL\n")
	writeTaskYAML(t, dir, "b/foo.task.yaml", "name: dup\ntype: SHELL\n")
	_, err := LocateTask(dir, "dup")
	if err == nil || !strings.Contains(err.Error(), "歧义") {
		t.Fatalf("expected ambiguity error, got: %v", err)
	}
}

func TestLocateTaskNotFound(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "a/foo.task.yaml", "name: foo\ntype: SHELL\n")
	_, err := LocateTask(dir, "bar")
	if err == nil {
		t.Fatal("expected not-found error")
	}
}

// ---- BuildLocalRunCmd / runCommand（退出码透传） ----

func TestBuildLocalRunCmdFatJar(t *testing.T) {
	jar := "/repo/dataweave-worker-0.0.1-SNAPSHOT-exec.jar"
	cmd := BuildLocalRunCmd(jar, "SHELL", 600, "/tmp/ds.json", []byte("echo hi"), nil)
	want := []string{"java", "-cp", jar, "-Dloader.main=" + localRunMainClass, propertiesLauncher,
		"--type", "SHELL", "--timeout", "600", "--ds-json", "/tmp/ds.json"}
	if !reflect.DeepEqual(cmd.Args, want) {
		t.Fatalf("args = %v\nwant %v", cmd.Args, want)
	}
	b, _ := io.ReadAll(cmd.Stdin)
	if string(b) != "echo hi" {
		t.Fatalf("stdin content = %q", b)
	}
}

func TestBuildLocalRunCmdPlainClasspath(t *testing.T) {
	cp := "/a/target/classes:/b/deps.jar"
	cmd := BuildLocalRunCmd(cp, "SQL", 0, "", []byte("select 1"), nil)
	want := []string{"java", "-cp", cp, localRunMainClass, "--type", "SQL"}
	if !reflect.DeepEqual(cmd.Args, want) {
		t.Fatalf("args = %v\nwant %v", cmd.Args, want)
	}
	for _, a := range cmd.Args {
		if a == "--timeout" {
			t.Fatal("--timeout should be omitted when timeout<=0")
		}
		if a == "--ds-json" {
			t.Fatal("--ds-json should be omitted when empty")
		}
	}
}

func TestRunCommandExitCodePassThrough(t *testing.T) {
	cases := []struct {
		script string
		want   int
	}{
		{"exit 0", 0},
		{"exit 42", 42},
		{"exit 1", 1},
	}
	for _, c := range cases {
		cmd := exec.Command("sh", "-c", c.script)
		code, err := runCommand(cmd)
		if err != nil {
			t.Fatalf("sh %s: unexpected err %v", c.script, err)
		}
		if code != c.want {
			t.Errorf("sh %s: code=%d want=%d", c.script, code, c.want)
		}
	}
	// 启动失败（无此二进制）→ -1 + err
	code, err := runCommand(exec.Command("nonexistent-dw-binary-xyz-123"))
	if err == nil || code != -1 {
		t.Fatalf("expected -1 + err for missing binary, got code=%d err=%v", code, err)
	}
}

// ---- writeTempDSJSON ----

func TestWriteTempDSJSON(t *testing.T) {
	dir := t.TempDir()
	ds := &Datasource{Name: "wh", TypeCode: "POSTGRESQL", JdbcURL: "jdbc:postgresql://h/db", Username: "u", Password: "p"}
	path, err := writeTempDSJSON(dir, ds)
	if err != nil {
		t.Fatal(err)
	}
	// 在 .weft/ 下
	if !strings.HasPrefix(path, sync.WeftDir(dir)) {
		t.Fatalf("temp file not in .weft/: %q", path)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), `"password":"p"`) || !strings.Contains(string(b), `"name":"wh"`) {
		t.Fatalf("ds json = %s", b)
	}
}

// ---- classpath 定位 ----

func TestFindWorkerClasspathEnv(t *testing.T) {
	t.Setenv("DW_WORKER_CP", "/custom/cp")
	cp, err := FindWorkerClasspath()
	if err != nil {
		t.Fatal(err)
	}
	if cp != "/custom/cp" {
		t.Fatalf("cp = %q", cp)
	}
}

func TestFindClasspathFromMissing(t *testing.T) {
	// tempdir 无 backend/ → 缺配置（ExitUsage，FR-013 用法错误：缺配置）
	_, err := findClasspathFrom(t.TempDir())
	if err == nil {
		t.Fatal("expected classpath error")
	}
	ee, ok := err.(*client.ExitError)
	if !ok || ee.Code != client.ExitUsage {
		t.Fatalf("expected ExitUsage (缺配置), got %v", err)
	}
	if !strings.Contains(ee.Message, "classpath") && !strings.Contains(ee.Message, "DW_WORKER_CP") {
		t.Fatalf("error should mention classpath: %v", err)
	}
}

func TestRunLocalUnsupportedType(t *testing.T) {
	dir := t.TempDir()
	writeTaskYAML(t, dir, "foo.task.yaml", "name: foo\ntype: DATA_SYNC\n")
	err := RunLocal(LocalOpts{WorkDir: dir, Task: "foo"}, &bytes.Buffer{}, &bytes.Buffer{})
	if err == nil {
		t.Fatal("expected unsupported-type error")
	}
	ee, _ := err.(*client.ExitError)
	if ee.Code != client.ExitUsage {
		t.Fatalf("expected ExitUsage, got code=%d", ee.Code)
	}
}

// ---- 退出码语义（FR-016） ----

func TestExitEnvironmentCodeIs7(t *testing.T) {
	if client.ExitEnvironment != 7 {
		t.Fatalf("ExitEnvironment = %d, want 7", client.ExitEnvironment)
	}
}

func TestExitRunFailedCodeIs6(t *testing.T) {
	if client.ExitRunFailed != 6 {
		t.Fatalf("ExitRunFailed = %d, want 6", client.ExitRunFailed)
	}
}

func TestExitCodeSemanticsDistinct(t *testing.T) {
	// FR-016：环境错误 (7) 与任务执行失败 (6) 必须可区分
	if client.ExitEnvironment == client.ExitRunFailed {
		t.Fatal("ExitEnvironment(7) MUST be distinct from ExitRunFailed(6)")
	}
}

func TestMapRunExit(t *testing.T) {
	// 成功：code 0 → nil
	if err := mapRunExit(0, nil); err != nil {
		t.Fatalf("code 0 → %v, want nil", err)
	}
	// 启动失败（runErr 非空，缺 JVM/classpath）→ ExitEnvironment(7)，与作业失败可区分
	envErr := mapRunExit(-1, exec.ErrNotFound)
	ee, ok := envErr.(*client.ExitError)
	if !ok || ee.Code != client.ExitEnvironment {
		t.Fatalf("启动失败应为 ExitEnvironment(7)，got %v", envErr)
	}
	// runner 非零码 → 一律归一到 ExitRunFailed(6)，原始码入 message（不透传原值撞 7/255/137 等契约外码）
	for _, code := range []int{1, 6, 7, 137, 255} {
		err := mapRunExit(code, nil)
		re, ok := err.(*client.ExitError)
		if !ok {
			t.Fatalf("runner code %d → %T, want *client.ExitError", code, err)
		}
		if re.Code != client.ExitRunFailed {
			t.Errorf("runner code %d → dw 退出码 %d, want ExitRunFailed(%d)（退出码契约:作业失败恒 6，不撞 7=环境错误）",
				code, re.Code, client.ExitRunFailed)
		}
		if !strings.Contains(re.Message, strconv.Itoa(code)) {
			t.Errorf("runner code %d 的 message 应含原始码供诊断，got %q", code, re.Message)
		}
	}
}

func TestRunLocalMissingClasspathReturnsUsageError(t *testing.T) {
	// FindWorkerClasspath 找不到 → ExitUsage(2)（配置问题，非环境错误）
	t.Setenv("DW_WORKER_CP", "")
	// 在一个没有 backend/ 的临时目录中，findClasspathFrom 应失败
	dir := t.TempDir()
	writeTaskYAML(t, dir, "foo.task.yaml", "name: foo\ntype: SHELL\n")
	writeTaskYAML(t, dir, "foo.sh", "echo hi")
	// 改变工作目录到 temp dir 以影响 findClasspathFrom 的起点
	origWd, _ := os.Getwd()
	_ = os.Chdir(dir)
	defer func() { _ = os.Chdir(origWd) }()

	err := RunLocal(LocalOpts{WorkDir: dir, Task: "foo"}, &bytes.Buffer{}, &bytes.Buffer{})
	if err == nil {
		t.Fatal("expected error for missing classpath")
	}
	ee, ok := err.(*client.ExitError)
	if !ok {
		t.Fatalf("expected *ExitError, got %T: %v", err, err)
	}
	if ee.Code != client.ExitUsage {
		t.Fatalf("missing classpath exit = %d, want ExitUsage(%d)", ee.Code, client.ExitUsage)
	}
	if !strings.Contains(ee.Message, "DW_WORKER_CP") && !strings.Contains(ee.Message, "classpath") {
		t.Fatalf("error should mention DW_WORKER_CP or classpath: %v", ee)
	}
}

// ---- SPARK 类型支持（US1，FR-015）----

func TestBuildLocalRunCmdSpark(t *testing.T) {
	cmd := BuildLocalRunCmd("/repo/dataweave-worker-0.0.1-SNAPSHOT-exec.jar", "SPARK", 600, "/tmp/ds.json",
		[]byte("print('x')"), &SparkRunOpts{SparkMode: "pyspark"})
	want := []string{"java", "-cp", "/repo/dataweave-worker-0.0.1-SNAPSHOT-exec.jar",
		"-Dloader.main=" + localRunMainClass, propertiesLauncher,
		"--type", "SPARK", "--timeout", "600", "--ds-json", "/tmp/ds.json", "--spark-mode", "pyspark"}
	if !reflect.DeepEqual(cmd.Args, want) {
		t.Fatalf("spark args = %v\nwant %v", cmd.Args, want)
	}
}

func TestBuildLocalRunCmdSparkJar(t *testing.T) {
	cmd := BuildLocalRunCmd("/a/classes", "SPARK", 0, "",
		nil, &SparkRunOpts{SparkMode: "jar", JarPath: "/tmp/app.jar", MainClass: "com.x.Main"})
	want := []string{"java", "-cp", "/a/classes", localRunMainClass,
		"--type", "SPARK", "--spark-mode", "jar", "--jar-path", "/tmp/app.jar", "--main-class", "com.x.Main"}
	if !reflect.DeepEqual(cmd.Args, want) {
		t.Fatalf("spark jar args = %v\nwant %v", cmd.Args, want)
	}
}

func TestDatasourceSparkJSONContainsSparkFields(t *testing.T) {
	ds := &Datasource{TypeCode: "SPARK", SparkHome: "/opt/spark", Master: "local[*]", DeployMode: "client"}
	b, err := json.Marshal(ds)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, want := range []string{`"sparkHome":"/opt/spark"`, `"master":"local[*]"`, `"deployMode":"client"`, `"typeCode":"SPARK"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("spark ds json missing %q: %s", want, s)
		}
	}
}

func TestLoadDatasourcesSparkWithoutJdbcUrl(t *testing.T) {
	dir := t.TempDir()
	path := sync.DatasourcePath(dir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	content := "spark_local:\n  typeCode: SPARK\n  master: local[*]\n  sparkHome: /opt/spark\n  deployMode: client\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	all, err := LoadDatasources(dir)
	if err != nil {
		t.Fatalf("SPARK datasource without jdbcUrl should load ok: %v", err)
	}
	ds, ok := all["spark_local"]
	if !ok || ds.SparkHome != "/opt/spark" || ds.Master != "local[*]" || ds.DeployMode != "client" {
		t.Fatalf("loaded spark ds = %+v", ds)
	}
}
