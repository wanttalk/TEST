# Phone Runner 交接給下一個 AI

最後更新：2026-09-02（Asia/Taipei）

## 一句話結論

基本遠端喚醒鏈已完成並穩定通過；目前唯一未閉合的是「本對話指定排程後，由 APK 直接套用 Android AlarmManager」。問題不是使用者漏按權限，而是手機沒有回報新版 `schedule_set` handler 已執行。

## 專案方向

- 使用者只在本對話指定時間，例如「設定 1 分鐘」、「設定 10 分鐘」或「每天某時間」。
- AI 修改私有 repo `wanttalk/android-phone-runner/remote_request.json`。
- GitHub push → Webhook → Cloudflare Worker → Firebase FCM → Android Phone Runner APK → Termux RUN_COMMAND → `phone_tick.sh` → GitHub Issue #1 回報。
- 不使用 Automate、ntfy 或 Termux:Tasker。
- 排程必須由 APK 原生 AlarmManager 設定；Termux 不應再用 `am broadcast` 設定排程。

## 已完成並驗證

- Firebase、FCM、Worker、裝置自動註冊、Termux、RUN_COMMAND、通知與 Xiaomi/Redmi/POCO 電池背景設定均已通過。
- 手機畫面曾顯示狀態全綠、`裝置登記: REGISTERED`、`原生排程: SCHEDULED`。
- 多次安全 `runner_check` 通過，包含 2026-09-02 12:28、12:30、12:34、12:39、12:40、12:41；皆為 `wake-source: phone_tick`。
- 這證明 FCM 收訊、APK 一般喚醒、Termux 執行與 GitHub 回報鏈正常。
- 公開 repo `wanttalk/TEST` 的 `phone-runner-v0.2.7` Release 是 versionCode 9，原始碼含 `schedule_set` 直接排程 handler。
- Cloudflare Worker parser 已改成可接受帶前綴的排程 commit message；Dashboard 顯示版本 `2412ab2d` 已部署。
- 私有 repo 的 `check.py` 版本診斷 regex 已修正，並增加套件存在檢查。
- `PHONE_RUNNER_RUNBOOK.md` 與 `PROJECT_STATE.md` 已記錄目前流程；公開 repo 的操作手冊也已同步。

## 真正碰到的問題

### 1. 先前測試流程的協定錯誤

曾經用一般 commit message 測試排程。Worker 因找不到排程標記，把它當成普通 `wake`：

```
action: wake
```

手機於是走舊的 Termux `am broadcast` 排程路徑，Xiaomi/MIUI 回報：

```
INTERACT_ACROSS_USERS
EXIT_255
```

這不是手機權限漏按，而是雲端請求路由錯誤。之後 Worker 已修正。

### 2. 修正路由後手機沒有回報

用正確格式後，Webhook 回應已確認：

```
action: schedule_set
```

但手機沒有對同一 request 回報 `received` 或 `dispatched`。v0.2.7 handler 一進入就會先回報 `received`，所以目前證據表示：

- 雲端 parser 正常；
- FCM API 送出正常；
- 一般 FCM 喚醒正常；
- 但手機實際執行的 APK 沒有觀察到新版直接排程 handler。

最符合證據的推論是手機仍在舊 APK，或更新下載後沒有真正由 Android 安裝器套用；不要把這個推論寫成已證實的版本號。

### 3. 版本診斷限制

手機回報：

```
package_installed=yes
package_version=unknown
package_code=unknown
```

Termux UID 在 MIUI 下無法可靠取得版本與螢幕/Doze 權限資訊。因此目前不能只靠畫面全綠或 `REGISTERED` 宣稱 v0.2.7 已安裝。畫面上的 `SCHEDULED` 只代表本機有排程，不代表遠端 1 分鐘排程已套用。

## 不要再做的事

- 不要重新配對。
- 不要複製或重新填 FCM token。
- 不要清除 App 資料。
- 不要解除安裝現有 App。
- 不要重按所有已經綠色的權限按鈕。
- 不要用 Termux `am broadcast` 設定排程。
- 不要用 Facebook 或其他正式業務任務代替安全排程驗收。
- 如果 Issue 出現 Xiaomi `INTERACT_ACROSS_USERS` / `EXIT_255`，先檢查 Webhook response 是否為 `action: wake`；那通常代表請求格式錯誤，不是使用者要重設手機。

## 下一步唯一流程

1. 若尚未真正完成 Android 安裝更新：在現有 App 按 **檢查 APK 更新**；Android 安裝器出現時只按一次 **安裝／更新**。保留原資料，不解除安裝。
2. 安裝後按 **開啟**，再按 **重新整理**；若狀態全綠，不要再按其他設定按鈕。
3. AI 送出 1 分鐘安全排程，commit message 必須包含：
   - `runner_schedule_set`
   - `interval_minutes=1`
   - `request_id=<唯一值>`
4. 必須在 Issue #1 看到同一 request 的 `received`、`dispatched` 與 `SCHEDULE_SET`／PASS，才算遠端排程通過。
5. 再做螢幕關閉至少三輪安全 `runner_check`。若沒有可靠 Doze 系統狀態，只能宣稱背景喚醒 PASS，不能宣稱完整 Doze PASS。
6. 最後才做正式業務驗收與清理舊工具。

若使用者已經確實看到 Android 安裝器並按過 **安裝／更新**，不要再叫使用者重做；下一個 AI 應先檢查新的 Issue 回報與 Worker response，再決定是否需要建立新 APK。

## 重要檔案與位置

- 私有 canonical repo：`wanttalk/android-phone-runner`
- 公開建置 repo：`wanttalk/TEST`
- 遠端控制檔：`remote_request.json`
- 狀態文件：`PROJECT_STATE.md`
- 操作手冊：`PHONE_RUNNER_RUNBOOK.md`
- APK 版本資訊：`update.json`
- APK handler：`phone-runner-app/app/src/main/java/com/wanttalk/phonerunner/PhoneRunnerMessagingService.java`
- 原生排程：`phone-runner-app/app/src/main/java/com/wanttalk/phonerunner/NativeWakeScheduler.java`
- Termux 橋接：`phone-runner-app/app/src/main/java/com/wanttalk/phonerunner/TermuxCommandClient.java`
- Worker parser：`phone-runner-worker/src/index.js`

## 安全規則

本文件刻意不包含 FCM token、Worker token、Webhook secret、Firebase private key、Telegram token、裝置 auth token、簽名密鑰或任何一次性配對碼。下一個 AI 不得從 Issue、KV、截圖或回覆中重新輸出這些機密。
