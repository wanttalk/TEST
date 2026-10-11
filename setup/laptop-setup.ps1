# 舊筆電一鍵安裝：Node.js、Git、Claude Code、Codex、Happy，並設定不睡眠、開機自動啟動 Happy
# 用法（在 PowerShell 貼上整行）：
#   irm https://raw.githubusercontent.com/wanttalk/TEST/ccr-4a97f468-owlvlj/setup/laptop-setup.ps1 | iex
# 可以重複執行，已裝好的會略過。

$ErrorActionPreference = 'Stop'

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Refresh-Path {
  $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
              [Environment]::GetEnvironmentVariable('Path', 'User')
}
function Has($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

if (-not (Has 'winget')) {
  Write-Host '找不到 winget。請先到 Microsoft Store 安裝「應用程式安裝程式 (App Installer)」，再重新執行。' -ForegroundColor Red
  return
}

Step '1/5 安裝 Node.js 與 Git'
if (-not (Has 'node')) {
  winget install --id OpenJS.NodeJS.LTS -e --accept-source-agreements --accept-package-agreements
} else { Write-Host "Node.js 已安裝：$(node --version)" }
if (-not (Has 'git')) {
  winget install --id Git.Git -e --accept-source-agreements --accept-package-agreements
} else { Write-Host "Git 已安裝：$(git --version)" }
Refresh-Path

Step '2/5 安裝 Claude Code、Codex、Happy'
# 用 npm.cmd 而不是 npm，避開 PowerShell 預設擋 .ps1 的執行原則
npm.cmd install -g @anthropic-ai/claude-code @openai/codex happy
Refresh-Path

Step '3/5 設定插電時不睡眠、蓋上螢幕不睡眠'
powercfg /change standby-timeout-ac 0
powercfg /change hibernate-timeout-ac 0
powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
powercfg /setactive SCHEME_CURRENT

Step '4/5 開機後自動啟動 Happy 背景服務'
$startup = [Environment]::GetFolderPath('Startup')
$cmdFile = Join-Path $startup 'happy-daemon.cmd'
Set-Content -Path $cmdFile -Encoding ASCII -Value "@echo off`r`ncall happy.cmd daemon start"
Write-Host "已建立：$cmdFile"

Step '5/5 建立專案資料夾'
$projects = Join-Path $HOME 'Projects'
New-Item -ItemType Directory -Force -Path $projects | Out-Null
Write-Host "專案資料夾：$projects"

Write-Host @"

安裝完成。接下來請手動做這幾步（都要登入，所以不能自動）：

  1. 登入 Claude：   claude.cmd  （跟著畫面用瀏覽器登入你的 Claude 帳號，登入後輸入 /exit 離開）
  2. 登入 Codex：    codex.cmd   （選 Sign in with ChatGPT，登入後按 Ctrl+C 離開）
  3. 下載專案：      cd $projects
                     git clone https://github.com/wanttalk/website
  4. 連接手機：      cd $projects\website
                     happy.cmd
     畫面會出現 QR Code，用手機的 Happy App 掃描。

"@ -ForegroundColor Green
