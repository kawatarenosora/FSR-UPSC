# THIRD-PARTY NOTICES — FSR-UPSC

本 mod が同梱・派生する第三者コードとそのライセンスの扱い。

## 1. AMD FidelityFX FSR 2 / FSR 3 compute シェーダ (GLSL)

- **同梱物**: `42/media/shaders/fsr2/*.glsl` (6 pass), `42/media/shaders/fsr3/*.glsl`
  (7 pass)、およびそれらの生成物 `src/fsrupsc/Fsr2Shaders.java` /
  `src/fsrupsc/Fsr3Shaders.java` (`scripts/gen-shaders.ps1` で再生成)。
- **入手経路**: Minecraft 用シェーダパック **fsr-reloaded 2.1.0** が配布する
  GLSL ポート (作者により「AMD FidelityFX SDK sources under the AMD FidelityFX
  license / MIT-compatible terms」と記載) から取得し、本 mod 用に uniform 差し
  替え (Minecraft 固有の sky/cloud ヒューリスティックを無効化する形) で取り込み。
  数式 (dither/Halton/jitter/reprojection/accu 係数) は上流のまま改変していない。
- **ライセンス**: AMD FidelityFX SDK 既定の MIT 互換ライセンス
  (gfx-fidelity ツールキット/jenkins 型 MIT)。各ディレクトリの
  `NOTICE-fsr2.txt` / `NOTICE-fsr3.txt` に由来注記がある。
  上記に明記の通り AMD (C) の著作物。MIT 互換ライセンスの全条件を満たす場合に
  再配布が可能。本 NOTICE と各 NOTICE-*.txt は同梱物と共に維持すること。
- **フォアグラウンド (FG) について**: FSR3 の FG pass (FG_DENOISE /
  FG_DISOCCLUSION / FG_INTERPOLATION / FG_COMPOSITION など) は**同梱していない**。
  本 mod のフレーム生成は純 GL のダブルプレゼント方式
  (`src/fsrupsc/FgPresent.java`、present hook で back buffer を capture し、
  compute pass 由来の per-pixel MV field で warp-blend した挿入フレームを差し込む)
  を自前実装したもので、AMD/上流コードは含まない。

## 2. AMD FidelityFX FSR 1 空間アップスケール (EASU + RCAS フラグメント)

- `42/media/shaders/fsr_easu.frag` は**自前実装** (AMD コードではない; ファイル
  冒頭を参照)。アルゴリズム上の参照として DlssFilterModel.java (CPU reference)
  を用いた。
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
- **PZ_Optimization (xD3I)** (`vendor/upstream/`): DLSS/NGX ネイティブ shim の
  記録。DLSS-UPSC 側で使用するもので、FSR-UPSC 自体はこれを**使用しない**。
  NVIDIA DLSS SDK バイナリはライセンス上 `vendor/upstream/` に置かず、
  ビルド時に別途 clone する (`vendor/upstream/SDK_NOTE.txt`)。
- **fsr-reloaded**: §1 の入手元。fsr-reloaded 自体のコードは FSR-UPSC に
  直接同梱していない (GLSL サブセットのみ)。

## 5. Project Zomboid / LWJGL

`org.lwjglx.*` / `org.lwjgl.*` はゲーム側が提供する (同梱しない)。
javaagent の変換対象はゲームの既存クラスのみで、配布物に LWJGL コードは
含まれない。
