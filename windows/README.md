# 流量看板 Windows · WinUI 3

原生 Windows VPN 订阅额度看板，版本 **1.0.0（公开测试版）**。使用 C#、.NET 10、WinUI 3 与 MVVM；源码位于本目录，Android 和网站代码独立维护。

## 下载与安装

[Windows 1.0.0 发布页](https://github.com/Max1426/traffic-board/releases/tag/windows-v1.0.0)

下载 `TrafficBoard-Windows-Setup-1.0.0-x64.exe`，双击按提示安装。目标系统为 Windows 10 22H2 / Windows 11 x64；当前仅在本机 Windows 11 验证过安装、启动和关闭。安装包包含 .NET 与 Windows App SDK 运行时，不需要预装开发工具。

安装范围为当前 Windows 用户，默认目录为 `%LOCALAPPDATA%\Programs\TrafficBoard`，提供开始菜单入口，可选择创建桌面快捷方式。安装包未做 Authenticode 代码签名。源码 ZIP 不能当作安装包运行。SHA-256 见发布附件 `SHA256SUMS.txt`。

## 功能与使用

1. 打开应用，点击“添加订阅”，填写名称并粘贴服务商提供的 **HTTPS 订阅链接**；高级设置可以修改 User-Agent，默认包含 `clash`。
2. 保存并刷新，在首页查看已用／总量、剩余量、实际使用比例、到期日和最近成功更新时间。
3. 点击“查看详情”，查看上传／下载和完整时间，可编辑、删除或单独刷新；首页也支持全部刷新。完整链接默认隐藏，只有主动显示或复制才会暴露。
4. 在“设置”切换跟随系统／浅色／深色外观，调整用量提醒（默认 80%、95%）和到期提醒（默认提前 7 天、3 天，以及已到期提醒）。

应用运行期间每六小时自动刷新，启动和系统恢复后补充更新过期数据。**关闭窗口即完全退出**，不注册托盘、开机启动或计划任务；退出后不会继续刷新或产生新的额度提醒。

界面使用 Windows 原生控件、强调色与图标；支持时使用 Mica，不支持或高对比模式下使用纯色回退。宽窗口双列卡片、窄窗口单列，不提供安卓按键震动。

## 数据、提醒与限制

- 使用 HTTPS GET 读取 `subscription-userinfo` 响应头，不建立 VPN、不运行代理内核，不下载或保存节点配置正文。套餐统计以服务商返回为准，可能延迟或涵盖其他设备。
- 上传加下载计算已用量。缺失、无效或重复字段显示未知；总额度为零不视为无限流量；超额显示真实比例，进度条最高填满。
- 区分超时、网络失败、失效链接、服务端异常和未提供统计。请求失败保留上次成功数据；成功响应缺少统计头则显示未提供统计信息。
- 仅在成功获取统计后判断提醒；相同状态去重记录加密持久化。系统通知不可用时保留应用内警示，不消耗提醒记录。Windows 专注模式可能隐藏通知横幅。
- 订阅、设置和统计使用 **DPAPI CurrentUser** 加密，以原子写入方式保存在 `%LOCALAPPDATA%\TrafficBoard\board.v1.enc`。读取失败保留原文件并阻止覆盖；不记录订阅链接到日志。
- 卸载默认保留数据，交互式卸载可明确选择删除。更换 Windows 用户或系统环境后，DPAPI 密文可能无法解密。
- Windows 与 Android 不同步，不提供导入导出、实时网速、历史趋势、桌面组件或常驻通知。

## 构建

在 Windows x64 上安装 `global.json` 指定的 .NET 10 SDK（10.0.401 或同功能带兼容补丁）和 Inno Setup 6，联网恢复 NuGet 依赖。WinUI 与 Windows SDK 构建工具由固定版本的 NuGet 引用提供；项目提交了依赖锁文件。

固定依赖包括 Windows App SDK **2.5.1**、Windows SDK BuildTools **10.0.26100.6584**、CommunityToolkit.Mvvm **8.4.2**。目标框架为 `net10.0-windows10.0.26100.0`，最低运行系统为 build 19045。

PowerShell 7，在本目录运行：

```powershell
# 测试、发布自包含程序、编译 EXE 安装包并生成校验文件
.\scripts\build.ps1

# 只运行核心测试
dotnet test .\tests\TrafficBoard.Tests\TrafficBoard.Tests.csproj -c Release

# 只发布应用目录；这不是额外的便携版交付
dotnet publish .\src\TrafficBoard.App\TrafficBoard.App.csproj -c Release -p:Platform=x64 -r win-x64 --self-contained true -o .\artifacts\publish
```

构建脚本自动寻找当前用户或 Program Files 中的 Inno Setup 6；也可用环境变量 `ISCC_PATH` 指向 `ISCC.exe`。正式交付文件位于 `dist/`。图标的 SVG 与 ICO 均已提交，日常构建无需 Python；可选的 `scripts/render_icon.py` 仅用于重新生成图标，依赖 Pillow。

发布目标会显式携带 `App.xbf`、`MainWindow.xbf` 和 `TrafficBoard.pri`，构建脚本检查这些 WinUI 必需资源是否存在，避免“构建成功但独立安装后无法启动”。

## 验证状态

验证记录见 [verification/验证结果.md](verification/验证结果.md)。

- **53 项核心测试通过**：解析和边界计算、输入校验、提醒去重与阈值更新、失败保留数据、编辑／删除时的旧请求保护、取消、加密原子存储、时钟驱动六小时刷新及 HTTP 行为。
- 本机 Windows 11 上实际安装成功；安装后的主程序和关键资源与发布目录 SHA-256 一致；冷启动出现“流量看板”主窗口，关闭窗口后 15 秒内进程退出。
- 开发阶段已实际操作添加／编辑和详情，通过 HTTP 测试服务返回的响应头验证正常统计；示例是虚构数据，不是真实服务商订阅，正式程序不预置订阅。
- **尚未完成**完整界面验收、Windows 通知投递及点击、100%／150%／200% 系统缩放、高对比模式、完整升级／卸载流程、无开发环境干净系统及 Windows 10 实机测试；未等待完整六小时验证长期运行。

自包含发布已经携带运行时，但当前尚无干净 Windows 设备实测，不将其表述为“所有设备均无需前置条件且验证通过”。请按测试版使用，并通过 [Issues](https://github.com/Max1426/traffic-board/issues) 提交复现步骤与系统版本，隐藏订阅链接和令牌。

## 开源

遵循仓库根目录 [MIT License](../LICENSE)；第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
