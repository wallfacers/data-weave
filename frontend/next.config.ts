import type { NextConfig } from "next"

const nextConfig: NextConfig = {
  async rewrites() {
    const backend = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8080"
    return [
      {
        source: "/api/:path*",
        destination: `${backend}/api/:path*`,
      },
    ]
  },
}

export default nextConfig
