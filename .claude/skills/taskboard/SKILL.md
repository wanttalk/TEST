---
name: taskboard
description: 管理 wanttalk/test 的任務板（GitHub Issues）。當使用者說「看任務板」「開新任務」「接手 #N」「交接」「做完了」「任務板」或提到 BOARD.md 時使用。
---

# 任務板（Taskboard）

任務板 = `wanttalk/test` 的 GitHub Issues。一個 Issue = 一個任務，用 GitHub MCP 工具（`mcp__github__*`）操作，不需要本機伺服器。
`BOARD.md` 由 GitHub Actions 自動產生，**不要手動編輯或提交它**。

## 狀態（用標籤表示，一次只能有一個）

| 標籤 | 意義 |
| --- | --- |
| `status:backlog` | 想法，未核准。**不可自行開工**，除非使用者明確指定這一個 |
| `status:todo` | 可以開工 |
| `status:in-progress` | 某個對話正在做 |
| `status:in-review` | 做完、等使用者確認 |
| `status:blocked` | 卡住，需要外部條件或使用者回覆 |
| 關閉（completed） | 使用者確認完成 |
| 關閉（not_planned） | 取消 |

改狀態時用 `issue_write` 的 `update` 傳入**完整**標籤清單（保留非 status 標籤，換掉舊的 status 標籤）。

## 取得目前對話連結

```bash
echo "https://claude.ai/code/${CLAUDE_CODE_REMOTE_SESSION_ID/cse_/session_}"
```

每次寫入任務（認領、紀錄、交接）都要附上這個連結，這樣使用者才知道任務在哪個對話。

## Issue 內文格式

```markdown
## 目標
（要做什麼、怎樣算完成）

## 位置
- repo: owner/name
- branch: 分支名稱

## 對話紀錄
- 2026-10-11 https://claude.ai/code/session_xxx 開始

## 交接
（最新狀態：做到哪、下一步、注意事項。新對話只要讀這段就能接手）
```

`- repo:` 和 `- branch:` 兩行會被 BOARD.md 讀取，格式不要改。從手機表單建立的 Issue 格式不同，認領時改寫成上面的格式（保留原本的目標內容）。

## 流程

### 開新任務（使用者：「開新任務：…」）
1. 先用 `search_issues` 找有沒有重複的任務，有就更新它，不要重複開。
2. `issue_write create`，標題簡短，內文用上面的格式，標籤 `status:todo`（使用者說「先記著」就用 `status:backlog`）。
3. 回覆 Issue 編號與連結。

### 接手 / 開工（使用者：「接手 #N」「做 #N」）
1. `issue_read` 讀完整內文和**所有留言**，最新留言就是目前的需求。留言說要等或先不要做，就停下來回報。
2. `backlog` 沒被明確指定就不做。已經是 `in-progress` 的任務，先看「對話紀錄」最後一筆：如果是別的對話而且使用者沒說要接手，先確認再繼續。
3. 改成 `status:in-progress`，在「對話紀錄」加一行本對話連結，並補上 repo、branch。
4. 如果「交接」有內容，照它繼續（切到記錄的分支，從下一步開始）。

### 做事中
- 有重要進展（完成一個階段、改了方向）就留一則簡短留言，附對話連結。
- 只做 Issue 範圍內的事。新冒出的需求另開任務，不要擴大範圍。

### 交接（使用者：「交接」，或對話快到上限、要結束時）
1. 更新 Issue 內文的「交接」段落：做到哪、下一步、分支、未解決的問題。
2. 留一則留言：`交接自 <對話連結>：一句話摘要`。
3. 狀態維持 `in-progress`（或改 `blocked` 並寫原因）。
4. 告訴使用者：開新對話後說「接手 #N」即可。

### 完成
1. 驗證主要功能真的能用，留言寫：改了什麼、怎麼驗證、結果、剩下的風險。
2. 改成 `status:in-review`，**不要自己關閉**。
3. 使用者說「#N 完成」「OK 了」才關閉（`state: closed`, `state_reason: completed`）。取消用 `not_planned`。

### 看任務板（使用者：「看任務板」「現在有哪些任務」）
用 `list_issues`（`state: OPEN`）依狀態分組列出：編號、標題、repo、最新對話連結。不用讀 BOARD.md（它可能晚幾秒才更新）。

## 其他 repo 的對話
在別的專案的對話中，如果 `wanttalk/test` 不在權限範圍內，先用 `add_repo`（owner `wanttalk`, repo `test`, access `push`）加入，才能寫入 Issues，然後照這個流程用 MCP 工具操作 Issues，不需要 clone。
