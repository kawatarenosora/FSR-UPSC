# PHASE 2 設計メモ — fsr-reloaded GLSL FSR2/FSR3 解析と FSR-UPSC への移植設計

作成: 2026-10-23 (解析サブエージェント)
入力: fsr-reloaded 2.1.0 (decompiled `com.fsr2mod.*` + `shaders/fsr2|fsr3/*.glsl`) → FSR-UPSC (PZ B42, `fsrupsc`)
前提: Phase 1 (FSR1 EASU/RCAS @ `Resolve`, resolve 点 = composite drawer) は動作済み。
本 doc は `docs/PHASE2-FSR3.md` の「方案 C (GLSL compute 自前統合)」を具体化する。

---

## 1. fsr-reloaded の FSR2 パイプライン解析

### 1.0 全体構造 — 最重要の発見

**fsr-reloaded の GL パス FSR2/FSR3 は完全に自己完結した GL 4.3 compute パイプライン**である。
スーパークラスなし・SSBO なし・bindless なし。`FSR2Pipeline.java` は 682 行で 6 シェーダ +
テクスチャ管理 + dispatch を全部担い、`FSRProcessor.onSceneRendered` (line ~3452-3503) から
フレーム 1 回 dispatch されるだけ。同梱 `shaders/fsr2|fsr3/*.glsl` は Java 側と完璧に対応する
(uniform 名・binding 番号・local_size が完全一致) — **GLSL をほぼそのまま移植できる**。

- **SSBO を要求する箇所はゼロ** (全入力 = sampler2D / 全出力 = image2D)。必要なのは
  GL 4.3 compute + image load/store + integer image atomic + shared memory + barrier のみ。
- `glClearTexImage` (GL 4.4, `GL44C`) だけが 4.3 を超える要求 → 置換は容易 (§3, §6)。
- dispatch はすべて render thread 上で同期実行 (PZ と同じ single-thread GL モデル)。

### 1.1 FSR2 パス順序 (FSR2Pipeline#dispatch, line 462-636)

各フレーム 1 回、`renderTex` (縮小世界カラー) と `depthTex` を受け取る。

```
[0] (currVP && prevVP 揃のとき) MV_GEN     dispatch ceil(RW/8) x ceil(RH/8), barrier=0x8
    else → motionVectorTex を FBO clear (glClearBufferfv, 0)
[1] glClearTexImage(reconstructedPrevDepth)   [GL44; R32UI]
[1] RECONSTRUCT   dispatch ceil(RW/8) x ceil(RH/8), barrier=0x8
[2] DEPTH_CLIP    dispatch ceil(RW/8) x ceil(RH/8), barrier=0x8  → preparedInputColor + dilatedReactiveMasks
[3] glClearTexImage(newLocksTex)              [R8, display-res]
[3] LOCK          dispatch ceil(RW/8) x ceil(RH/8), barrier=0x8
[4] ACCUMULATE    dispatch ceil(DW/16) x ceil(DH/4), barrier=0x20  (display-res History 積算 + upsample)
[5] RCAS          dispatch ceil(DW/16) x ceil(DH/4), sharpness>0 のときのみ (same image を read-modify-write)
[6] ping-pong 3 組: internalUpscaled ↔ internalUpscaledWrite,
    lockStatusHistory ↔ lockStatusCurr, previousDilatedData ↔ dilatedData。
    前後で program / texture unit / FBO / viewport の save & restore。
```

barrier 値: `0x8` = GL_TEXTURE_FETCH_BARRIER_BIT、`0x20` = GL_SHADER_IMAGE_ACCESS_BARRIER_BIT。

注目点:
- **ACCUMULATE が display-res で動く** (AMD 原典は render-res)。history texture が
  そのまま composite に渡せる構成で、PZ への転用に都合が良い。
- **`FSRProcessor は setUseDepth(false)` を常に渡す** (line 3481/3534)。
  つまり本 mod の GL パス FSR2/FSR3 は **実運用では常時 constant-depth (カメラのみ MV)**。
  depthTex は binding には来るが sky 判定・disocclusion は色ベース検出に切替わる。

### 1.2 バッファ一覧 (FSR2Pipeline#ensureTextures, line 363-400)

enum 値は decompile の生の数値:

| テクスチャ | 内部フォーマット | サイズ | filter | 役割 |
|---|---|---|---|---|
| motionVectorTex | RG16F (33327) | render | NEAREST | MV_GEN 出力 (render px 単位 xy) |
| reconstructedPrevDepthTex | R32UI (33334) | render | NEAREST | RECONSTRUCT が imageAtomicMax で埋める prev depth。毎フレーム clear |
| dilatedDataTex | RGBA16F (34842) | render | LINEAR (9729) | RECONSTRUCT 出力: (mv.xy, nearestDepth, lockLuma) |
| dilatedReactiveMasksTex | RG8 (33323) | render | LINEAR | DEPTH_CLIP 出力: (dilatedReactive, accumulationMask) |
| previousDilatedDataTex | RGBA16F | render | LINEAR | dilatedData の ping 相方 |
| preparedInputColorTex | RGBA16F | render | LINEAR | DEPTH_CLIP 出力: (YCoCg, depthClip in .a) |
| lockStatusCurrTex / lockStatusHistoryTex | RGBA8 (32856) | display | NEAREST | lock 状態 ping-pong (r=lockLife, g=lockLuma, b=accumWeight/15) |
| newLocksTex | R8 (33321) | display | NEAREST | LOCK 出力。毎フレーム clear |
| internalUpscaledTex / internalUpscaledWriteTex | R11F_G11F_B10F (35898) | display | LINEAR | **履歴 (history)** ping-pong |
| upscaledOutputTex | RGBA8 (32856) | display | LINEAR | 最終出力 (`externalOutputTex != -1` ならそちらへ書く) |
| (FSR3) shadingChangeTex / previousShadingChangeTex | RG8 | render | — | SHADING_CHANGE ping-pong (r=ratio, g=persist/3.0) |

glClearTexImage に渡す呼び出し enum: R32UI clear = `(format=36244 GL_RED_INTEGER, type=5125 GL_UNSIGNED_INT)`、
R8 clear = `(6403 GL_RED, 5121 GL_UNSIGNED_BYTE)`。

### 1.3 uniform 定数テーブル (全列挙)

供給源: `FSR2Pipeline#setCommonUniforms` (line 431-444) + dispatch 本体。
**GLSL 側と名前が完全一致するため PZ 側でもこの名前・値のまま使える**:

