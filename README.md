# FSR-UPSC — Project Zomboid B42 用 AMD FidelityFX (FSR) アップスケーラー mod

`pzopt` / DLSS-UPSC の実績あるレンダースケール基盤を**完全新規ネームスペース `fsrupsc`** で再構成した FSR 専用 mod。

- **FSR 1**: 空間アップスケール（EASU + RCAS）。compute 不能/fault 時の自動フォールバック先
- **FSR 2.1 / FSR 3.1**: 時間的アップスケール（純 GL compute、ネイティブブリッジ不使用）
  詳細は `docs/PHASE2-FSR3.md` / `docs/PHASE2-FSR3-GLSLDESIGN.md`
- **ソフトウェアフレーム生成** (`FgPresent`, `FsrControl.fsr2FgTest`): present path 上の
  ダブルプレゼント方式

参考にした実装: `mc-fsr` (Fabric, MIT)、`fsr-reloaded` (FSR2/3 GLSL compute)、
`oslo-render` (Streamline/DLSS-FG 版)。

このリポジトリは公開版で、テスト版にあった計測専用の足場
（outline/cone trace、mv probe、swap interval histogram、周期ログ emit）は
実使用で不要なオーバーヘッドとなるため除去しています。確定修正と機能トグルのみが
本体に残ります。開発期の診断記録は `docs/DIAGNOSIS-HISTORY.md` に退避。

## ディレクトリ

```
42/                  mod本体（mod.info, media/lua, media/shaders, agent jar）
  agent/             fsr-upsc-agent.jar（ビルド成果物）
src/fsrupsc/         javaagent ソース（package fsrupsc の単一パッケージ）
scripts/             build-agent.ps1 / install-agent.ps1 / rebrand.ps1(当時の変換記録)
vendor/lib/          byte-buddy 1.18.8（unshaded）
vendor/upstream/     元ネイティブ実装メモ（DLSS/NGX は休眠中）
docs/                設計文書 + 開発診断ログ (DIAGNOSIS-HISTORY.md)
```

## ビルド

```powershell
# ゲームディレクトリ・リビジョンはスクリプト既定（E:\...\ProjectZomboid, 4a0e9546ec）
powershell -ExecutionPolicy Bypass -File scripts\build-agent.ps1
```

- JDK 25 が必要（`C:\Program Files\Java` から自動検出もする）
- `<game>/projectzomboid.jar` の `zombie/GitVersion` を読み、**リビジョン不一致ならビルド中断**。
  ゲームが更新されたら `-Revision <新hash>` を渡す（エージェントも文字列で同じハッシュを待つ）。
- 成果物: `42\agent\fsr-upsc-agent.jar`（byte-buddy を jar 内展開して自己完結）

## インストール / アンインストール

ゲームを終了しておくこと。**DLSS-UPSC 等の他エージェントが同時装着されない**こと（二重パッチ衝突）。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1            # attach
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -Detach    # vmArgsだけ外す（判定用）
powershell -ExecutionPolicy Bypass -File scripts\install-agent.ps1 -Uninstall  # vmArgs+jar削除
```

attach で書き込まれる vmArgs:

```
-javaagent:fsr-upsc-agent.jar
--enable-native-access=ALL-UNNAMED
```

**それ以外は不要**です (hook マスク既定 = 全 groups 含む `present`,
downscale マスター既定 = ON)。絞り込み無効化は任意の上書きチャンネル:

```
-Dfsrupsc.hooks=all            (絞る: loop,internal,fbo,draw,viewport,present — FG test には present が必須)
-Dfsrupsc.downscale=false      (downscale パスを無効化して完全ストック動作に戻す)
```

優先順: env `FSRUPSC_DOWNSCALE` > `~\Zomboid\fsr-upsc-downscale` ファイル > `-Dfsrupsc.downscale`
（PZ JAM Launcher は未知の `-D` を落とすためフォールバック多段。hooks も
env `FSRUPSC_HOOKS` > `~\Zomboid\fsr-upsc-hooks` > `-Dfsrupsc.hooks` 同様）

## 設定（ゲーム内）

Main Menu → Options → Mods → **FSR-UPSC**（`FSR_UPSC_Options.lua` → `Zomboid\Lua\ModOptions.ini` に保存、起動時に premain が読む）

| 項目 | 値 |
|---|---|
| Upscaler | off / bicubic / **fsr1** / **fsr2** / **fsr3**（compute 不可能/fault 時は fsr1 に自動フォールバック） |
| Quality preset | Ultra Quality (1/1.285) / **Quality (1/1.444)** / Balanced (1/1.667) / Performance (1/2) / Native |
| Explicit scale % | 0(プリセット) / 50 / 60 / 69 / 78（16px スナップ付き） / **83 (1600x900 固定** — 1920x1080 環境で最も劣化の少ない縮小選択。高さ 900 は 16px スナップで 896 に丸めない) |
| RCAS sharpening | 0–100%（既定 80） |
| Frame generation | off / 2 複製挿入 / 3 補間挿入（即時適用、既定 off） |
| FG pacing | off (VSync ON 用) / safe (-1) / fast (-2)（即時適用） |

fsr2 / fsr3 は GL 4.3 compute 対応 GPU で実パスとして動作（検証済み）。
compute fault 時はそのフレームを fsr1 で救助し、fault はセッション限定で fsr1 継続。

FG の warp 方向反転 (`fsr2FgMvSign`) と手動露出微調整 (`fsr2FgPace(N)`,
0.1ms 単位) は Lua コンソール専用（オプション UI には出さない）。
`fsr2FgPace` の off/safe/fast はオプション UI・コンソールともに設定へ永続化。

## フレーム生成（FgPresent）

present path (`Display#swapBuffers`) 上のダブルプレゼント方式。**VSync ON 前提**
（ループが 2 vsync に伸び、PZ の FPS 表示は約半分になるのが正常で、画面は
全表示レートで挿入フレームを提示）。外部ランチャー使用時は hooks に
`present` を含めること。

