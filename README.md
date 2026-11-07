# TTS Bridge

把 Android 设备变成一台 **MCP 语音服务器**：AI Agent 通过 MCP（Model Context Protocol）调用
`speak` 等工具，即可驱动设备的系统 TTS（TextToSpeech）开口说话。

- Kotlin + Jetpack Compose + Material 3（Android 12+ 动态取色 Material You）
- 应用图标为矢量图（Vector Drawable / 自适应图标，无 PNG）
- 内嵌 Ktor CIO 服务器，实现 MCP **Streamable HTTP** 传输（JSON-RPC 2.0）
- `speak` 为同步语义：返回结果时语音已播放完成，Agent 可放心串接后续动作
- 前台服务保活：App 退到后台后端点依然在线

## 架构

```
┌──────────────┐   HTTP POST /mcp (JSON-RPC 2.0)   ┌─────────────────────────────┐
│  AI Agent    │ ────────────────────────────────▶ │ McpServerService (前台服务)  │
│ (MCP Client) │                                   │   └─ Ktor CIO :8765         │
└──────────────┘ ◀──────────────────────────────── │      └─ McpProtocol         │
                            工具执行结果            │          └─ TtsManager       │
                                                  │              └─ System TTS   │
                                                  └─────────────────────────────┘
```

| 模块 | 职责 |
| --- | --- |
| `tts/TtsManager.kt` | 封装 `TextToSpeech`：同步朗读、语言/语音/语速/音调、状态流、失败重试 |
| `mcp/McpProtocol.kt` | MCP 协议：`initialize` / `tools/list` / `tools/call` / `ping` |
| `mcp/McpServerController.kt` | Ktor 服务器生命周期、`/mcp` `/health` 路由 |
| `mcp/McpServerService.kt` | 前台服务，保证后台可连接 |
| `ui/MainScreen.kt` | MD3 界面：服务开关、端点展示、本地测试、工具清单 |
| `res/drawable/ic_launcher_foreground.xml` | 矢量应用图标 |

## MCP 工具一览

| 工具 | 说明 | 参数 |
| --- | --- | --- |
| `speak` | 朗读文本（同步，播完才返回） | `text`(必填), `queue`, `language`, `voice`, `rate`, `pitch` |
| `stop_speaking` | 立即停止朗读 | — |
| `list_voices` | 列出可用语音 | `language`(过滤) |
| `set_voice` | 切换语音 | `voice`(必填) |
| `set_language` | 切换语言 | `language`(必填) |
| `set_speech_rate` | 设置语速 0.1~4.0 | `rate`(必填) |
| `set_pitch` | 设置音调 0.1~2.0 | `pitch`(必填) |
| `tts_status` | 查看当前 TTS 状态 | — |

## Agent 接入

开启 App 内的 MCP 服务后，界面会显示设备 IP。在 MCP 客户端配置（Agent 与 App 在同一台设备时把 `<设备IP>` 换成 `127.0.0.1`，走本地回环，不受 Wi-Fi 影响）：

```json
{
  "mcpServers": {
    "tts-bridge": {
      "url": "http://<设备IP>:8765/mcp"
    }
  }
}
```

只支持 stdio 的客户端（如 Claude Desktop）可以用 `mcp-remote` 桥接：

```json
{
  "mcpServers": {
    "tts-bridge": {
      "command": "npx",
      "args": ["mcp-remote", "http://<设备IP>:8765/mcp"]
    }
  }
}
```

调试小抄（不开 Agent 也能测）：

```bash
# 健康检查
curl http://<设备IP>:8765/health

# 直接调 speak 工具
curl -X POST http://<设备IP>:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"speak","arguments":{"text":"你好，世界"}}}'
```

## 构建

### GitHub Actions（推荐）

推送/合并到 `main` 自动构建，产物在 Actions 的 Artifacts 里下载：

- `tts-bridge-debug-apk`（可直接安装调试）
- `tts-bridge-release-apk`（未签名 release，需自行签名）

