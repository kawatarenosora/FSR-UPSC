# FSR-UPSC 開発診断ログ (テスト版 README から退避)

テスト版 (judged 2026-10-23 .. 2026-10-26) を肉付けしてきた観察・計測・調査の一歩手前の記録。
公開版下では、この内の**計測専用足場 (OutlineTrace, cone trace 4 種, mv probe,
swap interval histogram, periodic emit) はコードから除去**済み。確定修正のみが
本体に残り、測定ログの趣旨や切り分け手順はこのファイルに記録します。

---

### Phase 2 進捗（M0–M4 ＋ FG S-1〜S-4b 完了・検証済み — wrap-up）

- `fsr2FgTest(1)` → **マゼンタ挿入 verified** (2026-10-23): 挿入 present が画面に届くこと
  を実証。初回テストでは PZ/zink 自前レンダリング由来の stale GL エラー (0x500) が
  asset check を誤爆するため drain-first 修正 (`ensureAssets` 冒頭 drain +
  挿入後は自前エラーのみ 3 ストライク制)。この環境は Mesa zink (NVIDIA)。
  `ProjectZomboid64.json` の `-Dfsrupsc.hooks=` は外部ランチャー側
  (hs_err pids で実引数確認済み `-Dfsrupsc.hooks=fbo,fbobegin,viewport,loop`) を
  使っているため launcher 側の hooks 値に `present` 追加が必要。
- **S-2/S-3 実装済み (2026-10-23)**: `FgPresent` ping-pong copy retention
  (tex1/tex2 + curSlot + prevValid) — swap 時点の表示フレーム (world+UI 合成済み
  1920x1080 RGBA8) を常時 2 枚保持。`fsr2FgTest(2)` = 複製挿入、
  `fsr2FgTest(3)` = camera-MV interpolation 挿入 (S-3 spike):
  prev copy を `+uHalf` (= 0.5 × `OFFSIGN × CameraMotion.deltaRel` × fb/native 比)、
  current copy を `-uHalf` に warp し 50/50 blend。`CameraMotion.still()`
  / `zoomChanged` / `resetRequested` / `!hasPrev` では duplicate にフォールバック。
  既知の S-3 限界: global shift のため entity は ghost (per-pixel MV field は後段)、
  UI は 1 フレーム遅れ (前フレーム copy 由来)。`FsrControl.fsr2FgMvSign(±1)`
  で warp 方向反転。
- **S-4 per-pixel MV field 接続 実装済み (2026-10-23)**: `Fsr2Compute` に FG
  accessor 追加 (`fgMvAvailable/fgMvTexture/fgMvWidth/fgMvHeight`) — present
  hook 時点 (end-of-frame ping-pong 後) の最新 dispatch 分 MV field は
  `previousDilatedDataTex` (RGBA16F render res, `rg` = UV 単位 mv,
  `prevUv = uv + rg`, jitter-cancel 済み=RECONSTRUCT 出力)。
  `fsr2FgTest(3)` は `motionUsable && fgMvAvailable()` なら per-pixel
  (shader uMode 2: `sh = 0.5×mvSign×mv`, prev at `uv+sh` / curr at `uv−sh`,
  mismatch 重み付け `w = 0.5×clamp(1−diff×4, 0.3, 1)` で current 側に寄せる
  FG_DENOISE-lite)、MV field 無し (fsr1 フォールバック等) は従来の global
  camera shift (shader uMode 3) に自動フォールバック。status に `pxmv=`
  カウンタ追加 (per-pixel で挿入されたフレーム数)。
  **ユーザー検証済み「特に問題はなく見えます」(2026-10-23)** — entity ghost
  を含め視覚的問題なし。
- **S-4b timing reset 実装済み (2026-10-23)**: `Fsr2Compute.dispatchEpoch`
  (dispatch 完了ごとに increment) を freshness marker に使用。present 時点で
  epoch が前回挿入時と変わらない = 新しい world フレームが無い
  (loading screen / cutscene / pause 等) → MV field も camera delta も stale
  のため duplicate 挿入にフォールバック。baseline は挿入成功時に更新
  (早期 return 時は stale なので次回 1 フレームだけ複製になり安全側)。
- **S-4b 検証**: 通常 play 中の pan/entity 挙動は S-4a から変化なし;
  遷移/pause 境界の残像チェックは未実施 (フォールバックは複製なので
  事故時も静的劣化のみの見込み)。
