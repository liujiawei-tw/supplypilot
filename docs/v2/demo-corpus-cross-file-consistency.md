# Demo Corpus 跨文件事實一致性盤點

> 盤點基準：sp-907-zh-tw-v3、Dataset SP-901，檢視分支基準 HEAD 9b110f6a6b9108c9611aa559c751c5b06d920ddc。本文記錄 Repository 靜態語料與 Fixture 的盤點，不代表文件已上傳、核准、索引或經真實 RAG 驗收。

## 盤點範圍與機械化結果

- Corpus Catalog 記錄 116 筆：115 筆 SEED_BASELINE（保留 15 份舊基線並新增 100 份供應商主題文件），另有 1 筆 MANUAL_UI_UPLOAD_ONLY。人工上傳項目不納入基線文件交叉盤點。
- 126 筆 Fixture 的 key 唯一；112 筆預期 ANSWER、14 筆預期 INSUFFICIENT_EVIDENCE。包含 251 組答案點證據、261 筆來源摘錄。
- 主資料含 10 家供應商、12 項物料及 45 組供應關係。供應商狀態為 9 家 ACTIVE、1 家 INACTIVE（SUP-004）；供應關係為 43 組 ACTIVE、2 組 INACTIVE（SUP-004/MAT-001、SUP-009/MAT-005）。
- 10 份新供應能力文件列出的 45 組關係，其狀態、單位、MOQ、包裝倍數、交期及幣別與 samples/demo-master-data.json 相符。10 份供應商檔案的關係及狀態清單亦與主資料相符。供應能力文件明確指出規劃量是測試輸入，不是實際產能、庫存或供應承諾。
- 供應商契約情境列出的 44 組關係條件均與主資料相符；契約情境有效期間是獨立的契約案例事實，不等於個別報價的有效期間，也不是法律契約證據。
- 10 份 V2 報價逐一對照其供應商／物料主資料後，MOQ、包裝倍數、交期、幣別及虛構單價均吻合。SUP-004/MAT-001 的報價明確屬 2025 年歷史資料，供應商及供應關係均為 INACTIVE，不可當作現行報價。
- 靜態驗證器檢查 115 筆基線來源、canonical-LF hash、Catalog 關聯、Fixture 摘錄是否為來源原文、答案要點的字面事實支持及案例結構；另對可信 Catalog 中明確盤點的四個 supplier/material 報價期間 scope，驗證 applicability source 必須指向相符的 Catalog QUOTATION、quoteTerms 及精確期間。本次命令 `python -B -X utf8 scripts/validate_demo_corpus.py` 結果為 Static corpus validation: PASS，Exit Code 0。此規則機械化驗證已盤點的關係，不會自行從任意文件推論所有業務語意，也不證明管理員核准、索引發布或回答品質。

## 報價有效期間跨文件衝突

下表中「現行來源」表示本次展示資料預期使用的 V2 報價；這是語料及需求層的適用性判斷，不等於實際資料庫的核准或 RAG 索引狀態。「舊版來源」列出仍在 115 份基線中的文件。SUP-002/MAT-001 沒有 V2 報價，所以不以 V2 報價推定其現行報價期限。

| Supplier | Material | Fact Type | Current Source | Historical Source | Conflict | Resolution |
|---|---|---|---|---|---|---|
| SUP-001 | MAT-001 | 報價有效期間 | DOC-SUP-001-QUOTATION-SP907 v2.0，supplier-sup-001-quotation-sp907-v2.txt:10：2026-10-01 至 2026-11-29 | DOC-SUP-001-QUOTATION v1.0，quotation-sup-001-v1.txt:6,11：同日起稱有效至 2026-12-31 | 兩期間重疊，但截止日不同；舊文件仍把 2026 條件稱為適用條件 | 現行期限採 V2 的 2026-11-29。保留舊文件供追溯，並以跨文件事實適用性將其舊版 2026 條件排除於現行證據；不要刪除歷史文件。 |
| SUP-002 | MAT-002 | 報價有效期間 | DOC-SUP-002-QUOTATION-SP907 v2.0，supplier-sup-002-quotation-sp907-v2.txt:10：2026-10-01 至 2026-11-30 | DOC-SUP-002-QUOTATION v1.0，quotation-sup-002-v1.txt:6,15：稱現行至 2026-12-31 | 同一組供應商／物料、同日起始的報價期間互相矛盾；MOQ 400 KG、包裝 25 KG、交期 8 日相同，舊文件未列單價 | 現行期限採 V2 的 2026-11-30。將 v1 的 2026 段落標為已退役／歷史條件；保留其中另行標示的 2025 歷史期間。 |
| SUP-002 | MAT-001 | 現行報價及有效期間 | 無 V2 報價來源。V2 contract-terms 與 supply-capability 可支持主資料快照（ACTIVE、MOQ 500 KG、包裝 50 KG、交期 3 日、TWD），但不提供此物料的 V2 報價期限。 | DOC-SUP-002-QUOTATION v1.0，quotation-sup-002-v1.txt:6,8,12,15：2026 段落仍自稱現行至 12-31；另列 2025-03-01 至 2025-06-30 歷史段落。 | 舊檔保留一項沒有 V2 報價對應的「現行」期限；目前主資料條件快照不能證明存在有效報價。 | 查詢現行報價期限時回覆資料不足；查詢 MOQ／包裝／交期時可引用可信且現行的結構化條件來源，清楚說明它不是報價。舊文件的 2026「現行」段落不能作現行報價證據。 |
| SUP-007 | MAT-010 | 報價有效期間 | DOC-SUP-007-QUOTATION-SP907 v2.0，supplier-sup-007-quotation-sp907-v2.txt:10：2026-10-01 至 2026-12-05 | DOC-SUP-007-QUOTATION v1.0，quotation-sup-007-v1.txt:6：同日起稱有效至 2026-12-31 | 兩期間重疊但截止日不同；MOQ 150 KG、包裝 50 KG、交期 4 日相同 | 現行期限採 V2 的 2026-12-05；舊版保留供追溯但不作現行有效期證據。 |

