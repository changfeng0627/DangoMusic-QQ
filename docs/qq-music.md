# QQ 音乐与网易云双平台

播放器侧栏顶部选择「网易云 / QQ 音乐」。切换平台会刷新首页、搜索、我的歌单和账号信息；正在播放的队列仍保留。歌曲列表和底部播放器显示平台名称。

## 登录你自己的 QQ 音乐账号

选择 QQ 音乐后点击侧栏底部登录，提供三种入口：

- **QQ 扫码**：用手机 QQ 扫描，在手机上确认授权。
- **微信扫码**：用微信扫描，在手机上确认授权。
- **手机验证码**：输入国家区号（中国为 `86`）和绑定 QQ 音乐的手机号，点击发送验证码，再输入验证码登录。只有点击发送才会请求短信，客户端限制连续发送间隔为 60 秒。

扫码或输入验证码本身不代表登录成功。必须收到 QQ 音乐服务器返回的完整账号凭证并写入本地，界面才会显示成功。若平台要求安全验证，点击「打开安全验证」在浏览器完成后再重试；若接口不允许手机号登录，界面会提示改用扫码。没有对安全验证、付费或地区权限做绕过。

凭证分别位于游戏目录：

```text
config/dangomusic/netease_cookie.txt          # 原网易云凭证，保持兼容
config/dangomusic/qqmusic_credentials.json    # QQ / 微信 / 手机登录取得的 QQ 音乐凭证
config/dangomusic/config.json                # 原播放器与歌词设置
```

手机号、短信验证码和扫码中间票据只在内存中使用。QQ 凭证采用临时文件加原子替换保存；失败不会用不完整数据覆盖原账号。退出 QQ 音乐会清空其凭证。不要分享这两个凭证文件，也不要将其提交到代码仓库。

## 播放和歌词

QQ 搜索、歌曲详情、封面、播放地址和 LRC 歌词直接请求 QQ 音乐，无需安装本地 API 服务。播放时以当前歌曲所属平台取资源，与侧栏当前选中的平台无关。

当前解码器支持 **MP3**。QQ 只请求其正常授权返回的 `M500` / `M800` MP3，不会根据歌曲 ID 拼接一个可直接播放的地址。平台未返回地址时会提示检查登录、会员或地区权限。AAC、M4A、FLAC 和加密音频尚未实现解码，不能因为搜索成功就认为该歌曲一定可播放。

QQ 当前可能返回成功的 `midurlinfo`、已授权的 `purl` / vkey，却给出空 `sip` 列表。此时使用 QQ 音乐正常 CDN `https://isure.stream.qqmusic.qq.com/` 解析服务器原样返回的路径；没有有效路径或文件结果非零时仍拒绝播放。测试已使用用户正常登录后的授权资源验证了实际请求、完整音频读取和 MP3 解码，并在游戏中观察到 QQ 播放及声卡输出；不修改会员或资源权限。

网易云继续优先使用 YRC。QQ 使用普通 LRC，保留逐行时间戳和现有 3D 歌词显示，不虚构逐字时间。QQ 逐字 QRC、推荐歌单和排行榜尚未接入；QQ 首页显示自己的歌单及搜索提示。

## 共享架构

- `MusicTrack`、`MusicPlaylist`、`UserProfile` 与 `MusicService` 在 `common/core`，歌曲 ID 为字符串。
- `NeteaseMusicService` 保留并适配原来的 `NeteaseApi`；认证和媒体请求仍按原流程处理。
- `QQMusicService` 默认使用 `QQWebProvider`，`QQProvider` / `MusicTransport` 可替换，便于接口变更和 mock 测试；没有默认第三方代理或外部后端。
- `AudioSource` / `AudioDecoder` 通过 PCM 帧连接已有 Java Sound、seek、暂停和鼓点逻辑。当前实现为原 JLayer MP3 解码。
- 封面、歌词和歌单缓存使用 `platform:id`，队列可包含不同平台歌曲。缓存只在内存中，不混用凭证。
- 一起听状态带 `protocolVersion=2`、`platform` 和字符串 `trackId`。缺少平台字段的旧房间按网易云解释；未知平台或格式错误的数据安全忽略。参与者各自向对应平台获取资源，账号和音源不会通过房间分享。旧客户端不能播放 QQ 房间中的歌曲，应使用本次版本。
- UI 全部位于 `common/ui`，不在 11 个平台目标中复制音乐业务。

## 验证与限制

