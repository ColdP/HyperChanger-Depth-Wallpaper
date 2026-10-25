<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger Depth Wallpaper" width="128">
</p>

<h1 align="center"><strong>空间壁纸实验室</strong></h1>

<p align="center">让静态照片拥有景深与 3D 空间感。</p>

<p align="center">
  <strong>简体中文</strong> |
  <a href="README_en.md">English</a> |
  <a href="README_ja.md">日本語</a>
</p>

<p align="center">
  <a href="https://github.com/ColdP/HyperChanger-Depth-Wallpaper">GitHub</a> |
  <a href="https://coldp.github.io/hyperchanger-updateinfo/">模型下载</a> |
  <a href="https://github.com/ColdP/HyperChanger">上游项目</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="平台">
  <img src="https://img.shields.io/badge/minSdk-35-blue?style=flat-square" alt="最低 SDK">
  <img src="https://img.shields.io/badge/License-AGPL--3.0--or--later-yellow?style=flat-square" alt="许可证">
</p>

## 项目简介

HyperChanger Depth Wallpaper 是 [HyperChanger](https://github.com/ColdP/HyperChanger) 的独立分支，专注「空间壁纸实验室」：在 Android 端侧运行深度估计模型，把单张照片转换为可随陀螺仪呈现景深与 3D 空间效果的图像。

**空间效果仅在全屏预览中呈现，不会写入系统壁纸。**

## 分支说明

- `main`：已合并空间壁纸实验室，包含本文描述的全部能力。
- `depth-wallpaper`：空间壁纸实验室原功能分支，其功能已合并到 `main`。

如需体验空间壁纸，请直接构建 `main` 分支。

## 功能

### 端侧深度估计

- Depth Anything V2 Small（INT8）
- MiDaS Mobile（MiDaS v2.1 Small，256×256）
- Depth Anything 3 Base（INT8）
- 模型按需下载并通过 SHA-256 校验，下载后完全离线推理

### 主体蒙版与分层渲染

- 透明立绘直接使用 Alpha 通道作为初始前景蒙版
- 生成默认启用主体抠图，仅通过生成页「抠图模型」下拉菜单切换 U2Netp、ISNet INT8 或 BiRefNet Lite INT8
- 连续蒙版按原图边缘细化，保留柔和轮廓和分离的小主体；透明图片保留原始 Alpha
- 主体与轮廓使用统一的视差变换，避免深度突变拉扯边缘
- 背景补全层加软蒙版合成，主体作为整体移动，而不是逐像素滑动
- 背景弱视差、主体强视差，产生有限而稳定的空间变化

### 预览与交互

- 陀螺仪驱动视差与轻微 3D 倾斜；无传感器设备可拖动预览
- 双指缩放（1–4 倍），放大后可拖动查看细节；双击重置缩放与视角
- 渲染模式：原图直通 / 深度图查看 / 纯视差 / 纯景深 / 最终合成
- 生成历史：每个项目一张卡片，点击进入全屏预览

### 资源下载

资源通过 GitHub Pages 分发：<https://coldp.github.io/hyperchanger-updateinfo/>

| 资源 | 大小 | 说明 |
| --- | --- | --- |
| Depth Anything V2 Small | ~26 MB | INT8，细节与边缘更稳 |
| MiDaS Mobile | ~63 MB | 更快，适合快速预览 |
| Depth Anything 3 Base | ~99 MB | 质量最佳，生成明显更慢 |
| U2Netp Matting | ~4.4 MB | 可选的轻量抠图模型 |
| ISNet INT8 | ~42.2 MB | 1024 分辨率，兼顾边缘与速度 |
| BiRefNet Lite INT8 | ~86.6 MB | 适合复杂轮廓和细节，内存与耗时较高 |

## 使用

1. 打开应用，首页即「空间壁纸实验室」。
2. 在底部「模型下载」中下载深度和抠图模型，建议先下载 Depth Anything V2 Small 和 U2Netp。
3. 返回「生成」选择图片和模型，点击「生成空间效果」。
4. 生成完成后进入全屏预览，倾斜手机体验景深与 3D。
5. 在底部「历史生成」中查看、预览或删除已生成的项目。

底部导航包含：生成、历史生成、模型下载、设置。切换标签不会取消当前生成或模型下载。

## 构建

- JDK 17
- compileSdk 37、minSdk 35、targetSdk 37
- 仅 `arm64-v8a`
- ONNX Runtime Android 1.31.0
- 不需要安装 NDK

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 说明

- 已移除与空间壁纸无关的 Hook 实现和注册入口；设置页及其二级页面保留原样，其中旧模块功能不再注入系统。
- 深度与抠图模型均在设备本地运行，不会上传图片。
- 生成结果保存在应用私有目录：原图、深度图、蒙版、背景补全图与缩略图。
- 删除已下载模型不会影响历史记录；旧记录如需分层数据需要重新生成。
- 蒙版边缘优化需重新生成；旧记录首次打开预览时会自动修复背景补全与背景深度，无需重新下载模型。
- 背景补全从可见区域推算颜色与深度，不能还原原图中被主体遮挡的真实纹理。
- 本项目使用 AGPL-3.0-or-later 许可。

## 致谢

- [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2) / [Depth Anything 3](https://github.com/ByteDance-Seed/depth-anything-3)
- [MiDaS](https://github.com/isl-org/MiDaS)
- [U2Net / rembg](https://github.com/danielgatis/rembg)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [Miuix](https://github.com/compose-miuix-ui/miuix) 与 [backdrop](https://github.com/Kyant0/AndroidLiquidGlass)
- 上游项目 [HyperChanger](https://github.com/ColdP/HyperChanger)
