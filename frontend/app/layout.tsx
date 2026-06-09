import { Geist, Geist_Mono, Raleway, Figtree, Space_Grotesk, Inter } from "next/font/google"

import "./globals.css"
import { ThemeProvider } from "@/components/theme-provider"
import { AppShell } from "@/components/app-shell"
import { cn } from "@/lib/utils";

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
      className={cn("antialiased", fontMono.variable, "font-sans", inter.variable, interHeading.variable)}
    >
      <body>
        <ThemeProvider>
          <AppShell>{children}</AppShell>
        </ThemeProvider>
      </body>
    </html>
  )
}
