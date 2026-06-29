package run

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/dataweave/dw/sync"
)

func writeDatasources(t *testing.T, dir, content string) {
	t.Helper()
	if err := os.MkdirAll(sync.WeftDir(dir), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(sync.DatasourcePath(dir), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestLoadDatasourcesParsesMultiple(t *testing.T) {
	dir := t.TempDir()
	writeDatasources(t, dir, `
warehouse_pg:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/warehouse
  username: dev
  password: devpass
ods_mysql:
  typeCode: MYSQL
  jdbcUrl: jdbc:mysql://localhost:3306/ods
  username: root
  password: ""
`)
	all, err := LoadDatasources(dir)
	if err != nil {
		t.Fatalf("LoadDatasources: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("expected 2 datasources, got %d: %+v", len(all), all)
	}
	pg := all["warehouse_pg"]
	if pg.TypeCode != "POSTGRESQL" || pg.Username != "dev" || pg.JdbcURL == "" {
		t.Fatalf("pg mismatch: %+v", pg)
	}
	if pg.Name != "warehouse_pg" {
		t.Fatalf("Name not filled from key: %q", pg.Name)
	}
}

func TestLoadDatasourcesMissingFileReturnsEmpty(t *testing.T) {
	all, err := LoadDatasources(t.TempDir())
	if err != nil {
		t.Fatalf("missing file should not error: %v", err)
	}
	if len(all) != 0 {
		t.Fatalf("expected empty map, got %+v", all)
	}
}

func TestLoadDatasourcesCorruptErrors(t *testing.T) {
	dir := t.TempDir()
	writeDatasources(t, dir, `
bad: : : not yaml
`)
	if _, err := LoadDatasources(dir); err == nil {
		t.Fatal("expected error for corrupt yaml")
	}
}

func TestLookupDatasourcesHit(t *testing.T) {
	dir := t.TempDir()
	writeDatasources(t, dir, `
wh:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/wh
  username: u
  password: p
`)
	ds, err := LookupDatasource(dir, "wh")
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if ds.JdbcURL != "jdbc:postgresql://localhost:5432/wh" {
		t.Fatalf("jdbcUrl = %q", ds.JdbcURL)
	}
	// JSON 序列化应含完整凭据（仅传本地 runner 子进程，不经网络）
	b, _ := json.Marshal(ds)
	if !strings.Contains(string(b), `"password":"p"`) || !strings.Contains(string(b), `"name":"wh"`) {
		t.Fatalf("JSON missing fields: %s", b)
	}
}

func TestLookupDatasourcesMissingNameErrors(t *testing.T) {
	dir := t.TempDir()
	writeDatasources(t, dir, `
wh:
  typeCode: POSTGRESQL
  jdbcUrl: x
  username: u
  password: p
`)
	_, err := LookupDatasource(dir, "nonexistent")
	if err == nil {
		t.Fatal("expected error for missing logical name")
	}
	if !strings.Contains(err.Error(), "nonexistent") {
		t.Fatalf("error should name the missing datasource: %v", err)
	}
}

func TestLookupDatasourcesIncompleteErrors(t *testing.T) {
	dir := t.TempDir()
	// FR-017：LoadDatasources 现已做提前校验 —— 缺 jdbcUrl 在加载时即报错
	writeDatasources(t, dir, `
wh:
  typeCode: POSTGRESQL
  # missing jdbcUrl
  username: u
`)
	_, err := LoadDatasources(dir)
	if err == nil {
		t.Fatal("expected error for incomplete datasource at load time (no jdbcUrl)")
	}
	if !strings.Contains(err.Error(), "jdbcUrl") {
		t.Fatalf("error should mention missing jdbcUrl: %v", err)
	}
	if !strings.Contains(err.Error(), "wh") {
		t.Fatalf("error should name the datasource 'wh': %v", err)
	}
}

func TestLoadDatasourcesMissingTypeCodeErrors(t *testing.T) {
	dir := t.TempDir()
	// FR-017：缺 typeCode 也应在加载时报错
	writeDatasources(t, dir, `
wh:
  jdbcUrl: jdbc:postgresql://localhost/db
  username: u
  password: p
`)
	_, err := LoadDatasources(dir)
	if err == nil {
		t.Fatal("expected error for datasource missing typeCode")
	}
	if !strings.Contains(err.Error(), "typeCode") {
		t.Fatalf("error should mention missing typeCode: %v", err)
	}
}

func TestLookupDatasourcesIncompleteAtLookupTime(t *testing.T) {
	// 通过 LoadDatasources 校验的数据源，在 Lookup 时仍可因缺失报错
	// （提前校验已覆盖 jdbcUrl/typeCode，此测试验证 Lookup 对逻辑名缺失的报错）
	dir := t.TempDir()
	writeDatasources(t, dir, `
wh:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost/db
  username: u
  password: p
`)
	_, err := LookupDatasource(dir, "nonexistent")
	if err == nil {
		t.Fatal("expected error for missing logical name")
	}
	if !strings.Contains(err.Error(), "nonexistent") {
		t.Fatalf("error should name the missing datasource: %v", err)
	}
}

func TestLookupDatasourcesEmptyNameErrors(t *testing.T) {
	_, err := LookupDatasource(t.TempDir(), "")
	if err == nil {
		t.Fatal("expected error for empty logical name")
	}
}
