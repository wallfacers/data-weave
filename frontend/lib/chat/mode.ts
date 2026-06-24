/**
 * 聊天 provider 模式开关（proactive-agent-discovery §D5）。
 *
 * 后端 🅰 主动发现链路（Inspector/AgentNotifier//api/agent/stream//api/findings）未就绪时，
 * 前端用 mock 顶住契约，使"聊天台渲染输入框、对 mock 流式出字、举手台渲染 mock Finding、
 * Agent 主动开口"可独立自洽。两窗口合流后置 `NEXT_PUBLIC_CHAT_MOCK=0` 即对真接口，
 * provider 切换、上层组件零改动。
 *
 * 默认 mock（后端未就绪）；显式 "0" 走真接口。
 */
export const CHAT_USE_MOCK = process.env.NEXT_PUBLIC_CHAT_MOCK !== "0"