挿入フレームは前後 2 実フレーム (world+UI 合成済み back buffer) の copy から作る:

- **fsr2FgTest(2) 複製挿入**: 実フレームの replay。**推奨 (安定)**。
  cadence だけ倍増する方式で、ちらつき出ない
- **fsr2FgTest(3) 補間挿入**: per-pixel MV field で前後 copy を warp 合成
  （compute が生きていない場合は camera shift、それも不可なら複製にフォールバック）。
  お好みで。mvScale 0.25 (= mv field の半分 Shift、probe 実測と理論一致) は code default

```lua
FsrControl.fsr2FgTest(0|2|3)
FsrControl.fsr2FgMvSign(-1|1)     -- warp 方向の反転（既定 = M2 検証済み符号）
FsrControl.fsr2FgMvScale(0.25)    -- per-pixel half-shift 倍率 (console 専用)
FsrControl.fsr2FgMult(1|2|3)      -- 1 実フレームあたりの挿入数 (既定 1 = 2x ベーシング)
FsrControl.fsr2FgPace(0|-1|-2|N)  -- VSync OFF 用ペーシング（実験）
FsrControl.fsr2FgHudSnap(0|1)     -- 静止 HUD 文字の snap 再生（既定 1, console 専用）
FsrControl.fsr2FgMvFlip(0..31)    -- mv field 符号/軸実験トグル (既定 0 = 正解, console 専用)
FsrControl.fsr2OffSign(±1)
FsrControl.status()               -- fgtest= real= inserted= interp= pxmv= pace= を確認
```

`fsr2FgPace` の詳細:

- `0` = off（既定、VSync ON 前提）
- `-1` = 自動安全側: 露出 = 1 refresh（ループ = 表示レート/2、テア無し）
- `-2` = 自動高速: 露出 = 1/2 refresh（ループ ≈ 表示レート、tearing 発生し得る）
- `N > 0` = 手動露出（0.1ms 単位、167 ≈ 60Hz 全露出 / 83 ≈ 60Hz 半露出）

挿入 flip 前に前実フレーム分の露出（spacing = 露出 × 2）を確保し、inner swap 後に
露出窓だけ sleep してから実フレームを復元。走査アライメントは制御しないので
境界の軽微な tearing は残り得る。

挿入フレームの既知限界: UI は 1 フレーム古い (world+UI 合成済み back buffer の
copy 由来)、高速度 entity は mismatch 重み付けで current 側に寄せつつもわずかに
smear。still / zoom 変化 / reset / 新鮮な dispatch 無し（loading・pause 等の
dispatch epoch 不変）フレームは複製にフォールバック。

**細部ちらつきの結論 (2026-10-26)**: 補間 (fsr2FgTest(3)) の挿入フレームは
2 実フレームの融合体であるため、細かく見るとフレーム全体で微小ちらつきが
原理的に発生する（実フレームの crisp 表示と交互に提示されることが源）。
画面静止 HUD の文字（インベントリ行/ダッシュボード数字等）については
`fsr2FgHudSnap` (既定 ON) が内容変化時のピクセルを current copy 単独再生に
snap して二重ゴーストを除去済みだが、world 自体は不可避と判定。
複製挿入 (`fsr2FgTest(2)`) は安定しているため **推奨構成は複製側**。

## ライブ制御（Lua → Java）

