# FullMoney 理財試算（網頁版）

以 **Java 17** 開發的響應式（RWD）理財試算網站，手機、平板、電腦都能使用。
計算邏輯沿用桌面版 FullMoney 1.4.0 的 `service` 與 `bean`（複利試算另外加入「原始本金」，其餘未修改公式），**不含大樂透分析**。

**線上版（GitHub Pages）：<https://snick911.github.io/fullCalMoney/>**

同一份程式有兩種執行方式：

| 方式 | 計算在哪裡 | 用途 |
| --- | --- | --- |
| Java 伺服器（`run.bat`） | Java 後端，網頁呼叫 `/api/...` | 本機使用、自行架站 |
| GitHub Pages 靜態網站 | 瀏覽器，由 TeaVM 把同一份 Java 試算程式編譯成 `js/engine.js` | 免費公開網址，不需要伺服器 |

## 功能

| 分頁 | 內容 |
| --- | --- |
| 複利試算 | 原始本金（第 1 年年初投入一次）、每年固定投入金額、每年額外增加投入（第 2 年起每年多投入固定金額）、年利率與年數，試算逐年累積財富 |
| 複利試算（浮動） | 同上，但「每年額外增加投入」與「期望年利率」改為最低～最高區間，每年在區間內隨機取值（年利率取到 0.01%、可為負數；金額取到元）。按「重新計算」或修改條件都會重新產生亂數 |
| 房貸計算 | 本息／本金平均攤還、寬限期、分段利率、提前還款（縮短年限或降低月付），列出每期攤還明細 |
| 債券計算 | 持有到期的配息、總報酬率、到期殖利率（YTM）、年化報酬率；外幣債券換回台幣的報酬與匯兌損益 |
| 幣別轉換 | 透過新台幣換算兩種幣別，可一鍵交換 |
| 投資計畫 | 輸入投資本金、年數＋月數、期望獲利金額，算出需要的年化報酬率，依「建議規則」給出建議標的、風險與調整建議，並列出各類標的試算與逐年試算 |

- 條件一改就自動重算，計算全部在 Java 後端執行。
- 金額欄位離開時自動補千分位，並顯示「萬／億」換算；可輸入全形數字。
- 複利、債券、幣別轉換有「金額不顯示小數（四捨五入）」選項，預設勾選。由 Java 端從完整數值四捨五入到整數（`HALF_UP`），不會先進位到小數兩位再進位；百分比不受影響。房貸本來就四捨五入到元，沒有這個選項。
- 輸入不合理時（例如寬限期不短於年限），欄位標紅並顯示原因，上一次的結果淡化保留。
- **保存試算條件**：每次試算都會把各分頁的條件（含房貸清單、浮動試算的亂數種子）存到瀏覽器的 localStorage，下次開啟時自動還原並重算，看到的就是上次的結果；網址沒有指定分頁時回到上次的分頁。按「恢復預設」回到預設值。詳見下方「資料保存」。
- **外觀設定**（右上角「外觀設定」）：
  - 主題色系 6 種：淺色、護眼暗色、深色、護眼米黃、海洋藍、櫻花粉。
  - 字體大小 4 段：小（14px）、中（16px）、大（19px）、特大（22px）。版面全部以 rem 計算，字體放大時欄位會自動減少欄數。
  - 設定保存在瀏覽器（localStorage）。第一次開啟時依系統的深淺色模式選擇「淺色」或「護眼暗色」。

## 環境需求

| 項目 | 內容 |
| --- | --- |
| JDK | 17 以上 |
| 相依套件 | 無，使用 JDK 內建的 `com.sun.net.httpserver` |
| 建置工具 | 伺服器版不需要；`pom.xml` 可用 Maven 3.9 建置，GitHub Pages 版需要 Maven（下載 TeaVM） |
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

`test.bat` 會建置後執行 `CalculatorCheck`（不依賴測試框架），共 76 項，全部通過時結束代碼為 0：

