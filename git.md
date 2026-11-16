# infinitiX_CarLauncher Git 版本控制與協作指引

本文件整理 `infinitiX_CarLauncher`（Infiniti M25 Y51 / FYT UIS7862 車機桌面）之 Git 忽略策略、提交規範與 APK 發版流程。

- 遠端儲存庫：`https://github.com/joe10897/infinitiX_CarLauncher.git`
- 預設主分支：`main`

---

## 1. `.gitignore` 忽略策略說明

根目錄 [`.gitignore`](.gitignore) 與 [`app/.gitignore`](app/.gitignore) 已設定排除以下環境專屬與編譯產物，避免跨機器衝突或儲存庫膨脹：

| 類別 | 忽略項目 | 原因說明 |
| :--- | :--- | :--- |
| 編譯產物 | `build/`、`app/build/`、`*.apk`、`*.aab`、`*.dex`、`*.class` | Gradle 編譯輸出與 APK 請改用 GitHub Releases 發布，不直接進版控 |
| 本機環境 | `local.properties` | 包含本機 Android SDK 路徑（例如 `D:\SDK`），各開發環境不同 |
| Gradle / Kotlin 快取 | `.gradle/`、`.kotlin/`、`.cxx/`、`.externalNativeBuild/` | 自動生成的暫存與建置索引 |
| IDE 設定 | `.idea/`、`*.iml` | Android Studio 本機快取、JDK 設定與視窗狀態 |
| 簽章金鑰 | `*.jks`、`*.keystore` | 避免私鑰外洩至遠端儲存庫 |
| 系統與除錯檔 | `.DS_Store`、`Thumbs.db`、`*.log`、`*.hprof` | 作業系統縮圖快取與記憶體傾印 |

> **保留入庫的 Wrapper 檔案**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 與 `gradle/wrapper/gradle-wrapper.properties` 必須保留在 Git 中，以確保任何環境皆能使用一致的 Gradle 8.4 版本建置。

---

## 2. 首次提交並推送至 GitHub

推送前請先確認工作區乾淨且沒有包含 `build/` 或 `local.properties`：

```powershell
# 1. 檢查追蹤狀態（確認不含 build/、.gradle/、.idea/、local.properties）
git status

# 2. 加入所有專案原始碼與設定檔
git add .

# 3. 再次確認暫存區清單
git status

# 4. 建立提交
git commit -m "feat: initialize Y51 DriveOS car launcher and AutoDriveService"

# 5. 推送至遠端 main 分支
git push -u origin main
```

---

## 3. 日常開發與 Commit 規範

建議遵循 **Conventional Commits** 格式，方便追蹤車機功能調整與 UI 修正：

- `feat:` 新增功能模組（例：`feat: add 5-minute monotonic cooldown for wake trigger`）
- `fix:` 修正問題（例：`fix: handle missing FYT surround package fallback`）
- `ui:` 介面樣式與 Fine Vision 儀表調整（例：`ui: refine 1280x480 speedometer weight`）
- `docs:` 文件更新（例：`docs: update git workflow and ADB install steps`）
- `chore:` Gradle / SDK / 專案設定調整（例：`chore: upgrade AGP to 8.2.2 for compileSdk 34`）

### 分支建議流程

```powershell
git checkout -b feat/split-screen-tuning
# 開發並測試編譯
.\gradlew.bat assembleDebug testDebugUnitTest
git add .
git commit -m "feat: tune split-screen delay for UIS7862"
git checkout main
git merge --no-ff feat/split-screen-tuning
git push origin main
```

---

## 4. APK 編譯、實機安裝與發版流程

### 本機編譯與測試

```powershell
.\gradlew.bat clean assembleDebug testDebugUnitTest
```

產出 APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

### 透過 ADB 安裝至車機

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 建立版本 Tag（供 GitHub Releases 附掛 APK）

```powershell
git tag -a v1.0.0 -m "Y51 DriveOS v1.0.0 (1280x480 Fine Vision)"
git push origin v1.0.0
```

---

## 5. 排除常見問題：若不小心追蹤到應忽略的檔案

若曾經把 `.idea/`、`build/` 或 `local.properties` 加入 Git 索引，更新 `.gitignore` 後需從索引移除追蹤（不會刪除本機檔案）：

```powershell
git rm -r --cached .idea build app/build .gradle local.properties
git commit -m "chore: stop tracking local IDE and build artifacts"
```