| uniform | 型 | 値 / 計算 | 備考 |
|---|---|---|---|
| uRenderSize | ivec2 | (renderW, renderH) | 全パス共通 |
| uDisplaySize | ivec2 | (displayW, displayH) | |
| uMaxRenderSize | ivec2 | = uDisplaySize | (原典 AMD は maxW/H、mod は display を代入) |
| uDeviceToViewDepth | vec4 | `[(1-p22)/2, -p23/2, -1/p00, -1/p11]` (setProjectionMatrix, line 246) | device depth → view Z 線形化 |
| uJitter | vec2 | (jitterX, jitterY) | render-pixel 単位, Halton ±0.5 |
| uMotionVectorScale | vec2 | (1/renderW, 1/renderH) | rawMV (render px) → UV |
| uDownscaleFactor | vec2 | (renderW/displayW, renderH/displayH) | |
| uMotionVectorJitterCancellation | vec2 | `(prevJit - jit) * mvScale` | RECONSTRUCT が mv から減算 |
| uPreExposure / uPreviousFramePreExposure | float | 1.0 / 1.0 | |
| uViewSpaceToMetersFactor | float | 1.0 | 単位非依存 |
| uExposureVal | float | 1.0 | (DEPTH_CLIP, ACCUMULATE) |
| uFarDepth | float | `p22 > 0 ? 0 : 1` | far plane の device depth |
| uFarViewZ | float | `dtv[1] / (farDepth - dtv[0] + 1e-10)` | |
| uFrameIndex | int | 0..n (reset 時=0) | isNewSample 判定 |
| uUseDepth | int | **mod は常に 0** (line 3481/3534) | 0 = constant-depth MV |
| (ACCUMULATE) uDebugMode | int | 0 | 視覚化 |
| (ACCUMULATE) uCamStill | int | CPU 判定「回転なし静止」 | |
| (ACCUMULATE) uCamReactive | float | camera-motion reactive floor 0..0.9 | |
| (ACCUMULATE) uCamStillExact | int | 平行移動も含め完全静止 | |
| (MV_GEN 独自) uInvCurrVP, uPrevVP | mat4 | `currViewProj.invert()` / prevViewProj | |
| (MV_GEN) uPrevJitter | vec2 | 前フレーム jitter | |
| (MV_GEN) uCameraDelta | vec3 | `camPos - prevCamPos` | camera-relative 補正 (`worldH.xyz += uCameraDelta`) |
| (MV_GEN) uZZeroToOne / uFlipY | int | 0 / 0 | GL パスなので |
| (LOCK) uRenderSize/uDisplaySize/uDownscaleFactor/uJitter | | | |
| (RCAS) uRcasConfig | uvec4 | `(clamp(sharpness,0,1)*7, 0, 0, 0)` | |
| (RCAS) uOutputSize | ivec2 | display | |
| (FSR3 SHADING_CHANGE) sc_uRenderSize / sc_uThreshold=0.5 / sc_uFarDepth | | FSR3Pipeline#dispatch line 615-627 | |

binding 番号は §1.1 の GLSL ヘッダのとおり。**PPT (fsr-reloaded の .glsl) と同じ番号を
Java 側 `glBindImageTexture/glActiveTexture` で付けるのが最小リスク**。

### 1.4 jitter の供給経路 (FSRProcessor)

1. **advance** (`onRenderBegin` 系, line ~2164-2231): `jitterManager.next(scaledWidth, displayWidth, result)`
   → `jitterX/jitterY ∈ [-0.5, 0.5]`、前フレーム値は prevJitter に保存。
   `JitterManager` (52 行) は Halton(2)/Halton(3)、`phaseCount = ceil(8 * (displayW/renderW)^2)` — **丸ごと移植可能**。
2. **projection への適用** (`captureAndJitter`, line 1232): clip-space jitter
   `px = jx*2/renderW`, `py = jy*2/renderH` を `Matrix4f.translation(px, py, 0) * proj` として
   本物の projection に前掛け (jitter 済投影 = `lastProjJittered`)。hand projection も同様。
3. **pipeline uniforms**: dispatch (line 3485) に jitterX/Y, prevJitterX/Y を render-pixel 単位で
   渡す → `uJitter`は ACCUMULATE の de-jitter (`lrUv = hrUv - uJitter/renderSize`) と
   LOCK の `hrPosFromLrPos` で使用。MV_GEN の uJitter/uPrevJitter は (clip-space translation が
   invVP で自動処理されることを示すコメント付きで) 実質未使用。
4. **currViewProj / prevViewProj**: dispatch 直前に `currViewProj = lastProjJittered * view(cam)`
   (line 3469-3470)、dispatch 成功後に `prevViewProj = currViewProj` (line 3502)。
   初回 (`!hasPrevFrame`) は null を渡す (= MV clear パス)。
5. **uCameraDelta**: `camPos - prevCamPos` (hasPrevFrame のときのみ, line 3471-3476)。
6. 追加情報: VulkanMod パスでは camera の pos/rot delta (dPos>0.02 / dRot>0.006 を
   0..1 に map) から **jitter 振幅を 1.0→0.4 に減衰**させている (line 1929-1948)。
   PZ でも motion 中の jitter 減衰はアーティファクト低減に転用価値がある。

### 1.5 GLSL の MC 固有部分 (glue) と AMD 純正部分の切分け

mod が直した/追加した箇所 (日付つきコメントで同定):

- **ACCUMULATE**: display-res 化 + de-jitter lrUv block、`isWhiteSky/isBlueSky` 検出
  (luma > 0.58 / chroma < 0.12 / screen-Y > 0.5)、`uCamStill/uCamReactive/uCamStillExact` による
  MV=0・reactive=0 上書き、elytra sky-ghost (mvPx > 11 → fresh)、cloud 検出、
  hand-zone identity reprojection (画面下 34% の first-person hand)、green-smear fix
  (history luma diff 0.25 knee)、lock cap 0.70、rejectDecay (persistent clamp-reject)、
  uDebugMode 視覚化。
- **RECONSTRUCT**: mvThreshold = 0.5/renderSize (原典 0.1 UV を下げた修正)。
  それ以外 (findNearestDepth / bilinear atomicMax / dilatedData) は AMD 原典どおり。
- **MV_GEN**: constant-depth fallback `ndcZ = 0.999` (uUseDepth=0、「z≈500 相当、
  遠景は ~4px 以内に収まる」の検証コメント)、sky (viewZ > 0.98*far) は mv=0。
