# SP-907 / Issue #113 驗收紀錄

## 範圍與版本

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#113](https://github.com/liujiawei-tw/supplypilot/issues/113)
- Base：`origin/main` `d3bd1c76a00d004f81b28711e669e20f4349ff63`
- Branch：`codex/sp907-ui-c-role-workbench`
- 功能與測試 commit：`791744b2d2720ec7bf6a65c943efe900580e35ac`
- Browser：Microsoft Edge `155.0.4283.60`，Playwright `channel=msedge`、headed，`1440 × 1000`
- Browser 證據：`edge-fixture-manifest.json` 與 `screenshots/`

所有 Edge 案例皆由真實 Microsoft Edge 開啟本機 Vite 頁面並檢查 URL、DOM 文字與 API response。API 由隔離的 Playwright Fixture 攔截，因此這是「真實 Browser＋受控 Fixture」驗收；`backendIntegration: false`，不代表真實 Backend 整合驗收。Fixture 只讀，不會對資料庫送出寫入操作。manifest 記錄各案例 Git SHA、Viewport、畫面摘要、URL、API method/path/query/status 及截圖路徑。

## Edge 案例

| 案例 | 結果 | 截圖 |
| --- | --- | --- |
| `PROCUREMENT_STAFF` Dashboard | PASS | `screenshots/dashboard-procurement-staff.png` |
| `PROCUREMENT_MANAGER` Dashboard | PASS | `screenshots/dashboard-procurement-manager.png` |
| `WAREHOUSE_STAFF` Dashboard | PASS | `screenshots/dashboard-warehouse.png` |
| `ADMIN` Dashboard | PASS | `screenshots/dashboard-admin.png` |
| Dashboard Empty | PASS | `screenshots/dashboard-empty.png` |
| Dashboard HTTP 403 部分失敗 | PASS | `screenshots/dashboard-forbidden-403.png` |
| Dashboard HTTP 500 部分失敗 | PASS | `screenshots/dashboard-error-partial.png` |
| `PURCHASE_NOT_REQUIRED` | PASS | `screenshots/recommendation-purchase-not-required.png` |
| `SUPPLIER_RECOMMENDED` | PASS | `screenshots/recommendation-supplier-recommended.png` |
| `NO_QUALIFIED_SUPPLIER` | PASS | `screenshots/recommendation-no-qualified-supplier.png` |

Edge Console 有 Ant Design `List`／`Drawer` deprecation warnings；403／500 Fixture 案例另會產生瀏覽器預期的 Failed to load resource 訊息。沒有未捕捉的 page error，所有頁面／狀態斷言通過。

## HTTP 403 Regression 與 RED／GREEN

- 新增 `frontend/tests/dashboard.test.tsx` 回歸案例：採購員對合法 `GET /purchase-orders?status=DRAFT` 收到 HTTP 403、`ACCESS_DENIED` 與格式有效 Trace ID；APPROVED／ORDERED 查詢成功。
- 驗證草稿區顯示繁中無權限狀態、不呈現 Empty／零筆／沒有待辦或 Backend 私密錯誤；部分失敗提示及其他成功區塊保留；三個合法採購清單請求各一次，沒有重試及無權限 API 呼叫。
- RED：暫時將 403 分類 mutation 改成 `EMPTY`，目標測試失敗於「此工作資料無權限顯示」斷言，DOM 顯示 Empty 文案；source 隨即還原。執行 log 留在本機暫存目錄，清理前已核對失敗測例及退出碼，不納入 Git。
- GREEN：還原正式分類後目標測試 `1 passed | 8 skipped`；Dashboard 套件 `9/9` 通過；完整套件亦通過。

## Impeccable 實際操作

