# PHASE 2 — FSR 2.1 / FSR 3.1 時間的アップスケール ＋ FSR 3 フレーム生成 移行計画

Phase 1（FSR1 空間パス）が確定した後の Phase 2 実装メモ。参考 3 本の fsr mod を分解して得た
事実と、`fsrupsc`（本プロジェクト、PZ B42 / LWJGL-GL）への移植方針。

## 0. 参考 mod のやり方（分解済みの事実）

### mc-fsr (0.3.2, Fabric, MIT `META-INF/LICENSE-MC-FSR.txt`)
- **FSR1** = GLSL EASU/RCAS（`vendor/ffx_fsr1.h` を include、`textureGather` 使用）
- **FSR2.1 / FSR3.1.5** = ネイティブ **mcfsr.dll**（12MB、FSR2/FSR3 SDK を同梱ビルド）を JNI で叩く
- GL↔Vulkan 相互運用: `NativeGlInterop.deviceUuid / createMemory / importMemory / textureStorage /
  createSemaphore / importSemaphore / signal / waitFor` — WGL/Vulkan メモリ共有で
  GL の FBO を Vulkan 側に輸入し、セマフォで同期（FSR3-FG も Vulkan で走る）
- JNI 面（`dev.mcfsr.nativebridge`）:

```
NativeFsr2/3.create(byte[] cfg, int dispW, int dispH, int inW, int inH) -> long ctx
NativeFsr2/3.image(long ctx, int kind) -> long[]   // kind: 0 COLOR 1 DEPTH 2 MOTION 3 REACTIVE 4 TRANSPARENCY 5 OUTPUT
NativeFsr2/3.semaphores(long ctx) -> long[]
NativeFsr2/3.dispatch(long ctx, boolean reset, float jx, float jy, mvScale..., ...)
NativeGlInterop.*  // GL 側 import/export
```

- mc-fsr 特有の依存: VSync 必須（FG 用）、WGL_NV_memory_object 系が使えるドライバ

### fsr-reloaded (2.1.0, `com.fsr2mod`)
- **ネイティブ無しで FSR2/FSR3 を走るのが要点**: FSR2/FSR3 の中核を GL compute shader として
  同梱（`shaders/fsr2/*.glsl`, `shaders/fsr3/*.glsl`）:
  ACCUMULATE / DEPTH_CLIP / LOCK / MV_GEN / RCAS / RECONSTRUCT（+ FSR3:
  FG_DENOISE / FG_DISOCCLUSION_MASK / FG_GAME_MV_FIELD / FG_INTERPOLATION / SHADING_CHANGE）
- frame generation のみネイティブ（AMD FG dx12 DLL + fsrvk.dll + VulkanInterop + GLSemaphoreManager）
- FSRProcessor / ScaledRenderTarget / JitterManager / ShaderBinaryCache / TemporalBlendShader が構成点

### oslo-render (0.1.0 "complet", `org.projectomega`)
- 全レンダラ置換型で PZ には過剰。外部ベンダーDLLを Steam 上のゲームへ同梱配布できない注意

## 1. 移植の分岐点：ネイティブ DLL 方式 vs GLSL compute 方式

| | A) mc-fsr DLL 再利用 | B) FFX SDK 自前ビルド (GL backend) | C) fsr-reloaded 方式 (GL compute で自前統合) |
|---|---|---|---|
| 開発コスト | 中（JNI 面を再現、クラス名は `dev.mcfsr.nativebridge` に合わせ固定） | 高（FFX SDK fetch + MSVC + ffx_gl backend | 高（シェーダ統合・accum 品質調整） |
| 動作実績 | mc-fsr で MC / GL 4.x で実績 | AMD SDK で実績あるが GL backend維持は要確認 | fsr-reloaded で実績 |
| FG | 可（FSR3-FGネイティブ） | FMG は Vulkan/DX制的で GL では不可寄り | 可（AMD FG DLL + Vulkan interop） |
| ライセンス | mc-fsr MIT + AMD FidelityFX ライセンス（再配布OK） | AMD FX ライセンス（OK） | 同左 |

**推奨**: まず **C（GLSL compute 版 FSR2/FSR3 統合）** を時間的アップスケール本体で実装し、
**FG のみ mc-fsr 方式のネイティブ情報を参照**して段階導入（mc-fsr DLL の同梱と
`dev.mcfsr.nativebridge` 面の互換実装）。FSR2 中核を GL compute 化してくれる C は Vulkan 相互運用を読まずに済むため、
PZ の GL パイプライン（単一 FBO / TextureFBO 合成済み）と相性が最も良い。

