# 波波投屏（bobotouping）

同一 WiFi 局域网内，把 **Android 手机屏幕和声音** 实时投到 **Windows 电脑**，
并且可以用电脑的鼠标反向操作手机。

## 仓库结构

```
android/         手机端（Kotlin，Gradle）
desktop/         电脑端（Electron + WebCodecs）
docs/            设计文档
.github/         GitHub Actions 云构建
```

## 电脑端本地运行

电脑端不依赖云构建，本机装好 Node 就能直接跑起来（Node 20 以上即可）：

```powershell
cd desktop
npm install
npm start
```

跑本地测试（不需要 Electron、不需要真机）：

```powershell
npm test
```

## 电脑端打包

```powershell
cd desktop
npm install
npm run dist            # 生成 Windows 安装版（NSIS 安装包）
npm run dist:portable   # 需要免安装的便携版时再用
```

产物在 `desktop/dist/`：

- `波波投屏-电脑端-<版本>-安装包.exe` —— **安装版，推荐**。装一次装到本机，
  以后双击桌面快捷方式直接秒开。
- `波波投屏-电脑端-<版本>-便携版.exe` —— 便携版，不占系统、可放 U 盘，但每次启动
  都要先把上百 MB 解压到临时目录，启动明显更慢。

## 云构建

本机不需要安装 Android SDK。代码推送到 `main` 后，GitHub Actions 会自动：

1. 编译出可安装的 APK
2. 上传为构建产物（Actions 页面 → 对应 run → Artifacts → `bobotouping-apk`）

也可以在 Actions 页面手动触发（`workflow_dispatch`）。

> CI 会用 GitHub Secrets 里的固定 keystore 签名，保证每次出的包签名一致、可直接覆盖安装。
> Secrets 没配时自动回退到 debug 签名（方便本地/临时测试）。

## 手机端使用

1. 安装 APK，打开「波波投屏」。
2. 电脑端先启动，记下显示的 IP。
3. 在手机上填写电脑 IP，点「开始投屏」，在系统弹窗里允许录屏。
4. 若需要反向控制，点「开启反向控制」跳到无障碍设置，手动打开对应服务。

投屏授权弹窗每次都要点一次，这是 Android 10 以后的系统限制，无法绕过。

## 文档

- [技术方案](docs/技术方案.md) —— 架构、通信协议、音画同步、反向控制实现方式、路线图
