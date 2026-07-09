package run

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/dataweave/dw/client"
	"github.com/dataweave/dw/sync"
	"gopkg.in/yaml.v3"
)

// 任务类型 → 脚本扩展名（与 worker TaskMapper.TYPE_EXTENSION 一致，B 的 D7 约定）。
var typeExtension = map[string]string{
	"SQL": ".sql", "SHELL": ".sh", "PYTHON": ".py", "DATA_SYNC": ".json", "ECHO": ".txt", "SPARK": ".py",
	"DATAX": ".json", "SEATUNNEL": ".conf",
	"FLINK": ".sql", // 默认 sql 形态；jar 形态无脚本体（由 ScriptForFlinkTask 判定）
}

// ScriptExtension 返回任务类型对应的脚本扩展名（未知类型默认 .txt）。
func ScriptExtension(taskType string) string {
	if ext, ok := typeExtension[strings.ToUpper(taskType)]; ok {
		return ext
	}
	return ".txt"
}

// TaskMeta 轻读的任务元数据（data-model §4，仅 dw run 用，不重写 B 双向契约）。
type TaskMeta struct {
	Name       string
	Type       string
	Datasource string // 逻辑名（SQL/PYTHON/SPARK 查 .weft/datasources.local.yaml）
	TimeoutSec int
	SparkMode  string // SPARK 内容形态：pyspark/spark-sql/jar（其它类型空）
	FlinkMode  string // FLINK 内容形态：sql/jar（其它类型空）
	JarRef     string // SPARK/FLINK jar 形态的 application jar 引用（本地路径）
	MainClass  string // SPARK/FLINK jar 形态的 --class 主类
}

// taskFile 对应 <slug>.task.yaml 的字段（与 filecontract TaskDoc 字段名一致）。
type taskFile struct {
	Name       string `yaml:"name"`
	Type       string `yaml:"type"`
	Datasource string `yaml:"datasource"`
	TimeoutSec int    `yaml:"timeoutSec"`
	SparkMode  string `yaml:"sparkMode"`
	FlinkMode  string `yaml:"flinkMode"`
	JarRef     string `yaml:"jarRef"`
	MainClass  string `yaml:"mainClass"`
}

// ParseTaskMeta 读 <slug>.task.yaml 提取最小字段（FR-005）。解析失败仅影响本地 run（低爆炸半径）。
func ParseTaskMeta(path string) (*TaskMeta, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取任务定义失败（%s）：%w", path, err)
	}
	var tf taskFile
	if err := yaml.Unmarshal(b, &tf); err != nil {
		return nil, fmt.Errorf("解析任务定义失败（%s）：%w", path, err)
	}
	if tf.Type == "" {
		return nil, fmt.Errorf("任务定义缺少 type 字段（%s）", path)
	}
	return &TaskMeta{Name: tf.Name, Type: tf.Type, Datasource: tf.Datasource, TimeoutSec: tf.TimeoutSec,
		SparkMode: tf.SparkMode, FlinkMode: tf.FlinkMode, JarRef: tf.JarRef, MainClass: tf.MainClass}, nil
}

// LocateTask 定位任务定义文件（FR-005 / D4）：相对文件路径优先，任务名别名次之。
// 路径定位规避 B 的中文名 hash 退化（路径稳定可 tab 补全）；名字匹配命中多个 → 歧义报错提示用路径。
func LocateTask(workDir, task string) (string, error) {
	if task == "" {
		return "", client.UsageError("缺少任务参数。用法：dw run <task>（相对路径或任务名）")
	}
	// 1. 路径优先：task 或 task.task.yaml 作为（相对）路径
	for _, c := range []string{task, task + ".task.yaml"} {
		abs := c
		if !filepath.IsAbs(c) {
			abs = filepath.Join(workDir, c)
		}
		if info, err := os.Stat(abs); err == nil && !info.IsDir() && strings.HasSuffix(abs, ".task.yaml") {
			return abs, nil
		}
	}
	// 2. 任务名别名：遍历工作副本 .task.yaml，匹配 name == task（跳过 .weft/）
	var matches []string
	weftPrefix := filepath.Join(workDir, sync.WeftDirName)
	_ = filepath.WalkDir(workDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			if err == nil && d.IsDir() && path != workDir && d.Name() == sync.WeftDirName {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".task.yaml") {
			return nil
		}
		if strings.HasPrefix(path, weftPrefix+string(filepath.Separator)) {
			return nil
		}
		if meta, e := ParseTaskMeta(path); e == nil && meta.Name == task {
			matches = append(matches, path)
		}
		return nil
	})
	switch len(matches) {
	case 0:
		return "", client.UsageError("未找到任务 %q（按相对路径或任务名）；中文名可能退化为 hash，请用路径定位", task)
	case 1:
		return matches[0], nil
	default:
		return "", client.UsageError("任务名 %q 匹配到 %d 个定义（歧义），请用相对路径：%v",
			task, len(matches), matches)
	}
}

// ScriptForTask 返回任务定义对应的脚本文件路径（<slug>.<ext>，ext 由 type 决定，B 的 D7 约定）。
func ScriptForTask(taskPath, taskType string) string {
	base := strings.TrimSuffix(taskPath, ".task.yaml")
	return base + ScriptExtension(taskType)
}

// ScriptExtensionForSpark 返回 SPARK 任务按 sparkMode 的脚本扩展名（与后端 TaskMapper.getScriptExtension 对齐）：
// pyspark→.py、spark-sql→.sql、jar→""（无独立脚本体）。
func ScriptExtensionForSpark(sparkMode string) string {
	switch strings.ToLower(sparkMode) {
	case "spark-sql":
		return ".sql"
	case "jar":
		return ""
	default:
		return ".py" // pyspark（默认）
	}
}

// ScriptForSparkTask 返回 SPARK 任务脚本体路径（jar 形态返回空串=无脚本体）。
func ScriptForSparkTask(taskPath, sparkMode string) string {
	ext := ScriptExtensionForSpark(sparkMode)
	if ext == "" {
		return ""
	}
	return strings.TrimSuffix(taskPath, ".task.yaml") + ext
}

// ScriptExtensionForFlink 返回 FLINK 任务按 flinkMode 的脚本扩展名（与后端对齐）：
// sql→.sql、jar→""（无独立脚本体，提交 application jar）。
func ScriptExtensionForFlink(flinkMode string) string {
	switch strings.ToLower(flinkMode) {
	case "jar":
		return ""
	default:
		return ".sql" // sql（默认）
	}
}

// ScriptForFlinkTask 返回 FLINK 任务脚本体路径（jar 形态返回空串=无脚本体）。
func ScriptForFlinkTask(taskPath, flinkMode string) string {
	ext := ScriptExtensionForFlink(flinkMode)
	if ext == "" {
		return ""
	}
	return strings.TrimSuffix(taskPath, ".task.yaml") + ext
}