corpus-catalog.json schema 4 加入 `businessFactApplicability`，以 Supplier、Material、Fact Type、來源文件版本及精確有效期間表達跨 Document Key 的 current、historical 與 superseded 關係。適用性來源必須綁定 Catalog 內的報價文件及相符 quoteTerms；`SUPERSEDED` 描述跨文件關係，來源原始 quoteTerms 仍可保留文件發布時的 `CURRENT` 狀態。MySQL lexical 與 PostgreSQL/pgvector 共用同一 eligibility 判斷，目前／最新查詢只取用涵蓋今天的 current source；明確年份、日期或期間可取用相符來源；同一 supplier/material 的明確歷史／現行比較須具備兩側來源，無法對應共同 scope 或缺少任一側即 fail closed。含糊歷史查詢亦 fail closed。歷史原檔、核准狀態、audit、chunks 與 semantic index 不因 RAG 不適用而刪除；普通無 Demo provenance 上傳不套用此規則。

## 指定 RAG Fixtures 複查

| Fixture | 預期答案與來源 | 複查結果 |
|---|---|---|
| RAG-002 (rag-fixtures.json:41) | SUP-001/MAT-001 MOQ 800 KG、包裝 100 KG、期限 2026-11-29；來源 DOC-SUP-001-QUOTATION-SP907 v2.0 | 答案點均有新版報價原文支持，日期及供應關係資料相符。舊 v1 的 2026-12-31 條件已在 Catalog 明列 superseded，現行 eligibility 會排除舊來源。 |
| RAG-003 (rag-fixtures.json:85) | 問題限定 SUP-002 的 V2 報價是否提供 MAT-001 MOQ／包裝／交期；來源列 DOC-SUP-002-QUOTATION-SP907 v2.0，預期資料不足 | 正確：該 V2 報價只列 MAT-002。主檔目前有 SUP-002/MAT-001 的供應條件快照 500/50/3，但不等於 MAT-001 報價，不能據此補出報價期限或 V2 報價。 |
| RAG-018 (rag-fixtures.json:625) | SUP-002 2025 歷史期限 2025-06-30，現行 V2 報價期限 2026-11-30；來源分別是 DOC-SUP-002-QUOTATION v1.0 及 DOC-SUP-002-QUOTATION-SP907 v2.0 | 日期與來源版本相符。原問題未列物料代碼；跨文件適用性只找到 SUP-002/MAT-002 同時具有 2025 歷史來源與現行 V2 來源，因此比較只使用 v1 的歷史到期句及 v2 的 2026-11-30 現行證據。舊 v1 的 2026-12-31 期限、其他 MAT-001 條件及混合物料歷史條件不得進入摘錄；MySQL／pgvector 整合測試分別檢查兩條路徑。 |
| RAG-019 (rag-fixtures.json:670) | SUP-002/MAT-002 虛構 Demo 報價為 73 TWD/KG、每 KG 計價；來源 DOC-SUP-002-QUOTATION-SP907 v2.0 | 答案點與 V2 報價原文及版本化主資料相符，且清楚標示虛構價格。只有通過 Catalog、source hash、provenance、Demo target 及 outbound policy 檢查的可信 Demo 報價能使用價格例外。 |

## 審查界線

### 可機械驗證

文件數、文件版本及 Catalog 來源、來源 hash、供應商／物料主檔欄位、報價日期字面期間、Fixture key 與來源 key、答案點的字面證據支持、歷史案例欄位和案例日期規則均可由靜態檢查核對。適用性驗證器另檢查 Catalog 明列的四個跨文件 scope、來源 quoteTerms、無現行來源理由與歷史／現行 Fixture 的同 scope 來源資格。上述三組有效期衝突是來源原文中可直接比較的日期事實。

### 需人工語意審查

「現行」或「歷史」的業務適用性、不同 Document Key 是否描述同一個事實、合約情境是否能支持某項報價，以及使用者是問報價還是結構化供應條件，須按 supplier/material/business theme/fact type/適用期間判斷。不得只按文件建立時間、資料庫 ID 或版號大小推定；沒有足夠來源時應回覆資料不足。SUP-002/MAT-001 尤須區分有現行條件快照與無 V2 報價來源。

### 真實 RAG／Gemini

MySQL／pgvector 證據來自隔離 Testcontainers 版本隔離整合測試，使用測試 embedding，不連線使用者 Demo DB，也不呼叫 Gemini。另有真實 Gemini Embedding／Chat synthetic provider smoke 2/2 通過；此 smoke 沒有查詢 Demo corpus 或驗證文件答案。Browser E2E、線上 Demo DB、AWS，以及 Gemini 下的 Demo 歷史／現行文件端到端比較仍為 NOT RUN。靜態 PASS、整合測試及 synthetic provider smoke 都不代表 Gemini 能在自由語言問題中理解未覆蓋的時態語意或選對所有現行版本。

### 非阻擋 V2 整合待辦

引用摘錄目前會附加系統產生的期間與 scope 註記；整合引用呈現時應將這些 metadata 與來源原文清楚區分，避免被誤認為文件逐字內容。
