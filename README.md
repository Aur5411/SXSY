# 尚香书院（Discuz! X3.5 小说论坛客户端）

一个基于 **Android WebView** 的 Discuz! X3.5 小说论坛壳应用，当前版本 **v1.0.0**。

## 功能特性

- **默认直达论坛**：打开即加载 `https://sxsy45.com/index.php`，设置页可修改论坛网址
- **电脑版网页**：默认桌面 UA，进入网站即为 PC 版完整页面
- **内置 TXT 阅读器**：txt 文件默认用内置阅读器打开（分页流式读取、章节目录、字号/行距/背景主题、阅读位置记忆），支持 UTF-8 / UTF-16 / GBK
- **多格式文件下载**：下载文件自动保留真实后缀（`.txt` `.zip` `.epub` `.pdf` `.rar` `.7z` 等），其余格式用外部软件打开
- **双通道下载器**：原生通道（浏览器导航头 + 手动重定向 + 每跳带 Cookie + 帖子页 Referer）失败时自动切换浏览器通道（页面内 fetch 分块回传）
- **附件一键下载**：点击附件自动下载，无弹窗确认，toast 提示 + 结果页
- **文件名智能处理**：自动去除站点标记（如 `[sxsy.org]` 前缀）、乱码文件名自动修复为中文、响应头文件名优先
- **App 内下载管理**：文件列表、多选删除、搜索过滤
- **自定义下载目录**：设置页配置，保存于系统 `Download/自定义目录`
- 下拉刷新、加载进度条、返回键退网页历史、站外链接走系统浏览器
- 登录态持久化：网页内登录一次，Cookie 自动携带，之后直接下载附件

> 说明：本版为**纯净基础版**，不注入任何页面脚本（未内置去广告 / 自动回复等），聚焦"论坛浏览 + 附件下载 + 内置阅读"。

## 快速使用

1. 安装 APK 后打开，直达尚香书院首页
2. 网页内登录论坛账号（下载器会自动携带登录 Cookie）
3. 进帖子页点击附件链接 → 自动下载，右上角菜单「下载文件」查看并用内置阅读器打开

## 技术栈

- 语言：Kotlin
- 最低系统：Android 5.0（API 21）
- 目标 SDK：34（JDK 17 + Gradle 8.7 + AGP 8.x）
- 依赖：AndroidX（AppCompat / Material / SwipeRefreshLayout / FileProvider）、系统 WebView

## 编译打包

### 方式 A：Android Studio（推荐）

1. 用 Android Studio 打开本目录
2. 等 Gradle 同步完成
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`

### 方式 B：命令行

```bash
# 前置：JDK 17 + Android SDK
# 配置 SDK 路径：编辑 local.properties，把 sdk.dir 改为本机 Android SDK 路径
cd SXSYReader
gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

### 签名

- 正式签名配置在工程根目录 `keystore.properties`（指向 `sxsy.keystore`）
- 这两个文件**已通过 `.gitignore` 排除**，不会上传到仓库，请自行备份；密钥丢失后新版本将无法覆盖安装

## 目录结构

```
app/src/main/
├── java/com/sxsy45/app/
│   ├── MainActivity.kt        # 主页：WebView、菜单、下载拦截网、JS 桥接
│   ├── SettingsActivity.kt    # 设置页
│   ├── DownloadsActivity.kt   # App 内下载管理
│   ├── ReaderActivity.kt      # 内置 TXT 阅读器
│   ├── DownloadHelper.kt      # 自研下载器：探测 + 重定向 + 文件名处理
│   └── Prefs.kt               # SharedPreferences 配置读写
└── res/
    ├── layout/                # 各页面布局
    ├── menu/                  # 菜单
    ├── xml/                   # FileProvider 路径
    └── drawable/              # 图标等资源
```

## License

[MIT](./LICENSE)
