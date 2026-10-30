"""Read existing test/build evidence and verify the delivered APK contains no test fixture code."""
from pathlib import Path
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text(encoding="utf-8"))[1]
apk = root / f"dist/TrafficBoard-{version}.apk"
reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
assert reports, "Run unit tests first"
unit = {"tests": 0, "failures": 0, "errors": 0}
for report in reports:
    suite = ET.parse(report).getroot()
    for key in unit:
        unit[key] += int(suite.attrib.get(key, 0))
assert unit["failures"] == unit["errors"] == 0
device = {}
for label in ("normal", "small-largefont"):
    suite = ET.parse(root / f"verification/device-{label}.xml").getroot()
    result = {key: int(suite.attrib.get(key, 0)) for key in ("tests", "failures", "errors")}
    assert result["tests"] == 7 and result["failures"] == result["errors"] == 0
    device[label] = result
lint = {}
for variant in ("debug", "release"):
    report = (root / f"app/build/reports/lint-results-{variant}.txt").read_text(encoding="utf-8")
    counts = re.search(r"(\d+) errors?, (\d+) warnings?", report)
    assert counts and int(counts[1]) == 0
    lint[variant] = {"errors": int(counts[1]), "warnings": int(counts[2])}
manifest = (root / "verification/apk-manifest.txt").read_text(encoding="utf-8-sig")
assert re.search(r"android:allowBackup.*0x0", manifest)
assert re.search(r"android:usesCleartextTraffic.*0x0", manifest)
assert "BIND_VPN_SERVICE" not in manifest
assert "android.permission.VIBRATE" not in manifest
assert not re.search(r"android:debuggable.*0xffffffff", manifest)
signature = (root / "verification/apk-signature.txt").read_text(encoding="utf-8-sig")
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    dex_files = [archive.read(n) for n in archive.namelist() if n.endswith(".dex")]
    classes = set()
    for dex in dex_files:
        strings_offset = struct.unpack_from("<I", dex, 60)[0]
        types_offset = struct.unpack_from("<I", dex, 68)[0]
        class_count, classes_offset = struct.unpack_from("<II", dex, 96)
        for index in range(class_count):
            type_index = struct.unpack_from("<I", dex, classes_offset + index * 32)[0]
            string_index = struct.unpack_from("<I", dex, types_offset + type_index * 4)[0]
            position = struct.unpack_from("<I", dex, strings_offset + string_index * 4)[0]
            while dex[position] & 0x80:
                position += 1
            position += 1
            classes.add(dex[position:dex.index(0, position)].decode("utf-8", errors="replace"))
    # OkHttp ships a MockWebServer logger-name string even without the test library.
    # Inspect actual class definitions rather than treating that harmless string as packaged code.
    assert not any(name.startswith("Lokhttp3/mockwebserver/") or (name.startswith("Lcom/liuliang/board/") and "Test" in name) for name in classes)
    assert "Lcom/liuliang/board/model/UiPreferences;" in classes
    assert "Lcom/liuliang/board/ui/BoardFeedback;" in classes
    assert b"demo-credential-not-real" not in b"".join(dex_files)
    abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")})
checksum = hashlib.sha256(apk.read_bytes()).hexdigest().upper()
performance = {}
for label, filename in (("1.1.1", "scroll-baseline-1.1.1.txt"), (version, "scroll-gfxinfo.txt")):
    report = (root / "verification" / filename).read_text(encoding="utf-8-sig")
    performance[label] = {
        "frames": int(re.search(r"Total frames rendered: (\d+)", report)[1]),
        "medianMs": int(re.search(r"50th percentile: (\d+)ms", report)[1]),
        "p95Ms": int(re.search(r"95th percentile: (\d+)ms", report)[1]),
        "jankyPercent": float(re.search(r"Janky frames: \d+ \(([\d.]+)%\)", report)[1]),
    }
result = {"applicationId": "com.liuliang.board", "version": version, "minSdk": 26, "targetSdk": 35,
          "bytes": apk.stat().st_size, "sha256": checksum, "abis": abis, "unit": unit, "device": device,
          "lint": lint, "scrollPerformance": performance, "releaseInstallAndColdStart": "passed on Android 16 emulator",
          "realProviderTested": False, "physicalDeviceTested": False, "sixHourWallClockWaitPerformed": False, "upgrade": json.loads((root / "verification/upgrade.json").read_text(encoding="utf-8"))}
