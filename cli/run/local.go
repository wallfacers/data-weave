package run

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/sync"
)

const localRunMainClass = "com.dataweave.worker.localrun.LocalRunMain"

// propertiesLauncher 是 Spring Boot 4 fat jar 的可配置启动器（允许经 -Dloader.main 指定主类）。
const propertiesLauncher = "org.springframework.boot.loader.launch.PropertiesLauncher"

// LocalOpts dw run（本地真跑）选项。
type LocalOpts struct {
	WorkDir string
	Task    string // 相对路径或任务名
	Timeout int    // 覆盖超时（≤0 用任务定义的 timeoutSec）
}

// RunLocal 执行 dw run（FR-005/006/007/008，US2）：轻读任务元数据 → 解析本地数据源连接 →
// 调起 java LocalRunMain 子进程（复用 worker 真实执行器）→ 管道直出 → 透传退出码。
// 脱机：不需服务器、不需 master/worker 调度进程（前提：本机有 JVM）。
func RunLocal(opts LocalOpts, stdout, stderr io.Writer) error {
	workDir := resolveRunWorkDir(opts.WorkDir)

	taskPath, err := LocateTask(workDir, opts.Task)
	if err != nil {
		return err
	}
	meta, err := ParseTaskMeta(taskPath)
	if err != nil {
		return err
	}

	typeUp := strings.ToUpper(meta.Type)
	if typeUp != "SHELL" && typeUp != "SQL" && typeUp != "PYTHON" {
		return client.UsageError("任务类型 %s 本地不支持（MVP 仅 SHELL/SQL/PYTHON，FR-007；DATA_SYNC 排除）", meta.Type)
	}

	// 脚本体：与 .task.yaml 同名、扩展名由 type 决定（B 的 D7 约定）。
	scriptPath := ScriptForTask(taskPath, typeUp)
	content, err := os.ReadFile(scriptPath)
	if err != nil {
		return client.UsageError("读取任务脚本失败（%s）：%v（脚本应与 .task.yaml 同目录同名，扩展名 .sql/.sh/.py 由 type 决定）",
			scriptPath, err)
	}

	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = meta.TimeoutSec
	}

	// SQL/PYTHON 数据源连接（凭据本地持有，绝不上行）
	var dsJSONPath string
	if (typeUp == "SQL" || typeUp == "PYTHON") && meta.Datasource != "" {
		ds, derr := LookupDatasource(workDir, meta.Datasource)
		if derr != nil {
			return derr
		}
		dsJSONPath, err = writeTempDSJSON(workDir, ds)
		if err != nil {
			return err
		}
		defer os.Remove(dsJSONPath)
	}

	classpath, err := FindWorkerClasspath()
	if err != nil {
		return err
	}

	cmd := BuildLocalRunCmd(classpath, typeUp, timeout, dsJSONPath, content)
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	code, runErr := runCommand(cmd)
	if runErr != nil {
		// java 启动失败（无 JVM / classpath 不可用）→ 环境错误 (7)，可定位（FR-016）
		return &client.ExitError{Code: client.ExitEnvironment,
			Message: fmt.Sprintf("启动本地 runtime 失败（缺 JVM 或 classpath）：%v。"+
				"请确认 java 在 PATH 中，或设 DW_WORKER_CP 指向 worker classpath/fat jar", runErr)}
	}
	if code != 0 {
		// 透传 runner 退出码（FR-016：runner 非零码=任务执行失败，与环境错区分）
		return &client.ExitError{Code: code,
			Message: fmt.Sprintf("任务执行失败（退出码 %d，详见上方输出）", code)}
	}
	return nil
}

// BuildLocalRunCmd 构造 java LocalRunMain 子进程命令；脚本体 content 经 stdin 传入。
//
// classpath 为 Spring Boot fat jar（*-exec.jar）时，classes 在 BOOT-INF/classes/，无法
// 直接 `java -cp <jar> <main>`，须用 PropertiesLauncher + -Dloader.main 访问；
// 普通 classpath（target/classes:deps 列表）则直接 `java -cp <cp> <main>`。
func BuildLocalRunCmd(classpath, taskType string, timeout int, dsJSONPath string, content []byte) *exec.Cmd {
	args := buildLocalRunArgs(classpath, taskType, timeout, dsJSONPath)
	cmd := exec.Command("java", args...)
	cmd.Stdin = strings.NewReader(string(content))
	return cmd
}

