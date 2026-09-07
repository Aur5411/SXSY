# 尚香书院（Discuz! X3.5 小说论坛客户端）

一个基于 **Android WebView** 的 Discuz! X3.5 小说论坛壳应用，当前版本 **v1.3.8**。

## 主要功能

- **付费附件自动购买 + 下载**：点付费附件自动读取售价与余额、自动购买，购买后自动下载文件
- **每日签到自动完成**：进入论坛自动签到，自动计算验证题（加/减/乘），全程无需手动
- **论坛弹窗广告自动屏蔽**：自动关闭论坛弹出广告，不误伤登录/购买附件等功能浮层
- **帖子图片外显屏蔽**：自动隐藏帖子正文里的图片，页面更干净、加载更快
- **附件一键下载**：点击附件自动下载，无弹窗确认
- **内置 TXT 阅读器（连续滚动）**：章节无缝衔接、跨章无跳变，支持章节目录、字号/行距/背景主题、阅读位置记忆
- **多格式文件下载**：自动保留真实后缀（`.txt` `.zip` `.epub` `.pdf` `.rar` `.7z` 等）
- **文件名智能处理**：自动去除站点标记（如 `[sxsy.org]` 前缀）、乱码文件名自动修复为中文
- **App 内下载管理**：文件列表、多选删除、搜索过滤，打开下载文件夹直接跳系统文件管理器
- **安全加固**：SSL 证书白名单校验、关闭本地文件访问、JS 接口域名白名单
- 电脑版网页、下拉刷新、返回键退网页历史、站外链接走系统浏览器、登录态持久化

## 快速使用

1. 安装后打开，首次进入设置页填写论坛网址（默认预填 `https://sxsy45.com`），保存后进入论坛
2. 网页内登录论坛账号
3. 进帖子页点击附件 → 自动购买 + 自动下载，右上角菜单「下载文件」查看并用内置阅读器打开

## 下载

最新安装包见 [Releases](https://github.com/Aur5411/SXSY/releases) 页面。

## 技术栈

- 语言：Kotlin
- 最低系统：Android 5.0（API 21）
- 目标 SDK：34（JDK 17 + Gradle 8.7 + AGP 8.x）
- 依赖：AndroidX（AppCompat / Material / SwipeRefreshLayout / FileProvider）、系统 WebView

## 编译打包

### 方式 A：Android Studio（推荐）

用 Android Studio 打开本目录，`Build → Build Bundle(s)/APK(s) → Build APK(s)`。

### 方式 B：命令行

```bash
cd SXSYReader
gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

### 签名

- 签名配置在工程根目录 `keystore.properties`（指向 `sxsy.keystore`），已通过 `.gitignore` 排除，请自行备份；密钥丢失后新版本将无法覆盖安装

## License

[MIT](./LICENSE)
