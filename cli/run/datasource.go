// Package run 实现 dw CLI 的本地真跑（dw run）与 TEST 提交（dw run --test）。
//
// 本地真跑（US2）：轻读任务元数据（type/datasource/content/timeout）→ 解析本地数据源连接
// → 调起 Java LocalRunMain 子进程执行（复用 worker 真实执行器）→ 管道直出、透传退出码。
// TEST 提交（US3）：按名解析 server task id → POST /api/tasks/{id}/run gated TEST_RUN → 消费日志 SSE。
package run

import (
	"fmt"
	"os"
	"strings"

	"github.com/dataweave/dw/sync"
	"gopkg.in/yaml.v3"
)

// Datasource 本地数据源连接（凭据本地持有，绝不上行）。对应 worker ExecutionContext.DataSourceRef
// 的本地子集（无上传驱动 jar，走内置 JDBC 驱动）。
//
// JSON tag 用于序列化为 LocalRunMain 的 --ds-json（本地子进程，不经网络）；
// YAML tag 用于解析 .weft/datasources.local.yaml。
type Datasource struct {
	Name     string `json:"name" yaml:"-"`          // 逻辑名（= map key，YAML 不重复解析）
	TypeCode string `json:"typeCode" yaml:"typeCode"` // MYSQL/POSTGRESQL/H2/SPARK/...
	JdbcURL  string `json:"jdbcUrl" yaml:"jdbcUrl"`
	Username string `json:"username" yaml:"username"`
	Password string `json:"password" yaml:"password"`
	// SPARK 集群提交配置（typeCode=SPARK 时用；SQL/PYTHON/SHELL 忽略）。缺失由执行器判 SKIPPED。
	SparkHome  string `json:"sparkHome,omitempty" yaml:"sparkHome"`
	Master     string `json:"master,omitempty" yaml:"master"`         // local[*] | yarn | spark://...
	DeployMode string `json:"deployMode,omitempty" yaml:"deployMode"` // client | cluster
	Queue      string `json:"queue,omitempty" yaml:"queue"`
}

// LoadDatasources 解析 `.weft/datasources.local.yaml` → 逻辑名→连接 映射（FR-008）。
// 文件缺失返回空 map + nil（并非所有任务都需数据源；缺失逻辑名在 Lookup 时报错，FR-007）。
func LoadDatasources(workDir string) (map[string]Datasource, error) {
	path := sync.DatasourcePath(workDir)
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return map[string]Datasource{}, nil
		}
		return nil, fmt.Errorf("读取本地数据源配置失败（%s）：%w", path, err)
	}
	var raw map[string]Datasource
	if err := yaml.Unmarshal(b, &raw); err != nil {
		return nil, fmt.Errorf("解析本地数据源配置失败（%s）：%w", path, err)
	}
	for name, ds := range raw {
		ds.Name = name
		raw[name] = ds
	}

		// FR-017：加载即校验必填字段，错误左移到运行前（可定位：数据源名 + 缺哪个字段）。
		// SPARK 数据源不要求 jdbcUrl（提交配置走 sparkHome/master，缺失由执行器判 SKIPPED）。
		for name, ds := range raw {
			if strings.ToUpper(ds.TypeCode) == "SPARK" {
				continue
			}
			if ds.JdbcURL == "" {
				return nil, fmt.Errorf("数据源 %q 配置不完整：缺少 jdbcUrl（%s）", name, path)
			}
			if ds.TypeCode == "" {
				return nil, fmt.Errorf("数据源 %q 配置不完整：缺少 typeCode（%s）", name, path)
			}
		}
	return raw, nil
}

// LookupDatasource 按逻辑名查本地连接。缺失或配置不完整（无 jdbcUrl）返回可定位错误（FR-007）。
// 返回的绝对路径 absPath 用于错误提示。
func LookupDatasource(workDir, logicalName string) (*Datasource, error) {
	if logicalName == "" {
		return nil, fmt.Errorf("任务未声明数据源逻辑名（SQL/PYTHON 任务需要 .weft/datasources.local.yaml 配置）")
	}
	all, err := LoadDatasources(workDir)
	if err != nil {
		return nil, err
	}
	ds, ok := all[logicalName]
	if !ok {
		return nil, fmt.Errorf("本地未配置数据源逻辑名 %q（请在 %s 添加；凭据本地持有，绝不上行）",
			logicalName, sync.DatasourcePath(workDir))
	}
	if strings.ToUpper(ds.TypeCode) != "SPARK" && ds.JdbcURL == "" {
		return nil, fmt.Errorf("数据源 %q 配置不完整：缺少 jdbcUrl（%s）",
			logicalName, sync.DatasourcePath(workDir))
	}
	ds.Name = logicalName
	return &ds, nil
}
