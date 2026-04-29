# KNOWLEDGE.md — ハマりポイント・学び集

## ビルド
- **必ず `./gradlew.bat reobfJar` を使う**。`jar` タスクは MCP フィールド名のままで実行時に `NoSuchFieldError: IRON` クラッシュ。

## 実行順序
- WingmanTickHandler (Phase.START) → AutonomousFlightHandler (Phase.START) → WingmanTickHandler (Phase.END)
- AFH の `setThrottle()` は WingmanTickHandler の `maintainEngine()` を上書きする（後から実行されるため）

## 方位・座標系
- Minecraft yaw: `atan2(-dx, dz)` で計算。東=0°, 南=90°, 西=±180°, 北=-90°（要確認）
- 実際の runway: A=(16762,2,-5227), B=(16560,2,-5227)。A→B = 西方向、dirX=-1, dirZ=0
- `runwayYaw = atan2(-dirX, dirZ) = atan2(1, 0) = 90°`（西向き）

## ピッチ符号
- WingmanTickHandler で `targetPitch = -atan2(movDy, hDist)`
- movDy<0 (目標が下) → targetPitch>0 → nose-down (正が下向き = Minecraft標準と同じ)
- MAX_PITCH_DOWN=-20°（nose-up 制限）, MAX_PITCH_UP=25°（nose-down 制限）

## スロットルと降下
- **throttle=1.0 では固定翼機は高度を下げられない**（推力過多で揚力が下降を相殺）
- DESCEND/DOWNWIND/BASE は throttle=0.6 以下にしないと circuitY まで降りられない
- CIRCUIT_FINAL でグライドスロープより高くいる場合は throttle=0.2 程度まで絞る必要あり

## サーキットサイズとチャンク
- **CIRCUIT_FINAL_DIST=500 は広すぎる** → 機体がチャンク外（`ChunkLoad: Released ticket`）
- ログで `departed aircraft` が出た場合はサーキット半径を疑う
- 滑走路長=202ブロックに対して CIRCUIT_OFFSET=100, CIRCUIT_FINAL_DIST=300 程度が適切

## TAKEOFF_ROLL yaw drift
- `autoTarget = 機体現在位置 + dir*1000` でも、WingmanTickHandler の MAX_YAW_RATE=1.5°/tick ではthrottle上昇と同時に機体が動き出すためズレが残る
- **直接 setRotYaw() を AFH から呼ぶことで完全に排除できる**（reflectionでキャッシュ済みのメソッドを使う）
- ALIGN 後の速度チェック `taxiSpd < 0.05` では McHeli 最低エンジン速度の慣性が残る場合がある

## LANDING nose-up / 後輪めり込み
- CIRCUIT_FINAL で `posY <= by+2` 時に `glideAlt = posY-3` の補正を入れると nose-down になるはずだが、着地前にMcHeli物理がピッチを固定する可能性
- LANDING state で `autoTargetY = by-3` の場合 dy=-5, hDist=200 → targetPitch=+1.4° (ごく小さい)
- **LANDING では近い目標 (30-50ブロック先) + 大きな下方オフセット** を使うと targetPitch が大きくなり有効
- `autoTargetY = by-20, hDist=40` → targetPitch ≈ +29° (nose-down, MAX_PITCH_UPに張り付き) で最も強力

## VTOL_TAKEOFF 水平ドリフト（修正済み）
- **症状**: VTOL離陸中に機体がヘリパッドから水平移動し、ミッションターゲット方向へ飛んでいってしまう
- **原因**: `entry.autoTargetX = wingman.posX + (toTargetDx/toTargetLen)*50` → `wingman.posX` が毎tick変わるため、ターゲットも移動し続け、WingmanTickHandler が機体を前進させる
- **修正**: 基点を `wingman.posX` → `padX`（ヘリパッドの固定座標）に変更。`padX + (direction)*50` は毎tick同じ値になり水平移動しない
- **教訓**: VTOLホバー中にXZターゲットを「現在位置+オフセット」にすると前進してしまう。固定基点（パッド座標）を使うこと

## VTOL_LAND 接近フェーズで後退（修正済み）
- **症状**: VTOL_LAND 中に hDist が増加し、機体がヘリパッドから遠ざかりながら高度を失って消滅
- **原因**: VTOL モードが接近フェーズ中もONのまま。F-35B のVTOLモードはホバリング専用で前進推力が出ない。スロットル0.7では高度維持もできず、後退+沈降
- **修正**: `WingmanEntry.vtolHoverMode` フラグを追加し、AFH が各フェーズで制御:
  - 接近フェーズ (hDist>20): `vtolHoverMode=false` (VTOL OFF) + throttle=0.8 → 固定翼で通常飛行
  - 降下フェーズ (hDist≤20): `vtolHoverMode=true` (VTOL ON) + throttle=0.35 → ホバリング垂直降下
- `WingmanTickHandler` は `entry.vtolHoverMode` のみを参照してVTOLモードをON/OFF。autoState での判定は廃止
- **教訓**: VTOL モードは離着陸の「ホバリングが必要な瞬間」だけON。接近・巡航は固定翼モードで行う

## autoGear
- WingmanTickHandler が `autoGear(wingman, entry.autoState)` で自動的にギアを制御
- LANDING/TAXI_IN/PARKED でギアが下りる（詳細は autoGear の実装確認）

## VTOL_LAND ドリフトバグ（修正済み）
- **症状**: VTOL着陸中にヘリパッドから毎tick10ブロックずつ離れ、最終的に地下に突入して消滅
- **原因**: `tickOrderVtolLand()` の先頭で `padX = entry.autoTargetX` と読み取り、降下フェーズで `entry.autoTargetX = padX + offset` と書き込む。次tickで再び汚染値を読み取る → 累積ドリフト
- **修正**: `padX/padZ` を `MarkerRegistry` から毎tick直接参照する（`findMarkerInfoById(ws, entry.assignedParkingId)` → `pad.pos.getX() + 0.5`）
- **教訓**: `entry.autoTargetX/Z` は「出力」専用。同一メソッド内で「入力（アンカー）」として使うのは危険

## McHeli CE アドオンフォルダ
- アドオン配置先: `C:\.minecraft\mcheli_addons\`
- 起動時ログに `Load complete addons and add resources. count:N` と出る（N=0なら未読込）
- 1.7.10向けアドオンJARはそのままでは動かない。OBJ/PNGは流用可、JSONは1.12.2 CE形式に書き直しが必要