- **DEPTH_CLIP**: `uUseDepth=0` なら depthClip=0 (disocclusion clip 全面無効, line 82)、
  sky (viewZ > 0.95*far) は full clip=1。depth clip 数式 (ksep=1.37e-05, power=mix(1,3,resFactor)) は原典。
- **LOCK**: ほぼ AMD 原典 (ridged-detection 8-neighbor mask)。`hrPosFromLrPos` の jitter 合成は mod。
- **RCAS**: 純 FSR1 RCAS (ffx_fsr1.h FsrRcasF、FSR_RCAS_LIMIT 0.1875、noise 検出)。
- **SHADING_CHANGE (FSR3)**: 8x8 タイル luma-ratio + persist ゲート (2 フレーム継続で発火)。
  sky/city tile 排除は mod 追加。
- **ACCUMULATE の AMD 純正核** (そのまま流用可): lanczos2ApproxSq / computeLanczosWeight /
  ellipsoidColorClamp / YCoCg 変換 / lockStatus 3ch 構造。

常用 uniform に MC 固有のものはなく、**FigidBody/block 定義も無し**。GLSL は
`ShaderSourceManager` から jar resource として読み込まれ、`ShaderBinaryCache.compileCompute`
(GL 4.3, PROGRAM_SEPARABLE) でコンパイル。つまり移植に必要なのは「uniform 値の供給側だけ」。

`:uniforms/defines が GPU ブロック分割を要する部分は存在しない。`

### 1.6 支援クラスの役割

| class | 内容 | PZ への含意 |
|---|---|---|
| JitterManager | Halton(2,3)、phaseCount=ceil(8·(d/r)²) | そのまま移植 (`JitterHalton.java`) |
| ShaderBinaryCache | SHA-256(normalized src + os/arch) → `config/fsr-reloaded/shader-cache/*.bin` に glProgramBinary キャッシュ、stale sweep、cache 不整合で自動 recompile | 同機構を `%USERPROFILE%\Zomboid\fsrupsc-shader-cache` へ |
| TemporalBlendShader | **GL 4.3 fragment shader** による depth-reproject history blend (current/history, luma diff < 0.15 半分 blend)。gl_VertexID 3 三角形の fullscreen draw。compute が駄目な環境向けの簡易 temporal | PZ でも fallback の雛形になる |
| SharedTextureManager | D3D12↔Vulkan 共有テクスチャ pool (native FSR4 / FgDX12 用)。GL パスには不要 | — |
| VulkanInterop / GLSemaphoreManager / Fg4Native / Fsr4Native | AMD FG DLL (frame generation 本体) 用 native bridge。GL パスから独立 | Phase 2 では未使用 |
| GlScissorGuard | glDisable(GL_SCISSOR_TEST) のスコープ管理 | Resolve の state restore と同趣旨 |
| ScaledRenderTarget | MC mainRT の swap 制御 | PZ は offscreenBuffer の サイズ変更 (worldFboScalePermille) で対応済み |

### 1.7 FSR3 の差分