- **Phase 2 wrap-up (2026-10-23)**: `THIRD-PARTY-NOTICES.md` 新規
  (AMD FidelityFX GLSL / fsr-reloaded 出自 / byte-buddy Apache-2.0 / DLSS SDK
  非同梱注記)、`mod.info` 1.1.0 (説明を現実に合わせ更新)、README「フレーム
  生成テスト」節新設 ＋ fsr2/fsr3 の取り扱い更新、`42/agent/README.txt` に
  FG 操作を追記 (`present` hook 要件含む)。FG はテストトグルのまま
  (既定 0=off) で、プロダクション化は Phase 3 の論点とする。
- **S-5 VSync-off pacing 実装済み (2026-10-23)**: `FsrControl.fsr2FgPace(0|-1|N)`
  — VSync OFF 環境では内スワップ/本スワップが同 refresh 内に完結し挿入
  フレームが走査されないため、CPU 側ペーシングで露出窓を自前確保:
  挿入 flip 前に前実フレーム分の露出保証 (`spacing = 露出 × 2` 基準の
  deadline wait)、inner swap 後に露出窓分 sleep (park + 末尾 2ms spin で
  サブ ms 精度)。実験的機能: 走査アライメント無制御のため境界 tearing
  が残り得る。auto は `Display.getDisplayMode().getFrequency()` 由来。
  status に `pace=` 表示。
- **S-5 検証 (2026-10-24)**: FPS 低下指摘 → `fsr2FgPace(-2)` (半 refresh
  露出、ループ ≈ 表示レート、tearing し得る) を追加。テア無しの完全表示
  FG ではロジック = 表示レート/2 が構造上限 (1 ループに 2 present × 1
  refresh 露出が必要、単一スレッド)。手動半露出相当値: 60Hz → 83、
  144Hz → 34。fps 表示 = ロジックレート、画面更新 (実+挿入) はその 2 倍。
- **options UI 整理 (2026-10-24)**: **auto quality (FPS 自動調整) 機能ごと削除**
  (`FSR_UPSC_AutoQuality.lua` / `FSR_UPSC_Profiler.lua` 削除、options の
  EnableAutoQuality/TargetFps 削除、MOD画面に残る翻訳キー削除)。
  **FG モードを ModOptions に追加** (`FgTest` コンボ 0-3、即時適用 +
  `FsrSettings.fgTest` 永続化、premain で ini 反映)。`fsr2FgPace`/
  `fsr2FgMvSign` は console 専用。`FsrControl.fsr2FgTest` も設定に保存。
- **agent-only install (2026-10-24)**: `-Dfsrupsc.downscale` の既定を ON に変更
  (FBO sizing path 確立済みなら安全; 無効化は env/マーカーファイル/-D の
  いずれかで明示)。**hook マスク既定 = `all`** (fbobegin/fboalloc 含む全 groups:
  `fbobegin` は resolve トリガで欠けると resolve が一度も走らない;
  `fboalloc` は世界 FBO 生成時縮小)。
  `install-agent.ps1` は既定で `-javaagent` + native-access の 2 行のみ書く
  (hooks/downscale プロパティ無しで完全動作); `-Hooks` / `-NoDownscale` /
  `-ForceNgx` は任意 (typo 修正済み)。FG pace は ModOptions に
  FgPace コンボ (off/safe/fast) 追加済み。
- **hooks 既定漏れ修正 (2026-10-24)**: agent-only 化の際「既定で全部入り」
  と判断したが、実際の既定文字列
  (`loop,internal,fbo,draw,viewport,present`) から `fbobegin` が欠けており、
  resolve (`TextureFBO#endDrawing` → resolveAtWorldEnd) が起動後一度も走ら
  ない上、f:39 の menu starvation 誤発動と重なって world が縮小のまま表示
  されるだけの状態になっていた (02:16 時点の動作 session は
  `hooks=fbo,fbobegin,viewport,loop,present` で武装、
  `worldEnd ok=42930 / composite swap / output wrapper ok` を確認済み —
  つまり動作構成には `fbobegin` が必要)。既定を `all` に修正。