func buildLocalRunArgs(classpath, taskType string, timeout int, dsJSONPath string) []string {
	var head []string
	if isFatJarClasspath(classpath) {
		head = []string{"-cp", classpath, "-Dloader.main=" + localRunMainClass, propertiesLauncher}
	} else {
		head = []string{"-cp", classpath, localRunMainClass}
	}
	args := append(head, "--type", taskType)
	if timeout > 0 {
		args = append(args, "--timeout", strconv.Itoa(timeout))
	}
	if dsJSONPath != "" {
		args = append(args, "--ds-json", dsJSONPath)
	}
	return args
}

// isFatJarClasspath 报告 classpath 是否为单个 Spring Boot fat jar（需 PropertiesLauncher）。
// 多路径 classpath 列表（含路径分隔符）→ false。
func isFatJarClasspath(classpath string) bool {
	if classpath == "" || strings.Contains(classpath, string(os.PathListSeparator)) {
		return false
	}
	return strings.HasSuffix(classpath, "-exec.jar") || strings.HasSuffix(classpath, ".jar")
}

// runCommand 执行子进程并返回退出码（透传）；进程启动失败（非退出码错误）返回 -1 + err。
func runCommand(cmd *exec.Cmd) (int, error) {
	err := cmd.Run()
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			return ee.ExitCode(), nil
		}
		return -1, err
	}
	return 0, nil
}

// writeTempDSJSON 把数据源连接写为 JSON 临时文件（.weft/ 下，git-ignored，含凭据；调用方用完删除）。
func writeTempDSJSON(workDir string, ds *Datasource) (string, error) {
	dir := sync.WeftDir(workDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("创建 %s 失败：%w", dir, err)
	}
	b, err := json.Marshal(ds)
	if err != nil {
		return "", fmt.Errorf("序列化数据源失败：%w", err)
	}
	f, err := os.CreateTemp(dir, ".ds-*.json")
	if err != nil {
		return "", fmt.Errorf("写数据源临时文件失败：%w", err)
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return "", fmt.Errorf("写数据源临时文件失败：%w", err)
	}
	name := f.Name()
	f.Close()
	return name, nil
}

// FindWorkerClasspath 定位 LocalRunMain 的 java classpath（FR-006 需本机 JVM）：
//  1. 环境变量 DW_WORKER_CP（显式指定 classpath 或 fat jar 路径）
//  2. 从 cwd 向上探测 backend/dataweave-worker/target/dataweave-worker-*-exec.jar（spring-boot fat jar）
//
// 找不到 → 可定位错误（提示设 DW_WORKER_CP 或生成 fat jar）。
func FindWorkerClasspath() (string, error) {
	if cp := os.Getenv("DW_WORKER_CP"); cp != "" {
		return cp, nil
	}
	cwd, _ := os.Getwd()
	return findClasspathFrom(cwd)
}

// findClasspathFrom 从 startDir 向上逐级探测 backend/dataweave-worker/target/*-exec.jar。
// 抽出为独立函数便于测试（不依赖真实 cwd）。
func findClasspathFrom(startDir string) (string, error) {
	dir := startDir
	for i := 0; i < 10; i++ {
		target := filepath.Join(dir, "backend", "dataweave-worker", "target")
		if jar, err := findFatJar(target); err == nil {
			return jar, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return "", client.UsageError("无法定位 worker classpath。请设环境变量 DW_WORKER_CP=<classpath 或 fat jar 路径>，" +
		"或先在仓库执行：cd backend && ./mvnw -pl dataweave-worker -am package -DskipTests")
}

func findFatJar(targetDir string) (string, error) {
	entries, err := os.ReadDir(targetDir)
	if err != nil {
		return "", err
	}
	for _, e := range entries {
		name := e.Name()
		if strings.HasPrefix(name, "dataweave-worker") && strings.HasSuffix(name, "-exec.jar") {
			return filepath.Join(targetDir, name), nil
		}
	}
	return "", fmt.Errorf("fat jar not found in %s", targetDir)
}

func resolveRunWorkDir(dir string) string {
	if dir != "" {
		return dir
	}
	if wd, err := os.Getwd(); err == nil {
		return wd
	}
	return "."
}

// orStr：s 非空白返回 s，否则返回 fallback。
func orStr(s, fallback string) string {
	if strings.TrimSpace(s) != "" {
		return s
	}
	return fallback
}
