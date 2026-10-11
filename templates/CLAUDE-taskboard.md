<!-- 把這段貼到其他專案的 CLAUDE.md 最後面 -->

## 任務板

我的開發任務記錄在 GitHub Issues：`wanttalk/test`（任務板）。

- 當我說「接手 #N」「做 #N」「交接」「開新任務」「看任務板」時，#N 指的是 `wanttalk/test` 的 Issue，不是這個 repo 的。
- 若 `wanttalk/test` 不在權限內，先用 `add_repo`（owner `wanttalk`, repo `test`, access `push`）加入。
- 流程與格式照 `wanttalk/test` 的 `.claude/skills/taskboard/SKILL.md`（用 `get_file_contents` 讀取）。重點：
  - 開工：讀完 Issue 與所有留言 → 標籤改 `status:in-progress` → 在「對話紀錄」加上本對話連結，填好 `- repo:` 和 `- branch:`。
  - 交接（我說「交接」或對話快到上限）：更新 Issue 的「交接」段落（做到哪、下一步、分支），留言附對話連結。
  - 完成：留言寫驗證結果 → 改 `status:in-review`，等我確認才關閉。
- 本對話連結：`echo "https://claude.ai/code/${CLAUDE_CODE_REMOTE_SESSION_ID/cse_/session_}"`