順序 = MV_GEN → **SHADING_CHANGE (新增)** → RECONSTRUCT → DEPTH_CLIP → LOCK → ACCUMULATE → RCAS。
- SHADING_CHANGE 出力 (RG8 render-res ping-pong) は FSR3 DEPTH_CLIP の `binding 6 = uShadingChange`
  に送られ、dilated reactive へ合成される (FSR3Pipeline#dispatch line 663)。
  ACCUMULATE 側も binding 配列が FSR2 からずれている (fsr3 ACCUMULATE ヘッダ参照)。
- `FSR3Pipeline#dispatchFrameGen` (line 876-976) は FG_PASSES:
  FG_GAME_MV_FIELD (R32UI x/y field) → FG_DISOCCLUSION_MASK (uDepthClipConst/uDepthClipPower
  は deviceToViewDepth と対角 halfVp から Java 側計算: `1.37e-5 * kfov * halfVp`, `1+2*resFactor`) →
  FG_INTERPOLATION (game MV field + prev/curr color, uBlendWeight) → FG_DENOISE。
  **ただし frame generation 本体は AMD FG DLL (native, `doFrameGenDispatch`)** で、
  GL パスは FG 入力の補助に留まる。PZ Phase 2 では FG 全体を後回し (M4+)。

### 1.8 履歴 reset / 状態遷移 (VersionStatus / RetiredSceneTarget)

- `VersionStatus` (line 5601+): 環境拒否の一覧 — **Intel GPU ("compute shader issues")**、
  DistantHorizons なし shader、VulkanMod 等 → FSR2 を起動しない。PZ 側も同種の denylist が必要。
- `RetiredSceneTarget` (line 679-700): 使用中だった texture を最大 3 個遅延削除
  (ライフタイム管理)。quality 変更時は `recreateTextures()` で全テクスチャ再生成。
- **履歴リセットは暗黙**: 初回のみ `prevViewProj = null` → MV texture が 0 クリアされ、
  `uFrameIndex = 0` で isNewSample。新規 world 参加 (`worldJoinedAtMs`) から 20 秒は
  `stormWindow` により FSR1 (blit) にフォールバック (`fsr1TransitionStorm`)。
  scale 変更 (`requestScaleChange`) は `purgePipelineTextures` → full reset。
  → **PZ でも world load / zoom 変更 / scale 変更 / セーブ復帰を reset トリガにする** (§6)。

---

## 2. GLSL の依存条件 (PZ 側が満たすべきこと)

shaders/fsr2|fsr3/*.glsl について:

1. **`#version 430 core`** — compute (GL 4.3)、image load/store (4.2)、`imageAtomicMax`
   (integer atomic, extension 不要)、shared memory + barrier。
2. **uniform block / SSBO / `#define` は一切ない**。全依存 = uniform 値 + sampler/image binding。
3. glMemoryBarrier は Java 側の仕事。シェーダ内 barrier() は shared memory 用のみ。
4. mod 固有 uniform (uCamStill 等) は「0 を入れておけば無効」— MC 専用パラメータではない。
5. sky/cloud 検出の色定数 (luma 0.58, blue > 1.15r, screen-Y > 0.5) は MC の空に合わせた
   ヒューリスティック。PZ の空は SOD/sunset のみ明るく昼はグレー寄り → **PZ では sky 検出を
   ほぼ無効化 (uUseDepth=0 + isSky 以外一切) するか、PZ 実測色に合わせてチューニング**。
6. RCAS の FSR_RCAS_LIMIT / FsrRcasCon 数式は完全に AMD 由来、PZ でも同一。

## 3. PZ の GL で compute は動くか

**実測**: `Resolve.java` のコメント (line 616) に
「native NVIDIA GL (OpenGL 4.6.0 NVIDIA 617.14)」の記録が残っている —
開発機の PZ B42 は **GL 4.6 native**。LWJGL デスクトップコンテキストで
GL43C/GL44C が普通にアクセスできる。つまり compute 必須要件は満たす。

- **必須**: PZ 起動時に probe (`glGetString(GL_VERSION)` + `GL.getCapabilities().OpenGL43/.OpenGL44`)
  を行い、ログに残す (M0)。PZ が別のドライバ / Mesa / Intel で動く可能性は排除できない。
- **Intel iGPU**: fsr-reloaded 自身が OpenGL 版 FSR2 を Intel で
  `VersionStatus.no("Intel GPUs — compute shader issues")` として無効化している
  (line 5614/5626)。PZ 側も同じ denylist + 自動 FSR1 fallback を実装すべき。
- Mesa/Zink: `Resolve.java` の GL_DRAW_BUFFER 周りの記録から、動作保証は弱い
  (դրiver により draw buffer 既定が GL_NONE) → denylist に入れる。

**Fallback (GL 4.3 未満 / compute 不可環境向け)**: `TemporalBlendShader` 雛形を
`#version 330` fragment 版 ACCUMULATE-lite に落とす:

- ACCUMULATE ≒ fullscreen fragment: preparedInputColor を display-res で 13-tap lanczos
  sample + history textureLod + box clamp (3x3)。imageStore → FBO 出力。cost は EASU 1 回 + α。
- RECONSTRUCT/LOCK の `imageAtomicMax` は GL330 には存在しない (atomics は GL330 不可):
  disocclusion 検出を depth 差分近似に落とす。history lockStatus も RGBA8 float 近似。
  → **「FSR1 EASU + FSR2-lite 積算 (lock なし)」品質, FSR1 比 +α で 90-95% 再現**。
- RCAS は Phase 1 の RCAS fragment shader をそのまま使用。

native fallback (mc-fsr 方式) は FG の時点まで先送り (docs/PHASE2-FSR3.md §1 の表のとおり)。

---

## 4. 深度とモーション — isometric PZ 向け設計

fsr-reloaded GL パスは **`uUseDepth=0` (constant-depth) での運用実績がある**。
PZ では depth 取得が明示 API でないため、これが第一の現実解。PZ は MC より
さらに条件が良い:

1. **iso 投影 = orthographic + 視線固定** → 回転による depth 変化が本質的に無い。
2. world ほぼ axis-aligned + camera frame 間固定 → **MV は「camera pan 一様 + entity rect」の近似で ほぼ正解**。
3. zoom 変更時のみ投影像が変わる → **zoom delta = reset** とすれば良い。

### 4.1 入力マップ (fsr-reloaded → PZ)

| fsr-reloaded 入力 | MC での供給 | PZ での供給源 | 難度 |
|---|---|---|---|
| renderTex (縮小 color) | MC mainRT | `Core.getOffscreenBuffer(0)` color texture — **既存** | ✅ 済 |
| depthTex | MC RenderTarget depth | **公開 API なし** → §4.3 DepthProxy | ◯ |
| motion vectors | MV_GEN shader (真 depth or constant) | **CameraMotion (uniform pan) + ObjectMotion (entity)** | ◯ |
| reactive/transparency | FSR4 path / T&C | 初期は uniform 0; 後で T&C auto-reactive | ◯ |
| uCameraDelta | MC camera pos diff | `IsoCamera.frameState` (offX/offY/zoom diff) | ◯ |
| uJitter/uPrevJitter | JitterManager → projection | `RenderScale.setJitter` + iso camera 注入 (§4.4) | ◯△ |
| currViewProj / prevViewProj | MC view-proj | **合成 iso ortho VP (IsoViewProjection)** §4.2 | ◯△ |

### 4.2 合成 isometric VP (IsoViewProjection) — シェーダ無修正で MV を出す

MV_GEN shader は **任意の VP pair** で動く (`uInvCurrVP`, `uPrevVP` を受けるだけ)。
isometric でも GLSL 側は触らずに済む:

- **view**: `lookAt(eye, eye + isoDir, up)`。isoDir = PZ の視線 (world→screen 変換、
  XToScreen/YToScreen 30° 係数)。方向は固定。
- **projection**: ortho、スケール = `zoom * Core.tileScale` 相当の tile→px 変換。
  `p22 = 0` で ortho の z 線形 → `uDeviceToViewDepth[2..3] = -1/p00, -1/p11` は ortho でも有効、
  `uFarDepth=0` (ortho は z が near 側 0)。`setProjectionMatrix` の計算式がそのまま成立。
- **jitter**: curr VP に clip-space translation `translation(jx*2/renderW, jy*2/renderH, 0)`
  を前掛け (MC と同一手法、§1.4-2)。prev VP は前フレームの jitter を反映した同型行列。
- **MV 単位**: MV_GEN は `(prevUv - uv) * uRenderSize` (render px) を imageStore する。
  ortho で全 pixel が同じ viewZ を持つ UV 空間になるので、camera pan に対し
  **MV は field としてほぼ一様**になる。

代替案 (B): MV_GEN を使わず uniform MV を直接 R16F texture に 1 回 glClear ぶん
で書く — 実装が更容易だが、entity rect を別 pass で書く必要があり、結局 shader
dispatch が増える。**方案 A (VP 合成) を本命とする**。

### 4.3 DepthProxy (PZ 深度の作り方)

PZ の world FBO (`getOffscreenBuffer(0)`) は 3D model の depth ソート用に
depth attachment を持つ可能性が高いが、**jar public API から depth texture を
直接得る手段がない**。実測路線:

1. **(M2 実測) depth attachment probe**:
   `glGetFramebufferAttachmentParameteriv(GL_DEPTH, ATTACHMENT_OBJECT_TYPE)` で
   world FBO の depth attach 型 (TEXTURE vs RENDERBUFFER) を確認。
   - TEXTURE → `glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)` → 自前 R32F FBO →
     FSRProcessor line 394 の `DEPTH_COPY_CS` (R32F 変換, uReversedZ flag) で整えて
     `uUseDepth=1` に昇格。**probe で depth 値が妥当そうなら採用**。
   - RENDERBUFFER → depth 直接取得は不可能。
2. **(本命) DepthProxy tile-rasterize**: iso では world→screen が `IsoUtils.XToScreen/YToScreen`
   で解析的に書けるため、**CPU 側で visible tile の z をラスター化できる**:
   - visible tile (~20x16 @ 1080p/zoom1) をループ、tile の screen rect にその cell の
     world z ÷ ortho depth range を R8 (0..255) で fill。cost < 0.5ms/frame。
   - `ObjectMotion` の rect (entity の screen rect, stencil id 同様) を上書き
     (entity は前景 depth なので「screen 前景 = 小さい depth」)。
   - `glTexSubImage2D(R8 render-res)` upload。R8 で十分 (iso の depth 空間は離散)。
   - **ground z=0 が支配的な世界では depth はほぼ一様** — MC constant-depth `0.999`
     パスがそうであったように、一様深度 + entity rect で十分 MV が閉じる。
3. **uUseDepth=0 のまま** でも動作する (mod 実績)。M1 はこれで始める。

### 4.4 Jitter の注入点 (isometric PZ)

`RenderScale.setJitter(jx, jy)` は API 存在するが未接続。PZ では world の screen 位置は
`IsoUtils.XToScreen(...) - camera.getOffX()` で決まる:

- **主ルート**: `IsoCamera.frameState` (or PlayerCamera) の offX/offY に
  `jx * (1/zoom)` px を加算して world pass 開始から endDrawing まで有効化。
  全 world sprite が subpixel shift し、pipeline の `uJitter` と整合する。
  - visibility cone は少し offset がずれる可能性 → Phase 1 の `ConeViewportHold/ConeSuppress`
    機構と同じ窓口で cone は jitter 外に置く decal で検証。
  - mouse hit / 入力は ±0.5px のずれで実害が極小 (input は render 中の offX 変更を受けない)。
- **副ルート**: `RawViewportEnter` で world viewport に ±0.5px offset を入れて image を
  移動する (rasterizer のみ shift、subpixel 精度は下がる) — 本命が駄目なときの fallback。
- M2.5 で実際に offX 反射 read/modify/write が正しく動くか確かめる (Reflection入手は
  IsoCameraScreenSize と同じ鴻溝)。**opt-in marker file** (`fsrupsc-jitter=on`) にする。

### 4.5 各オブジェクトの PZ 特化考察

- **静止 world + thin entities**: iso 固定 camera のため **MV はほぼ正解**
  (world axis-aligned, ortho, camera pan のみ)。MC の elytra / sky ghost 系は
  PZ には原理的に存在しない。現実の問題と対策:
  - **動く entities (zombies/NPC/cat/vehicle)**: `ObjectMotion` が既に
    screen-space rect + (dx,dy) を 126 slots で計算済み (`ObjectMotion#record`、
    IsoUtils.XToScreen - camera.getOff で算出、stencil id も使う)。**MV_GEN を拡張し、
    entity rect 内 pixel には rect の (dx,dy) を代入する** (UBO/SSBO 126x vec4 = 2KB、
    image 書き込みでスプライト全体ではなく rect 内 px のみ上書き)。Phase 2 の
    **最大の ready-made asset**。
  - **注意**: ObjectMotion は `FsrConfig.isUpscalerObjectMv()` で toggle (stencil
    tagging は drawModel 前後で現存)。Phase 2 / 公開版では stencil データは参照せず
    rect データのみ使う。
  - **animation artifact (zombie 歩行 anim、植物 anim、雨・パーティクル)**: camera MV
    では零で → 当該 pixel が「静止」と誤認し ghost 化。対策は 3 段:
    (i) FSR3 SHADING_CHANGE (M4)、
    (ii) **T&C auto-reactive** (prev color texture を 1 枚 copy しておき、MV_GEN 的に
    prevUv の prev color と比べ diff>threshold で reactive にする —
    FSRProcessor line 393 の merged MV_GEN (FSR4 用 computer MV_GEN+reactive 合体
    shader) がそのまま参考実装。**PZ で最も効果の大きい 1 つの追加**)。
    (iii) zoom delta や world 流れの強制 reset。
  - **zoom 変更**: proj が全替わり → MV 正常性が崩れる → `CameraMotion.reset()` で
    history を捨てる (1 フレーム FSR1 に落とす)。

### 4.6 深度がある場合の PZ 優位

PZ iso では **depth (tile z / entity z) の CPU からの準確解が可能** (§4.3)。
`DepthProxy` が動けば `uUseDepth=1` に切り替えるだけで:
- disocclusion clip (DEPTH_CLIP) が真の depth 差分で動く
- FSRProcessor の depth probe 同様の「depth 信頼 gate」が成立する
  (`uUseDepth` の depth 取れない家中の constant-depth 強制運用を緩和できる)。

---

## 5. fsrupsc への具体的移植計画

### 5.1 新クラス一覧

| class | 責任 | 接続先 |
|---|---|---|
| **Fsr2Compute.java** | FSR2Pipeline を 1:1 ポート (6 program, 12 texture, dispatch/state save/restore)。`setExternalOutputTex()` で ResolveOutput の出力 texture へ直書き | `Upscaler` (mode==fsr2/fsr3), composite drawer 上 |
| **JitterHalton.java** | JitterManager 移植 | `RenderScale.setJitter` feed |
| **CameraMotion.java** | frame 間 camera delta (offX/offY/zoom) → uCameraDelta/uCamStill/uCamStillExact/uCamReactive、reset 検知 | world pass 前後 (startFrame/endFrame 数値 hook) |
| **IsoViewProjection.java** | 合成 iso ortho VP (curr + prev + jitter)、deviceToViewDepth 計算、farDepth | Fsr2Compute#dispatch |
| **DepthProxy.java** | (M2) depth attach probe + blit; (M2b) tile-rasterize R8 proxy; ObjectMotion rect 反映 | Fsr2Compute の depth 入力 |
| **MotionField.java** | (M2) ObjectMotion.Frame → UBO/SSBO (126x vec4) + rect 上書き dispatch | Fsr2Compute |
| **FsrShaders.java** | ShaderBinaryCache 移植 (`Zomboid/fsrupsc-shader-cache/`)、GLSL は jar resource (`fsrupsc/shaders/fsr2/*.glsl`) | 全 compute |
| **Fsr3Compute.java** (M4) | SHADING_CHANGE pass 追加、FSR3 ACCUMULATE binding 差吸収、(将来 FG native bridge) | Upscaler mode==fsr3 |
| (opt) **AaFallback.java** (M-fallback) | TemporalBlendShader 雛形 (GL330 fragment ACCUMULATE-lite) | GL 4.3 不可環境 |

### 5.2 接続点 — 既存 hook への取り付け

現行で動いているのは **composite drawer 経路** (`Upscaler.resolveAtComposite` →
`SpriteRenderer.drawGeneric(Resolver)`)。Phase 2 の取付:

1. `Upscaler.mode()==fsr2|fsr3` → `Resolver.render()` 内で **`Resolve.fsr(...)` の代わりに
   `Fsr2Compute.dispatch(worldTex, worldFbo, jx, jy, ...)`** を呼ぶ。
   最終出力は `upscaledOutputTex` という**独立 RGBA8 texture** に書いて
   `ResolveOutput.set(tex, outW, outH)` で publish — **現行 fsr1 の publish 経路と同一**、
   composite 側は無変更。world FBO 自体には一切触れない (Phase 1 の方針踏襲)。
2. **jitter feed**: `JitterHalton.next` → `RenderScale.setJitter`; world pass (enterWorldPass)
   で camera offset に適用 (M2.5 まで jitter=0 でも pipeline は動作、ただし AA 効果なし)。
3. **per-frame 統計**: `Upscaler.resolveAtComposite` 側で `CameraMotion.begin/end` を
   world pass 範囲で記録。composite 描画前に「前フレーム統計 seal」—
   `ObjectMotion.endFrame()` の slot pattern と同じ規範に合わせる。
4. **起動時**: `FsrAgent` premain → `Fsr2Compute.ctxProbe()` (OpenGL43/44 caps + GPU renderer
   文字列) → Intel / Mesa / <4.3 なら `Upscaler` が自動 fsr1 fallback (`RenderScale.mode()`
   は FsrConfig を通じてのみ書き換え)。
5. **zoom / world 変化**: `CameraMotion.reset()` + `Fsr2Compute.resetHistory()`
   (frameIndex=0, ping バッファ clear, prevVP を無効化 = MV clear パス)。

### 5.3 buffer 集合 / サイズ (Fsr2Compute 初期化)

`inW/inH = RenderScale.scaledRect(Core.w, Core.h)` (render)、`outW/outH = Core` (display)。
必要オブジェクト (FSR2Pipeline#ensureTextures と一致、enum は §1.2):

```
mv        RG16F         (inW,inH)   MV_GEN out (GL44 不可なら FBO + glClearBufferfv clear)
rpd       R32UI         (inW,inH)   RECONSTRUCT in/out; clear は GL44 glClearTexImage または
                                    FBO attach → glClearBufferuiv
ddil      RGBA16F       (inW,inH)   RECONSTRUCT out (mv.xy, nearestDepth, lockLuma)
ddilPrev  RGBA16F       (inW,inH)   ping
mask      RG8           (inW,inH)   DEPTH_CLIP out (dilatedReactive, accumulationMask)
prepared  RGBA16F       (inW,inH)   DEPTH_CLIP out (YCoCg, depthClip.a)
lockCurr  RGBA8         (outW,outH) ACCUMULATE out (ping)
lockHist  RGBA8         (outW,outH) (pong)
newLocks  R8            (outW,outH) LOCK out; per-frame clear
hist      R11F_G11F_B10F (outW,outH) ACCUMULATE writeonly image (ping)
histPrev  〃                         (pong)
output    RGBA8         (outW,outH) 最終。externalOutputTex == -1 のときのみ自前持つ → ResolveOutput.set
depthIn   R32F          (inW,inH)   DepthProxy 供給 (probe 成功時は blit 上書き)
```

- **texture の filter は mod どおり** (mv/rpd/lock/newLocks = NEAREST、それ以外 LINEAR)。
- **binding 0..14 は PPT .glsl と同一番号** (`glBindImageTexture`/`glActiveTexture`)。
  PZ の game 側 state は Resolve.fsr と同じ save/restore (GL11-GL30 + 拡張:
  active texture 各 unit, image unit, program, FBO, viewport, scissor, blend, draw buffer) を
  前後で行う必要がある。現行の `Resolve.fsr` が state restore を慎重にやっている
  (draw buffer GL_NONE 問題, RASTERIZER_DISCARD 等) — この慣行を引き継ぐ。
- **texture format は image load/store 互換**が必須。R11F_G11F_B10F は image load/store
  で保証される (GLSL layout に既指定)。RGBA8/R8/RG8/R32UI/RG16F/RGBA16F も OK。

### 5.4 uniforms の PZ 側供給

§1.3 の表どおり。PZ 起源の一次入力:

- jx/jy: `RenderScale.frameJitterX/Y` (JitterHalton)。
- renderW/H: `RenderScale.scaledRect` (world FBO の有効領域)。
- displayW/H: `Core.width/height`。
- currViewProj/prevViewProj: `IsoViewProjection` (camera offX/offY/zoom diff + jitter)。
- uCameraDelta: `CameraMotion.deltaX/Y/Z` (iso では screen-space への変換後 delta)。
- uCamStill / uCamStillExact / uCamReactive: `CameraMotion.still()/reactive()`
  (threshold: posDelta > 0.02 / rotDelta > 0.006, MC 実績値を踏襲、bob 相当は PZ では不要)。
- uDownscaleFactor = scaledRect/display、uMotionVectorScale = (1/inW, 1/inH)、
  uMotionVectorJitterCancellation = `(prevJit-jit) * mvScale`。

### 5.5 Milestone

- **M0**: GL43/GL44 caps probe + GPU denylist (Intel/Mesa) + ShaderBinaryCache。
  失敗環境は自動 fsr1。
- **M1 (「jitter=0 static 積算デモ」)**: Fsr2Compute を `prevViewProj = null` 常時
  (= MV clear パス), `uCamStill=1` で dispatch。出力は「lanczos EASU 近似 + 毎フレーム
  fresh」とほぼ FSR1 相当の spatial upsample。**ここで検証する 3 点**:
  (a) compute dispatch が PZ 上で正しく動く、(b) state restore が無害、
  (c) `ResolveOutput` publish 経路が compute 出力 texture で回る。
- **M2 (camera MV + DepthProxy)**: CameraMotion + IsoViewProjection 有効化 (pan/zoom delta)、
  `prevViewProj` が有効 → MV_GEN 有効 → 真の temporal 積算 (静止時加重、pan で浪费)。
  DepthProxy (Depth attach probe または tile rasterize) 補装、`uUseDepth` 切替を probe 結果で決定。
  ObjectMotion UBO → entity MV 上書き。
- **M2.5 (jitter 注入)**: JitterHalton → `RenderScale.setJitter` → iso camera offX/offY 注入 +
  IsoViewProjection への uJitter feed。opt-in marker。静止時 edge AA が回復する。
- **M3 (検証/比較)**: FSR1 と screenshot A/B、負荷測定 (`logSlow` 相当)、
  T&C auto-reactive (prev color copy, merged MV_GEN 参照) で entity ghost 対策、
  FSR3 mode では SHADING_CHANGE 有効化。
  - **実装済み (2026-10-23)**: `AUTO_REACTIVE_CS.glsl`（FSR4 合成 MV_GEN の T&C 仕様を
    忠実移植 — still 0.10/0.30 / moving 0.18/0.50 ramp、×0.9 cap、offscreen prevUv →
    reactive 1.0、searchR ±1/±2、constant-depth trust gate mvLen≥1.5px（DepthProxy
    有効時解除））。dispatch 順: RECONSTRUCT → AUTO_REACTIVE → DEPTH_CLIP が
    `uReactiveMask`(binding 8) を restore・`max(disocclusion, tcReactive)` で合流。
    prev color は dispatch 終了時 `glCopyImageSubData` (colorTex 内部フォーマット照会
    ベース、非対応フォーマットは自動無効)。既定 ON、`FsrControl.fsr2AutoReactive(0/1)`,
    status 行 `ar=on+prev`。
- **M4 (FSR3 / FG)**: Fsr3Compute (SHADING_CHANGE 追加 + FSR3 ACCUMULATE binding 差)、
  FG は native bridge (mc-fsr 準拠) で別 doc。
  - **実装済み (2026-10-23)**: fsr3 モードは mod の fsr3 GLSL セット（SHADING_CHANGE
    を含む 9 program）を `42/media/shaders/fsr3` ミラー → `Fsr3Shaders.java` で
    埋め込み。`Fsr2Compute` がモードに応じ program セットを切替（fsr2⇔fsr3 は
    destroy+再compile、履歴全リセット）。dispatch 順 = MV → (MV_OVERRIDE) →
    barrier → rpd clear → **SHADING_CHANGE** (uThreshold=0.5, uFarDepth) →
    RECONSTRUCT → DEPTH_CLIP (binding 6 供給) → LOCK → ACCUMULATE (binding 8
    供給、temporalReactive=lockStatus.a + margin confidence 減衰) → RCAS →
    shading RG8 ping-pong。FG (FG_*) はネイティブ bridge フェーズに持ち越し。
- **M5 (FG — 純 GL ソフトウェア方式へ変更, 2026-10-23)**: native bridge
  (mcfsr.dll / fsrvk + Vulkan interop) は PZ の GL path には過剰と判断し、
  **ダブルプレゼント方式**（pure Java/GL）に確定。
  PZ present path 調査結果: `GameWindow.render()` → `renderInternal()` →
  GameStateMachine.render → world/UI → present は唯一の swap 呼び出し
  `org.lwjglx.opengl.Display.swapBuffers()`（内部で
  `GLFW.glfwSwapBuffers(Display$Window.handle)`、imgui 破棄系の本体も確認）。
  - **S-1 (present control spike, 検証済み 2026-10-23)**: `FsrTransformer` +
    `FsrHooks.present` group（既定 ON、外部ランチャー時は hooks 値に `present`
    追加が必要）で `org.lwjglx.opengl.Display#swapBuffers` enter を hook。
    `FgPresent` が差し込み present を実施: back buffer copy → dummy frame →
    再入ガード付き inner swap → copy 再描画で N を新 back buffer に復元 →
    元 swap が N を提示。マゼンタ点滅で挿入 present が画面に届くことを
    検証済み。swap 間隔ヒストグラム（600 presents ごとに log）。
    stale GL エラー (PZ/zink 自前描画由来 0x500) は asset 構築前に drain、
    挿入後は自前エラーのみ 3 ストライクでセッション無効化。
  - **S-2/S-3 (retention + camera-MV interpolation, 実装済み 2026-10-23)**:
    copy テクスチャ ping-pong 2 枚で表示フレーム (world+UI 合成済み FB 解像度)
    を常時保存。`fsr2FgTest(2)`=複製挿入、`fsr2FgTest(3)`=camera-MV interpolation
    挿入: prev copy を `+uHalf`(=0.5×`OFFSIGN×CameraMotion.deltaRel`×fb/native)、
    current copy を `-uHalf` に warp し blend。still/zoom/reset/no-prev は
    duplicate フォールバック。`FsrControl.fsr2FgMvSign(±1)` で方向修正。
    (per-pixel MV 接続済みのため global 版はフォールバック経路)
  - **S-4 (per-pixel MV field 接続, 実装済み 2026-10-23)**:
    `Fsr2Compute.fgMvAvailable/fgMvTexture/fgMvW/H` accessor (present 時点の
    最新 dispatch 分 = end-of-frame ping-pong 後の `previousDilatedDataTex`、
    `rg` = UV 単位 mv `prevUv = uv + rg`)。挿入シェーダ uMode 2: render-res
    dilatedData を同じ UV でサンプルし `sh = 0.5×mvSign×mv`、prev/curr を
    `±sh` warp、mismatch 重み付け (diff→current 側寄せ) で 50/50 blend を
    補正。MV field 不在時は global camera 版 (uMode 3) / duplicate に自動
    フォールバック。status `pxmv=` で per-pixel 実行数を確認可能。
  - **S-4 残課題**: timing reset (pause/menu/mode 切替での FG skip +
    prevValid/ヒストリ保持リセット — CameraMotion フラグの自動フォールバック
    のみ現状)、UI を新鮮な状態で挿入する composite capture 戦略。
    写真版 FG_INTERPOLATION GLSL (decomp fsr3) の本格移植はその次。
  - **S-4b (timing reset, 実装済み 2026-10-23)**: `Fsr2Compute.fgDispatchEpoch()`
    (dispatch 完了ごとに increment) で「新鮮な world フレームが last present
    以降に存在するか」を判定。loading/cutscene/pause 等で dispatch が走って
    いないフレームは stale MV/camera を参照しないよう duplicate にフォール
    バック (stale epoch で差分 0 → 複製)。zoom/still/reset は既存の
    CameraMotion フラグ fallback が継続。
  - **S-5 (VSync-off pacing spike, 実装済み 2026-10-23)**: 純ソフト FG の表示を
    VSync OFF でも成立させる CPU 側ペーシング (`fsr2FgPace`)。挿入 flip 前に
    前実フレームの露出保障 (spacing = 露出 × 2 の deadline wait)、inner swap
    後に挿入フレームの露出窓 (parkNanos + 末尾 2ms spin でサブ ms エッジ)。
    → vblank block の代用でループを ~2 露出/real present にスロットル。
    アライメントは制御しないので境界 tearing が残り得る = 実験扱い。
    本格解は swap interval 制御 / ネイティブペーシングで Phase 3 の論点。

---

## 6. リスクと緩和策

| # | リスク | 根拠 / 発生条件 | 緩和 |
|---|---|---|---|
| R1 | PZ の GL context が環境により 4.3 未満 / compute 不可 | Resolve.java line 616 実測 4.6.0 NVIDIA は 1 例のみ | M0 probe (OpenGL43/OpenGL44 caps + GL_VERSION log) → FSR1 fallback。Intel は fsr-reloaded 実績から denylist |
| R2 | `glClearTexImage` (GL 4.4) が駄目な環境 | FSR2/FSR3 Pipeline が使用 | caps.OpenGL44 が true なら使用、未満なら FBO attach + `glClearBufferfv/uiv` (FSR2Pipeline 自身の clearMvFbo と同一手法) |
| R3 | **history reset 漏れ** (world load, zoom 変, scale 変, セーブ復帰, 迷子場面遷移) | FSRProcessor: VersionStatus / RetiredSceneTarget / 20s `stormWindow` / `fsr1TransitionStorm` で再現 | `CameraMotion.reset` + `Fsr2Compute.resetHistory` を zoom diff / world load トリガに接続。初期 N フレームは FSR1 blit に落とす storm window を移植 |
| R4 | `getOffscreenBuffer` の depth attachment が取れない | PZ 公開 API 無し, attach 型未知 | (i) attachment probe, (ii) DepthProxy tile-rasterize fallback, (iii) `uUseDepth=0` のまま運用 (mod 実績あり) |
| R5 | **camera motion の検出漏れ** — iso camera は回転しないが **cell wrap shift + zoom** を miss すると全体 wrong-MV で ghost/褐色 | cell crossing で offX が tile 単位 jump | CameraMotion は offX (wrap込み) delta をそのまま cameraDelta に feed (shift は mono-MV で正しい)。zoom delta → reset |
| R6 | **jitter 注入の波及** (cone, UI, mouse,VisibilityPolygon) | PZ 内部 unknown | M2.5 opt-in marker で検証。cone は Phase 1 の suppress/hold 機構で jitter 外に。mouse は ±0.5px 実害極小。問題が出れば副ルート (viewport offset) に切替 |
| R7 | compute dispatch が render thread を block (6 dispatch + barrier/frame) | fsr-reloaded MC 実績 logSlow | `logSlow` 相当の測定を FsrLog に。ACCUMULATE (display-res) が大部分 — 必要なら dispatch 数を render-res に落とす改修。scale auto-adjust (`FsrSettings` AutoQuality) |
| R8 | shader compile cache / driver 差 / キャッシュ破損 | ShaderBinaryCache 実績 | 同機構移植 (SHA-256 + glProgramBinary + 不整合自動 recompile)。cache dir `%USERPROFILE%\Zomboid\fsrupsc-shader-cache` |
| R9 | composite sampler 側が RGBA8 以外 / sRGB 挙動 | `ResolveOutput.compositeTexture` | output texture は RGBA8 維持 — fsr1 実績通路と同一 |
| R10 | **Intel iGPU / Mesa での compute crash** | fsr-reloaded VersionStatus 実績 | GPU string denylist + 自動 FSR1 |
| R11 | **entity ghost** (zombie, vehicle, NPC, particle) — camera MV では 0 のため | fsr-reloaded コメント (particles/mobs ghosting) | (短期) uCamReactive floor、(中期) ObjectMotion rect-MV、(計画) T&C auto-reactive + SHADING_CHANGE |
| R12 | `uUseDepth=0` 下、静止時 mv=0 強制 → jitter=0 だと全く積算が進まず FSR1 と同じ | mod 挙動 | これは M1 の意図した挙動。M2 で jitter + prevVP が有効になり temporal 効果が立ち上がる |
| R13 | **depth probe が誤って depth を信じて SHAKE** (mod で 「26.1.2 depth unwritten → trust gate 必須」) | DEPTH_CLIP コメント | DepthProxy/probe が確実と確認できるまで `uUseDepth=0` で運用、probe による確認後に昇格 |

---

## 7. 参照元 (fsr-reloaded 内)

- `FSR2Pipeline.java` (line 235-682) — FSR2 全パス
- `FSR3Pipeline.java` (line 253-976) — FSR3 差分 + FG GL pass
- `FSRProcessor.java`: line 1232-1253 (captureAndJitter), 1918-1956 (jitter advance),
  3430-3556 (dispatch 呼び出し), line 393 あたり (merged MV_GEN string, FSR4 用
  MV + reactive + shading change 合体), 394 (DEPTH_COPY_CS), 5000+/5169+/5223+
  (depth probe / captureWorldDepthBeforeHandClear / stageRealDepth),
  5601+ (VersionStatus / GPU denylist)
- `JitterManager.java` / `ShaderBinaryCache.java` / `TemporalBlendShader.java` (全体)
- `shaders/fsr2/*.glsl` (ACCUMULATE 33KB, DEPTH_CLIP, LOCK, MV_GEN, RCAS, RECONSTRUCT)
- `shaders/fsr3/*.glsl` (+ SHADING_CHANGE, FG_*)

FSR-UPSC 側: `RenderScale.java` (setJitter / scaledRect / enterWorldPass / worldFboBound),
`Upscaler.java` (resolveAtComposite + Resolver), `Resolve.java` (state restore 慣行, probe 機構),
`ObjectMotion.java` (entity screen-motion — Phase 2 最大の ready-made),
`Gl.java` (state save/util, compute 拡張を別 `GlCompute.java` に追加推奨),
`ResolveOutput.java` (composite publish 経路)。

ライセンス: AMD FidelityFX GLSL は BSD 系で再配布可 (NOTICE 付与)。
`docs/PHASE2-FSR3.md` §4 の整理に従う。
