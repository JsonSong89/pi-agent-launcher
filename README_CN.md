# Pi Agent Launcher

一键启动 [Pi coding agent](https://pi.dev) 的 JetBrains IDE 插件 — 在 Terminal 窗口中打开 "Pi" tab 并自动启动 `pi`。

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-orange)](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## 功能

- **一键启动** — 点击工具栏 π 按钮启动 Pi
- **终端集成** — Pi 作为 IDE Terminal 窗口的一个 tab（和 Local 并列）
- **会话持久化** — 会话列表跨 IDE 重启保留（id/name 与 pi 自身会话文件对齐），点击时按需 `pi --session` 接回
- **实时会话绑定** — bridge extension 同步 terminal 内 `/new`、`/fork`、`/resume` 造成的会话切换
- **完成通知** — agent 结束时气泡通知（默认关闭）
- **打开 Pi 修改的文件** — 自动刷新/打开 Pi 编辑过的文件（默认关闭）
- **发送选区** — 选中代码 → 右键 → "Send to Pi"，自动插入 `@path/file.go#L10-25` 到 Pi 输入框
- **模型配置** — 从 `~/.pi/agent/models.json` 加载模型列表

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Cmd+Esc` / `Ctrl+Esc` | 启动或聚焦 Pi |
| `Cmd+Shift+P` / `Ctrl+Shift+P` | 发送选区到 Pi |

## 快速开始

1. 从 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher) 安装插件
2. 确保 `pi` CLI 已安装且在 PATH 中
3. 点击工具栏 **π** 按钮
4. Terminal 窗口中打开 "Pi" tab，自动启动 `pi`

## 配置

**Settings → Tools → Pi Agent**

- **Model** — 从 `~/.pi/agent/models.json` 中选择模型
- **Custom model id** — 自定义模型标识符
- **Thinking level** — Default / none / low / medium / high / max
- **Pi command** — pi 二进制路径
- **Extra arguments** — 额外 CLI 参数
- **Send shortcut** — Ctrl+Enter 或 Enter
- **Conversation font size** — 只改字号，字体跟随 Editor → Font
- **Notify on agent completion** — agent 结束时气泡通知（默认关闭）
- **Open files modified by Pi** — 自动刷新/打开 Pi 编辑过的文件（默认关闭）

## 支持的 IDE

支持所有 JetBrains IDE：IntelliJ IDEA、GoLand、PyCharm、WebStorm、PhpStorm、CLion、Rider、RubyMine 等。

## 开发

```bash
# 构建
./gradlew build

# 运行沙箱 IDE 测试
./gradlew runIde

# 打包
./gradlew buildPlugin
```

## 许可证

MIT
