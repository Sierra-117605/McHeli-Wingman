# McHeli Wingman

[日本語](#日本語) | [English](#english)

---

## 日本語

### 概要

**McHeli Wingman** は、Minecraft Forge 1.12.2 向け航空機 Mod「[McHeli](https://github.com/Murachiki/McHeli-Mod)」を対象としたアドオン Mod です。

### 主な機能

- **UAV 飛行距離制限の撤廃**
  UAV ステーションの通信圏外でも UAV を継続して運用できます。

- **CCA 僚機機能**
  他の航空機をプレイヤー機に随伴させ、編隊飛行や敵への協調攻撃を行わせることができます。

- **自律飛行ミッション**（開発中）
  離陸・巡航・攻撃・着陸といった一連の行動を自動でこなすミッション機能を開発中です。

### 動作環境

| 項目 | バージョン |
|------|-----------|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847 以上 |
| McHeli | 1.1.4 |

### インストール

1. [Releases](../../releases) から最新の `.jar` ファイルをダウンロード
2. `.minecraft/mods/` フォルダに配置

---

### コマンド一覧

すべてのコマンドは `/wingman <サブコマンド>` の形式です。

#### 僚機（CCA）

| コマンド | 説明 |
|----------|------|
| `/wingman follow [uuid]` | 最寄りの航空機（または指定 UUID）を僚機に設定 |
| `/wingman stop` | 自機に随伴中の僚機をすべて解除 |
| `/wingman status` | 登録済み僚機の一覧と状態を表示 |

#### 編隊

| コマンド | 説明 |
|----------|------|
| `/wingman dist <横> <高度> <後退>` | 編隊の各距離をリアルタイムで変更（単位: ブロック） |
| `/wingman maxwings <数>` | 1機が持てる最大僚機数を設定（1〜64） |

#### 攻撃

| コマンド | 説明 |
|----------|------|
| `/wingman engage [uuid]` | 僚機に指定 UUID のエンティティを攻撃させる（省略時はプレイヤーのロックオン対象） |
| `/wingman auto` | 僚機を自動攻撃モードに切り替え（近くの敵 Mob を自動攻撃） |
| `/wingman hold` | 攻撃を中止し、編隊に戻る |
| `/wingman weapon [種別\|clear]` | 使用する武器種を指定（省略で現在の設定を表示） |
| `/wingman minalt [Y]` | 攻撃時の最低高度を設定（0 = 制限なし） |
| `/wingman maxalt [Y]` | 攻撃時の最高高度を設定（0 = 制限なし） |
| `/wingman alt clear` | 高度制限をリセット |

#### UAV

| コマンド | 説明 |
|----------|------|
| `/wingman spawnuav [種別]` | UAV をプレイヤーの正面にスポーン（種別省略で一覧表示） |

#### マーカー（自律飛行用）

マーカーブロックを設置したうえで以下のコマンドで設定します。

| コマンド | 説明 |
|----------|------|
| `/wingman marker list` | ワールド内のマーカー一覧を表示 |
| `/wingman marker type <種別>` | 見ているマーカーブロックの種別を設定 |
| `/wingman marker id <ID>` | 見ているマーカーブロックに ID を設定 |

**マーカー種別：**

| 種別 | 用途 |
|------|------|
| `runway_a` | 滑走路 A 端（離陸起点・着陸終点） |
| `runway_b` | 滑走路 B 端（着陸起点・タッチダウンゾーン） |
| `parking` | 駐機場 |
| `waypoint` | 空中経由地点 |

#### ルート・ミッション（自律飛行用・開発中）

| コマンド | 説明 |
|----------|------|
| `/wingman route create <名前> <ノード...>` | ルートを作成 |
| `/wingman route list` | ルート一覧を表示 |
| `/wingman route show <名前>` | ルートの内容を表示 |
| `/wingman route delete <名前>` | ルートを削除 |
| `/wingman mission assign <uuid> <ルート名>` | 航空機にミッションを割り当て |
| `/wingman mission abort [uuid]` | ミッションを中断（省略で全機） |
| `/wingman mission status` | 実行中のミッション一覧を表示 |
| `/wingman gui` | ミッションプランナー GUI を開く |

**ルートノード書式：**

| ノード | 書式 | 説明 |
|--------|------|------|
| 飛行 | `flyto:X,Y,Z` | 指定座標へ飛行 |
| 離陸 | `takeoff:滑走路ID` | 指定滑走路から離陸 |
| 着陸 | `land:滑走路ID` | 指定滑走路に着陸 |
| 攻撃 | `attack:半径` | 指定半径内の敵を攻撃 |
| 旋回待機 | `loiter:tick数` | 指定 tick 数その場で待機 |
| 駐機 | `park:駐機場ID` | 指定駐機場に停止 |

**ルート作成例：**
```
/wingman route create patrol takeoff:main flyto:100,80,200 attack:150 loiter:600 land:main park:main
```

### 注意事項

本 Mod は McHeli の非公式アドオンです。McHeli 本体の開発者様（Murachiki 氏）とは別の制作者によるものです。

---

## English

### Overview

**McHeli Wingman** is an addon mod for [McHeli](https://github.com/Murachiki/McHeli-Mod), an aircraft mod for Minecraft Forge 1.12.2.

### Features

- **Remove UAV Range Limit**
  Allows UAVs to operate beyond the communication range of the UAV station.

- **CCA Wingman**
  Assign other aircraft as wingmen to your player aircraft. They will fly in formation and engage enemies cooperatively.

- **Autonomous Flight Mission** *(Work in Progress)*
  A mission system that allows aircraft to automatically perform a series of actions including takeoff, cruise, attack, and landing.

### Requirements

| Item | Version |
|------|---------|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847+ |
| McHeli | 1.1.4 |

### Installation

1. Download the latest `.jar` from [Releases](../../releases)
2. Place it in your `.minecraft/mods/` folder

---

### Commands

All commands follow the format `/wingman <subcommand>`.

#### Wingman (CCA)

| Command | Description |
|---------|-------------|
| `/wingman follow [uuid]` | Assign the nearest aircraft (or specified UUID) as a wingman |
| `/wingman stop` | Dismiss all wingmen from your aircraft |
| `/wingman status` | Display a list of all registered wingmen and their state |

#### Formation

| Command | Description |
|---------|-------------|
| `/wingman dist <side> <alt> <rear>` | Adjust formation spacing at runtime (in blocks) |
| `/wingman maxwings <n>` | Set the maximum number of wingmen per aircraft (1–64) |

#### Combat

| Command | Description |
|---------|-------------|
| `/wingman engage [uuid]` | Order wingmen to attack the specified entity UUID (or player's lock-on target if omitted) |
| `/wingman auto` | Switch wingmen to auto-attack mode (attack nearest hostile mob) |
| `/wingman hold` | Cease attack and return to formation |
| `/wingman weapon [type\|clear]` | Set the weapon type to use (show current setting if omitted) |
| `/wingman minalt [Y]` | Set minimum altitude for attack runs (0 = no floor) |
| `/wingman maxalt [Y]` | Set maximum altitude for attack runs (0 = no ceiling) |
| `/wingman alt clear` | Reset both altitude limits |

#### UAV

| Command | Description |
|---------|-------------|
| `/wingman spawnuav [type]` | Spawn a UAV in front of the player (omit type to list available types) |

#### Markers (for Autonomous Flight)

Place a Wingman Marker block, then configure it with the following commands.

| Command | Description |
|---------|-------------|
| `/wingman marker list` | List all markers in the world |
| `/wingman marker type <type>` | Set the type of the marker block you are looking at |
| `/wingman marker id <id>` | Set the ID of the marker block you are looking at |

**Marker types:**

| Type | Use |
|------|-----|
| `runway_a` | Runway end A — takeoff start / landing end |
| `runway_b` | Runway end B — touchdown zone / landing start |
| `parking` | Parking spot |
| `waypoint` | Aerial waypoint |

#### Routes & Missions (Autonomous Flight — WIP)

| Command | Description |
|---------|-------------|
| `/wingman route create <name> <node...>` | Create a route |
| `/wingman route list` | List all saved routes |
| `/wingman route show <name>` | Display the contents of a route |
| `/wingman route delete <name>` | Delete a route |
| `/wingman mission assign <uuid> <route>` | Assign a mission to an aircraft |
| `/wingman mission abort [uuid]` | Abort a mission (omit to abort all) |
| `/wingman mission status` | Show the status of all active missions |
| `/wingman gui` | Open the Mission Planner GUI |

**Route node syntax:**

| Node | Format | Description |
|------|--------|-------------|
| Fly To | `flyto:X,Y,Z` | Fly to the specified coordinates |
| Takeoff | `takeoff:runwayId` | Take off from the specified runway |
| Land | `land:runwayId` | Land at the specified runway |
| Attack | `attack:radius` | Attack enemies within the specified radius |
| Loiter | `loiter:ticks` | Circle and wait for the specified number of ticks |
| Park | `park:parkingId` | Taxi to and stop at the specified parking spot |

**Route example:**
```
/wingman route create patrol takeoff:main flyto:100,80,200 attack:150 loiter:600 land:main park:main
```

### Disclaimer

This is an unofficial addon for McHeli. It is not affiliated with or endorsed by the original McHeli developer (Murachiki).