单元测试覆盖 QQ 新旧 JSON 字段、MID / media MID、授权空地址、格式拒绝、LRC、网易云新旧 JSON 和 YRC、平台缓存隔离、旧房间、Cookie 解析、不完整凭证回滚、短信输入及安全验证 URL 等。测试夹具仅含虚构凭证。

共享层测试可通过现代 Gradle wrapper 运行：

```bat
modern\gradlew.bat -p common :core:test :ui:compileKotlin
```

完整构建使用 `build-all.bat`，设置 `DANGO_JDK8`、`DANGO_JDK21`、`DANGO_JDK25` 指向对应 JDK。1.8.9 原依赖 `ForgeGradle-Modern:2.1` 并非 Forge 官方仓库中的发布物；`tools/bootstrap-legacy.ps1` 从其原始仓库固定提交 `94fc6aa3bdefbfd848256c9a2e365739232ee071` 编译到项目 `.gradle/maven`（或 `DANGO_MAVEN_REPO`）。该插件只参与构建，不进入 Mod JAR。

单元测试只使用虚构凭证。用户已在 1.21.1 NeoForge 上完成真实 QQ 扫码登录和授权播放链路测试，并确认 QQ 界面流畅；微信扫码、短信、安全验证、私人歌单完整内容及各种会员音质尚未全面验收。不能将 mock 或匿名搜索成功当成真实账号登录成功。接口为非官方协议，平台可能调整或停止提供；遇到授权错误应重新登录，不能保证所有账号、歌曲和地区均可用。

后续用户又反馈开始播放后世界界面严重卡顿。一次 90 秒采样抓到计算负载 94～99%，总显存约 2.6～2.8 / 8 GB，该轮未播放音乐，未抓到显存耗尽，不能据此排除其它场景问题。性能验收仍未完成。hotfix 4 让现代版窗口、Song Card 和灵动岛共享一套 30 Hz 模糊背景，并将每次小图形绘制的顶点存储分配改为追加写入复用的缓冲；前景保持游戏原帧率。该改动目前只完成 1.21.1 NeoForge 编译，其余现代目标须重新构建，实际 GPU 曲线和体感须复测。

### 账号界面与构建资源修复

QQ 登录状态从内存快照读取，渲染线程不读凭证文件，也不等待凭证保存。关闭登录弹窗可用 Esc，M 可关闭播放器。手机确认后继续显示具体授权阶段，失败时日志和弹窗仅包含阶段与安全错误码，不输出账号、票据或 Cookie。QQ OAuth 请求使用 QQ Connect 的 Origin / Referer，并测试了确认后的票据与重定向交换。

扫码每次使用独立内存会话，保留轮询、取票和授权跳转中的 Cookie，取消后不用于下一次登录。会话按 QQ 域名后缀、Secure、路径和有效期筛选 Cookie；没有使用 Java 默认的 RFC 2965 多级子域限制。取票跳转只允许列出的 HTTPS QQ 域名，拒绝把票据发到其它站点。

针对测试中反馈的 QQ 页卡顿，QQ 背景截图及 13 次模糊绘制最多每秒更新 30 次，控件、鼠标和文字继续按游戏帧率绘制；窗口尺寸变化时立即重建背景。网易云保持原刷新方式。预览版每个界面实例最多记录六条不含个人信息的 `UI timing`，包括平台、页面、帧间隔 p95 / 最大值、长帧数量及界面绘制耗时，用于区别渲染耗时与两次绘制之间的停顿。此调整需要在实际游戏环境复测，不能仅凭编译通过宣称卡顿消失。

本项目关闭 Gradle 常驻 daemon，Kotlin 编译放在本次构建进程内，最多两个构建 worker，Gradle 堆上限 2 GB。构建期间仍需要 Java 编译进程，构建结束即退出。旧版 Forge 插件附带 `tools/forgegradle-gradle7.patch` 修复 Gradle 7 的任务输入输出声明，bootstrap 会自动应用；该补丁的 LGPL 许可见同目录 `ForgeGradle-LICENSE.txt`。

## 许可证

项目继续采用 GPL-3.0，原有 JLayer（LGPL-2.1）、ZXing、Kotlin 和字体许可说明保留。新增 QQ 实现未捆绑或运行第三方 QQ API 项目，没有增加解码依赖。协议核对参考 [L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi) 和 [EllanStudio/qqmusic-api](https://github.com/EllanStudio/qqmusic-api) 的公开接口资料。构建插件 [ForgeGradle-Modern-2.1](https://github.com/ManTouQAQ/ForgeGradle-Modern-2.1) 的 LGPL 许可仅涉及构建工具；歌曲和封面版权归平台和权利人所有。
