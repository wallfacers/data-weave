package sync

import (
	"encoding/json"
	"strconv"

	"github.com/dataweave/dw/client"
)

// ResolveProjectID 把 `dw pull <project>` 的入参解析为项目 id。
// 入参为纯数字 → 直接当作 projectId（code 未知，留空）；
// 否则按 code 精确查询 GET /api/projects?search=<code>，命中唯一者取其 id。
// 0 命中 → 可定位错误；>1 命中 → 歧义错误（提示用数字 id）。
//
// 返回 (id, code)：code 用于回填 .weft/state.json 的 projectCode（人类可读）。
func ResolveProjectID(cfg client.Config, project string) (int64, string, error) {
	if project == "" {
		return 0, "", client.UsageError("缺少项目参数。用法：dw pull <project>")
	}
	// 纯数字 → 直接当 id。
	if id, err := strconv.ParseInt(project, 10, 64); err == nil {
		return id, "", nil
	}
	// 按 code 搜索（GET /api/projects?search=… 走分页 query，返回 {items:[…]}）。
	// 分页键是 items —— 与 ProjectController.list/User/Role 控制器平台惯例一致。
	path := "/api/projects?search=" + project + "&size=100"
	data, err := client.Do(cfg, "GET", path, nil)
	if err != nil {
		return 0, "", err
	}
	var page struct {
		Items []struct {
			ID   int64  `json:"id"`
			Code string `json:"code"`
			Name string `json:"name"`
		} `json:"items"`
	}
	if err := json.Unmarshal(data, &page); err != nil {
		return 0, "", client.UsageError("解析项目列表失败：%v", err)
	}
	// 精确匹配 code（search 是 LIKE，可能匹配 name）。
	var hits []int64
	for _, p := range page.Items {
		if p.Code == project {
			hits = append(hits, p.ID)
		}
	}
	if len(hits) == 0 {
		return 0, "", client.UsageError("未找到 code 为 %q 的项目（可用 `dw pull <数字id>` 或核对项目 code）", project)
	}
	if len(hits) > 1 {
		return 0, "", client.UsageError("code %q 匹配到 %d 个项目（歧义），请用数字 id：%v",
			project, len(hits), hits)
	}
	return hits[0], project, nil
}
