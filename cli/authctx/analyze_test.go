package authctx

import "strings"
import "testing"

func TestFormatContext_RendersTablesGroundingNeighborsColumns(t *testing.T) {
	raw := []byte(`{
	  "taskRef":"10","depthUsed":3,
	  "reads":[{"table":"ods.user","groundingState":"PRESENT","direction":"READS",
	            "neighbors":[{"name":"src.raw","kind":"TABLE","hop":1}]}],
	  "writes":[{"table":"dw.user_daily","groundingState":"UNGROUNDED","direction":"WRITES","neighbors":[]}],
	  "columnLineage":[{"srcTable":"ods.user","srcColumn":"id","dstTable":"dw.user_daily","dstColumn":"id"}],
	  "truncated":[],"partial":[]
	}`)
	out, err := FormatContext(raw)
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	for _, want := range []string{
		"任务 10（深度 3）",
		"ods.user [PRESENT]",
		"上游: src.raw(1)",
		"dw.user_daily [UNGROUNDED]",
		"ods.user.id → dw.user_daily.id",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q\n---\n%s", want, out)
		}
	}
}

func TestFormatContext_SurfacesPartialAndTruncation(t *testing.T) {
	raw := []byte(`{"taskRef":"9","depthUsed":3,"reads":[],"writes":[],"columnLineage":[],
	  "truncated":[{"at":"ods.big","reason":"邻居数超阈值"}],
	  "partial":[{"source":"grounding","reason":"探测降级"}]}`)
	out, err := FormatContext(raw)
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if !strings.Contains(out, "⚠ 截断 ods.big") || !strings.Contains(out, "⚠ 缺失 grounding") {
		t.Errorf("degradation notes not surfaced:\n%s", out)
	}
}

func TestFormatDeps_RendersOriginAndDirection(t *testing.T) {
	raw := []byte(`{"taskRef":"10",
	  "upstream":[{"fromTaskRef":"20","toTaskRef":"10","hop":1,"origin":"BOTH"}],
	  "downstream":[{"fromTaskRef":"10","toTaskRef":"30","hop":2,"origin":"DERIVED"}]}`)
	out, err := FormatDeps(raw)
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if !strings.Contains(out, "20 → 10 [BOTH] (跳距 1)") {
		t.Errorf("upstream edge not rendered:\n%s", out)
	}
	if !strings.Contains(out, "10 → 30 [DERIVED] (跳距 2)") {
		t.Errorf("downstream edge not rendered:\n%s", out)
	}
}

func TestFormatContext_RejectsBadJSON(t *testing.T) {
	if _, err := FormatContext([]byte("not json")); err == nil {
		t.Error("expected error on malformed JSON")
	}
}
