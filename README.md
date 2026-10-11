# 任務板

把所有開發工作集中在一個地方，不用再記「這個功能是在哪個對話做的」。

做法參考 [dashi-taskboard](https://github.com/chuspeeism/dashi-taskboard) 的流程（認領 → 綁定對話 → 交接 → 待確認 → 完成），但不需要裝 App 或跑伺服器：任務存在這個 repo 的 **GitHub Issues**，Claude Code（網頁、手機）可以直接讀寫。

- **一個 Issue = 一個任務**，記錄 repo、分支、處理過的對話連結，以及給下一個對話的交接。
- **[BOARD.md](BOARD.md)**：自動產生的總覽，每個任務的最新對話連結都在這裡。
- 對話到上限也沒關係：舊對話寫交接，新對話說「接手 #N」就能繼續。

## 怎麼用（直接在對話裡說）

| 你說 | Claude 會 |
| --- | --- |
| 開新任務：幫 shop 做登入頁 | 開一個 Issue（`status:todo`） |
| 先記著：之後想加深色模式 | 開一個 Issue（`status:backlog`，不會自動開工） |
| 接手 #3 | 讀 Issue 和交接，標成進行中，記下這個對話的連結，繼續做 |
| 交接 | 把進度、下一步、分支寫進 Issue，告訴你下次怎麼接 |
| 看任務板 | 列出所有進行中／待辦的任務和它們的對話連結 |
| #3 完成 | 關閉 Issue |

做完時 Claude 會先改成「待你確認」（`status:in-review`），你確認後才會關閉。

你也可以在手機的 GitHub App 直接新增 Issue（選「新任務」範本），之後在任何對話說「做 #N」。

## 狀態

`status:backlog` 想法 → `status:todo` 待辦 → `status:in-progress` 進行中 → `status:in-review` 待你確認 → 關閉（完成）
卡住時用 `status:blocked`。

## 在其他專案的對話裡使用

在那個專案 repo 的 `CLAUDE.md` 貼上 [templates/CLAUDE-taskboard.md](templates/CLAUDE-taskboard.md) 的內容。之後在那個專案的對話中說「接手 #N」，Claude 就知道 #N 是這裡的任務。

## 檔案

| 檔案 | 用途 |
| --- | --- |
| `CLAUDE.md` | 在這個 repo 開的對話會自動讀取的規則 |
| `.claude/skills/taskboard/SKILL.md` | 完整流程（可輸入 `/taskboard`） |
| `templates/CLAUDE-taskboard.md` | 貼到其他專案的 `CLAUDE.md` |
| `scripts/build-board.mjs` | 從 Issues 產生 `BOARD.md` |
| `.github/workflows/board.yml` | Issue 有變動時自動更新 `BOARD.md` |
| `.github/ISSUE_TEMPLATE/task.yml` | 手機新增任務用的表單 |
