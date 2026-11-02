# SupplyPilot 目前 main 架構

這份架構圖依本機 `main` 的服務設定、Nginx 路由與 Spring 設定整理。盤點基準為 `1c8540c`；圖只描述 Repo 中可確認的執行路徑，不代表所有可選服務已啟用或已部署。

- [架構圖 HTML](supplypilot-architecture-overview.html)：自包含網頁，內嵌相同的 SVG 圖面，窄螢幕可水平捲動。
- [架構圖 SVG](supplypilot-architecture-overview.svg)：可獨立檢視的 16:9 向量圖。
- [專案簡報](../presentations/supplypilot-project-overview.html)：封面加六頁簡報。

圖面採用頂部採購操作步驟、中段系統分層、底部文件 RAG 流程的編排，參考使用者提供的架構頁呈現方式；節點、連線和設定狀態都按 SupplyPilot main 核對。

## 人員與服務分工

圖上的唯一人員使用者是**採購人員**。V2 文件中的 `ADMIN` 是程式內的業務角色代碼，不代表另有主管、倉管或管理員等人員類型；人員管理是額外授權能力。

採購人員用瀏覽器操作 React 前端。Nginx 提供前端頁面，並把 `/api/v1/` 轉送到 Spring Boot。後端負責 JWT 身分驗證、權限檢查與採購交易規則，再讀寫 MySQL 權威業務資料。回應沿原 API 路徑返回前端。

後端另連接文件 Volume，保存上傳的原始文件；MySQL 保存文件資訊與文字資料。文件語意索引使用獨立 PostgreSQL 17 / pgvector，不能與 MySQL 業務資料混為同一資料庫。

## 可選的 RAG 路徑

文件須符合核准及外送政策後，後端才可抽取、切段並建立向量。查詢流程使用 Spring AI `ChatClient`、`RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever` 與 `PgVectorStore`，再由已設定的 Gemini Chat／Embedding Provider 處理模型請求。

此路徑在圖中以虛線表示，因為目前 `SEMANTIC_READ_ENABLED` 預設為 `false`，Compose 的 Chat 與 Embedding Provider 預設為 `none`。設定存在不等於服務已開啟。Agentic SQL 是隔離實驗且 `AGENTIC_SQL_ENABLED` 預設關閉，不畫成一般採購請求的主路徑。

## 本機 Compose 邊界

`compose.yaml` 定義四個服務：Frontend、Backend、MySQL 8.4、PostgreSQL 17 / pgvector。服務使用 `web`、`data`、`mysql-host` 三個網路；`data` 設為內部網路。Frontend 綁定 `127.0.0.1:8080`；MySQL 預設綁定 `127.0.0.1:13306`；PostgreSQL 沒有發布主機連接埠。MySQL、PostgreSQL 與文件分別使用 `mysql-data`、`pgvector-data`、`document-storage` volumes。

AWS EC2 是文件中的部署目標，圖不表示已完成雲端部署。GitHub Actions 依使用者決策標示為 `PAUSED BY USER / NOT USED AS GATE`。

## 依據

- [Compose 服務、網路與 volumes](../../compose.yaml)
- [Nginx API 代理設定](../../frontend/nginx.conf)
- [Spring AI、Tool Calling 與功能開關](../../backend/src/main/resources/application.yml)
- [文件 RAG 規格及目前路徑](../v2/document-rag.md)
- [Agentic SQL 真實 smoke 證據](../experiments/agentic-sql/evidence/ab-2026-10-27-live-gemini-dual-db/README.md)
