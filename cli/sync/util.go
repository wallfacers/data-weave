package sync

import (
	"os"
	"time"
)

// nowRFC3339 返回当前 UTC 时间的 RFC3339 字符串（写入 state.pulledAt）。
func nowRFC3339() string {
	return time.Now().UTC().Format(time.RFC3339)
}

// orStr：s 非空返回 s，否则返回 fallback。
func orStr(s, fallback string) string {
	if s != "" {
		return s
	}
	return fallback
}

// resolveWorkDir 返回工作副本根：opts 指定优先，缺省回退当前进程 cwd。
func resolveWorkDir(dir string) string {
	if dir != "" {
		return dir
	}
	if wd, err := os.Getwd(); err == nil {
		return wd
	}
	return "."
}