- 試算結果與桌面版文件記載的數值相同（見下方「計算參考值」）。
- 投資計畫：各級規則的邊界（剛好 5% 是債券、5.02% 是股債平衡）、期間不足的提醒、高風險與彩券的判定、短期目標不誤判成彩券。
- 浮動複利：區間上下限相同時與固定複利相同；相同種子結果相同、不同種子結果不同；每年的值都在區間內；固定種子的結果不可改變（否則使用者保存的種子會算出不同結果）。
- 輸入檢查：拒絕科學記號、超出範圍、位數過多；接受千分位與全形數字。
- 伺服器與瀏覽器端引擎共用的 `ApiEndpoint.execute`：正常結果、錯誤欄位、千分位格式。
- 實際啟動伺服器呼叫 API：正常結果、錯誤欄位、未知路徑、路徑穿越與 `.class` 檔都回 404。

GitHub Actions 每次部署前也會先執行這些檢查，失敗就不會部署。

## 專案結構

```text
src/main/java/com/fullmoney/
├─ WebApp.java              進入點，啟動網站
├─ AppVersion.java          名稱與版本號（頁尾與 /api/meta 由此取得）
├─ bean/                    沿用桌面版：複利、房貸、債券的條件與結果
│                           新增：InvestmentTier（投資計畫的建議規則）、InvestmentPlan（投資計畫結果）
├─ service/                 沿用桌面版：MoneyCalculator、MortgageCalculator、BondCalculator、CurrencyConverter
│                           新增：FloatingMoneyCalculator（浮動複利）、RangeRandom（可重現的亂數，SplitMix64）、InvestmentPlanner（投資計畫）
└─ web/
   ├─ ApiEndpoint.java      一個 API：參數 → 結果，不依賴 HttpServer，伺服器與瀏覽器端引擎共用
   ├─ ApiHandler.java       把 ApiEndpoint 接到 HttpServer：路徑、請求方式、錯誤紀錄
   ├─ WebServer.java        建立 HttpServer、註冊路徑
   ├─ Params.java           表單參數解析與範圍檢查
   ├─ Json.java / Numbers.java   最小 JSON 輸出、千分位格式
   ├─ StaticHandler.java    從 classpath 提供網頁檔（副檔名白名單、ETag）
   ├─ Http.java             回應標頭（CSP 等安全標頭）、gzip
   └─ api/                  ApiEndpoints（清單）、CompoundApi、FloatingCompoundApi、MortgageApi、BondApi、CurrencyApi、PlanApi、MetaApi
src/main/resources/static/
├─ index.html               四個分頁的版面
├─ css/app.css              主題色系、字體大小、RWD
├─ js/boot.js               畫面繪製前套用主題與字體，避免閃爍
├─ js/app.js                即時重算、外觀設定、分頁切換、保存與還原試算條件
└─ js/engine.js             伺服器版的空殼；Pages 建置時換成 TeaVM 編譯的試算引擎
src/pages/java/com/fullmoney/pages/BrowserEngine.java   Pages 版的瀏覽器端引擎（只在 Maven pages 設定檔編譯）
src/test/java/com/fullmoney/CalculatorCheck.java         自我檢查
.github/workflows/pages.yml                              推送到 main 時自動部署 GitHub Pages
build.bat / run.bat / test.bat                           不需 Maven 的建置、啟動、檢查
```

分層原則與桌面版相同：`service`、`bean` 不知道網頁的存在，`web` 只負責轉換輸入與輸出。

`ApiEndpoint` 與它呼叫到的類別（`web.api`、`Params`、`Json`、`Numbers`、`service`、`bean`）也會被編譯成 JavaScript，只能使用 TeaVM 支援的 Java 類別：不要用 `java.text`（`NumberFormat`、`Normalizer`）、`String.format`、`Locale` 與反射（例如 `Enum.valueOf`）。不確定時執行一次 Pages 建置，不支援的類別會在編譯時報錯。

## GitHub Pages

推送到 `main` 後，GitHub Actions（`.github/workflows/pages.yml`）會：

1. 執行自我檢查。
2. `mvn -P pages clean process-classes`：TeaVM 把 `BrowserEngine` 與試算程式編譯成 ES 模組 `target/pages/js/engine.js`，並複製網頁檔到 `target/pages`。
3. 部署到 <https://snick911.github.io/fullCalMoney/>。

網頁載入時用 `import('./engine.js')` 取得引擎：Pages 版有 `call` 函式就在瀏覽器計算；伺服器版的 `engine.js` 是空殼，改呼叫 `/api/...`。兩者的輸入與結果格式完全相同。

本機產生靜態網站（需要 Maven 3.9 以上）：

```powershell
mvn -P pages clean process-classes
```

