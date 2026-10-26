# THIRD-PARTY NOTICES — FSR-UPSC

本 mod が同梱・派生する第三者コードとそのライセンスの扱い。

## 1. AMD FidelityFX FSR 2 / FSR 3 compute シェーダ (GLSL)

- **同梱物**: `42/media/shaders/fsr2/*.glsl` (6 pass), `42/media/shaders/fsr3/*.glsl`
  (7 pass)、およびそれらの生成物 `src/fsrupsc/Fsr2Shaders.java` /
  `src/fsrupsc/Fsr3Shaders.java` (`scripts/gen-shaders.ps1` で再生成)。
- **入手経路**: Minecraft mod **fsr-reloaded 2.1.0** (Modrinth `gFl0kYvv`;
  取得 jar の sha256 `64A7869048B92D008CD9DAAC0D2167CDB0DD2334BE8F38EFEBDA3F64D2043C0A`,
  2026-10-26 検証) 内の `shaders/fsr2|fsr3/*.glsl` (GLSL 430 compute ポート)
  から取得。シェーダ本文は**無改変** — ミラー pass は jar 同梱物とバイト一致。
  FSR-UPSC 側の追記は fsr2/fsr3 `DEPTH_CLIP_CS.glsl` の uReactiveMask
  1 ブロック (コメント "FSR-UPSC M3") と、上流に存在しない追加 pass
  (AUTO_REACTIVE / DEPTH_PROXY / MV_OVERRIDE) のみ。数式
  (dither/Halton/jitter/reprojection/accu 係数) は上流のまま改変していない。
- **ライセンス (2026-10-26 に実物に対して検証)**:
  1) AMD 公式 FidelityFX-SDK リポジトリ — `docs/license.md` は既定に
     「AMD License」(バイナリ形態での配布許諾、リバースエンジニアリング禁止)
     を置くが、**MIT の例外リスト**に `Kits/FidelityFX/upscalers/fsr3/
     include/gpu/` 以下の FSR2/FSR3 compute pass 一式 (`ffx_fsr2_*.h` /
     `ffx_fsr3upscaler_*.h`、FG の GLSL/xHL pass headers を含む) を列挙して
     いる。従って本同梱物の出典 pass は **MIT** (`Copyright (C) Advanced
     Micro Devices, Inc.` + 本文は `NOTICE-fsr2.txt` / `NOTICE-fsr3.txt`
     に同梱、MIT の copyright/permission 同梱義務履行)。
  2) fsr-reloaded — GLSL 430 移植本体。作者 **Human-Name998**、Modrinth
     (`gFl0kYvv`) で標準 MIT を表明 (API license id `MIT`, url null。
     `fabric.mod.json` も `license: "MIT"` / `authors: ["Human-Name998"]`)。
     リポジトリ本体/jar 内にライセンス文書は存在せず、jar 内
     `natives/AMD_FSR_API_LICENSE.txt` はプレースホルダスタブのため grant
     としては引用しない。**Modrinth で表明された標準 MIT が grant として
     使える**ため、`NOTICE-fsr2.txt` / `NOTICE-fsr3.txt` に
     `Copyright (c) 2026 Human-Name998 (fsr-reloaded)` 附きの標準 MIT 本文を
     同梱して MIT の copyright/permission 義務を履行する。
  本 NOTICE と各 NOTICE-*.txt は同梱物と共に維持すること。
- **MC 固有ヒューリスティクス**: fsr-reloaded の GLSL には Minecraft 用
  sky/cloud 分類が残存する。FSR-UPSC はシェーダ本文を改変せず、ホストが
  供給する uniform 値で挙動を制御する (既定 `uUseDepth=0` で depth 由来の
  sky 分類をゲート。設計記録: `docs/PHASE2-FSR3-GLSLDESIGN.md` §1.5)。
- **フォアグラウンド (FG) について**: FSR3 の FG pass (FG_DENOISE /
  FG_DISOCCLUSION_MASK / FG_GAME_MV_FIELD / FG_INTERPOLATION) は上流 jar 内
  に存在するが**同梱していない**。fsr-reloaded 自体の FG は jar 同梱の
  AMD 署名済 native DLL (`amd_fidelityfx_framegeneration_dx12.dll` 等、
  本 mod は再配布しない) で動く。本 mod のフレーム生成は純 GL の
  ダブルプレゼント方式 (`src/fsrupsc/FgPresent.java`、present hook で
  back buffer を capture し、compute pass 由来の per-pixel MV field で
  warp-blend した挿入フレームを差し込む) を自前実装したもので、
  AMD/上流コードは含まない。

## 2. AMD FidelityFX FSR 1 空間アップスケール (EASU + RCAS フラグメント)

- `42/media/shaders/fsr_easu.frag` は**自前実装** (AMD コードではない; ファイル
  冒頭を参照)。アルゴリズム上の参照として開発期の Java 製 CPU reference 実装を用いた。
- `42/media/shaders/fsr_rcas.frag` は AMD FidelityFX FSR RCAS の数式を実装した
  GLSL ポート。(AMD MIT 互換、§1 と同様の扱い。)
- `42/media/shaders/fsr2/RCAS_CS.glsl` / `fsr3/RCAS_CS.glsl` は上流に同一内容
  (fsr-reloaded 経由の AMD 系) — §1 に含まれる。

## 3. byte-buddy 1.18.8 (javaagent 用バイトコード変換ライブラリ)

- **同梱物**: `vendor/lib/byte-buddy-1.18.8.jar`,
  `vendor/lib/byte-buddy-agent-1.18.8.jar`。ビルド時に agent jar 内へ
  unpacked で埋め込む (自己完結 jar)。
- **ライセンス**: Apache License 2.0。
  https://github.com/raphw/byte-buddy — jar 内の LICENSE / NOTICE は
  upstream 版に同梱のまま、本 NOTICE と併せて維持すること。

## 4. 参照元 (コード非同梱)

- **mc-fsr** (Fabric, MIT): FSR2/FSR3 のセッションフォールバック原則や
  MV/カメラ統合の構造を参考にした。コードは欠けていない (組み込んでいない)。
- **fsr-reloaded**: §1 の入手元。Java 側実装・FG native DLL・設定 UI 等全体は
  同梱しておらず、GLSL ミラー (§1) のみを取り込んでいる。

## 5. Project Zomboid / LWJGL

`org.lwjglx.*` / `org.lwjgl.*` はゲーム側が提供する (同梱しない)。
javaagent の変換対象はゲームの既存クラスのみで、配布物に LWJGL コードは
含まれない。