CI 使用 `gradle/actions/setup-gradle` 提供 Gradle 8.13。若想让仓库自带 wrapper，
本地装好 Gradle 后执行一次 `gradle wrapper --gradle-version 8.13` 并提交即可。

### 本地

```bash
gradle :app:assembleDebug        # 或 ./gradlew :app:assembleDebug
```

环境要求：JDK 17、Android SDK（compileSdk 35）、Gradle 8.13+ / AGP 8.7.3 / Kotlin 2.1.0。

## 故障排查

**一直显示"TTS 引擎未就绪" / 无法朗读**

1. `AndroidManifest.xml` 必须声明包可见性（Android 11+ 查不到引擎是头号原因，本项目已含）：
   ```xml
   <queries>
       <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
   </queries>
   ```
   没有它，`TextToSpeech.onInit` 会稳定收到 `ERROR`，无论设备装没装引擎。
2. 确认设备装有语音引擎（Google 语音服务 / 讯飞等），并在「系统设置 → 语音」下载好语言数据。
3. 装好后回到 App 点「重新初始化 TTS」，界面会显示真实错误原因（如有）。
4. `set_language` 报"缺少语言数据"→ 到语音设置下载对应语言包。

**朗读没声音**

- 检查媒体音量（TTS 走媒体音轨）；`set_speech_rate` 别调过快；部分引擎对空文本/纯符号会静默跳过。

**Agent 连不上**

- 手机和 Agent 设备同一局域网；`curl http://<设备IP>:8765/health` 应返回 `{"status":"ok",...}`；
- 关闭代理/VPN 对局域网的劫持；部分公司网络会隔离客户端。

## 后台保活

Android 的 Doze / 电池优化会在闲置后限制后台服务，导致 Agent 连不上。App 顶栏 `⋮` 菜单提供：

- **申请电池优化白名单**：弹系统对话框一次性授权（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
- **电池优化设置列表**：跳转系统电池优化页面手动调整（`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）

- **唤醒锁开关**（顶栏 `⋮` 菜单）：默认开启——即使走本地回环，息屏深度睡眠时 CPU 挂起也无法响应；纯省电场景可手动关闭。

部分厂商 ROM（小米 / 华为 / OPPO 等）还需在「自启动管理」里放行。


**后台 / 息屏后 Agent 连不上**

1. 先看通知栏有没有常驻「TTS Bridge 运行中」通知——没有就是前台服务没跑起来（去系统设置允许本 App 通知）；
2. 用 `curl http://<设备IP>:8765/health` 区分症状：
   - **立即 connection refused** → 进程被杀：点顶栏菜单「申请电池优化白名单」、厂商 ROM 里放行「自启动」并把省电策略设为「无限制」，避免用「一键清理」杀掉本 App；
   - **一直 timeout** → CPU 休眠：App 已持有 WakeLock 保持 CPU 唤醒（本地回环调用同样依赖它）；若从其他设备经局域网访问，另请在 WLAN 设置开启「睡眠时保持 WLAN 连接」；
   - **返回正常** → 网络层没问题，是 TTS 层问题，看 App 内错误提示。


## 权限与限制

- `INTERNET`：MCP HTTP 服务器；`FOREGROUND_SERVICE(_SPECIAL_USE)`：后台保活；
  `POST_NOTIFICATIONS`：Android 13+ 显示服务通知
- **服务监听整个局域网且无鉴权**，请只在可信网络使用，勿暴露公网（可自行加 Bearer Token 校验）
- 设备需安装语音引擎（如 Google 语音服务）并下载对应语言数据
- `minSdk 26`（Android 8.0+）

## 可扩展方向

- 鉴权：在 `/mcp` 校验 `Authorization: Bearer <token>`
- 通知类工具：`notify`、`vibrate`、`show_toast`
- SSE 反向推送：朗读进度、打断事件（Streamable HTTP 的 GET 通道）
- 多设备：按设备名路由，或做成 Wear OS 版本
