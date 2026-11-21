# FullMoney 理財試算（網頁版）

以 **Java 17** 開發的響應式（RWD）理財試算網站，手機、平板、電腦都能使用。
計算邏輯沿用桌面版 FullMoney 1.4.0 的 `service` 與 `bean`（未修改公式），**不含大樂透分析**。

## 功能

| 分頁 | 內容 |
| --- | --- |
| 複利試算 | 依第一年本金、年利率、每年加碼與年數，試算逐年累積財富 |
| 房貸計算 | 本息／本金平均攤還、寬限期、分段利率、提前還款（縮短年限或降低月付），列出每期攤還明細 |
| 債券計算 | 持有到期的配息、總報酬率、到期殖利率（YTM）、年化報酬率；外幣債券換回台幣的報酬與匯兌損益 |
| 幣別轉換 | 透過新台幣換算兩種幣別，可一鍵交換 |

- 條件一改就自動重算，計算全部在 Java 後端執行。
- 金額欄位離開時自動補千分位，並顯示「萬／億」換算；可輸入全形數字。
- 輸入不合理時（例如寬限期不短於年限），欄位標紅並顯示原因，上一次的結果淡化保留。
- **外觀設定**（右上角「外觀設定」）：
  - 主題色系 6 種：淺色、護眼暗色、深色、護眼米黃、海洋藍、櫻花粉。
  - 字體大小 4 段：小（14px）、中（16px）、大（19px）、特大（22px）。版面全部以 rem 計算，字體放大時欄位會自動減少欄數。
  - 設定保存在瀏覽器（localStorage）。第一次開啟時依系統的深淺色模式選擇「淺色」或「護眼暗色」。

## 環境需求

| 項目 | 內容 |
| --- | --- |
| JDK | 17 以上 |
| 相依套件 | 無，使用 JDK 內建的 `com.sun.net.httpserver` |
| 建置工具 | 不需要；附 `pom.xml` 供 IDE 匯入或已安裝 Maven 時使用 |
| 編碼 | UTF-8 |

## 快速開始

Windows 直接執行 `run.bat`，會編譯、打包並啟動，然後用瀏覽器開啟 <http://localhost:8080/>。按 `Ctrl+C` 停止。

手動建置與啟動（PowerShell）：

```powershell
javac --release 17 -encoding UTF-8 -d target\classes (Get-ChildItem src\main\java -Recurse -Filter *.java).FullName
Copy-Item src\main\resources\* target\classes -Recurse -Force
java -cp target\classes com.fullmoney.WebApp
```

`build.bat` 會另外產生可單獨執行的 `target\fullcalmoney.jar`：

```powershell
java -jar target\fullcalmoney.jar
```

### 監聽位址

| 系統屬性 | 環境變數 | 預設 | 說明 |
| --- | --- | --- | --- |
| `-Dport=9090` | `PORT` | `8080` | 連接埠 |
| `-Dhost=0.0.0.0` | `HOST` | `127.0.0.1` | 預設只有本機可連。要用同一個網路的手機測試 RWD，或部署到伺服器時改為 `0.0.0.0` |

例如讓手機連線測試：

```powershell
$env:HOST = '0.0.0.0'; java -jar target\fullcalmoney.jar
```

手機瀏覽器開啟 `http://電腦的區網 IP:8080/`。Windows 防火牆第一次會詢問是否允許 Java 連線。

## 自我檢查

```powershell
cmd /c test.bat
```

`test.bat` 會建置後執行 `CalculatorCheck`（不依賴測試框架），共 35 項，全部通過時結束代碼為 0：

- 試算結果與桌面版文件記載的數值相同（見下方「計算參考值」）。
- 輸入檢查：拒絕科學記號、超出範圍、位數過多；接受千分位與全形數字。
- 實際啟動伺服器呼叫 API：正常結果、錯誤欄位、未知路徑、路徑穿越與 `.class` 檔都回 404。

## 專案結構

```text
src/main/java/com/fullmoney/
├─ WebApp.java              進入點，啟動網站
├─ AppVersion.java          名稱與版本號（頁尾與 /api/meta 由此取得）
├─ bean/                    沿用桌面版：複利、房貸、債券的條件與結果
├─ service/                 沿用桌面版：MoneyCalculator、MortgageCalculator、BondCalculator、CurrencyConverter
└─ web/
   ├─ WebServer.java        建立 HttpServer、註冊路徑
   ├─ ApiHandler.java       API 共用流程：解析參數、輸出 JSON、錯誤處理
   ├─ Params.java           表單參數解析與範圍檢查
   ├─ Json.java             最小 JSON 輸出
   ├─ StaticHandler.java    從 classpath 提供網頁檔（副檔名白名單、ETag）
   ├─ Http.java             回應標頭（CSP 等安全標頭）、gzip
   └─ api/                  CompoundApi、MortgageApi、BondApi、CurrencyApi、MetaApi
src/main/resources/static/
├─ index.html               四個分頁的版面
├─ css/app.css              主題色系、字體大小、RWD
├─ js/boot.js               畫面繪製前套用主題與字體，避免閃爍
└─ js/app.js                即時重算、外觀設定、分頁切換
src/test/java/com/fullmoney/CalculatorCheck.java   自我檢查
build.bat / run.bat / test.bat                     不需 Maven 的建置、啟動、檢查
```

