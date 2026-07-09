import { describe, it, expect } from "vitest"
import { taskTypeToLang, TASK_TYPES } from "./params-table"

describe("TASK_TYPES — 创建入口暴露的任务类型集合", () => {
  it("暴露全部 8 种大数据开发任务类型（SC-002）", () => {
    expect(TASK_TYPES).toEqual([
      "SQL",
      "SHELL",
      "PYTHON",
      "SPARK",
      "HIVE",
      "FLINK",
      "DATAX",
      "SEATUNNEL",
    ])
  })

  it("不暴露仅测试内部用的 ECHO", () => {
    expect(TASK_TYPES).not.toContain("ECHO")
  })
})

describe("taskTypeToLang — 编辑器语言高亮映射（T030）", () => {
  it.each([
    ["SQL", "sql"],
    ["SHELL", "bash"],
    ["PYTHON", "python"],
    ["SPARK", "scala"],
    ["HIVE", "sql"],
    ["FLINK", "sql"],
    ["DATAX", "json"],
    ["SEATUNNEL", "text"],
  ])("类型 %s → 高亮语言 %s", (type, lang) => {
    expect(taskTypeToLang(type)).toBe(lang)
  })

  it("大小写不敏感", () => {
    expect(taskTypeToLang("sql")).toBe("sql")
    expect(taskTypeToLang("Flink")).toBe("sql")
    expect(taskTypeToLang("datax")).toBe("json")
  })

  it("未知类型回退为 text（无高亮，不报错）", () => {
    expect(taskTypeToLang("UNKNOWN")).toBe("text")
    expect(taskTypeToLang("")).toBe("text")
  })
})