- **agent-only 構成検証完了 (2026-10-24 03:45 jar)**: hook プロパティ無しの
  `-javaagent` のみで全機能が動作。`hooks=all downscale=true` で武装、
  **`world fbo requested 1494x840 (scale 0.7782)`** = fboalloc (FBO sizing
  path) が初めて本番復帰 → `fbo end` カウントで `ok=runs / mismatch=0 /
  noGen=0 / notReady=0` (旧 viewport funnel 方式の mismatch≈54% と別物の
  健康度)、resolve は毎フレーム成功。**`fsr3 compute unavailable` の毎フレ
  ームフォールバックが消滅** — FBO sizing path 下で fsr3 compute が正しく
  dispatch されている。残る minor 警告:
  `fsr2: unexpected color fmt 0x1908 (GL_RGBA), auto-reactive disabled`
  (auto-reactive だけが不活性のまま)。menu starvation gate・`fsrReenable()`
  は防御として維持。Phase 2 完了扱い。
- **1600x900 preset 追加 + FBO 生成の exact size 化 (2026-10-24)**:
  explicit scale に **83 (exact 1600x900 @ 1080p)** を追加
  (`computeScale` で比率 1600/1920、`scaledRect` で高さ 900 を 16px
  スナップ (896) へ丸めない pin)。世界 FBO 生成は旧 single permille
  (幅 833.6‰ / 高さ 833.2‰ が一枚の整数 permille で表現不可能 →
  1600x900 を作れない) から `worldFboTargetSize` → `scaledRect` 直渡しに
  変更。副次効果で ultra (0.7782) の既存ドリフト — バッファ 1494x840 vs
  resolve rect 1488x832 の 6x8px のずれ — も解消される。
- **worldFboRef 再生成追従 (2026-10-24)**: PZ が世界バッファを再生成すると
  `params.worldFboRef` が旧オブジェクトのまま (幾何不変なら再構築されない)
  で、resolve が本物の世界バッファを静かに skip し続け mismatch が漸増
  (live scale apply 後の 2 回目の viewport resize で発現、ok/runs ≈ 30% に
  落ちていた)。`endDrawing` の対象が `Core.getOffscreenBuffer(0)` と同一
  identity なら params を強制再構築して追従する防御を追加。
- **FG swapBuffers hook 死因確定 + 修正 (2026-10-24)**: `FsrAgent.premain`
  が install 前に `FgPresent.setPace(FsrConfig.FG_PACE)` を呼び、pace=-1/-2
  の復元が `org.lwjglx.opengl.Display.getDisplayMode()` に触れる →
  **Display クラスが install 前にロード済み**となり、byte-buddy (既定 =
  再変換なし) は当該クラスを transform しない → swapBuffers advice が
  一度も発火せず `fg present ... real=0` 固定・magenta 点滅なし。
  pace の premain 復元 (persist) 導入以降の全 boot が該当。
  premain の順序を **install → setTest/setPace** に入れ替え、
  install の `targets=` 一覧を FsrLog (agent log) にも出すようにした
  (console.txt は premain 時点の System.out を取りこぼすため)。
  併せて mode 1 の挿入フレームを duplicate (uMode 1) から
  **solid magenta (uMode 0) へ復帰** — S-1 当時の drawReplay 専用実装では
  mode 1 と mode 2 の区別が付けられなかった。
- **fg insert の stale GL drain-first 完全化 (2026-10-24)**: 観察 boot で
  mode 3 挿入が多数フレーム成功した後、1 枚/フレーム間隔の 0x500
  (zink 由来のゲーム自前エラー) が 3 連続で自前ストライクと誤計上され
  3 ストライク → session disabled → 以後 mode toggle が全部無反応
  (magenta 点滅なしの実態)。13:31:48 の mode 1 選択は disabled 中の
  無音 skip だった (histogram / real は生き続けるため、分かり難く
  「気づけない」事故の型となる)。修正: `insertPresent` 冒頭でエラー
  キューを drain (staleDrains 計数、ログは最初 8 回 + 以後 900 回ごと)、
  finally の glGetError は drain 後なので自前エラーのみ計上 — 設計
  通りの意味論へ。`setTest` ログに DISABLED 表示追加、
  `fsrReenable()` は `FgPresent.reEnable()` も呼ぶ (console から revives)。
- **FG mode 3 移動時の全画面振動 調査開始 (2026-10-24)**: 補完 (mode 3)
  中の移動で画面全体が振動のようにブレる。pace fast (~3ms exposure) より
  safe/vsync (6-16.7ms) の方が振幅が大きい = 「挿入フレームと実フレームの
  位置ずれが alternating に現れ、露出時間に比例して知覚される」型
  (完全な補完なら露出長に無関係に滑らかになるはず)。コード再検証では
  warp 符号 / half 係数 / 層 convention (`prevUv = uv + mv` = dilated field
  の発行 convention) は整合。振動源の切り分けのため histogram 周期
  (600 フレーム) ごとの insert style 内訳を追加: `fg3 pxmv=X gcam=Y
  dup=Z` — per-pixel MV field (pxmv) が ~0 で gcam (global camera delta)
  が支配なら恒等差分側に問題があり、pxmv 支配でも振れるなら mv の
  magnitude/timing (露出と挿入の位相) の疑い。
