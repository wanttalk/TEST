# 這個 repo 是任務板

`wanttalk/test` 不放程式碼，它是使用者所有開發工作的**任務板**：每個 GitHub Issue 是一個任務，記錄它在哪個 repo、哪個分支、哪些 Claude Code 對話處理過，以及給下一個對話的交接。

- 操作任務板一律照 `.claude/skills/taskboard/SKILL.md` 的流程（使用者也可以輸入 `/taskboard`）。
- 只要使用者提到任務編號（#N）、「任務板」、「接手」、「交接」，就先讀那份 skill。
- 每次寫入任務都附上目前對話連結：`echo "https://claude.ai/code/${CLAUDE_CODE_REMOTE_SESSION_ID/cse_/session_}"`
- `BOARD.md` 由 GitHub Actions 自動產生，不要手動改。
- 回覆使用者用繁體中文。