- Skill：`C:\Users\z0976\.codex\skills\impeccable\SKILL.md`，Skill metadata version `4.5.2`。Launcher script `scripts/VERSION` 為 `0.1.14`；`impeccable.cmd --version` 回報 bundled engine `4.0.0`。以上是三個不同版本欄位。
- 官方專案／使用說明：[pbakaus/impeccable](https://github.com/pbakaus/impeccable)、[Impeccable](https://impeccable.style)。
- 依 Skill 流程在 Repository 工作目錄呼叫 Impeccable `context`，載入結果指出沒有 `PRODUCT.md`、`DESIGN.md`、surface brief 或自動設計 hook，`targetPath: null`，所以直接以現有頁面與程式碼審查，不假造產品／設計規格。
- 以 `impeccable.cmd detect --json frontend\src\pages\Dashboard\DashboardPage.tsx frontend\src\pages\Recommendations\RecommendationResult.tsx frontend\src\components\RecommendationSummary\RecommendationSummary.tsx frontend\src\components\RecommendationDraftForm\RecommendationDraftForm.tsx` 執行 Detector；最後結果為 `[]`。
- 讀取 Skill `reference/craft-floor.md`，並依 Skill 的 Critique／Harden／Polish 審查方向完成兩份互相隔離的 Critique 評估及手動狀態檢視。獨立 Critique 評分為 `27/40`，沒有 showstopper。找到 Dashboard 把狀態列舉值直接寫給使用者，以及 Recommendation 的 Tag 與 Alert 標題重複呈現 Outcome；兩項均在本 Issue 範圍內修正。另記錄八欄排名表在窄畫面可能需要後續 RWD 工作，屬其他範圍，本次不延伸。
- Harden 檢視涵蓋 Loading／Empty／403／500／部分失敗、長文字／大數值、角色切換競態及敏感錯誤內容；對應前端測試與 Edge Fixture 案例均保留。實際 Browser 與測試結果見本文件其他章節。
- 該安裝版本的 CLI `--help` 沒有獨立 `critique`、`harden`、`polish` 或 `audit` 子命令（支援列表為 `detect`、`ignores`、`help`、`install`、`link`、`update`、`check`）。因此沒有聲稱執行不存在的 CLI 指令；Critique／Harden／Polish 是依已載入 Skill 參考流程所做的人工審查，而 Detector 是實際 CLI 執行。

## 前端驗證

下列結果對應功能與測試 commit `791744b2d2720ec7bf6a65c943efe900580e35ac`：

| 指令 | 結果 |
| --- | --- |
| `npm run lint` | PASS，exit 0 |
| `npm test` | PASS，23 files、191 tests，耗時 898.73 秒 |
| `npm run build` | PASS，exit 0；Vite 提示既有 JavaScript chunk 約 1,216.76 kB，高於 500 kB 建議值 |
| `git diff --check` | PASS；僅 Git CRLF 正規化提醒 |

`gitleaks`／`trufflehog`／`detect-secrets`／`secretlint` 不在本機安裝。對本次 diff 新增行執行 secret/token/API key 私密值 pattern scan，沒有命中；因此本紀錄不將其稱作 gitleaks 或 repository-wide 完整 Secret Scan。

## CI 與環境限制

- 舊 CI Run [39015671670](https://github.com/liujiawei-tw/supplypilot/actions/runs/39015671670) 最終狀態：Frontend Lint SUCCESS、Test FAILURE、Build SKIPPED、Backend CI SKIPPED。原 Test 失敗是 SP-806 測試把 Dashboard 的三個合法 GET `/purchase-orders` 當成建立採購單；此 commit 已將斷言限縮為 `POST /purchase-orders`。
- 本文件建立時，修正 commit 尚待推送並觸發新的 GitHub Actions。只有新 Run 的 Frontend Lint／Test／Build 均成功後，才能標記 CI 驗收完成。
- Docker daemon／後端 `localhost:8080` 在此執行環境不可連線；沒有重啟 Docker、重置 r4、清除 Recommendation 或修改資料庫。本次 Backend 整合未執行，必須與上述 Fixture Browser 驗收分開看待。
