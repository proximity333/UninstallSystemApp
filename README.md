# 系统应用卸载 · UninstallSystemApp

一个面向 Android 10+ 的系统应用卸载工具。通过 **Shizuku** 或 **Root** 获得包管理权限，扫描设备上的应用，并按空间（多用户/工作资料）执行卸载、停用与恢复。

> 卸载逻辑参考并保持了 [TelemetryCleaner](https://github.com/Eynnzerr/TelemetryCleaner) 的实现方式：命令层卸载 → 回读包状态校验 → `app_process` 兜底 → 失败后降级停用。请在使用前了解相关风险。

## 功能

- 扫描全部应用，默认展示系统应用，可按「系统 / 全部 / 用户」筛选，支持名称与包名搜索。
- 展示每个应用在各 Android 空间中的状态：已安装、已停用、可恢复、未安装。
- 按选中的空间执行：
  - **卸载**：清除该空间的数据，系统保留 APK 时可恢复；
  - **停用**：保留数据，可随时启用；
  - **恢复**：使用系统保留的 APK 恢复到当前空间。
- 所有破坏性操作都有二次确认，并内置一份受保护的核心系统包名单，阻止误操作。
- 支持 Shizuku（无需 Root）与 Root 两种权限方式，可在首页切换。

## 卸载流程

与 TelemetryCleaner 一致：

1. 通过 `dumpsys package` 读取目标包版本号、系统标识与各空间状态；
2. 使用 `pm uninstall --user <id> --versionCode <code> <package>` 执行卸载；
3. 回读包状态，确认目标空间变为「未安装/可恢复」；
4. 若 ROM 在命令层拒绝卸载，Shizuku 模式下通过 `app_process` 直接调用隐藏接口
   `IPackageManager.deletePackageAsUser`（仅 shell UID、且版本号匹配时）再试一次，并轮询确认；
5. 若卸载仍然失败且包版本未变化，降级为 `disable-user` 停用，保留数据；
6. 恢复使用 `install-existing` 或 `enable`。

整个过程使用版本号校验防止并发更新被误删，失败时保留系统原始错误信息。

## 环境

- Android 10（API 29）及以上；
- [Shizuku](https://shizuku.rikka.app/) 已启动并授权，或设备已 Root；
- 安装与使用请自行评估风险。

## 构建

需要 JDK 17、Android SDK 37。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Release 签名读取根目录的 `keystore.properties`（该文件与 `keystore/*.jks` 不纳入版本控制）：

```properties
storeFile=keystore/release.jks
storePassword=********
keyAlias=systemuninstaller
keyPassword=********
```

## 风险提示

卸载系统应用可能导致依赖它的系统功能异常，甚至无法开机；恢复能力取决于设备是否保留 APK。请只处理你明确了解的组件，不确定时优先使用「停用」。

## 致谢

- 卸载、校验与兜底流程参考 [Eynnzerr/TelemetryCleaner](https://github.com/Eynnzerr/TelemetryCleaner)。
- [Shizuku](https://github.com/RikkaApps/Shizuku) 与 [libsu](https://github.com/topjohnwu/libsu) 提供了权限与 Root 能力。