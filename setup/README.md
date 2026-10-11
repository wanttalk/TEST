# 舊筆電設定：用手機控制 Claude Code / Codex（Happy）

[Happy](https://github.com/slopus/happy) 是開源（MIT）的手機 App，可以從任何地方控制筆電上的 Claude Code 和 Codex，連線經過端對端加密，不用 Tailscale、也不用開 remote-control。

## 筆電（Windows 10）

1. 開始選單搜尋「PowerShell」→ 開啟。
2. 貼上這一行，按 Enter：
   ```powershell
   irm https://raw.githubusercontent.com/wanttalk/TEST/ccr-4a97f468-owlvlj/setup/laptop-setup.ps1 | iex
   ```
   會安裝 Node.js、Git、Claude Code、Codex、Happy，設定插電不睡眠、蓋上螢幕不睡眠，並讓 Happy 開機自動在背景執行。
3. 照畫面最後的提示登入 Claude、Codex，下載專案，執行 `happy.cmd` 顯示 QR Code。

## 手機（Android）

1. Google Play 搜尋「Happy Codex Claude Code」安裝（套件名稱 `com.ex3ndr.happy`）。
2. 打開 App，掃筆電上的 QR Code。
3. 之後在 App 裡就能看到筆電，可以直接開新對話、選資料夾、切換 Claude / Codex。

## 第一次測試（只讀，不會改任何東西）

在手機 Happy App 開新對話（資料夾選 `Projects\website`），輸入：

> 讀 PROGRESS.md，用三句話告訴我目前進度和下一步

## 注意

- 筆電要插電、連網，不能關機。
- 不要開「全部自動允許」（yolo / bypass permissions）模式，讓 AI 改檔案或執行指令前先問你。
- Happy 是第三方開源專案，訊息經過它的伺服器轉送（加密）。