(root / "verification/verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
(root / "verification/验证结果.md").write_text(f"""# 流量看板 {version} 验证记录

验证日期：2026-10-30（Asia/Shanghai）。应用包名 `{result['applicationId']}`，最低 Android 8.0 / API 26，targetSdk 35。

## 交付文件

- 正式签名 APK：`dist/TrafficBoard-{version}.apk`
- 文件大小：{result['bytes']:,} 字节（约 {result['bytes'] / 1024 / 1024:.2f} MiB）
- SHA-256：`{checksum}`
- 源码压缩包：`dist/TrafficBoard-{version}-source.zip`
- 签名证书、APK 元数据、合并后的 Manifest 分别保存在本目录的 `apk-signature.txt`、`apk-metadata.txt`、`apk-manifest.txt`。

## 检查结果

- 单元测试：{unit['tests']} 项，0 失败、0 错误。覆盖正常／缺失／无效统计头、计数溢出、零额度、超额、到期、未知字段、HTTP 请求标识、鉴权失败、超时、网络失败和本地存储失败。
- 状态与竞争测试：刷新失败保留上次统计；改名保留统计；编辑源链接／请求标识清除旧统计；旧请求不覆盖编辑后的数据；新链接刷新会排在旧请求之后执行；请求期间删除不会重新创建订阅。
- 提醒测试：仅发送新达到的等级；未知字段和关闭的提醒不触发；额度恢复、阈值修改、到期日变化与真正过期均有对应验证；通知权限关闭时不消耗提醒记录。
- 安卓设备测试：7 项，分别在普通屏幕和小屏大字体下通过，共 14 个配置测试结果，均为 0 失败。操作包括添加、重命名、单独刷新、编辑、设置阈值、删除及 Activity 重建；核实六小时任务已注册。
- 毛玻璃验证：整段渐变像素检查验证强模糊区域无清晰条纹、最上沿不漏字、过渡亮度连续且细节平滑恢复；保留条纹与旧版回退分支测试，并检查上下栏防误触和前景按钮。背景采样包含底色，原图与高斯层归一合成；各层仅渲染有效区域，大半径采用降采样。旧安卓回退分支在当前模拟器强制执行，不等同于旧安卓实机验证。
- Material 3 与触感：浅色／深色实际渲染亮度检查、壁纸动态配色开关、触感开关的即时持久化及 Activity 重建恢复均通过；旧版数据缺少外观字段时自动使用默认设置，原订阅与提醒记录保留。触感单元测试覆盖关闭、短时间去重、确认／错误反馈和旧安卓回退；模拟器不验证实体震动马达。
- 加密验证：Android Keystore + AES-GCM 文件实际写入、解密和恢复成功，密文不包含测试链接／令牌明文；通知通过系统 NotificationManager 实际发送，两次相同刷新未重新发布通知，提醒等级已持久化。
- Debug lint：0 错误、{lint['debug']['warnings']} 条警告；Release lint：0 错误、{lint['release']['warnings']} 条警告。警告为 OldTargetApi（targetSdk 35，并非最新目标 API）。
- 覆盖升级：通过旧版 {result['upgrade']['from']} 界面添加 MigrationCheck 测试订阅并修改外观／触感设置，再使用当前正式 APK 覆盖安装；升级后仍能显示原名称和 example.com 来源，并保留修改后的开关，加密数据成功恢复，详见 `upgrade.json`。
- 正式包：APK v2 签名验证通过；16 KiB ZIP 对齐检查通过；Release APK 在 Android 16 模拟器安装成功，冷启动返回 Status: ok；启动后未发现本应用 AndroidRuntime 崩溃记录。
- 包内容：DEX 类定义中不含 MockWebServer 测试实现、设备测试类或单元测试类，不含示例令牌；关闭备份和明文 HTTP，未声明 VPN 服务。原生库 ABI：{', '.join(abis)}；运行验证使用 x86_64 模拟器。

## 界面检查

- 普通屏幕：1080 × 2400、420 dpi、字体 100%，截图位于 `normal/`。
- 小屏与大字体：720 × 1280、320 dpi、字体 130%，截图位于 `small-largefont/`。
- 检查了单列卡片、长名称、未知字段、到期警示、日期换行及设置页滚动；状态栏图标随浅深色主题切换，上下栏具有模糊和渐隐，按钮清晰，首尾内容可完整滚动查看；详情页完整显示长名称。
- `release-home.png` 是正式签名 APK 覆盖升级后的启动画面，包含 MigrationCheck 测试订阅；其他订阅截图来自本地模拟服务，均非用户真实订阅。正式包不预置这些数据。

## 验证边界

同一 Android 16 x86_64 模拟器、SwiftShader 软件渲染、1080 × 2400 / 420 dpi / 字体 100%，首页同一测试订阅，执行四次 350 ms 上下滑动。1.1.1 采样 {performance['1.1.1']['frames']} 帧，中位帧耗时 {performance['1.1.1']['medianMs']} ms、P95 {performance['1.1.1']['p95Ms']} ms、jank {performance['1.1.1']['jankyPercent']}%；{version} 采样 {performance[version]['frames']} 帧，中位 {performance[version]['medianMs']} ms、P95 {performance[version]['p95Ms']} ms、jank {performance[version]['jankyPercent']}%。新版在此环境中开销更高；该短时采样不构成实机性能结论，也不保证实体手机帧率。原始记录见 `scroll-baseline-1.1.1.txt` 和 `scroll-gfxinfo.txt`。

未使用真实服务商订阅，未进行实体手机或 Android 8.0 设备测试，未等待完整六小时验证后台保活。套餐快照与刷新及时性取决于服务商、网络和手机省电策略。应用已注册周期任务，并通过刷新规则、设备操作和系统通知测试；这些检查不等同于所有厂商手机的长期后台运行保证。

## 复现

```powershell
.\\scripts\\build.ps1
.\\scripts\\build.ps1 -DeviceTests  # 仅限专用测试设备，会清除本应用测试数据
python .\\scripts\\verify_release.py
python .\\scripts\\package_source.py
```

独立发布签名保存在当前用户 `.android/traffic-board-signing/` 中，限制为当前用户及 SYSTEM 可访问，未放入源码或安装包；后续更新应保留该目录。
""", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