エージェントが `LuaManager$Exposer#exposeAll` にアドバイスを差し込み、Lua 初期化時に
`fsrupsc.FsrControl` を環境へ登録します。コンソールに
`[fsr-upsc] exposed fsrupsc.FsrControl (+FsrControl alias)` が出れば成功。

```lua
local fsr = rawget(_G, "FsrControl")            -- トップレベル別名
-- or: local fsr = rawget(rawget(_G, "fsrupsc"), "FsrControl")
fsr.setUpscaler("fsr1") ; fsr.setUpscalerQuality("balanced")
fsr.setUpscalerScalePct(60) ; fsr.setFsrSharpnessPct(85)
fsr.status()        -- upscaler/mode/quality/scale + 状態串
fsr.resolveStatus() -- resolve 成功率
fsr.log("...")      -- fsr-upsc-agent.log への書き込み
```

実験トグルの全体像: `fsr2FgMvSign / fsr2FgMvScale / fsr2FgMult / fsr2FgPace(N) /
fsr2FgMvFlip / fsr2FgHudSnap / fsr2OutlineFix / fsr2OffSign / setFsr2Debug` などは
console 専用（persist なし）。確定済みの値は code default に反映済み。

ログ: `%USERPROFILE%\Zomboid\fsr-upsc-agent.log` と console.txt（`[fsr-upsc]` タグ）。
ログはセッション冒頭と失敗時のみ書き、フレームごとの測定出力はない。

## セーフティ設計

- リビジョン不一致 → 何も変換せず完全ストック動作
- `RenderScale.disable()` が失敗時に永久にストックへフォールバック（理由をログ）
- ワールドパス限定 = UI/テキスト/コンポジットはフル解像度のまま
- GL はレンダースレッドのみ、フック本体での GL query / allocation / log禁止
- 視界コーンはスケール対象外（`fsrupsc-nocone` マーカーで任意抑制）

## 確定修正レコード

- **outline ズレ修正 (2026-10-26)**: スケーリング時、near ターゲット（ゾンビ/コンテナ）
  のアウトラインが実像より左上方向に 0.775x (= 1488/1920) 縮んで表示される問題。
  原因: `ModelOutlines.endFrame` 内の 4 カ所 glViewport はすべて同一ローカル rect を
  使い、fboA の mask は世界パスと同一 camera basis (= region 座標) で既に描かれている。
  viewport funnel が world-bound の blit (:234) も region サイズに絞ったため mask に
  **2 回目の squeeze** が乗っていた（縮率が SCALE 1 回分と一致することが決定打）。
  修正: `RenderScale.requestedViewport` の `OUTLINE_VP_RAW` (code default ON) で
  endFrame seam 全行の funnel 書換を SKIP（blit は fboA→world 1:1）。seam 判定は
  `RenderScale.endFrameRawVp()`（stack 走査）。`FsrControl.fsr2OutlineFix(0)` で戻せる。
- **composite flood 修正**: `ResolveOutput.compositeTexture` は `inWindowThread()` gate
  で composite スレッドのみ substituted texture を返す。render thread が outline の
  fboA/B/C `getTexture` を window 開放中に通ると swap が漏れて outline 色で
  フレーム全体が flood する。
- **menu starvation 誤発動修正 (2026-10-24)**: メインメニューの背景合成は world pass
  無しで毎フレーム走るため、`composite starved` カウンタ（120 で session 全域 disable）
  が「world が一度も無い段階」で満たされ、起動直後に mod 全体が stock のまま遊ぶ
  事故の型。starvation 計数は resolve pipeline が一度でも走ったあとに限定。
  fail-safe 発動後の console 再武装 `FsrControl.fsrReenable()` を搭載。
- **FG still gate 確定 (2026-10-24)**: 補間の停止判定は `CameraMotion.stillExact()`
  (d < 0.01px) のみ。旧緩い閾値 (0.5px) は歩行 delta と同桁で dup freeze-jump
  による全画面振動を生んでいた。
- **FG HUD snap (fsr2FgHudSnap, 2026-10-26)**: 補間挿入フレームで内容が変わった
  画面静止 HUD ピクセルを current copy 単独再生にする（シェーダ uFlags bit5、
  dz > 0.05 閾値、noise は従来ブレンド維持）。

## 主要コンポーネント

- `src/fsrupsc/IsoViewProjection.java` — screen-space ortho VP 合成（reprojection 用
  curr/prev/inv ペア。`OFFSIGN` は `FsrControl.fsr2OffSign(±1)` で反転）
- `src/fsrupsc/CameraMotion.java` — `IsoCamera.frameState` 由来の camera delta / still /
  zoom 変化・resume gap 検知（reset = MV クリア＋frameIndex=0）
