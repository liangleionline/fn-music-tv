## 飞牛音乐 TV 增强版 v1.2.15

### 更新检查改为读取本仓库 Releases
- 设置页「检查更新」不再请求外部清单地址，直接查本仓库最新 Release（`api.github.com/repos/liangleionline/fn-music-tv/releases/latest`），从资产里的 `update.json` 读取版本号、APK 直链、大小与 SHA-256。
- **打开 App 自动检查**：进入前台即后台静默检查一次，有新版本直接弹窗（可选「忽略此版本」或「稍后」）；从后台回到前台时，距上次检查超过 30 分钟也会再查；另外每 12 小时定时检查。
- **设置页「检查更新」**：随时手动检查，已是最新会明确提示当前版本。

### 更新包走 GitHub 官方直链
- 从 Release 资产的 `browser_download_url` 下载 APK，自动跟随 GitHub 的 302 跳转（`github.com` → `objects.githubusercontent.com` / `release-assets.githubusercontent.com`），每一跳都要求 HTTPS 且只允许落在 GitHub 受信域名。
- 下载后依次校验：文件大小与 SHA-256、安装包包名、`versionCode`、签名一致性，全部通过才交给系统安装程序。
- Release 说明会直接进弹窗正文：Markdown 的粗体、行内代码、链接、分隔线自动清洗，`#` 标题与 `-` 列表按现有排版分组显示。

### 安装信息
- 包名 `com.fnmusic.tv`，与 1.2.14 使用同一签名，可直接覆盖安装（数据不丢）。
- 支持 Android 6.0（API 23）及以上，含 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` 四种架构。
- **提示**：应用内更新需要设备能访问 `api.github.com` 与 `github.com`。若网络访问 GitHub 不稳定，手动检查会提示「检查更新失败」，可直接用本 Release 的 APK 覆盖安装。

### 版本保留
Release 区只保留最近 **3 个**版本，更早版本的下线说明保留在 [CHANGELOG.md](https://github.com/liangleionline/fn-music-tv/blob/main/CHANGELOG.md)。
