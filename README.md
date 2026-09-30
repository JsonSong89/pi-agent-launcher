# Pi Agent Launcher

[中文文档](README_CN.md)

One-click [Pi coding agent](https://pi.dev) launcher for JetBrains IDEs — opens a "Pi" tab inside the Terminal tool window and starts `pi` automatically.

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-orange)](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## Features

- **One-click launch** — Click the π button in the toolbar to start Pi
- **Terminal integration** — Pi runs as a tab inside the IDE's Terminal window (alongside Local)
- **Persistent conversation manager** — Sessions survive IDE restarts (id/name aligned with pi's own session files); reattach with `pi --session` on demand
- **Live session binding** — A bridge extension keeps the IDE in sync when you run `/new`, `/fork` or `/resume` inside the terminal
- **Completion notifications** — Optional balloon when the agent settles (off by default)
- **Open files modified by Pi** — Optional auto-open/refresh in the editor (off by default)
- **Send to Pi** — Select code → Right-click → "Send to Pi" inserts `@path/file.go#L10-25` into Pi's input
- **Model configuration** — Pick model and thinking level from `~/.pi/agent/models.json`

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+Esc` / `Ctrl+Esc` | Launch or focus Pi |
| `Cmd+Shift+P` / `Ctrl+Shift+P` | Send selection to Pi |

## Quick Start

1. Install the plugin from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
2. Ensure `pi` CLI is installed and in your PATH
3. Click the **π** button in the toolbar
4. A "Pi" tab opens in the Terminal window and `pi` starts automatically

## Configuration

**Settings → Tools → Pi Agent**

- **Model** — Select from models defined in `~/.pi/agent/models.json`
- **Custom model id** — Override the dropdown with any model identifier
- **Thinking level** — Default / none / low / medium / high / max
- **Pi command** — Custom path to pi binary
- **Extra arguments** — Additional CLI flags
- **Send shortcut** — Ctrl+Enter or Enter
- **Conversation font size** — Size only; family follows Editor → Font
- **Notify on agent completion** — Balloon when the agent settles (default off)
- **Open files modified by Pi** — Auto-open/refresh files Pi edits (default off)

## Supported IDEs

Works with all JetBrains IDEs: IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, CLion, Rider, RubyMine, and more.

## Development

```bash
# Build
./gradlew build

# Run sandbox IDE for testing
./gradlew runIde

# Package
./gradlew buildPlugin
```

## Project Structure

```
src/main/kotlin/com/piagent/launcher/
├── actions/
│   ├── LaunchPiAction.kt          # Toolbar button → launch Pi
│   ├── OpenPiAction.kt            # Cmd+Esc → focus Pi
│   └── SendSelectionAction.kt     # Send @file#L reference
├── bridge/
│   ├── PiBridgeServer.kt        # Loopback TCP server: events from pi extension
│   └── PiBridgeInstaller.kt     # Writes ~/.pi/agent/extensions/pi-launcher-bridge.ts
├── conversations/
│   ├── PiConversation.kt        # Conversation model (id + piSessionId)
│   ├── PiConversationStore.kt   # PersistentStateComponent index
│   └── PiConversationService.kt # Conversation lifecycle + bridge routing
├── services/
│   ├── PiTerminalService.kt       # Terminal lifecycle + send text
│   └── PiStatusWidget.kt          # Status bar running count
└── settings/
    ├── PiSettings.kt              # Persistent config
    ├── PiSettingsConfigurable.kt  # Settings UI panel
    └── PiModelLoader.kt           # Load models from models.json
```

## License

MIT