- **mode 3 補完の全 dup 確定 + gate 内訳計測 (2026-10-24)**: 観察 boot で
  全 histogram window が `fg3 pxmv=0 gcam=0 dup=599-600` — 插入が
  duplicate ばかり (補完・global warp が一度も発火していない)。duplicate
  の振動は実フレームと同一内容の freeze→2Δ jump の繰り返しで、移動時に
  露出長比例の「障碍/振動」知覚になる (fast/safe/vsync の振幅差と一致)。
  gate 内訳 (still/zoom/reset/noprev/epoch) を histogram に追加して
  次の boot で特定: still 閾値 0.5px/frame は pace fast (=165fps) の
  walking 移動量 (≈0.5-0.6px/frame) と同桁のため still() 誤判定の
  可能性が最有力、次いで zoomChanged による hasPrev reset。
- **振動原因確定: still() 誤判定 → dup freeze-jump (2026-10-24)**:
  再観察 boot の内訳 `fg3 pxmv=489 gcam=0 dup=111 reasons still=111
  (zoom/reset/noprev/epoch 全部 0)` — per-pixel MV 補完は ~81% 正常
  発火しており、global-warp 病や mv magnitude 病は無かった。dup の
  100% が **walk 中の瞬間的な 0.5px 未満のカメラ delta = `still()` 誤
  判定**。duplicate 挿入は `c(N-1), c(N)[dup], c(N)` の freeze 2 露出
  → 2Δ jump = 移動中の「画面全体が振動する」知覚、露出長比例
  (fast < safe < vsync) は freeze 一致 fix: FG gate を `CameraMotion.
  stillExact()` (d < 0.01px — fsr2 側も mv を 0 化する区間、dup が正当な
  唯一の区間) に替え、fsr2 の still() reactive 語彙はそのまま。
  margin: sub-pixel 補完でも mv field が本物なので interp 側が正しい。
- **軸非対称ブレの実験トグル (2026-10-25)**: stillExact 化後も振動報告は
  継続で、観察条件が新たに得られた: ui (固定) を観測すると **上下移動では
  移動と反対方向に少量ブレ (ゴースト = 移動と反対側)**、**左右移動では
  移動方向に大きくブレ (ゴースト = 移動と同側)** — 軸ごとに符号挙動が
  違う = mv field 適用の x/y 軸どちらかの符号/convention に単発の誤りが
  ある型 (AMD FSR2 の motionVectorScale が (-1, +1)/size の x 負 y 正と
  いう準拠や、PZ の texture v-flip 因子は axis で偶発的に差分を生む典型)。
  切り分けのため per-pixel path 専用の console 実験トグルを追加:
  `FsrControl.fsr2FgMvFlip(0..7)` — bit0 = mv sample を (x, 1-y) 反転、
  bit1 = mv.x 反転、bit2 = mv.y 反転 (0 = 現行)。配合て shader に
  uFlags uniform + mvField() を追加、drawInterp で uFlags を push
  (global path は 0 固定)。persist 無し — 確定したら code default 化。
  補足: console の最初の 1 発で "tried to call nil" になることがある
  (Lua env 再構築 timing で alias 未登録の env を console が参照する) —
  世界に入って log に `exposed fsrupsc.FsrControl env#...` の 2 回目が
  出るか、同 env で再入力すると直る。実測: mvFlip=1→2→3→4 が順次
  発火済み (00:42-00:43)、各設定での見え方の報告待ち。
- **mv magnitude 実測で 2x 誤差確定 (2026-10-25, probe)**: console 入力が
  プレイヤーを停止させるため「移動中に武装」は不可能 — サンプラーを
  常時稼働 (移動 insert の ~1/20 を RAM ring 8 件に取得、`fsr2MvProbe()`
  は直近 8 件の dump 専用) に変更。実測: **両軸とも mv field = カメラの
  フレームあたり delta のちょうど 2.0x** (横 camRel 3px→mv 5.99px、7→14、
  縦 -4→-8、-1→-2)、**符号は 2 軸とも camRel と一致 (flip=0 正解)**。
  「上下少量/左右 ~10x」の非対称は mv 誤差ではなく歩行→カメラ delta の
  軸差。修正: 插入 warp の half shift を 0.5x→**0.25x field**
  (uHalfScale = 0.25 * mvSign) で頂点が mid 位置に揃う。