- `src/fsrupsc/MotionField.java` + `MV_OVERRIDE_CS.glsl` — ObjectMotion entity rect を
  camera MV へ上書き（std140 UBO、render px 単位、`mv = dx*zoom*k`）
- `Fsr2Compute` — fsr2: 8 compute programs（MV_GEN/MV_OVERRIDE/RECONSTRUCT/
  DEPTH_CLIP/AUTO_REACTIVE/LOCK/ACCUMULATE/RCAS）; fsr3: 9（fsr3 セット +
  SHADING_CHANGE）。debug は `FsrControl.setFsr2Debug(n)`（3=mv 可視化）
- `src/fsrupsc/CameraJitter.java` — world pass への sub-pixel jitter 注入
  （Halton(2,3)、`IsoCamera.frameState` の offX/offY read/modify/write、
  pass 終了後に値一致確認つきで復帰）。ACCUMULATE de-jitter と LOCK
  `hrPosFromLrPos` に実 jitter を供給。既定 off、`FsrControl.fsr2Jitter(0/1)`。
  compute fault 時は自動で注入停止。
- `src/fsrupsc/DepthProxy.java` + `DEPTH_PROXY_CS.glsl` — world FBO の depth
  attachment を probe（型/フォーマット、1 回ログ）→ `glBlitFramebuffer(DEPTH)` で
  自前 same-format depth FBO へ → R32F 逆深度マップ（`viewZ = 1/d`、背景=far）に
  変換し `uUseDepth=1` を RECONSTRUCT/DEPTH_CLIP/ACCUMULATE に昇格。既定 off、
  `FsrControl.fsr2Depth(0/1/2)`（1=auto, 2=force）。
- `AUTO_REACTIVE_CS.glsl` + `Fsr2Compute` M3 統合 — auto-reactive: 現在色と
  prevUv（MV フィールド由来）の prev 色の輝度差で screen-space オーバーレイ
  （outline/view cone/パーティクル/攻撃ハイライト等、MV 対象外の注視点系）を
  検出して fresh-sample weight を与える。FSR4 合成 MV_GEN の T&C 仕様を忠実移植。
  prev color は dispatch 終了時に `glCopyImageSubData(colorTex→prevColorTex)`。
  既定 ON、`FsrControl.fsr2AutoReactive(0/1)`。
- M3.1 停止時リセット — 移動→`stillExact`（<0.01px）への遷移 1 度で
  `currentFrameIndex=0`（静止位置基準で再収束）。
- M3.2 `fsr2CamReactive(0-5)` — 移動時 smear 減衰（fsr-reloaded の
  `uCamReactive` フロア移植）。既定 1（mild）、0 で off。
- `42/media/shaders/fsr3/*.glsl` + `Fsr3Shaders.java` + `Fsr2Compute` M4 統合 —
  fsr3 モード実装: mod の fsr3 シェーダセット（SHADING_CHANGE 含む 9 program）を
  借用し、8x8 タイル luma-ratio diff の SHADING_CHANGE pass を dispatch 順
  MV → SHADING_CHANGE → RECONSTRUCT に挿入。M3 の auto-reactive も
  fsr3 DEPTH_CLIP に同移植済み。
- `src/fsrupsc/Fsr2Shaders.java` — AMD FidelityFX FSR2 compute シェーダ＋MV_OVERRIDE を内蔵
  （`42/media/shaders/fsr2/*.glsl` が上流ミラー、`scripts/gen-shaders.ps1` で再生成）
- `Upscaler`: `fsr2`/`fsr3` モードは compute が利用可能なら `runResolve` 経由で
  Fsr2Compute dispatch、失敗時はそのフレームを fsr1 で救助（compute fault は
  セッション限定、fsr1 は常に生きる）
- `42/media/shaders/fsr_blit.vert + fsr_easu.frag + fsr_rcas.frag` — FSR 1 の
  空間アップスケール 3 補完（フォールバック経路）
- `src/fsrupsc/FgPresent.java` — ソフトウェアフレーム生成（ping-pong copy
  保持、複製/補間挿入、HUD snap、VSync-off pacing、fail-safe strike 制御）

## ライセンス / 第三者ライブラリ

`THIRD-PARTY-NOTICES.md` を参照（AMD FidelityFX GLSL 出自、fsr-reloaded 由来、
byte-buddy Apache-2.0、DLSS SDK 非同梱）。

## 開発記録

開発期の診断過程（outline ズレ・FG 振動・ちらつき等の切り分け手順と実測）は
`docs/DIAGNOSIS-HISTORY.md`、Phase 2 設計は `docs/PHASE2-FSR3.md` /
`docs/PHASE2-FSR3-GLSLDESIGN.md`。