分層原則與桌面版相同：`service`、`bean` 不知道網頁的存在，`web` 只負責轉換輸入與輸出。

## API

接受 `POST`（`application/x-www-form-urlencoded`）或 `GET`（查詢字串），回傳 JSON。百分比欄位都以「%」輸入，例如年利率 2.2。

| 路徑 | 參數 |
| --- | --- |
| `GET /api/meta` | 無。回傳版本號與下拉選單選項（幣別與範例匯率、配息頻率、還款方式、提前還款方式） |
| `/api/compound` | `baseAmount`、`rate`、`addAmount`、`years` |
| `/api/mortgage` | `loanAmount`、`rate`、`years`、`graceYears`、`method`、`prepaymentMode`；分段利率以 `ratePeriod` + `rateValue` 成對重複送出，提前還款以 `prepayPeriod` + `prepayAmount` 成對重複送出 |
| `/api/bond` | `currency`、`faceValue`、`buyPrice`、`redemptionPrice`、`couponRate`、`frequency`、`years`、`feeRate`、`taxRate`、`buyExchangeRate`、`couponExchangeRate`、`redemptionExchangeRate`（新台幣不必送匯率） |
| `/api/currency` | `amount`、`from`、`fromRate`、`to`、`toRate`（新台幣不必送匯率） |

條件不合理時回傳 `400`，內容為 `{"error": "給使用者看的訊息", "field": "欄位名稱"}`；清單欄位為 `名稱:索引`，例如 `prepayPeriod:0`。

範例：

```powershell
Invoke-RestMethod "http://localhost:8080/api/mortgage?loanAmount=10000000&rate=2.2&years=30&graceYears=0&method=EQUAL_PAYMENT"
```

## 計算參考值

計算規則與桌面版 `DEVELOPMENT.md` 的「房貸計算器」「債券計算」兩節相同。以下數值由 `test.bat` 自動檢查，也已在瀏覽器實際操作確認：

| 情境 | 結果 |
| --- | --- |
| 複利預設（20 萬、4%、每年加 1 萬、20 年） | 累積財富 $8,736,140.77 |
| 房貸預設（1000 萬、2.2%、30 年、本息平均） | 月付 $37,970、利息總額 $3,669,232 |
| 房貸＋第 25 期起 2.5% | 利息總額 $4,156,549 |
| 房貸＋第 25 期提前還 100 萬，縮短年限 | 26 年 2 個月還清，省 $770,918 |
| 房貸＋第 25 期提前還 100 萬，降低月付 | 月付降為 $33,972，省 $339,153 |
| 債券預設（USD 面額 1 萬、98.5、4.5%、每半年、10 年） | 總報酬率 47.21%、YTM 4.69%、年化 4.74% |
| 債券匯率 32 → 配息 31、到期 30 | 台幣年化 4.07%、匯兌損益 -$24,500 |
| 幣別轉換 1000 USD（匯率 32） | TWD 32,000.00 |

## 新增主題色系

1. `css/app.css`：複製一段 `[data-theme="…"]`，填入全部色票，並加一個 `.swatch--…`。
2. `index.html`：在「主題色系」加一個選項。
3. `js/boot.js`、`js/app.js`：把名稱加入 `THEMES`。

對比度沿用桌面版的規則（WCAG）：`--fg` 對 `--bg`／`--surface`／`--stripe` 至少 7:1；`--muted`、`--accent` 對底色至少 4.5:1；`--on-accent` 對 `--accent`、`--selection-fg` 對 `--selection` 至少 4.5:1。目前六個主題都已實測通過。

## 安全性

- 回應附 Content-Security-Policy（只允許同源的 script、style）、`X-Content-Type-Options`、`X-Frame-Options`。
- 靜態檔只開放白名單副檔名，拒絕 `..`、反斜線等路徑。
- 數字只接受一般寫法、最多 13 位整數與 8 位小數，避免極端輸入耗盡 BigDecimal 運算資源；請求內容上限 64 KB；清單最多 120 筆。

## 已知限制

- 匯率由使用者輸入，不連網抓取；幣別的預設匯率只是範例。
- 沒有帳號，也不保存試算條件，重新整理後回到預設值（外觀設定會保存）。
- 內建 HttpServer 適合個人或內部使用；對外公開時建議放在 Nginx、IIS 等反向代理之後並啟用 HTTPS。
- 網站執行中無法重新建置（jar 檔被占用），請先 `Ctrl+C` 停止。
- `pom.xml` 未以 Maven 實際建置驗證（開發環境沒有安裝 Maven）；`build.bat`、`test.bat` 已驗證。
