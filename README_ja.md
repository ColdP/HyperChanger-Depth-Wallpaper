<p align="center">
  <img src="app/src/main/res/drawable/ic_hyperchanger_full.png" alt="HyperChanger Depth Wallpaper" width="128">
</p>

<h1 align="center"><strong>HyperChanger Depth Wallpaper</strong></h1>

<p align="center">静止画に奥行きと 3D の空間感を。</p>

<p align="center">
  <a href="README.md">简体中文</a> |
  <a href="README_en.md">English</a> |
  <strong>日本語</strong>
</p>

<p align="center">
  <a href="https://github.com/ColdP/HyperChanger-Depth-Wallpaper">GitHub</a> |
  <a href="https://coldp.github.io/hyperchanger-updateinfo/">モデルダウンロード</a> |
  <a href="https://github.com/ColdP/HyperChanger">アップストリーム</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="プラットフォーム">
  <img src="https://img.shields.io/badge/minSdk-35-blue?style=flat-square" alt="最小 SDK">
  <img src="https://img.shields.io/badge/License-AGPL--3.0--or--later-yellow?style=flat-square" alt="ライセンス">
</p>

## 概要

HyperChanger Depth Wallpaper は [HyperChanger](https://github.com/ColdP/HyperChanger) の独立ブランチで、「空間壁紙ラボ」に特化しています。端末内の深度推定モデルを使い、1 枚の写真を端末の傾きに応じて奥行きと 3D の動きを感じられる画像に変換します。

**空間効果はアプリ内の全画面プレビューでのみ表示され、システム壁紙には書き込まれません。**

## ブランチ

- `main`：空間壁紙ラボの入口を含まない HyperChanger のベース版。
- `depth-wallpaper`：空間壁紙ラボの機能ブランチ。以下で説明するすべての機能を含みます。

空間壁紙を試すには `depth-wallpaper` ブランチをビルドしてください。

## 機能

### 端末内での深度推定

- Depth Anything V2 Small（INT8）
- MiDaS Mobile（MiDaS v2.1 Small、256×256）
- Depth Anything 3 Base（INT8）
- モデルは必要時にダウンロードし、SHA-256 で検証したうえで完全にオフラインで推論します

### 被写体マスクとレイヤー描画

- 透過イラストは Alpha チャンネルを初期の前景マスクとして使用
- 通常の写真は深度から生成したソフトマスクを使用し、任意で軽量な U2Netp マッティングモデルを利用可能
- 被写体内部の深度は平均値を中心に制約し、輪郭の破綻を防ぎます
- 背景補完プレートとソフトマスクの合成により、被写体はピクセル単位ではなく一体として移動します
- 背景は弱い視差、被写体は強い視差にすることで、限定的で安定した空間変化を生み出します

### プレビューと操作

- ジャイロによる視差と控えめな 3D チルト。センサーのない端末ではドラッグでプレビュー
- ダブルタップで視点をリセット
- レンダリングモード：元画像 / 深度マップ / 視差のみ / 被写界深度 / 最終合成
- 生成履歴：1 件ごとにカードを表示し、タップで全画面プレビューを開きます

### リソースのダウンロード

リソースは GitHub Pages で配布しています：<https://coldp.github.io/hyperchanger-updateinfo/>

| リソース | サイズ | 説明 |
| --- | --- | --- |
| Depth Anything V2 Small | 約 26 MB | INT8、ディテールとエッジが安定 |
| MiDaS Mobile | 約 63 MB | 高速でクイックプレビュー向け |
| Depth Anything 3 Base | 約 99 MB | 最高品質だが生成は明らかに遅い |
| U2Netp Matting | 約 4.4 MB | 任意の軽量マッティングモデル |

## 使い方

1. HyperChanger を開き、「システム UI とマテリアルの調整」へ進みます。
2. 一番下までスクロールし、「空間壁紙ラボ」を開きます。
3. 「リソース」からモデルをダウンロードします。まずは Depth Anything V2 Small がおすすめです。
4. 画像を選択し、「空間効果を生成」をタップします。
5. 生成が完了したらカードをタップして全画面プレビューを開き、端末を傾けて奥行きと 3D を体験します。

## ビルド

- JDK 17
- compileSdk 37、minSdk 35、targetSdk 37
- `arm64-v8a` のみ
- ONNX Runtime Android 1.31.0
- NDK 28.2.13676358。プロジェクト直下に `.build-tmp/android-ndk-r28c` がある場合は、そのローカル NDK を優先します

```bash
./gradlew :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

## 注意

- 深度・マッティングモデルはすべて端末内で動作し、画像はアップロードされません。
- 生成結果はアプリのプライベート領域に保存されます：元画像、深度マップ、マスク、背景補完画像、サムネイル。
- ダウンロード済みモデルを削除しても履歴には影響しません。古い履歴でレイヤーデータが必要な場合は再生成してください。
- ライセンスは AGPL-3.0-or-later です。

## 謝辞

- [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2) / [Depth Anything 3](https://github.com/ByteDance-Seed/depth-anything-3)
- [MiDaS](https://github.com/isl-org/MiDaS)
- [U2Net / rembg](https://github.com/danielgatis/rembg)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [Miuix](https://github.com/compose-miuix-ui/miuix) と [backdrop](https://github.com/Kyant0/AndroidLiquidGlass)
- アップストリーム [HyperChanger](https://github.com/ColdP/HyperChanger)