- **FG 補完確定 (2026-10-25)**: 検証の結果 (1) mv scale 0.25 = 正解値のまま
  code default (probe 実測と理論一致)、(2) HUD/画面静止コンテンツ検出
  (無シフト pair 一致 & ±sh 不一致 → warp しない) は常設化、(3) mvFlip
  bits は符号実測で default 0 = 正解なので実験トグルのまま残置
  (bit3/4 = 軸別半減も含む)、(4) FS マルチ挿入 `fsr2FgMult(2|3)` (3x/4x
  ベーシング) は蛇足と判定 — console 実験のまま既定 1 (=2x) 継続。
  同様に `fsr2FgMvScale` も console 維持 (既定 = 確定値 0.25)。
  確定運用構成: **fg3 + pace -2 (fast) + mvScale 0.25、静止/zoom は
  stillExact dup、HUD は無シフト replay**。
- **FG ちらつき結論・推奨構成更新 (2026-10-26)**: 補完 (fg3) 挿入フレームは
  2 実フレームの融合で、細部ではフレーム全体の微小ちらつきが原理的に残る
  （crisp 実フレームとの交互提示）。静止 HUD の文字二重ゴーストのみ
  `FsrControl.fsr2FgHudSnap(0/1)` (既定 ON, uFlags bit5) で解消 — 内容変化
  のあった静止 HUD ピクセル (インベントリ行/ダッシュボード数字等) を挿入
  フレームで current copy 単独再生に snap (dz > 0.05 閾値, noise はブレンド
  維持)。world 層のちらつきは不可避と判定し、**推奨は複製挿入
  `fsr2FgTest(2)` (安定)**。補完 (3) は残してお好み選択。
- **menu starvation 誤発動修正 (2026-10-24)**: メインメニューの背景合成は
  world pass 無しで毎フレーム走るため、`composite starved` カウンタ (120 で
  session 全域 disable) が「world が一度も無い段階」で満たされ、起動 39
  フレーム目で mod 全体が諸められて stock のまま遊ぶ事故が発生。
  starvation 計数は resolve pipeline が一度でも走ったあと (world frame の
  存在が確認できてから) に限定するよう修正。併せて、fail-safe 発動後の
  console 再武装 `FsrControl.fsrReenable()` を追加 (disable 状態 + stale
  output の clear; 同一故障が再発したらまた自動で stock に落ちる)。

- **outline ズレ修正 確定 (2026-10-26)**: スケーリング時、近接ゾンビ/コンテナ
  ターゲットのアウトラインが実像より左上方向に **0.775x (= 1488/1920) 縮んで**
  表示される問題。原因: `ModelOutlines.endFrame` 内の 4 カ所 glViewport
  (:161/:179 thicken → fboB/fboC, :215 model pass → fboA, :234 blit → world)
  はすべて同一ローカル rect を使い、世界パスと同一 camera basis で fboA に
  mask が描かれる (--fboA の内容は既に **region 座標**)。funnel が world bound
  の :234 blit を region サイズに絞っていたため、mask に **2 回目の squeeze**
  が乗っていた (縮率が SCALE 1 回分と一致することが決定打)。修正:
  `RenderScale.requestedViewport` の `OUTLINE_VP_RAW` (code default ON,
  `fsr2OutlineFix(0)` で戻し用) を endFrame seam 全行で funnel 書換を SKIP
  (blit は fboA→world 1:1)。seam 判定ロジックは公開版では
  `RenderScale.endFrameRawVp()` (内部 stack 走査) に本体移植された。
  判定過程の trace 足場 (vtI/vtF vp seam, fboA bind watcher 等) は公開版では
  クラスごと除去済み。
- **composite flood 修正**: `ResolveOutput.compositeTexture` は
  `inWindowThread()` gate で composite スレッドのみ substituted texture を
  返すように限定。render thread が outline の fboA/B/C `getTexture` を
  window 開放中に通ると swap が漏れて outline 色でフレーム全体が flood
  していた。
- **ByteBuddy Advice inline 参照規則 (備忘)**: advice 本体は対象クラスに複製
  されるため、本体から参照する対象クラス外の private/package メンバーは
  IllegalAccessError となり `suppress=Throwable.class` が黙って飲む。advice
  からは fsrupsc 側の public helper を経由するのが安全。