`target/pages` 必須透過網頁伺服器開啟，直接雙擊 `index.html`（`file://`）時瀏覽器不允許載入 JavaScript 模組。

第一次部署前要在 GitHub 專案的 **Settings → Pages → Build and deployment → Source** 選 **GitHub Actions**。

## API

接受 `POST`（`application/x-www-form-urlencoded`）或 `GET`（查詢字串），回傳 JSON。百分比欄位都以「%」輸入，例如年利率 2.2。

| 路徑 | 參數 |
| --- | --- |
| `GET /api/meta` | 無。回傳版本號、下拉選單選項（幣別與範例匯率、配息頻率、還款方式、提前還款方式）與投資計畫的建議規則 `investmentTiers` |
| `/api/compound` | `initialAmount`（原始本金）、`baseAmount`（每年固定投入金額）、`rate`、`addAmount`（每年額外增加投入）、`years` |
| `/api/floating` | `initialAmount`、`baseAmount`、`addMin`、`addMax`、`rateMin`、`rateMax`（-100～100）、`years`、`seed`（0～999,999,999，相同種子結果相同；不送時隨機產生，回傳的 `seed` 為實際使用的種子） |
| `/api/mortgage` | `loanAmount`、`rate`、`years`、`graceYears`、`method`、`prepaymentMode`；分段利率以 `ratePeriod` + `rateValue` 成對重複送出，提前還款以 `prepayPeriod` + `prepayAmount` 成對重複送出 |
| `/api/bond` | `currency`、`faceValue`、`buyPrice`、`redemptionPrice`、`couponRate`、`frequency`、`years`、`feeRate`、`taxRate`、`buyExchangeRate`、`couponExchangeRate`、`redemptionExchangeRate`（新台幣不必送匯率） |
| `/api/currency` | `amount`、`from`、`fromRate`、`to`、`toRate`（新台幣不必送匯率） |
| `/api/plan` | `principal`（投資本金）、`years`（0～100）、`months`（0～11，合計至少 1 個月）、`profit`（期望獲利金額） |

`/api/compound`、`/api/floating`、`/api/bond`、`/api/currency`、`/api/plan` 可另外送 `round=1`，金額四捨五入到整數；不送時到小數兩位。回傳的 `decimals` 是金額的小數位數（0 或 2）。

條件不合理時回傳 `400`，內容為 `{"error": "給使用者看的訊息", "field": "欄位名稱"}`；清單欄位為 `名稱:索引`，例如 `prepayPeriod:0`。

範例：

```powershell
Invoke-RestMethod "http://localhost:8080/api/mortgage?loanAmount=10000000&rate=2.2&years=30&graceYears=0&method=EQUAL_PAYMENT"
```

## 計算參考值

計算規則與桌面版 `DEVELOPMENT.md` 的「房貸計算器」「債券計算」兩節相同。以下數值由 `test.bat` 自動檢查，也已在瀏覽器實際操作確認：

| 情境 | 結果 |
| --- | --- |
| 複利預設（原始本金 0、每年投入 20 萬、4%、每年加 1 萬、20 年） | 累積財富 $8,736,140.77（不顯示小數時 $8,736,141） |
| 複利＋原始本金 100 萬 | 投入 $6,900,000，累積財富 $10,927,263.92（多出 100 萬 × 1.04²⁰） |
| 浮動複利（20 萬、加 5,000～15,000、2%～6%、20 年、種子 123456789） | 累積財富 $8,514,979 |
| 投資計畫預設（100 萬、5 年、獲利 25 萬） | 需要年化 4.56%，建議債券 |
| 投資計畫 100 萬、10 年、獲利 150 萬 | 需要年化 9.60%，建議股票／基金 |
| 投資計畫 100 萬、2 年、獲利 100 萬 | 需要年化 41.42%，高風險投機（可能不可行），以 12% 估算約需 6 年 2 個月 |
| 投資計畫 100 萬、1 年、獲利 300 萬 | 需要年化 300%，只能靠運氣（不可行） |

## 投資計畫的建議規則

需要的年化報酬率 =（目標金額 ÷ 本金）^（1 ÷ 年數）− 1，年數含月份（2 年 6 個月 = 2.5 年）。依此值由低到高套用第一個符合的規則，規則只定義在 `bean/InvestmentTier.java`，網頁的規則表由 `/api/meta` 取得。

