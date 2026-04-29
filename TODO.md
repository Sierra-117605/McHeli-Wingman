# TODO.md

## 未解決バグ

- [ ] **TAKEOFF_ROLL yaw drift**: 離陸時に斜めに滑走する
  - 対策案: AFH から `McheliReflect.setRotYaw()` を直接呼び出して機首方向を強制固定
- [ ] **LANDING nose-up / 後輪めり込み**: 着陸後に機首が上がったまま・後輪がめり込む
  - 対策案: LANDING state で近距離（30〜50ブロック先）+ 大きな下方オフセット（-20ブロック）をターゲットにする
- [ ] **着陸位置精度**: チャンク外逸脱時にサーキット半径が大きすぎる
  - 対策案: CIRCUIT_FINAL_DIST を 300 以下（CIRCUIT_OFFSET=100 程度）に制限
- [ ] **要動作確認**: VTOL_TAKEOFF / VTOL_LAND の修正後テストが未実施

## 完了済み

- [x] VTOL_LAND ドリフトバグ → AFH で padX/Z を MarkerRegistry から毎tick取得するよう修正
- [x] 武器別攻撃機動 → `computeOrbitTarget` / `computeApproachTarget` / `computeOverflyTarget` 実装
- [x] AC-130 旋回攻撃 → `MissionOrder.orbitAttack` + GUI ボタン + `PacketBaseAction` シリアライズ
- [x] VTOL スピン（離着陸）→ `hDist<1.0` ガード + 降下フェーズの XZ ターゲット安定化
- [x] `isHelicopter` / `isVtol` / `canUseHelipad` 修正（F-35B を正しく VTOL 判定）
- [x] 攻撃命中精度向上 → ヨーレート増加・機首整合チェック（gun/rocket 20°以内）
- [x] VTOL_TAKEOFF 水平ドリフト → XZ ターゲットをヘリパッド固定座標ベースに変更・`vtolHoverMode` 導入
- [x] VTOL_LAND 後退バグ → 接近フェーズ (hDist>20) で VTOL OFF+固定翼飛行、降下フェーズで VTOL ON
- [x] 搭乗中の自律飛行ヨー追従 → `ClientAutopilotHandler` + `PacketAutopilotVisual`（Phase.END）
- [x] 搭乗中の自律飛行スロットル非反映 → `PacketAutopilotVisual` に `targetThrottle` を追加・クライアント側で上書き
- [x] マーカーブロックのタイプ別テクスチャ（7種）
- [x] マーカーブロックのアイテム名（JP/EN）
- [x] BASE マーカー右クリックで基地設定 GUI を開く
- [x] タキシールートをミッションフローに完全統合
- [x] Numpad 0 キーバインドで僚機パネル GUI を開く
- [x] TAXI_OUT が直進しない → ターゲットを A 端に変更
- [x] FLY_TO が到達しない → XZ 水平距離判定に変更
- [x] FLY_TO で急降下 → `targetY = max(node.y, CRUISE_ALT)`
- [x] STOL 離陸 → `MIN_ROLL_DIST=130` で滑走距離確保
- [x] 回路方向反転 → DESCEND=A側, DOWNWIND=B側 に修正
- [x] グライドスロープ高度バグ → `tProj` でなく `projFromB` を使用
- [x] STOL 着陸 (14.4°進入) → `circuitY=by+40` に変更
- [x] PARK が即到達 → `PARK_DIST=8.0` を追加
- [x] 着陸後 PARK が正しい位置で行われない → 修正済み
- [x] スロットル全フェーズ設定 → ENROUTE/CLIMB/DESCEND/DOWNWIND/BASE に `setThrottle` 追加
