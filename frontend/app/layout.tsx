import { Geist_Mono, Inter } from "next/font/google"

import "./globals.css"
import { ThemeProvider } from "@/components/theme-provider"
import { AppShell } from "@/components/app-shell"
import { AuthProvider } from "@/lib/auth"
import { Toaster } from "@/components/ui/sonner"
import { cn } from "@/lib/utils";

// 回归 shadcn 默认黑白主题：全局走 sans（Inter），不再用 serif。
// --font-serif / --font-heading 仍指向 Inter，使散落各处的显式 font-serif/font-heading 用法统一渲染为 sans。
const interSerif = Inter({subsets:['latin'],variable:'--font-serif'});

const interHeading = Inter({subsets:['latin'],variable:'--font-heading'});

export const metadata = {
  title: "DataWeave · AI 数据中台",
  description: "用 Agent 编织数据 —— AI Agent 原生的数据中台",
}

const inter = Inter({subsets:['latin'],variable:'--font-sans'})

const fontMono = Geist_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
})

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={cn("antialiased", fontMono.variable, inter.variable, interHeading.variable, interSerif.variable)}
    >
      <body>
        {/*
          阻塞脚本：作为 body 首个子元素，浏览器解析到此处时同步执行，
          把 localStorage 中的面板宽度设为 CSS 变量（挂在 <html> 上），
          确保首帧渲染即为正确宽度，消除闪动。
        */}
        <script
          dangerouslySetInnerHTML={{
            __html: `try{var w=localStorage.getItem("dw.agentRail.width");if(w&&+w>=340&&+w<=680)document.documentElement.style.setProperty("--dw-rail-width",w+"px")}catch(e){}`,
          }}
        />
        <ThemeProvider>
          <AuthProvider>
            <AppShell>{children}</AppShell>
            <Toaster richColors position="top-right" />
          </AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  )
}