| 需要的年化報酬率 | 建議 | 標的 | 風險 | 建議期間 |
| --- | --- | --- | --- | --- |
| 2% 以下 | 保本型 | 定存、貨幣市場基金、儲蓄險 | 極低 | 不限 |
| 2%～5% | 債券 | 政府公債、投資等級公司債、債券型 ETF／基金 | 低 | 1 年以上 |
| 5%～8% | 股債平衡 | 股債平衡配置、平衡型基金、高股息 ETF | 中 | 3 年以上 |
| 8%～12% | 股票／基金 | 市值型 ETF、股票型基金、績優股（可定期定額） | 中高 | 5 年以上 |
| 12%～20% | 積極成長 | 成長股、產業／主題型基金、槓桿型 ETF | 高 | 5 年以上 |
| 20% 以上 | 高風險投機（可能不可行） | 期貨、選擇權、權證 | 極高 | 短線操作 |
| 100% 以上，且總報酬也超過 100% | 只能靠運氣（不可行） | 大樂透、威力彩等彩券 | 幾乎必定虧損 | — |

- 投資期間短於建議期間時仍給同一級建議，但提醒短期波動並列出「延長到建議年數後需要的年化報酬率」。年數夠長時，總報酬 100%～200% 也會落在股票／基金或積極成長。
- 高風險投機與彩券視為不可行，另以「股票／基金」的 12% 估算三種替代方案：延長期間、增加本金、降低目標。
- 彩券一級另外要求總報酬超過 100%，避免「1 個月賺 10%」這類短期目標年化後超過 100% 而被判成彩券。
- 「各類標的試算」以每一級的年報酬率上限（2%、5%、8%、12%、20%）計算期滿金額與達標所需時間；高風險投機與彩券沒有可預期的報酬率，不列入。
- 這些報酬率是假設值，頁面上也註明不是個人化的投資建議。
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

## 資料保存

| 鍵（localStorage） | 內容 |
| --- | --- |
| `fm.theme`、`fm.font` | 外觀設定 |
| `fm.page` | 上次開啟的分頁 |
| `fm.form.分頁` | 該分頁的試算條件（JSON，含保存時間 `savedAt`），每次試算時寫入 |

用 localStorage 而不是 cookie：

- **沒有到期日**，不需要設定保留天數，會一直保留到使用者清除網站資料（因此超過 180 天）。cookie 必須設定期限，而且 Safari 對網頁程式寫入的 cookie 最多只保留 7 天。
- 資料只存在使用者自己的裝置，不會隨每個請求送到伺服器；GitHub Pages 版也不能由伺服器寫入 cookie。
- 容量約 5 MB，cookie 只有約 4 KB，房貸清單最多 240 筆可能放不下。

瀏覽器本身的限制（cookie 也一樣受限，無法由網站避開）：

- 無痕／私密瀏覽：關閉視窗後清除。
- Safari（iPhone、iPad、Mac）：使用 Safari 期間連續 7 天沒有開啟本網站，會刪除網站寫入的資料。加入主畫面的網頁不受此限。
- 使用者手動清除瀏覽資料，或瀏覽器設定為關閉時清除網站資料。
- 同一台電腦的其他人也看得到上次的試算條件；共用電腦可按「恢復預設」。

## 安全性

- 回應附 Content-Security-Policy（只允許同源的 script、style）、`X-Content-Type-Options`、`X-Frame-Options`。
- 靜態檔只開放白名單副檔名，拒絕 `..`、反斜線等路徑。
- 數字只接受一般寫法、最多 13 位整數與 8 位小數，避免極端輸入耗盡 BigDecimal 運算資源；請求內容上限 64 KB；清單最多 120 筆。

## 已知限制

- 匯率由使用者輸入，不連網抓取；幣別的預設匯率只是範例。
- 沒有帳號，試算條件只保存在各自的瀏覽器，換裝置或換瀏覽器看不到（見「資料保存」）。
- 內建 HttpServer 適合個人或內部使用；對外公開時建議放在 Nginx、IIS 等反向代理之後並啟用 HTTPS。
- 網站執行中無法重新建置（jar 檔被占用），請先 `Ctrl+C` 停止。
- GitHub Pages 版第一次開啟要多下載約 240 KB 的試算引擎（壓縮傳輸後較小）。
