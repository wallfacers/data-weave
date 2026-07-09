import type { NextConfig } from "next"
import createNextIntlPlugin from "next-intl/plugin"

const withNextIntl = createNextIntlPlugin("./i18n/request.ts")

const nextConfig: NextConfig = {
  // dev 下 React StrictMode 会「挂载→卸载→再挂载」，令每个取数 useEffect 跑两遍，
  // 造成 Network 面板里所有请求成对出现（两条都成功）。取数层无请求去重，故两次都真发。
  // 生产构建从不双跑，此现象仅 dev 可见；关闭以消除开发期的重复请求噪音。
  reactStrictMode: false,
  async rewrites() {
    const backend = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8000"
    return [
      {
        source: "/api/:path*",
        destination: `${backend}/api/:path*`,
      },
    ]
  },
}

export default withNextIntl(nextConfig)