## 2. 本プロジェクト側で既に揃っている土台

| 土台 | 場所 | 備考 |
|---|---|---|
| フレーム内的縮小レンダー | `RenderScale` | `scaledRect()`、縮小世代 `shrunkGen()` rendezvous |
| ジッタ | `RenderScale.setJitter/ frameJitterX/Y` | FSR2/3 の jitter 入力用に呼び出し口がある（未実装） |
| 深度/モーション capture | `ObjectMotion` | drawModel 前後の rect/MV 記録フック |
| resolve 実行点 | `Upscaler.resolveAtWorldEnd / resolveAtComposite` | TextureFBO endDrawing / MultiTextureFBO2.render 入口 |
| GL program/quad | `Resolve`, `Gl` | fsr1 が §330 GLSL で動く。compute も GL43 を同じ helper で展開可能 |
| 設定/UI/自動画質 | `FsrSettings`, `FSR_UPSC_Options.lua`, AutoQuality | モード項目追加済み (`fsr2`/`fsr3`) |

## 3. 実装ステップ（fsr2 から始める）

1. **TemporalInputs**: fsr2 入力として (color, depth(線形1ch), motion(±x,y), reactive なし) を
   ワールドパス終了時に生成。depth は DepthComponent→1ch のを C で GL_R32F に変換;
   motion はカメラ差分（IsoCamera.frameState）＋ ObjectMotion の実物。
2. **JitterManager**: 8点半分arian Jitter を `RenderScale.setJitter()` に供給し、
   ワールドパスの projection/iOffset へ入力（DrawWorld 前にカメラ補正を注入）。
3. **FSR2 パイプライン**: shaders/fsr2/*.glsl 応用 — DEPTH_CLIP → MV_GEN(カメラベース) → LOCK →
   RECONSTRUCT → ACCUMULATE → RCAS。GL43 (`glDispatchCompute`) + `GL_SHADER_STORAGE`。出力は
   「Upscaler の出力テクスチャ」にそのまま流して既存 composite 経路を変えない。
4. **fsr3**: SHADING_CHANGE / FG_DENOISE 追加（実は fsr2 側と同戻）。
5. **FSR3-FG**: 別枠（`NativeFsr3` の呼び出し準拠で mcfsr.dll 相当が必要）。
   前提: VSync ON 押付け、`Context` が reset false 推奨。mc-fsr は「FGはVSync必要」を明示している。

## 4. ライセンス処理

- `mc-fsr`: MIT。Java 側の JNI 面を移植する場合に `dev.mcfsr.nativebridge` パッケージ名維持が DLL 結合に必要。
  出所 (NOTICE): MIT ライセンス文の複製を 42/agent に添付。
- FidelityFX (AMD): 現初版 BSD-style で再配布可。fsr2/fsr3 GLSL の出所 (fsr-reloaded jar 内)
  に AMD 記載があります（`natives/AMD_FSR_API_LICENSE.txt`）。
- 再配布するなら THIRD-PARTY-NOTICES を mod フォルダへ。

## 5. 検証チェックリスト (fsr2 on)

- [ ] fsr2 モードで EASU が走らない（compute パスが選択され、RCAS 一本化）ことをログで確認
- [ ] ジッタ適用で 8 歩半分パターンが `RenderScale.frameJitterX/Y` に流れる
- [ ] depth/motion を無し（zeros）で動いた際に mc-fsr 的フォールバック（= bicubic blend）へ落ちる
- [ ] UI フル解像度維持（resolve は world only）を fsr1 と同条件で確認
- [ ] 負荷: 1080p Quality で fsr1 比較 FPS 変化とアーティファクト

## 6. 参考文献

- `C:\Users\kawat\Documents\デフォルトプロジェクト\参考\mc-fsr-0.3.2+mc26.3-fabric.jar`（MIT, FFX FSR1/2/3 ライセンス同梱）
- `...\参考\fsr-reloaded-2.1.0.jar`（GLSL compute 版 FSR2/FSR3 shaders 同梱）
- `...\参考\oslo-render-0.1.0-complet.jar`（Streamline/FG native 組み込み例）
- AMD FidelityFX SDK: https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK
- mc-fsr 公式: `https://modrinth.com/mod/mc-fsr` (Quaternion)
