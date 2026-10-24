# SupplyPilot DEMO 繁中 RAG 語料使用說明

> Corpus Version：`sp-906-zh-tw-v2`；Dataset Key：`SP-901`。本文說明虛構展示語料的結構、引用方式與審查邊界，不代表語料已上傳、核准、索引或完成 Gemini 驗收。

## 語料來源與檔案

- [主資料契約](../../samples/demo-master-data.json) 是供應商、物料、供應關係、PO 範例及 Corpus Version 的一致性依據。供應商及物料名稱、代碼、單位、MOQ、包裝倍數與交期以該檔為準。
- [Corpus Catalog](../../samples/documents/corpus-catalog.json) 列出文件鍵、`DocumentType`、版號、來源檔、SHA-256、供應商／物料關聯、首次審查狀態、索引預期與 Fixture 關聯。
- [RAG Fixtures](../../samples/documents/rag-fixtures.json) 列出 25 條靜態問答案例、預期答案要點、引用來源、引用要求、案例類別及預期狀態。
- `samples/documents/` 包含 15 份 `SEED_BASELINE` 文件，以及 1 份 `MANUAL_UI_UPLOAD_ONLY` 文件。15 份 Seed 文件分布如下，每類各 3 份：

| DocumentType | 文件數 |
| --- | ---: |
| `SUPPLIER_CONTRACT` | 3 |
| `QUOTATION` | 3 |
| `PROCUREMENT_SOP` | 3 |
| `PRODUCT_SPECIFICATION` | 3 |
| `QUALITY_REQUIREMENT` | 3 |

## 文件內容約束

所有內容均為虛構的繁體中文供應鏈展示案例。業務代碼、狀態及物料單位維持系統使用的英文識別值。報價文件只記錄結構化資料中可核對的供貨期間、MOQ、包裝倍數與交期，不記錄單價或金額；其他文件亦不含憑證、個人資料或認證結果。歷史條件清楚標示適用期間，不能當成現行條件引用。

採購 SOP 使用 V2 直接建立 PO `DRAFT` 及八階段 Kanban，並說明正式採購單狀態與看板階段分離。文件沒有把 V1 Demand／Recommendation 流程帶回主流程。

## 審查、出站與索引

Catalog 的 `firstReviewStatus`、`outboundPolicy` 及 `indexingExpectation` 是語料追蹤資訊，不是管理員核准憑據。所有文件首次仍須由管理員依實際內容版本與 SHA-256 執行正常審查；Catalog 旗標不得授權內容送往外部 Provider。通過適用審查與政策後，索引仍須走既有持久化非同步流程及逐文件發布規則。

`UI-SUP-005-ADDENDUM` 對應 `ui-upload-sup-005-v1.txt`，用途限於人工 UI Upload 驗收。其分類為 `MANUAL_UI_UPLOAD_ONLY`，Coordinator 不得自動 Seed，也不得當成基線來源。`RAG-021` 只適用於此文件由測試人員人工上傳後的查詢情境。

## 雜湊維護與 Fixture 解讀

文件內容有任何變更後，先重新計算來源檔案 SHA-256，再更新 Catalog 的 `sourceSha256`。雜湊以 UTF-8 文字內容計算；計算前將 CRLF 或單獨 CR 換行正規化為 LF，因此不同作業系統的 checkout 會得到相同結果。`samples/documents/*.txt` 在 Repository 中應固定使用 LF 換行。下列 PowerShell 指令可從 Repository 根目錄計算單一檔案的 canonical-LF 雜湊：

```powershell
$path = 'samples/documents/quotation-sup-002-v1.txt'
$text = [System.IO.File]::ReadAllText($path) -replace "`r`n?", "`n"
$bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($text)
$digest = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
($digest | ForEach-Object { $_.ToString('x2') }) -join ''
```

文件內容或版號改變不會沿用舊版本的管理員審查。更新後應由人工按實際 hash 重新審查；不得因 Catalog、Fixture 或歷史資料曾出現 `APPROVED` 而自動恢復授權。

Fixture 的 `expectedStatus`、答案要點及引用條件是靜態驗證契約，不表示執行過 RAG、Browser、pgvector 或 Gemini。實際執行時應確認答案逐點有來源支持、引用標題及版本正確，並排除無關文件；缺少證據的題目應拒答，不得補寫推測。
