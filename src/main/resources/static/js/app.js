/* 全算利計算器：條件一改變就呼叫後端 API 重算，計算全部在 Java 端 */
'use strict';

(() => {
  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  const NO_VALUE = '—';
  const THEMES = ['light', 'eye-dark', 'dark', 'sepia', 'ocean', 'sakura'];
  const FONTS = ['s', 'm', 'l', 'xl'];
  const MONTHS_PER_YEAR = 12;
  const MAX_PERIOD = 600;
  /** 期數提示的上限：投資計畫最長 100 年 */
  const MAX_HINT_PERIOD = 1200;
  const FIRST_NEW_PERIOD = 13;
  const NEW_PERIOD_STEP = 12;
  const DEFAULT_PREPAYMENT = '100,000';
  const DEBOUNCE_MS = 200;
  /** 與後端相同：房貸清單最多 120 筆、亂數種子最多 9 位數 */
  const MAX_LIST_ROWS = 120;
  const MAX_SEED = 999999999;
  const FORM_STATE_VERSION = 1;
  const root = document.documentElement;
  /** 部署版本：GitHub Pages 建置時換成提交編號；本機伺服器維持 local */
  const buildMeta = document.querySelector('meta[name="build"]');
  const BUILD = (buildMeta && buildMeta.content) || 'local';
  const BUILT_AT = (buildMeta && buildMeta.dataset.built) || '';
  const IS_DEPLOYED = BUILD !== 'local';

  /* ---------- 瀏覽器儲存：讀寫失敗時不影響畫面 ---------- */
  const store = {
    get(key) {
      try { return localStorage.getItem(key); } catch (e) { return null; }
    },
    set(key, value) {
      try { localStorage.setItem(key, value); } catch (e) { /* 忽略 */ }
    },
  };

  /* ---------- 數字格式（固定繁體中文） ---------- */
  const numberFormats = new Map();
  const numberFormat = (digits) => {
    if (!numberFormats.has(digits)) {
      numberFormats.set(digits, new Intl.NumberFormat('zh-TW', { minimumFractionDigits: digits, maximumFractionDigits: digits }));
    }
    return numberFormats.get(digits);
  };
  const percentFormat = new Intl.NumberFormat('zh-TW', { style: 'percent', maximumFractionDigits: 2 });
  const percentFixedFormat = new Intl.NumberFormat('zh-TW', { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const wanFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 4 });
  const ratePercentFormat = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 4 });

  const fmt = {
    /** 不含貨幣符號，用於新台幣以外的金額 */
    amount: (n, digits = 2) => (n == null ? NO_VALUE : numberFormat(digits).format(n)),
    twd: (n, digits = 2) => (n == null ? NO_VALUE : (n < 0 ? '-$' : '$') + numberFormat(digits).format(Math.abs(n))),
    percent: (ratio) => (ratio == null ? NO_VALUE : percentFormat.format(ratio)),
    /** 固定兩位小數 ex. 4.00%，用於平均年利率，避免剛好整數時看起來像沒算到小數 */
    percentFixed: (ratio) => (ratio == null ? NO_VALUE : percentFixedFormat.format(ratio)),
    /** 銀行利率常到小數第三位 ex. 2.185% */
    rate: (percent) => (percent == null ? NO_VALUE : Number(percent).toFixed(3) + '%'),
    /** 浮動利率取到 0.01%，輸入的區間有更多位數時最多顯示到小數第四位 */
    floatRate: (percent) => (percent == null ? NO_VALUE : ratePercentFormat.format(percent) + '%'),
    term(periods) {
      const years = Math.floor(periods / MONTHS_PER_YEAR);
      const months = periods % MONTHS_PER_YEAR;
      if (!months) return `${years} 年`;
      return years ? `${years} 年 ${months} 個月` : `${months} 個月`;
    },
    periodHint(period) {
      if (!Number.isInteger(period) || period < 1 || period > MAX_HINT_PERIOD) return '';
      return `第 ${Math.floor((period - 1) / MONTHS_PER_YEAR) + 1} 年第 ${((period - 1) % MONTHS_PER_YEAR) + 1} 個月`;
    },
    /** 金額換成「萬、億」，方便確認位數 */
    wan(n) {
      if (!Number.isFinite(n) || n < 10000) return '';
      const yi = Math.floor(n / 1e8);
      const wan = (n - yi * 1e8) / 1e4;
      const parts = [];
      if (yi) parts.push(`${numberFormat(0).format(yi)} 億`);
      if (wan) parts.push(`${wanFormat.format(wan)} 萬`);
      return parts.join(' ') + '元';
    },
  };

  const cleanNumber = (text) => String(text).normalize('NFKC').replace(/[,\s_]/g, '');
  const parseNumber = (text) => {
    const clean = cleanNumber(text);
    return clean === '' ? NaN : Number(clean);
  };

  /** 金額欄位離開時補上千分位 */
  function formatMoneyInput(input) {
    const match = /^(\d+)(\.\d*)?$/.exec(cleanNumber(input.value));
    if (!match) return;
    input.value = numberFormat(0).format(Number(match[1])) + (match[2] && match[2] !== '.' ? match[2] : '');
  }

  function updateHint(input) {
    if (!input || !input.matches || !input.matches('[data-kind]')) return;
    const field = input.closest('.field');
    const hint = field && $('[data-hint]', field);
    if (!hint) return;
    const n = parseNumber(input.value);
    hint.textContent = input.dataset.kind === 'money' ? fmt.wan(n) : fmt.periodHint(n);
  }

  const refreshHints = (scope) => $$('[data-kind]', scope).forEach(updateHint);

  const setOut = (scope, name, text) => $$(`[data-out="${name}"]`, scope).forEach((el) => { el.textContent = text; });

  /*
   * ---------- 保存試算條件 ----------
   * 每次試算時把表單存到這台裝置的瀏覽器（localStorage，鍵為 fm.form.分頁），下次開啟時還原。
   * localStorage 沒有到期日，不會像 cookie 一樣過期，也不會隨每個請求送到伺服器。
   */
  const formKey = (id) => `fm.form.${id}`;

  function saveForm(id, form) {
    const fields = [];
    new FormData(form).forEach((value, name) => {
      if (typeof value === 'string') fields.push([name, value]);
    });
    //沒勾選的勾選框不在 FormData 裡，另外記錄
    const checks = {};
    $$('input[type="checkbox"][name]', form).forEach((box) => { checks[box.name] = box.checked; });
    store.set(formKey(id), JSON.stringify({ v: FORM_STATE_VERSION, savedAt: Date.now(), fields, checks }));
  }

  function loadForm(id) {
    let state = null;
    try { state = JSON.parse(store.get(formKey(id))); } catch (e) { return null; }
    if (!state || state.v !== FORM_STATE_VERSION || !Array.isArray(state.fields)) return null;
    state.fields = state.fields.filter((f) => Array.isArray(f) && typeof f[0] === 'string' && typeof f[1] === 'string');
    return state;
  }

  const countField = (state, name) => state.fields.filter(([n]) => n === name).length;

  /** 依保存的順序填回欄位，同名欄位（房貸清單）依序對應 */
  function applyForm(form, state) {
    const used = new Map();
    state.fields.forEach(([name, value]) => {
      const index = used.get(name) || 0;
      used.set(name, index + 1);
      const el = $$(`[name="${CSS.escape(name)}"]`, form)[index];
      if (!el || el.type === 'checkbox') return;
      if (el.tagName === 'SELECT' && !Array.from(el.options).some((o) => o.value === value)) return;
      el.value = value;
    });
    Object.entries(state.checks || {}).forEach(([name, checked]) => {
      const box = $(`input[type="checkbox"][name="${CSS.escape(name)}"]`, form);
      if (box) box.checked = checked === true;
    });
    $$('[data-kind="money"]', form).forEach(formatMoneyInput);
  }

  /* ---------- API ---------- */
  class ApiError extends Error {
    constructor(message, field) {
      super(message);
      this.name = 'ApiError';
      this.field = field || null;
    }
  }

  async function postForm(url, params, signal) {
    let response;
    try {
      response = await fetch(url, { method: 'POST', body: params, signal, headers: { Accept: 'application/json' } });
    } catch (err) {
      if (err.name === 'AbortError') throw err;
      throw new ApiError('無法連線到伺服器，請確認網站仍在執行後再試一次。');
    }
    let data = null;
    try { data = await response.json(); } catch (e) { /* 不是 JSON */ }
    if (!response.ok) {
      throw new ApiError((data && data.error) || `伺服器回應錯誤（${response.status}）。`, data && data.field);
    }
    return data;
  }

  /*
   * GitHub Pages 沒有伺服器：改用 TeaVM 由 Java 試算程式編譯的引擎 js/engine.js，
   * 回傳格式與伺服器 API 相同。伺服器版的 engine.js 只是空殼，仍呼叫 /api/...
   */
  let engineCall = null;

  async function loadEngine() {
    try {
      const engine = await import(IS_DEPLOYED ? `./engine.js?v=${BUILD}` : './engine.js');
      if (typeof engine.call === 'function') engineCall = engine.call;
    } catch (e) {
      /* 載入失敗時改呼叫伺服器 */
    }
  }

  async function callApi(path, params, signal) {
    if (!engineCall) return postForm(path, params, signal);
    let envelope;
    try {
      envelope = JSON.parse(engineCall(path, params.toString()));
    } catch (e) {
      throw new ApiError('試算時發生錯誤，請重新整理頁面後再試。');
    }
    if (envelope.status !== 200) throw new ApiError(envelope.body.error, envelope.body.field);
    return envelope.body;
  }

  /** 錯誤欄位名稱，清單欄位為「名稱:索引」 */
  function fieldElement(form, field) {
    if (!field) return null;
    const [name, index] = field.split(':');
    return $$(`[name="${CSS.escape(name)}"]`, form)[index ? Number(index) : 0] || null;
  }

  /* ---------- 試算頁共用：輸入後延遲重算，只採用最後一次的結果 ---------- */
  /** 等瀏覽器畫完下一個畫面；分頁在背景時 requestAnimationFrame 不會執行，最多等 100 毫秒 */
  const nextPaint = () => new Promise((resolve) => {
    const timer = setTimeout(resolve, 100);
    requestAnimationFrame(() => setTimeout(() => { clearTimeout(timer); resolve(); }, 0));
  });

  class Calculator {
    /**
     * @param beforeRun 每次試算前呼叫（保存與送出之前）
     * @param beforeRestore 還原保存的條件前呼叫，可先建立清單列
     * @param afterRestore 還原後呼叫，用來同步依欄位值顯示的區塊
     * @param manual true：改條件時不自動重算，按「開始試算」（或 Enter）才計算，用在計算量大的頁面
     */
    constructor(id, { render, onReset, beforeRun, beforeRestore, afterRestore, manual = false }) {
      const noop = () => {};
      this.id = id;
      this.section = $(`#page-${id}`);
      this.form = $('form', this.section);
      this.alert = $('.form-alert', this.form);
      this.results = $('[data-results]', this.section);
      this.endpoint = this.form.dataset.endpoint;
      this.renderResult = render;
      this.onReset = onReset || noop;
      this.beforeRun = beforeRun || noop;
      this.beforeRestore = beforeRestore || noop;
      this.afterRestore = afterRestore || noop;
      this.manual = manual;
      this.runButton = $('[data-run]', this.form);
      this.pending = $('[data-pending]', this.form);
      this.loaded = false;
      this.timer = 0;
      this.controller = null;

      this.form.addEventListener('input', (e) => { updateHint(e.target); this.changed(); });
      //勾選框（金額不顯示小數）不必等打字結束，立即重算
      this.form.addEventListener('change', (e) => (e.target.type === 'checkbox' ? this.run() : this.changed()));
      this.form.addEventListener('submit', (e) => { e.preventDefault(); this.run(); });
      this.form.addEventListener('focusout', (e) => {
        if (e.target.matches('[data-kind="money"]')) formatMoneyInput(e.target);
      });
      //reset 事件發生時欄位還沒還原，下一輪再處理
      this.form.addEventListener('reset', () => setTimeout(() => {
        this.onReset();
        refreshHints(this.form);
        this.run();
      }));
      refreshHints(this.form);
    }

    activate() {
      if (!this.loaded) this.run();
    }

    /** 填回上次保存的條件，在第一次試算之前呼叫 */
    restore() {
      const state = loadForm(this.id);
      if (!state) return;
      this.beforeRestore(state);
      applyForm(this.form, state);
      this.afterRestore();
      refreshHints(this.form);
    }

    /** 條件改變：自動模式等打字告一段落再算；手動模式只把結果淡化並提示按按鈕 */
    changed() {
      if (!this.manual) {
        this.schedule();
        return;
      }
      if (this.loaded) this.results.classList.add('is-stale');
      if (this.pending) this.pending.hidden = false;
    }

    schedule() {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.run(), DEBOUNCE_MS);
    }

    /** 清單新增或刪除一列：自動模式立即重算，手動模式只提示按按鈕 */
    edited() {
      if (this.manual) this.changed();
      else this.run();
    }

    /** 計算中：按鈕顯示「試算中…」並停用，避免重複按 */
    setBusy(busy) {
      if (!this.runButton) return;
      this.runButton.disabled = busy;
      $('[data-run-label]', this.runButton).textContent = busy ? '試算中…' : '開始試算';
    }

    async run() {
      clearTimeout(this.timer);
      //timer 不為 0 表示還有等打字結束的重算（下載 PDF 前會先算完）
      this.timer = 0;
      if (this.controller) this.controller.abort();
      const controller = new AbortController();
      this.controller = controller;
      this.beforeRun();
      //條件有誤也照樣保存，下次開啟時看到的是使用者最後輸入的內容
      saveForm(this.id, this.form);
      this.results.setAttribute('aria-busy', 'true');
      if (this.pending) this.pending.hidden = true;
      this.setBusy(true);
      try {
        //瀏覽器端引擎在主執行緒上同步計算，先讓「試算中…」畫出來再開始
        if (this.manual) await nextPaint();
        if (this.controller !== controller) return;
        const data = await callApi(this.endpoint, new URLSearchParams(new FormData(this.form)), controller.signal);
        if (this.controller !== controller) return;
        this.showError(null);
        this.renderResult(data, this.section);
        this.loaded = true;
        this.results.classList.remove('is-stale');
      } catch (err) {
        if (err.name === 'AbortError' || this.controller !== controller) return;
        this.showError(err);
        //保留上一次的結果但淡化，避免打字途中整個畫面跳動
        this.results.classList.add('is-stale');
      } finally {
        if (this.controller === controller) {
          this.results.removeAttribute('aria-busy');
          this.setBusy(false);
        }
      }
    }

    showError(err) {
      $$('[aria-invalid]', this.form).forEach((el) => el.removeAttribute('aria-invalid'));
      if (!err) {
        this.alert.hidden = true;
        this.alert.textContent = '';
        return;
      }
      this.alert.textContent = err.message;
      this.alert.hidden = false;
      const input = fieldElement(this.form, err.field);
      if (input) {
        input.setAttribute('aria-invalid', 'true');
        const details = input.closest('details');
        if (details) details.open = true;
      }
    }
  }

  /* ---------- 複利試算 ---------- */
  //d.decimals：金額的小數位數，勾選「金額不顯示小數」時後端已四捨五入到整數並回傳 0
  function renderCompound(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    setOut(scope, 'totalBaseAmount', twd(d.totalBaseAmount));
    setOut(scope, 'gains', twd(d.gains));
    setOut(scope, 'gainRate', fmt.percent(d.gainRate));
    setOut(scope, 'totalSumAmount', twd(d.totalSumAmount));
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${twd(r[1])}</td><td>${twd(r[2])}</td><td>${twd(r[3])}</td><td>${twd(r[4])}</td></tr>`).join('');
  }

  /* ---------- 複利試算（浮動） ---------- */
  function renderFloating(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    const down = (n) => (n < 0 ? ' class="is-down"' : '');
    setOut(scope, 'totalBaseAmount', twd(d.totalBaseAmount));
    setOut(scope, 'gains', twd(d.gains));
    setOut(scope, 'gainRate', fmt.percent(d.gainRate));
    setOut(scope, 'averageRate', fmt.percentFixed(d.averageRate));
    setOut(scope, 'totalSumAmount', twd(d.totalSumAmount));
    //第 1 年沒有額外增加投入，顯示「—」
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td${down(r[1])}>${fmt.floatRate(r[1])}</td><td>${r[0] === 1 ? NO_VALUE : twd(r[2])}</td>`
      + `<td>${twd(r[3])}</td><td${down(r[4])}>${twd(r[4])}</td><td>${twd(r[5])}</td><td>${twd(r[6])}</td></tr>`).join('');
  }

  function initFloating() {
    const seed = $('#page-floating input[name="seed"]');
    const reseed = () => { seed.value = String(Math.floor(Math.random() * MAX_SEED)); };
    const calculator = new Calculator('floating', {
      render: renderFloating,
      onReset: reseed,
      //第一次開啟還沒有種子；還原時沿用保存的種子，看到的就是上次的結果
      beforeRun: () => { if (!seed.value) reseed(); },
    });
    //條件改變就重新產生亂數；只切換「金額不顯示小數」時保留同一組結果
    calculator.form.addEventListener('input', (e) => {
      if (e.target.type !== 'checkbox') reseed();
    });
    $('[data-reroll]', calculator.form).addEventListener('click', () => {
      reseed();
      calculator.run();
    });
    return calculator;
  }

  /* ---------- 投資計畫 ---------- */
  //需要的報酬率極端時（ex. 1 個月要 100 倍）不顯示一長串數字
  const MAX_SHOWN_RATIO = 100;
  const planRate = (ratio) => (ratio > MAX_SHOWN_RATIO ? `超過 ${percentFormat.format(MAX_SHOWN_RATIO)}` : fmt.percentFixed(ratio));

  const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

  const FIT_TAGS = {
    COMFORTABLE: ['一般行情可達成', ''],
    STRETCH: ['需行情較好', ' option__tag--warn'],
  };
  //模擬達成機率的程度：75% 以上、50% 以上、25% 以上、其他
  const CHANCES = {
    HIGH: ['把握高', ''],
    LIKELY: ['有機會', ''],
    STRETCH: ['需行情較好', ' option__tag--warn'],
    LOW: ['不太可能', ' option__tag--danger'],
  };
  const chancePercent = (ratio) => `${Math.round(Number(ratio) * 100)}%`;

  /** 一個方案的卡片：適合程度、標的、風險、兩種行情的期滿金額 */
  function optionCard(o, twd, tier) {
    const [fitText, fitClass] = FIT_TAGS[o.fit] || [tier === 'LOTTERY' ? '不可行' : '可能不可行', ' option__tag--danger'];
    const tags = `<span class="option__tag${fitClass}">${fitText}</span>`
      + (o.horizonOk ? '' : '<span class="option__tag option__tag--warn">期間短於建議</span>')
      + (o.lowCaseOk ? '' : '<span class="option__tag option__tag--warn">浮動投資只投入下限時不足</span>');
    const projection = o.typicalRate == null ? '' : '<ul class="option__projection">'
      + '<li class="option__projection-head"><span>年報酬率假設</span><span>期滿金額（約）</span><span></span></li>'
      + `<li><span>一般行情 ${fmt.percentFixed(o.typicalRate)}</span><span>${twd(o.typicalEnd)}</span>`
      + `${o.typicalReached ? '<span class="is-reached">達標</span>' : '<span class="is-short">未達標</span>'}</li>`
      + `<li><span>行情好 ${fmt.percentFixed(o.maxRate)}</span><span>${twd(o.maxEnd)}</span><span class="is-reached">達標</span></li>`
      + `<li class="option__probability"><span>模擬達成機率</span><span>${chancePercent(o.probability)}</span><span></span></li></ul>`;
    return `<section class="option" data-fit="${escapeHtml(o.fit)}">`
      + `<div class="option__head"><h3 class="option__name">${escapeHtml(o.name)}</h3>${tags}</div>`
      + `<p class="option__instruments">${escapeHtml(o.instruments)}</p>`
      + `<dl class="option__facts"><div><dt>風險</dt><dd>${escapeHtml(o.risk)}</dd></div><div><dt>建議期間</dt><dd>${escapeHtml(o.horizon)}</dd></div></dl>`
      + `${projection}<p class="option__note">${escapeHtml(o.note)}</p></section>`;
  }

  function renderPlan(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    const warn = d.warnings.length > 0;
    let level = 'ok';
    let verdict = '可行';
    if (!d.feasible) {
      level = 'danger';
      verdict = d.tier === 'LOTTERY' ? '不可行' : '可能不可行';
    } else if (warn) {
      level = 'warn';
      verdict = '可行，但需留意';
    }

    setOut(scope, 'target', twd(d.target));
    setOut(scope, 'invested', twd(d.invested));
    setOut(scope, 'withdrawn', twd(d.withdrawn));
    $('[data-withdrawn-tile]', scope).hidden = !(d.withdrawn > 0);
    setOut(scope, 'totalReturn', planRate(d.totalReturn));
    setOut(scope, 'requiredRate', planRate(d.requiredRate));
    setOut(scope, 'tierName', d.tierName);
    setOut(scope, 'verdict', verdict);
    setOut(scope, 'summary', d.summary);
    $('[data-advice]', scope).dataset.level = level;

    //每月浮動投資：投入上限、預期（中間值）、只投入下限三種情況
    $('[data-floating]', scope).hidden = !d.floating;
    if (d.floating) {
      const cases = [
        ['投入上限', d.floating.high, ''],
        ['預期（中間值）', { monthly: d.monthly, invested: d.invested, rate: d.requiredRate, tierName: d.tierName }, ' class="is-expected"'],
        ['只投入下限', d.floating.low, ''],
      ];
      $('tbody[data-floating-rows]', scope).innerHTML = cases.map(([label, c, attr]) =>
        `<tr${attr}><th scope="row">${label}</th><td>${twd(c.monthly)}</td><td>${twd(c.invested)}</td>`
        + `<td>${c.rate == null ? '沒有投入資金' : planRate(c.rate)}</td><td>${c.tierName == null ? NO_VALUE : escapeHtml(c.tierName)}</td></tr>`).join('');
    }

    $('[data-option-cards]', scope).innerHTML = d.options.map((o) => optionCard(o, twd, d.tier)).join('');

    //資產配置：保守型、穩健型、積極型各自的最佳配置（每 5% 一級搜尋、蒙地卡羅模擬）
    setOut(scope, 'portfolioSummary', d.portfolioSummary);
    const body = $('tbody[data-portfolio-rows]', scope);
    body.innerHTML = d.portfolios.map((p) => {
      const [chanceText, chanceClass] = CHANCES[p.chance];
      const badge = p.recommended ? ' <span class="option__tag">建議</span>' : '';
      const tags = `<span class="option__tag${chanceClass}">${chanceText}</span>`
        + (p.horizonOk ? '' : ` <span class="option__tag option__tag--warn">建議 ${escapeHtml(p.horizon)}</span>`);
      return `<tr${p.recommended ? ' class="is-recommended"' : ''}><th scope="row">${profilePill(p)}${badge}`
        + `<span class="portfolio__count">${p.candidates.toLocaleString('zh-TW')} 種中約 ${p.reachable.toLocaleString('zh-TW')} 種機率過半</span></th>`
        + `<td>${allocationPie(p, 48)}${endStockNote(p)}</td><td>${fmt.percentFixed(p.typicalRate)}</td><td>${fmt.percentFixed(p.volatility)}</td>`
        + `<td><span class="portfolio__chance">${chancePercent(p.probability)}</span><span class="portfolio__tags">${tags}</span></td>`
        + `<td>${twd(p.median)}<span class="portfolio__count">${twd(p.pessimistic)}～${twd(p.optimistic)}</span></td></tr>`;
    }).join('');

    $('[data-items="warnings"]', scope).innerHTML = d.warnings.map((w) => `<li>${escapeHtml(w)}</li>`).join('');
    $('[data-items="suggestions"]', scope).innerHTML = d.suggestions.map((s) => `<li>${escapeHtml(s)}</li>`).join('');
    $('[data-suggestions]', scope).hidden = d.suggestions.length === 0;

    renderScenarios(d, scope);

    //三種配置的逐年試算：各資產金額與獲利，旁邊對照需要的報酬率下的目標進度（d.rows 的金額）
    $('[data-allocation-years]', scope).innerHTML = d.portfolios.map((p) => allocationYears(p, d.rows, twd)).join('');
    renderPlanCharts(d, scope);
  }

  /** 逐年降低股票比例時，標示最後一年的股票比例 */
  const stockPercent = (n) => `${Number(n).toLocaleString('zh-TW', { maximumFractionDigits: 1 })}%`;
  const endStockNote = (p) => (Number(p.endStock) === p.weights[3] ? ''
    : `<span class="portfolio__count">股票逐年降低，期滿時 ${stockPercent(p.endStock)}</span>`);

  /**
   * 策略比較與壓力測試：同樣的最佳配置改用 5 種調整方式的結果（每欄機率最高者標色），
   * 以及壓力情境下的達成機率與金額（箭頭左邊一般情況、右邊壓力情境）
   */
  function renderScenarios(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    setOut(scope, 'settingsText', d.settings.text);
    setOut(scope, 'strategySummary', d.strategySummary);
    $('[data-strategy-head]', scope).innerHTML = '<tr><th scope="col">調整方式</th>'
      + `${d.portfolios.map((p) => `<th scope="col">${profilePill(p)}</th>`).join('')}</tr>`;
    const best = d.portfolios.map((p, i) => Math.max(...d.strategies.map((s) => s.outcomes[i].probability)));
    $('[data-strategy-rows]', scope).innerHTML = d.strategies.map((s) => {
      const tag = s.selected ? '<span class="option__tag">目前設定</span>' : '';
      const cells = s.outcomes.map((o, i) => `<td><span class="portfolio__chance${o.probability >= best[i] ? ' is-best' : ''}">${chancePercent(o.probability)}</span>`
        + `<span class="portfolio__count">中位數 ${twd(o.median)}</span><span class="portfolio__count">悲觀 ${twd(o.pessimistic)}</span></td>`).join('');
      return `<tr${s.selected ? ' class="is-recommended"' : ''}><th scope="row">${escapeHtml(s.name)}${tag}</th>${cells}</tr>`;
    }).join('');

    const stress = $('[data-stress]', scope);
    stress.hidden = !d.stress;
    $('[data-stress-legend]', scope).hidden = !d.stress;
    if (!d.stress) return;
    setOut(scope, 'stressText', d.stress.text);
    const change = (from, to, format) => `${format(from)} → <span class="${to < from ? 'is-down' : 'is-accent'}">${format(to)}</span>`;
    $('[data-stress-rows]', scope).innerHTML = d.portfolios.map((p) => `<tr><th scope="row">${profilePill(p)}</th>`
      + `<td>${change(p.probability, p.stress.probability, chancePercent)}</td><td>${change(p.median, p.stress.median, twd)}</td>`
      + `<td>${change(p.pessimistic, p.stress.pessimistic, twd)}</td></tr>`).join('');
  }

  /** 投資計畫：按按鈕才試算；中途提領清單；配置調整與壓力測試選項的說明 */
  function initPlan(meta) {
    const section = $('#page-plan');
    const glide = $('#p-glide');
    const stress = $('#p-stress');
    const timingField = $('#p-timing').closest('.field');
    const rebalanceHint = $('[data-rebalance-hint]', section);
    const stressDesc = $('[data-stress-desc]', section);
    const descriptions = new Map((meta.stressScenarios || []).map((s) => [s.code, s.description]));
    const sync = () => {
      rebalanceHint.textContent = glide.value && glide.value !== 'FIXED' ? '逐年調整比例時，每年依新比例調整' : '';
      const description = descriptions.get(stress.value);
      stressDesc.textContent = description ? `${stress.selectedOptions[0].text}：${description}。` : '';
      stressDesc.hidden = !description;
      timingField.hidden = !description;
    };
    const plan = new Calculator('plan', {
      render: renderPlan,
      manual: true,
      onReset: () => { withdrawList.clear(); sync(); },
      beforeRestore: (state) => withdrawList.restore(countField(state, 'withdrawMonth')),
      afterRestore: sync,
    });
    const withdrawList = initPeriodList(plan, 'withdraw', 'tpl-withdraw-row', () => '500,000', 36);
    glide.addEventListener('change', sync);
    stress.addEventListener('change', sync);

    //推薦範例：帶入全部條件與模擬設定並清空中途提領；配置調整不是固定比例時列在條件摘要
    const glideNames = new Map((meta.glidePaths || []).map((g) => [g.code, g.label]));
    const wan = (n) => (n >= 1e4 ? `${rangeWanFormat.format(n / 1e4)} 萬` : `${numberFormat(0).format(n)} 元`);
    const fields = ['principal', 'monthlyFixed', 'floatMin', 'floatMax', 'years', 'months', 'profit', 'age', 'glide', 'rebalance', 'distribution', 'stress', 'stressTiming'];
    initPresets(plan, meta.planPresets, {
      card: (p) => ({
        tag: `目標 ${wan(p.target)}`,
        terms: [`本金 ${wan(p.principal)}`, `每月 ${wan(p.monthlyFixed)}`, `${p.years} 年`, ...(p.glide === 'FIXED' ? [] : [glideNames.get(p.glide) || ''])],
      }),
      values: (p) => Object.fromEntries(fields.map((name) => [name, p[name]])),
      extraMatch: () => $$('[name="withdrawMonth"]', plan.form).length === 0,
      beforeApply: () => withdrawList.clear(),
      afterApply: sync,
    });
    sync();
    return plan;
  }

  /* ---------- 投資計畫圖表：手寫 SVG（網站的 CSP 只允許同源腳本，不載入外部圖表函式庫） ---------- */

  /** 圖表寬度：平常依容器寬度；列印（下載 PDF）時固定為紙張寬度，字級才與畫面相同 */
  let printChartWidth = 0;
  const chartWidth = (box) => Math.max(printChartWidth || box.clientWidth, 280);

  /** 座標軸用的精簡金額 ex. 2,150,000 → 215 萬、120,000,000 → 1.2 億 */
  const compactMoney = (n) => {
    const abs = Math.abs(n);
    if (abs >= 1e8) return `${(n / 1e8).toFixed(abs >= 1e9 ? 0 : 1).replace(/\.0$/, '')} 億`;
    if (abs >= 1e4) return `${Math.round(n / 1e4).toLocaleString('zh-TW')} 萬`;
    return Math.round(n).toLocaleString('zh-TW');
  };

  /** 刻度取 1、2、5 × 10 的次方，涵蓋 min～max */
  function niceTicks(min, max, count) {
    const rough = (max - min || Math.abs(max) || 1) / count;
    const power = 10 ** Math.floor(Math.log10(rough));
    const step = [1, 2, 5, 10].map((m) => m * power).find((s) => s >= rough * (1 - 1e-9));
    const ticks = [];
    for (let v = Math.floor(min / step) * step; v <= max + step * 0.999; v += step) ticks.push(Math.round(v / step) * step);
    return ticks;
  }

  /** 提示框：用 textContent 填字；位置用 CSSOM 設定（CSP 不允許 style 屬性） */
  function chartTip(box) {
    let tip = $('.chart__tip', box);
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'chart__tip';
      tip.hidden = true;
      box.appendChild(tip);
    }
    return {
      show(title, rows, x, y) {
        tip.replaceChildren();
        const head = document.createElement('div');
        head.className = 'chart__tip-title';
        head.textContent = title;
        tip.appendChild(head);
        rows.forEach(({ value, label, key }) => {
          const row = document.createElement('div');
          row.className = 'chart__tip-row';
          const line = document.createElement('i');
          line.className = key;
          const strong = document.createElement('b');
          strong.textContent = value;
          const name = document.createElement('span');
          name.textContent = label;
          row.append(line, strong, name);
          tip.appendChild(row);
        });
        tip.hidden = false;
        const left = x + 14 + tip.offsetWidth > box.clientWidth ? x - 14 - tip.offsetWidth : x + 14;
        tip.style.left = `${Math.max(0, left)}px`;
        tip.style.top = `${Math.max(0, y)}px`;
      },
      hide() { tip.hidden = true; },
    };
  }

  /** 端點標籤互相太近時往下推開，並記錄原位置以畫連接線 */
  function spreadLabels(labels, minGap, bottom) {
    labels.sort((a, b) => a.y - b.y);
    labels.forEach((label, i) => {
      label.labelY = i > 0 ? Math.max(label.y, labels[i - 1].labelY + minGap) : label.y;
    });
    const overflow = labels.length ? labels[labels.length - 1].labelY - bottom : 0;
    if (overflow > 0) labels.forEach((label) => { label.labelY -= overflow; });
    return labels;
  }

  /**
   * 累積財富走勢：三型最佳配置的總金額（一般行情）逐年折線，加上目標進度虛線與累積投入淡色面積；
   * 線尾直接標示類型名稱，滑鼠或手指移動時縱線對齊最近的年度並列出每條線的金額
   */
  function growthChart(box, d) {
    const width = chartWidth(box);
    const height = width < 480 ? 240 : 290;
    const m = { top: 14, right: width < 480 ? 64 : 84, bottom: 28, left: width < 480 ? 46 : 58 };
    const plotW = width - m.left - m.right;
    const plotH = height - m.top - m.bottom;
    const principal = Math.max(0, d.invested - d.monthly * d.months);
    const months = [0, ...d.rows.map((r) => r[0])];
    const target = [principal, ...d.rows.map((r) => r[2])];
    const invested = [principal, ...d.rows.map((r) => r[1])];
    const series = d.portfolios.map((p) => ({ code: p.code, name: p.name, values: [principal, ...p.rows.map((r) => r[10])] }));
    const top = Math.max(...target, ...series.flatMap((s) => s.values)) * 1.04;
    const yTicks = niceTicks(0, top, width < 480 ? 4 : 5);
    const yMax = yTicks[yTicks.length - 1];
    const x = (month) => m.left + (month / d.months) * plotW;
    const y = (v) => m.top + plotH - (v / yMax) * plotH;
    const points = (values) => values.map((v, i) => `${x(months[i]).toFixed(1)},${y(v).toFixed(1)}`).join(' ');

    //年度刻度：最多約 8 個
    const years = d.months / 12;
    const yearStep = [1, 2, 5, 10, 20, 25, 50].find((s) => years / s <= (width < 480 ? 5 : 8)) || 50;
    const xTicks = years >= 1 ? Array.from({ length: Math.floor(years / yearStep) + 1 }, (_, i) => i * yearStep * 12) : [0, d.months];
    const xLabel = (month) => (years >= 1 ? `${month / 12} 年` : `${month} 個月`);

    const grid = yTicks.map((v) => `<line class="chart__grid" x1="${m.left}" x2="${m.left + plotW}" y1="${y(v)}" y2="${y(v)}"></line>`
      + `<text x="${m.left - 6}" y="${y(v) + 4}" text-anchor="end">${compactMoney(v)}</text>`).join('');
    const axis = `<line class="chart__axis" x1="${m.left}" x2="${m.left + plotW}" y1="${y(0)}" y2="${y(0)}"></line>`
      + xTicks.map((t) => `<text x="${x(t)}" y="${height - 8}" text-anchor="middle">${xLabel(t)}</text>`).join('');
    const area = `<polygon class="chart__invested" points="${x(0)},${y(0)} ${points(invested)} ${x(d.months)},${y(0)}"></polygon>`;
    const lines = series.map((s) => `<polyline class="chart__line profile--${s.code}" points="${points(s.values)}"></polyline>`).join('')
      + `<polyline class="chart__target" points="${points(target)}"></polyline>`;
    //中途提領：在提領月份畫一條細虛線
    const withdrawn = [0, ...d.rows.map((r) => r[5] || 0)];
    const events = (d.settings.withdrawals || []).map(([month]) => `<line class="chart__event" x1="${x(month)}" x2="${x(month)}" y1="${m.top}" y2="${m.top + plotH}"></line>`
      + `<text class="chart__label chart__event-label" x="${x(month)}" y="${m.top + 10}" text-anchor="middle">提領</text>`).join('');

    //線尾標籤：類型名稱用文字色，旁邊的圓點才是類型色
    const last = months.length - 1;
    const ends = spreadLabels([
      ...series.map((s) => ({ y: y(s.values[last]), text: s.name, code: s.code })),
      { y: y(target[last]), text: '目標', code: null },
    ], 15, m.top + plotH);
    const endX = x(d.months);
    const endLabels = ends.map((e) => (e.code
      ? `<circle class="chart__dot profile--${e.code}" cx="${endX}" cy="${e.y}" r="4"></circle>` : '')
      + (Math.abs(e.labelY - e.y) > 1 ? `<line class="chart__leader" x1="${endX + 5}" y1="${e.y}" x2="${endX + 10}" y2="${e.labelY}"></line>` : '')
      + `<text class="chart__label" x="${endX + 12}" y="${e.labelY + 4}">${e.text}</text>`).join('');

    box.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="累積財富走勢圖，數值見下方逐年試算表">`
      + `${grid}${area}${axis}${events}${lines}${endLabels}`
      + `<line class="chart__cross" data-cross x1="0" x2="0" y1="${m.top}" y2="${m.top + plotH}" visibility="hidden"></line>`
      + series.map((s) => `<circle class="chart__dot profile--${s.code}" data-hover="${s.code}" r="4" visibility="hidden"></circle>`).join('')
      + `<rect class="chart__hit" x="${m.left}" y="${m.top}" width="${plotW}" height="${plotH}"></rect></svg>`;

    //十字線：對齊最近的年度，一個提示框列出所有線的金額
    const svg = $('svg', box);
    const cross = $('[data-cross]', svg);
    const tip = chartTip(box);
    const twd = (n) => fmt.twd(n, d.decimals);
    const moveTo = (event) => {
      const rect = svg.getBoundingClientRect();
      const px = (event.clientX - rect.left) * (width / rect.width);
      let index = 0;
      months.forEach((month, i) => { if (Math.abs(x(month) - px) < Math.abs(x(months[index]) - px)) index = i; });
      const cx = x(months[index]);
      cross.setAttribute('x1', cx);
      cross.setAttribute('x2', cx);
      cross.setAttribute('visibility', 'visible');
      series.forEach((s) => {
        const dot = $(`[data-hover="${s.code}"]`, svg);
        dot.setAttribute('cx', cx);
        dot.setAttribute('cy', y(s.values[index]));
        dot.setAttribute('visibility', 'visible');
      });
      const rows = [...series.map((s) => ({ value: twd(s.values[index]), label: s.name, key: `legend-line profile--${s.code}` })),
        { value: twd(target[index]), label: '目標進度', key: 'legend-line legend-line--target' },
        { value: twd(invested[index]), label: '累積投入', key: 'legend-area' },
        ...(withdrawn[index] > 0 ? [{ value: twd(withdrawn[index]), label: '累積提領', key: 'legend-event' }] : [])]
        .sort((a, b) => parseNumber(b.value.replace('$', '')) - parseNumber(a.value.replace('$', '')));
      tip.show(index === 0 ? '開始' : fmt.term(months[index]), rows, cx * (rect.width / width), m.top);
    };
    const hide = () => {
      cross.setAttribute('visibility', 'hidden');
      $$('[data-hover]', svg).forEach((dot) => dot.setAttribute('visibility', 'hidden'));
      tip.hide();
    };
    const hit = $('.chart__hit', svg);
    hit.addEventListener('pointermove', moveTo);
    hit.addEventListener('pointerdown', moveTo);
    hit.addEventListener('pointerleave', hide);
  }

  /**
   * 期滿金額區間：每型一列，線段為悲觀（第 10 百分位）到樂觀（第 90 百分位），圓點為中位數，
   * 虛線為目標金額；滑過一列顯示三個金額與達成機率
   */
  function rangeChart(box, d) {
    const width = chartWidth(box);
    const narrow = width < 480;
    //有壓力測試時，每列多一條虛線區間（同一組走勢、情境年份換成情境報酬）
    const stressed = d.portfolios.some((p) => p.stress);
    const rowH = stressed ? 66 : 48;
    const m = { top: 24, right: 18, bottom: 26, left: narrow ? 58 : 70 };
    const height = m.top + rowH * d.portfolios.length + m.bottom;
    const plotW = width - m.left - m.right;
    const values = d.portfolios.flatMap((p) => [p.pessimistic, p.optimistic, ...(p.stress ? [p.stress.pessimistic, p.stress.optimistic] : [])])
      .concat(d.target);
    const low = Math.min(...values);
    const high = Math.max(...values);
    const pad = (high - low) * 0.06 || high * 0.05 || 1;
    const ticks = niceTicks(Math.max(0, low - pad), high + pad, narrow ? 3 : 5);
    const min = ticks[0];
    const max = ticks[ticks.length - 1];
    const x = (v) => m.left + ((v - min) / (max - min)) * plotW;
    const twd = (n) => fmt.twd(n, d.decimals);

    const grid = ticks.map((v) => `<line class="chart__grid" x1="${x(v)}" x2="${x(v)}" y1="${m.top}" y2="${height - m.bottom}"></line>`
      + `<text x="${x(v)}" y="${height - 8}" text-anchor="middle">${compactMoney(v)}</text>`).join('');
    const rows = d.portfolios.map((p, i) => {
      const middle = m.top + rowH * i + rowH / 2;
      const cy = stressed ? middle - 4 : middle;
      const sy = middle + 16;
      const stress = p.stress ? `<line class="chart__range chart__range--stress" x1="${x(p.stress.pessimistic)}" x2="${x(p.stress.optimistic)}" y1="${sy}" y2="${sy}"></line>`
        + `<circle class="chart__median chart__median--stress" cx="${x(p.stress.median)}" cy="${sy}" r="4"></circle>` : '';
      return `<g class="profile--${p.code}">`
        + `<rect class="chart__row-hit" data-row="${i}" x="0" y="${middle - rowH / 2}" width="${width}" height="${rowH}"></rect>`
        + `<circle class="chart__dot" cx="10" cy="${cy}" r="4"></circle>`
        + `<text class="chart__label" x="20" y="${cy + 4}">${escapeHtml(p.name)}</text>`
        + `<line class="chart__range" x1="${x(p.pessimistic)}" x2="${x(p.optimistic)}" y1="${cy}" y2="${cy}"></line>`
        + `<circle class="chart__median" cx="${x(p.median)}" cy="${cy}" r="6"></circle>`
        + `<text class="chart__value" x="${x(p.median)}" y="${cy - 11}" text-anchor="middle">${compactMoney(p.median)}</text>`
        + `${stress}</g>`;
    }).join('');
    const targetX = x(d.target);
    const target = `<line class="chart__target" x1="${targetX}" x2="${targetX}" y1="${m.top - 4}" y2="${height - m.bottom}"></line>`
      + `<text class="chart__value" x="${Math.min(Math.max(targetX, m.left + 30), width - 30)}" y="${m.top - 9}" text-anchor="middle">目標 ${compactMoney(d.target)}</text>`;

    box.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="期滿金額區間圖，數值見資產配置表">`
      + `${grid}${target}${rows}</svg>`;

    const svg = $('svg', box);
    const tip = chartTip(box);
    $$('[data-row]', svg).forEach((hit) => {
      const p = d.portfolios[Number(hit.dataset.row)];
      const show = () => {
        const rect = svg.getBoundingClientRect();
        const scale = rect.width / width;
        const key = `legend-line profile--${p.code}`;
        const stressKey = 'legend-range legend-range--stress';
        const title = `${p.name}，達成機率 ${chancePercent(p.probability)}`
          + (p.stress ? `（壓力情境 ${chancePercent(p.stress.probability)}）` : '');
        tip.show(title, [
          { value: twd(p.optimistic), label: '樂觀（最好 10%）', key },
          { value: twd(p.median), label: '中位數', key },
          { value: twd(p.pessimistic), label: '悲觀（最差 10%）', key },
          ...(p.stress ? [
            { value: twd(p.stress.median), label: '壓力情境中位數', key: stressKey },
            { value: twd(p.stress.pessimistic), label: '壓力情境悲觀', key: stressKey },
          ] : []),
          { value: twd(d.target), label: '目標', key: 'legend-line legend-line--target' },
        ], Math.max(x(p.optimistic), p.stress ? x(p.stress.optimistic) : 0) * scale, (m.top + rowH * Number(hit.dataset.row)) * scale);
      };
      hit.addEventListener('pointerenter', show);
      hit.addEventListener('pointerdown', show);
      hit.addEventListener('pointerleave', () => tip.hide());
    });
  }

  /** 圖例與兩張圖；視窗大小改變時用最後一次的結果重畫 */
  let lastPlan = null;
  function renderPlanCharts(d, scope) {
    lastPlan = { d, scope };
    $('[data-growth-legend]', scope).innerHTML = d.portfolios.map((p) =>
      `<span class="profile--${escapeHtml(p.code)}"><i class="legend-line"></i>${escapeHtml(p.name)}</span>`).join('')
      + '<span><i class="legend-line legend-line--target"></i>目標進度</span><span><i class="legend-area"></i>累積投入</span>'
      + (d.settings.withdrawals.length ? '<span><i class="legend-event"></i>中途提領</span>' : '');
    growthChart($('[data-growth-chart]', scope), d);
    rangeChart($('[data-range-chart]', scope), d);
  }
  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (lastPlan && !lastPlan.scope.hidden) renderPlanCharts(lastPlan.d, lastPlan.scope);
    }, DEBOUNCE_MS);
  });

  const ASSET_NAMES = ['定存', '公債', '公司債', '股票'];

  /** 配置類型標籤：圓點與邊框是類型色，文字是一般文字色 */
  const profilePill = (p) => `<span class="profile-pill profile--${escapeHtml(p.code)}">${escapeHtml(p.name)}</span>`;

  /** 資產名稱，股票附上等級 ex. 股票（穩健型股票） */
  const assetLabel = (p, i) => (i === 3 && p.stockLevelName ? `股票（${escapeHtml(p.stockLevelName)}）` : ASSET_NAMES[i]);

  /**
   * 資產配置的甜甜圈圖：圓周長設為 100，比例直接當線段長度；每段之間留約 2px 間隔，從 12 點鐘方向順時針排列。
   * 旁邊列出各資產的名稱與比例（顏色不單獨表達意義），滑過每段顯示名稱與比例
   */
  function allocationPie(p, size) {
    const radius = 15.9155;
    const parts = p.weights.map((w, i) => ({ w, i })).filter((part) => part.w > 0);
    const gap = parts.length > 1 ? 1.6 : 0;
    let start = 0;
    const segments = parts.map(({ w, i }) => {
      const offset = (125 - start) % 100;
      start += w;
      return `<circle class="asset-${i + 1}" cx="21" cy="21" r="${radius}" stroke-dasharray="${Math.max(w - gap, 0.1)} ${100 - Math.max(w - gap, 0.1)}"`
        + ` stroke-dashoffset="${offset}"><title>${assetLabel(p, i)} ${w}%</title></circle>`;
    }).join('');
    const label = parts.map(({ w, i }) => `${ASSET_NAMES[i]} ${w}%`).join('、');
    const legend = parts.map(({ w, i }) => `<li><i class="swatch-dot asset-${i + 1}"></i>${assetLabel(p, i)} ${w}%</li>`).join('');
    return `<span class="allocation-pie"><svg class="donut" viewBox="0 0 42 42" width="${size}" height="${size}" role="img" aria-label="${label}">`
      + `<circle class="donut__track" cx="21" cy="21" r="${radius}"></circle>${segments}</svg><ul class="parts">${legend}</ul></span>`;
  }

  /**
   * 一種配置的逐年試算表；p.rows 每列為 [月, 累積投入, 定存、公債、公司債、股票的金額（4 欄）, 四者的累積獲利（4 欄）, 總金額, 累積獲利, 累積提領]
   */
  function allocationYears(p, targetRows, twd) {
    const count = ASSET_NAMES.length;
    //整個期間都沒有的資產顯示「—」（逐年降低股票時，公債可能從 0 開始增加）
    const unused = ASSET_NAMES.map((name, i) => p.rows.every((r) => r[2 + i] === 0 && r[2 + count + i] === 0));
    const withdrawals = p.rows.some((r) => r[4 + count * 2] > 0);
    const asset = (r, i) => (unused[i] ? `<td class="is-muted">${NO_VALUE}</td>`
      : `<td>${twd(r[2 + i])}<span class="asset-gain">獲利 ${twd(r[2 + count + i])}</span></td>`);
    const body = p.rows.map((r, i) => {
      const total = r[2 + count * 2];
      const target = targetRows[i] ? targetRows[i][2] : null;
      const ahead = target == null || total >= target;
      return `<tr><td>${fmt.term(r[0])}</td><td>${twd(r[1])}</td>${withdrawals ? `<td>${twd(r[4 + count * 2])}</td>` : ''}`
        + `${ASSET_NAMES.map((name, j) => asset(r, j)).join('')}`
        + `<td class="${ahead ? 'is-accent' : 'is-down'}">${twd(total)}</td><td>${twd(r[3 + count * 2])}</td>`
        + `<td>${target == null ? NO_VALUE : twd(target)}</td></tr>`;
    }).join('');
    const badge = p.recommended ? '<span class="option__tag">建議</span>' : '';
    //欄位標題前加上與圓餅相同顏色的小方塊
    const head = (i) => `<th scope="col"><span class="asset-head"><i class="swatch-dot asset-${i + 1}"></i>${ASSET_NAMES[i]}</span>`
      + (i === 3 && p.stockLevelName ? `<span class="asset-gain">${escapeHtml(p.stockLevelName)}</span>` : '') + '</th>';
    return `<div class="card card--flush profile-card profile--${escapeHtml(p.code)}">`
      + `<div class="card__head"><h2>${profilePill(p)} 逐年試算${badge}</h2>`
      + `<span class="profile-card__summary">${allocationPie(p, 56)}<span class="card__meta">達成機率 ${chancePercent(p.probability)}`
      + `${Number(p.endStock) === p.weights[3] ? '' : `，股票逐年降到 ${stockPercent(p.endStock)}`}</span></span></div>`
      + `<div class="table-wrap" tabindex="0" aria-label="${escapeHtml(p.name)}逐年試算，可捲動"><table class="data-table">`
      + `<thead><tr><th scope="col">期間</th><th scope="col">累積投入</th>${withdrawals ? '<th scope="col">累積提領</th>' : ''}`
      + `${ASSET_NAMES.map((name, i) => head(i)).join('')}`
      + '<th scope="col">總金額</th><th scope="col">累積獲利</th><th scope="col">目標進度</th></tr></thead>'
      + `<tbody>${body}</tbody></table></div></div>`;
  }

  /* ---------- 樂透投資 ---------- */
  const lotteryGames = new Map();
  const oddsFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 1 });
  const countFormat = new Intl.NumberFormat('zh-TW', { maximumSignificantDigits: 3 });

  const yiFormat = new Intl.NumberFormat('zh-TW', { maximumSignificantDigits: 3 });

  /** 機率：千分之一以上顯示百分比，更小時顯示「約 N 分之 1」，N 超過 1 億時以億為單位 */
  const chanceText = (p) => {
    if (p == null) return NO_VALUE;
    if (p <= 0) return '0%';
    if (p > 0.9999) return '超過 99.99%';
    if (p >= 0.001) return fmt.percentFixed(p);
    const n = 1 / p;
    return n >= 1e8 ? `約 ${yiFormat.format(n / 1e8)} 億分之 1` : `約 ${numberFormat(0).format(Math.round(n))} 分之 1`;
  };

  /** 回本機率常小到沒有意義，不到 1 兆分之 1 就不列數字 */
  const tinyChance = (p) => (p < 1e-12 ? '不到 1 兆分之 1' : chanceText(p));

  /** 只靠這個獎回本要中幾個 ex. 6 個；每注獎金不到每注價格時不可能回本 */
  const breakEvenText = (p, totalBets) => {
    if (!p.breakEvenPossible) return '不可能';
    return `${numberFormat(0).format(p.breakEvenWins)} 個${p.breakEvenWins === totalBets ? '（每一注都要中）' : ''}`;
  };

  /** 預計中獎次數：很少時保留三位有效數字 ex. 0.0217 次 */
  const winsText = (n) => {
    if (n >= 10) return `${numberFormat(0).format(Math.round(n))} 次`;
    if (n >= 0.0001) return `${countFormat.format(n)} 次`;
    return '不到 0.0001 次';
  };

  function renderLottery(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    const cents = (n) => fmt.twd(n, 2);
    const sim = d.simulation;
    const jackpot = d.prizes[0];
    const smallest = d.prizes[d.prizes.length - 1];

    setOut(scope, 'totalSpent', twd(d.totalSpent));
    setOut(scope, 'returnRate', fmt.percentFixed(d.returnRate));
    setOut(scope, 'returnRateAfterTax', fmt.percentFixed(d.returnRateAfterTax));
    setOut(scope, 'expectedBack', twd(d.expectedBack));
    setOut(scope, 'expectedLoss', twd(d.expectedLoss));
    setOut(scope, 'simMedian', fmt.percentFixed(sim.medianRate));
    setOut(scope, 'breakEven', chanceText(sim.breakEvenChance));
    setOut(scope, 'jackpotChance', chanceText(jackpot.chance));
    setOut(scope, 'compareValue', twd(d.rows[d.rows.length - 1][4]));

    const lossPer100 = numberFormat(1).format(100 * (1 - d.returnRateAfterTax));
    setOut(scope, 'lotterySummary', `${d.gameName}每注 ${d.price} 元，把每個獎項的中獎機率乘上獎金再加起來，每注平均拿回 ${cents(d.perBet)}`
      + `（稅前回報率 ${fmt.percentFixed(d.returnRate)}），扣稅後約 ${cents(d.perBetAfterTax)}（${fmt.percentFixed(d.returnRateAfterTax)}），`
      + `也就是每買 100 元平均虧 ${lossPer100} 元。`);

    const years = `${d.years} 年`;
    const jackpotSource = jackpot.estimated ? `以長期平均約 ${compactMoney(jackpot.amount)} 估算`
      : d.jackpotInput ? `以輸入的 ${compactMoney(jackpot.amount)} 計算` : `固定 ${compactMoney(jackpot.amount)}`;
    const compare = d.rows[d.rows.length - 1][4];
    const points = [
      `每次買 ${numberFormat(0).format(d.betsPerPurchase)} 注（${twd(d.spendPerPurchase)}），${escapeHtml(d.frequency)}，一年 ${d.timesPerYear} 次；`
        + `${years}共 ${numberFormat(0).format(d.totalBets)} 注、投入 ${twd(d.totalSpent)}，平均拿回 ${twd(d.expectedBack)}、虧損 ${twd(d.expectedLoss)}。`,
      `平均每 ${oddsFormat.format(d.anyPrizeOdds)} 注中一次獎，${years}預計中獎約 ${winsText(d.expectedPrizeCount)}，`
        + `但大多是${escapeHtml(smallest.name)}（${twd(smallest.amount)}）這類小獎。`,
      `頭獎機率 1/${numberFormat(0).format(jackpot.odds)}（${jackpotSource}），${years}內至少中一次頭獎的機率${jackpot.chance >= 0.001 ? '為 ' : ''}${chanceText(jackpot.chance)}。`,
      `要回本（投入 ${twd(d.totalSpent)}，以稅後獎金計算）：${d.prizes.slice(0, Math.min(4, d.prizes.length - 1))
        .map((p) => `${escapeHtml(p.name)}${p.breakEvenPossible ? `要中 ${numberFormat(0).format(p.breakEvenWins)} 個` : '不可能'}`).join('、')}；`
        + `最小的${escapeHtml(smallest.name)}${smallest.breakEvenPossible ? `要中 ${breakEvenText(smallest, d.totalBets)}` : '怎麼中都回不了本'}。`,
      `模擬 ${numberFormat(0).format(sim.paths)} 人照同樣方式買：中間那個人拿回 ${twd(sim.median)}（回報率 ${fmt.percentFixed(sim.medianRate)}），`
        + `最差的 10% 只拿回 ${twd(sim.pessimistic)} 以下，沒有虧損的人${sim.breakEvenChance > 0 ? `只有 ${chanceText(sim.breakEvenChance)}` : '一個也沒有'}。`
        + (sim.medianRate < d.returnRateAfterTax - 0.02 ? '一般人拿回的比期望回報率少，是因為少數人中了大獎把平均拉高。' : ''),
      `同樣的錢在每次買彩券時改成定期投資，年報酬率 ${fmt.percent(d.compareRate)} 時 ${years}後約 ${twd(compare)}，比買彩券平均拿回的多 ${twd(compare - d.expectedBack)}。`,
    ];
    $('[data-lottery-points]', scope).innerHTML = points.map((p) => `<li>${p}</li>`).join('');

    const sub = (text) => `<span class="asset-gain">${text}</span>`;
    //中幾個才回本：只靠同一個獎項，稅後獎金累計到總投入
    setOut(scope, 'breakEvenMeta', `投入 ${twd(d.totalSpent)}，只靠同一個獎項、以稅後獎金計算`);
    $('tbody[data-break-even-rows]', scope).innerHTML = d.prizes.map((p) =>
      `<tr><th scope="row">${escapeHtml(p.name)}</th><td>${p.estimated ? '約 ' : ''}${twd(p.afterTax)}${p.estimated ? sub('長期平均') : ''}</td>`
      + `<td class="is-accent">${breakEvenText(p, d.totalBets)}${p.breakEvenPossible ? '' : sub(`每注獎金不到 ${d.price} 元`)}</td>`
      + `<td>${winsText(p.expectedWins)}</td><td>${p.breakEvenPossible ? tinyChance(p.breakEvenChance) : NO_VALUE}</td></tr>`).join('');

    setOut(scope, 'prizeMeta', `每注 ${d.price} 元，稅後平均拿回 ${cents(d.perBetAfterTax)}`);
    $('tbody[data-prize-rows]', scope).innerHTML = d.prizes.map((p, i) => {
      let note = '';
      if (p.estimated) note = sub('長期平均');
      else if (i === 0 && d.jackpotInput) note = sub('輸入的金額');
      return `<tr><th scope="row">${escapeHtml(p.name)}</th><td>${escapeHtml(p.rule)}</td><td>1/${oddsFormat.format(p.odds)}</td>`
        + `<td>${p.estimated ? '約 ' : ''}${twd(p.amount)}${note}</td>`
        + `<td>${twd(p.afterTax)}${p.taxed ? sub('扣 20.4% 稅') : ''}</td>`
        + `<td>${cents(p.expectedValue)}</td><td>${fmt.percent(p.share)}</td>`
        + `<td>${winsText(p.expectedWins)}</td><td>${chanceText(p.chance)}</td></tr>`;
    }).join('');
    $('tfoot[data-prize-foot]', scope).innerHTML = `<tr><th scope="row">合計</th><td>任一獎項</td><td>1/${oddsFormat.format(d.anyPrizeOdds)}</td>`
      + `<td></td><td></td><td>${cents(d.perBetAfterTax)}</td><td>${fmt.percent(1)}</td><td>${winsText(d.expectedPrizeCount)}</td><td>${chanceText(d.anyPrizeChance)}</td></tr>`;

    setOut(scope, 'rowsMeta', `每年買 ${d.timesPerYear} 次，共 ${numberFormat(0).format(d.totalBets)} 注`);
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${twd(r[1])}</td><td>${twd(r[2])}</td><td class="is-down">${twd(r[3])}</td><td>${twd(r[4])}</td></tr>`).join('');
  }

  /** 樂透投資：依彩券顯示每注價格、可買幾注、一年買幾次；頭獎金額只有大樂透與威力彩可以輸入 */
  function initLottery(meta) {
    (meta.lotteryGames || []).forEach((g) => lotteryGames.set(g.code, g));
    const section = $('#page-lottery');
    const game = $('#l-game');
    const amount = $('#l-amount');
    const frequency = $('#l-frequency');
    const jackpot = $('#l-jackpot');
    const jackpotField = $('[data-jackpot-field]', section);
    const sync = () => {
      const g = lotteryGames.get(game.value);
      if (!g) return;
      $('[data-game-hint]', section).textContent = `每注 ${g.price} 元，${g.schedule}`;

      const n = parseNumber(amount.value);
      let betsHint = '';
      if (Number.isFinite(n)) {
        const bets = Math.floor(n / g.price);
        const left = Math.round((n - bets * g.price) * 100) / 100;
        const wan = fmt.wan(n);
        betsHint = bets < 1 ? `至少 ${g.price} 元（一注）`
          : `可買 ${numberFormat(0).format(bets)} 注${left > 0 ? `，剩 ${fmt.amount(left, left % 1 ? 2 : 0)} 元不夠一注` : ''}${wan ? `（${wan}）` : ''}`;
      }
      $('[data-bets-hint]', section).textContent = betsHint;

      const times = { EVERY_DRAW: g.drawsPerWeek * 52, WEEKLY: 52, MONTHLY: 12 }[frequency.value];
      $('[data-times-hint]', section).textContent = times ? `一年 ${times} 次` : '';

      //今彩539 頭獎固定 800 萬，不必輸入；停用的欄位不會送出
      const pool = g.jackpotAverage != null;
      jackpotField.hidden = !pool;
      jackpot.disabled = !pool;
      if (pool) {
        const typed = parseNumber(jackpot.value);
        $('[data-jackpot-hint]', section).textContent = Number.isFinite(typed) ? fmt.wan(typed)
          : `空白時以長期平均約 ${compactMoney(g.jackpotAverage)} 估算，保底 ${compactMoney(g.jackpotMinimum)}`;
      }
    };
    const calculator = new Calculator('lottery', { render: renderLottery, onReset: sync, afterRestore: sync });
    //各彩券的頭獎金額差很多，換彩券時清除上一個輸入的頭獎
    game.addEventListener('change', () => {
      jackpot.value = '';
      sync();
    });
    frequency.addEventListener('change', sync);
    [amount, jackpot].forEach((input) => input.addEventListener('input', sync));
    sync();
    return calculator;
  }

  /* ---------- 定期定額 ---------- */
  const dcaTargets = new Map();
  const feeRateFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 4 });
  const rangeWanFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 1 });

  /** 投入間隔 ex. 每月、每 2 個月、每季 */
  const intervalText = (k) => ({ 1: '每月', 3: '每季', 6: '每半年', 12: '每年' }[k] || `每 ${k} 個月`);
  /** 一年投入幾次：12 的因數時是整數，否則到小數一位 */
  const timesPerYearText = (k) => (12 % k === 0 ? `一年 ${12 / k} 次` : `一年約 ${rangeWanFormat.format(12 / k)} 次`);
  /** 費率 ex. 0.1425%、1% */
  const feeRateText = (ratio) => `${feeRateFormat.format(ratio * 100)}%`;
  /** 手續費常只有幾元，有小數時顯示到分 */
  const feeMoney = (n) => fmt.twd(n, n % 1 ? 2 : 0);
  /** 期末市值區間的精簡金額 ex. 77.7 萬、1.23 億 */
  const rangeMoney = (n) => {
    if (Math.abs(n) >= 1e8) return `${numberFormat(2).format(n / 1e8)} 億`;
    if (Math.abs(n) >= 1e4) return `${rangeWanFormat.format(n / 1e4)} 萬`;
    return numberFormat(0).format(Math.round(n));
  };

  function renderDca(d, scope) {
    const twd = (n) => fmt.twd(n, d.decimals);
    const single = d.scenarios.length === 1;
    const mid = d.scenarios.find((s) => s.code === 'MID');
    const low = d.scenarios[0];
    const high = d.scenarios[d.scenarios.length - 1];
    const m = d.matrix;

    $$('[data-mid-label]', scope).forEach((el) => { el.hidden = single; });
    $('[data-range-tile]', scope).hidden = single;
    $('[data-scenario-card]', scope).hidden = single;
    setOut(scope, 'cost', twd(mid.cost));
    setOut(scope, 'value', twd(mid.value));
    //兩端各自不斷行，空間不夠時只在「～」後面換行
    $('[data-out="valueRange"]', scope).innerHTML = `<span class="tile__part">${rangeMoney(low.value)}～</span><span class="tile__part">${rangeMoney(high.value)}</span>`;
    setOut(scope, 'profit', twd(mid.profit));
    const profitTile = $('[data-profit-tile]', scope);
    profitTile.classList.toggle('tile--loss', mid.profit < 0);
    profitTile.classList.toggle('tile--gain', mid.profit > 0);
    setOut(scope, 'irr', fmt.percentFixed(mid.irr));
    setOut(scope, 'count', `${numberFormat(0).format(d.count)} 次`);
    setOut(scope, 'totalReturn', fmt.percentFixed(mid.totalReturn));
    setOut(scope, 'fees', twd(mid.fees));
    setOut(scope, 'sellCost', twd(mid.sellCost));

    //試算結果：投入方式、期末市值區間、報酬率與金額的影響、費用
    const range = (a, b, format) => (a === b ? format(a) : `${format(a)}～${format(b)}`);
    const pct = fmt.percent;
    const points = [
      `${intervalText(d.interval)}投入一次（${timesPerYearText(d.interval)}），${d.years} 年共 ${numberFormat(0).format(d.count)} 次；`
        + `每期 ${range(d.amountMin, d.amountMax, twd)}${single ? '' : `，以中間的 ${twd(mid.amount)} 計算`}，總投入 ${twd(mid.cost)}`
        + `${mid.fees > 0 ? `（本金 ${twd(mid.invested)}＋手續費 ${twd(mid.fees)}）` : ''}。`,
      single
        ? `年化報酬率 ${pct(mid.rate)} 時，期末市值約 ${twd(mid.value)}，獲利 ${twd(mid.profit)}（總報酬率 ${fmt.percentFixed(mid.totalReturn)}）。`
        : `年化報酬率 ${range(d.rateMin, d.rateMax, pct)}：期末市值約 ${twd(low.value)}（保守）～${twd(high.value)}（樂觀）；`
          + `中間情境（每期 ${twd(mid.amount)}、年化 ${pct(mid.rate)}）約 ${twd(mid.value)}，獲利 ${twd(mid.profit)}（總報酬率 ${fmt.percentFixed(mid.totalReturn)}）。`,
    ];
    if (mid.profit > 0) {
      points.push(`期末市值中有 ${fmt.percent(mid.profit / mid.value)} 是投資獲利，其餘是投入的本金與手續費。`);
    }
    const effects = [];
    if (m.rateSpread != null) {
      effects.push(`同樣每期 ${twd(m.amounts[m.midAmount])}，年化 ${pct(d.rateMin)} 與 ${pct(d.rateMax)} 的期末市值相差 ${twd(m.rateSpread)}`);
    }
    if (m.amountSpread != null) {
      effects.push(`同樣年化 ${pct(m.rates[m.midRate])}，每期 ${twd(d.amountMin)} 與 ${twd(d.amountMax)} 相差 ${twd(m.amountSpread)}`);
    }
    if (effects.length) points.push(`${effects.join('；')}。`);
    if (mid.fees > 0 || mid.sellCost > 0) {
      const gap = (mid.rate - mid.irr) * 100;
      const buy = mid.fees > 0 ? `每次買入手續費約 ${feeMoney(mid.fee)}（${feeRateText(d.buyFee)}${d.minFee > 0 ? `，最低 ${feeMoney(d.minFee)}` : ''}），共 ${twd(mid.fees)}`
        : '買入不收手續費';
      const sell = mid.sellCost > 0 ? `期末賣出費用 ${feeRateText(d.sellFee)} 約 ${twd(mid.sellCost)}，賣出後拿回 ${twd(mid.net)}` : '期末賣出不收費用';
      points.push(`${escapeHtml(d.targetName)}：${buy}；${sell}。扣除買賣費用後的實際年化報酬率為 ${fmt.percentFixed(mid.irr)}，`
        + `${gap >= 0.005 ? `比預估的 ${pct(mid.rate)} 少 ${numberFormat(2).format(gap)} 個百分點` : `與預估的 ${pct(mid.rate)} 相差不到 0.01 個百分點`}。`);
    } else {
      points.push(`未計手續費與賣出費用，實際年化報酬率等於預估的 ${pct(mid.rate)}。`);
    }
    $('[data-dca-points]', scope).innerHTML = points.map((p) => `<li>${p}</li>`).join('');

    $('tbody[data-scenario-rows]', scope).innerHTML = d.scenarios.map((s) =>
      `<tr${s.code === 'MID' ? ' class="is-recommended"' : ''}><th scope="row">${escapeHtml(s.name)}</th><td>${twd(s.amount)}</td><td>${pct(s.rate)}</td>`
      + `<td>${twd(s.cost)}</td><td class="is-accent">${twd(s.value)}</td><td${s.profit < 0 ? ' class="is-down"' : ''}>${twd(s.profit)}</td>`
      + `<td>${fmt.percentFixed(s.totalReturn)}</td><td>${twd(s.net)}</td><td>${fmt.percentFixed(s.irr)}</td></tr>`).join('');

    //期末市值對照表：列為年化報酬率、欄為每期金額，格內是期末市值與獲利
    $('[data-matrix-card]', scope).hidden = m.amounts.length === 1 && m.rates.length === 1;
    setOut(scope, 'matrixMeta', `${intervalText(d.interval)}投入、${d.years} 年；格內為期末市值與獲利`);
    const sub = (text) => `<span class="asset-gain">${text}</span>`;
    $('thead[data-matrix-head]', scope).innerHTML = '<tr><th scope="col">年化報酬率＼每期投資</th>'
      + m.amounts.map((a, i) => `<th scope="col">${twd(a)}${sub(`總投入 ${twd(m.costs[i])}`)}</th>`).join('') + '</tr>';
    $('tbody[data-matrix-rows]', scope).innerHTML = m.rates.map((rate, r) => `<tr><th scope="row">${pct(rate)}</th>`
      + m.amounts.map((a, i) => `<td${r === m.midRate && i === m.midAmount ? ' class="is-accent"' : ''}>${twd(m.values[r][i])}`
        + `${sub(`獲利 ${twd(m.profits[r][i])}`)}</td>`).join('') + '</tr>').join('');

    renderDcaChart(d, scope);

    const heads = single ? ['年度', '累積次數', '累積投入', '年底市值', '累積獲利']
      : ['年度', '累積次數', '累積投入（中間）', '年底市值（中間）', '累積獲利（中間）', '保守：年底市值', '樂觀：年底市值'];
    $('thead[data-year-head]', scope).innerHTML = `<tr>${heads.map((h) => `<th scope="col">${h}</th>`).join('')}</tr>`;
    $('tbody[data-rows]', scope).innerHTML = mid.rows.map((r, i) =>
      `<tr><td>${r[0]}</td><td>${numberFormat(0).format(r[1])}</td><td>${twd(r[2])}</td><td class="is-accent">${twd(r[3])}</td>`
      + `<td${r[4] < 0 ? ' class="is-down"' : ''}>${twd(r[4])}</td>`
      + `${single ? '' : `<td>${twd(low.rows[i][3])}</td><td>${twd(high.rows[i][3])}</td>`}</tr>`).join('');
    setOut(scope, 'rowsMeta', single ? `${intervalText(d.interval)} ${twd(mid.amount)}、年化 ${pct(mid.rate)}`
      : `保守：每期 ${twd(low.amount)}、${pct(low.rate)}；樂觀：每期 ${twd(high.amount)}、${pct(high.rate)}`);
  }

  /**
   * 累積市值走勢：中間情境實線、保守～樂觀區間帶、累積投入淡色面積；
   * 滑鼠或手指移動時縱線對齊最近的年度，列出各情境的市值
   */
  function dcaChart(box, d) {
    const width = chartWidth(box);
    const narrow = width < 480;
    const height = narrow ? 240 : 290;
    const single = d.scenarios.length === 1;
    const m = { top: 14, right: single ? 16 : (narrow ? 44 : 52), bottom: 28, left: narrow ? 46 : 58 };
    const plotW = width - m.left - m.right;
    const plotH = height - m.top - m.bottom;
    const years = d.years;
    //第 0 年：剛投入第一筆
    const series = d.scenarios.map((s) => ({ code: s.code, name: s.name, values: [s.amount, ...s.rows.map((r) => r[3])] }));
    const mid = series.find((s) => s.code === 'MID');
    const midScenario = d.scenarios.find((s) => s.code === 'MID');
    const invested = [midScenario.amount + midScenario.fee, ...midScenario.rows.map((r) => r[2])];
    const top = Math.max(...series.flatMap((s) => s.values), ...invested) * 1.04;
    const yTicks = niceTicks(0, top, narrow ? 4 : 5);
    const yMax = yTicks[yTicks.length - 1];
    const x = (year) => m.left + (year / years) * plotW;
    const y = (v) => m.top + plotH - (v / yMax) * plotH;
    const coords = (values) => values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);
    const points = (values) => coords(values).join(' ');

    const yearStep = [1, 2, 5, 10, 20, 25, 50].find((s) => years / s <= (narrow ? 5 : 8)) || 50;
    const xTicks = Array.from({ length: Math.floor(years / yearStep) + 1 }, (_, i) => i * yearStep);
    const grid = yTicks.map((v) => `<line class="chart__grid" x1="${m.left}" x2="${m.left + plotW}" y1="${y(v)}" y2="${y(v)}"></line>`
      + `<text x="${m.left - 6}" y="${y(v) + 4}" text-anchor="end">${compactMoney(v)}</text>`).join('');
    const axis = `<line class="chart__axis" x1="${m.left}" x2="${m.left + plotW}" y1="${y(0)}" y2="${y(0)}"></line>`
      + xTicks.map((t) => `<text x="${x(t)}" y="${height - 8}" text-anchor="middle">${t} 年</text>`).join('');
    const area = `<polygon class="chart__invested" points="${x(0)},${y(0)} ${points(invested)} ${x(years)},${y(0)}"></polygon>`;
    const low = series[0];
    const high = series[series.length - 1];
    const band = single ? '' : `<polygon class="chart__band" points="${points(high.values)} ${coords(low.values).reverse().join(' ')}"></polygon>`
      + `<polyline class="chart__edge" points="${points(low.values)}"></polyline><polyline class="chart__edge" points="${points(high.values)}"></polyline>`;
    const line = `<polyline class="chart__line" points="${points(mid.values)}"></polyline>`;

    //線尾標籤：保守、中間、樂觀
    const endX = x(years);
    const endLabels = single ? '' : spreadLabels(series.map((s) => ({ y: y(s.values[years]), text: s.name })), 15, m.top + plotH)
      .map((e) => (Math.abs(e.labelY - e.y) > 1 ? `<line class="chart__leader" x1="${endX + 3}" y1="${e.y}" x2="${endX + 8}" y2="${e.labelY}"></line>` : '')
        + `<text class="chart__label" x="${endX + 10}" y="${e.labelY + 4}">${escapeHtml(e.text)}</text>`).join('');

    box.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="累積市值走勢圖，數值見下方逐年明細">`
      + `${grid}${area}${band}${axis}${line}${endLabels}`
      + `<line class="chart__cross" data-cross x1="0" x2="0" y1="${m.top}" y2="${m.top + plotH}" visibility="hidden"></line>`
      + `<circle class="chart__dot" data-hover r="4" visibility="hidden"></circle>`
      + `<rect class="chart__hit" x="${m.left}" y="${m.top}" width="${plotW}" height="${plotH}"></rect></svg>`;

    const svg = $('svg', box);
    const cross = $('[data-cross]', svg);
    const dot = $('[data-hover]', svg);
    const tip = chartTip(box);
    const twd = (n) => fmt.twd(n, d.decimals);
    const moveTo = (event) => {
      const rect = svg.getBoundingClientRect();
      const px = (event.clientX - rect.left) * (width / rect.width);
      const index = Math.max(0, Math.min(years, Math.round(((px - m.left) / plotW) * years)));
      const cx = x(index);
      cross.setAttribute('x1', cx);
      cross.setAttribute('x2', cx);
      cross.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', cx);
      dot.setAttribute('cy', y(mid.values[index]));
      dot.setAttribute('visibility', 'visible');
      const rows = [...series].reverse().map((s) => ({
        value: twd(s.values[index]),
        label: single ? '市值' : `${s.name}市值`,
        key: s.code === 'MID' ? 'legend-line' : 'legend-line legend-line--edge',
      }));
      rows.push({ value: twd(invested[index]), label: single ? '累積投入' : '累積投入（中間）', key: 'legend-area' });
      tip.show(index === 0 ? '第一筆投入' : `第 ${index} 年底`, rows, cx * (rect.width / width), m.top);
    };
    const hide = () => {
      cross.setAttribute('visibility', 'hidden');
      dot.setAttribute('visibility', 'hidden');
      tip.hide();
    };
    const hit = $('.chart__hit', svg);
    hit.addEventListener('pointermove', moveTo);
    hit.addEventListener('pointerdown', moveTo);
    hit.addEventListener('pointerleave', hide);
  }

  /** 圖例與走勢圖；視窗大小改變時用最後一次的結果重畫 */
  let lastDca = null;
  function renderDcaChart(d, scope) {
    lastDca = { d, scope };
    const single = d.scenarios.length === 1;
    $('[data-dca-legend]', scope).innerHTML = single
      ? '<span><i class="legend-line"></i>市值</span><span><i class="legend-area"></i>累積投入</span>'
      : '<span><i class="legend-line"></i>中間情境</span><span><i class="legend-band"></i>保守～樂觀</span><span><i class="legend-area"></i>累積投入（中間）</span>';
    setOut(scope, 'chartMeta', single ? '' : '區間帶的下緣是保守情境、上緣是樂觀情境');
    dcaChart($('[data-dca-chart]', scope), d);
  }
  let dcaResizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(dcaResizeTimer);
    dcaResizeTimer = setTimeout(() => {
      if (lastDca && !lastDca.scope.hidden) renderDcaChart(lastDca.d, lastDca.scope);
    }, DEBOUNCE_MS);
  });

  /** 定期定額：換標的類型時帶入預設費用；欄位下方顯示一年投入幾次、總次數與每次手續費；推薦範例帶入全部條件 */
  function initDca(meta) {
    (meta.dcaTargets || []).forEach((t) => dcaTargets.set(t.code, t));
    const section = $('#page-dca');
    const target = $('#d-target');
    const interval = $('#d-interval');
    const years = $('#d-years');
    const amountMin = $('#d-amount-min');
    const amountMax = $('#d-amount-max');
    const buyFee = $('#d-buy-fee');
    const minFee = $('#d-min-fee');
    const sellFee = $('#d-sell-fee');
    const applyFees = () => {
      const t = dcaTargets.get(target.value);
      if (!t) return;
      buyFee.value = String(t.buyFee);
      minFee.value = String(t.minFee);
      sellFee.value = String(t.sellFee);
    };
    const sync = () => {
      const t = dcaTargets.get(target.value);
      $('[data-target-hint]', section).textContent = t ? t.description : '';

      const k = Number(interval.value);
      const y = parseNumber(years.value);
      const total = Number.isInteger(y) && y > 0 ? `，${y} 年共 ${numberFormat(0).format(Math.ceil((y * 12) / k))} 次` : '';
      $('[data-interval-hint]', section).textContent = `${timesPerYearText(k)}${total}`;

      //每次手續費以中間金額估算，與試算結果的中間情境相同
      const low = parseNumber(amountMin.value);
      const high = parseNumber(amountMax.value);
      const rate = parseNumber(buyFee.value);
      const least = parseNumber(minFee.value);
      let feeHint = '';
      if ([low, high, rate, least].every(Number.isFinite) && high >= low && rate >= 0 && least >= 0) {
        const amount = (low + high) / 2;
        feeHint = `每次約 ${feeMoney(Math.max((amount * rate) / 100, least))}（以每期 ${feeMoney(amount)} 計算）`;
      }
      $('[data-fee-hint]', section).textContent = feeHint;
    };
    const calculator = new Calculator('dca', {
      render: renderDca,
      onReset: () => { applyFees(); sync(); },
      afterRestore: sync,
    });
    target.addEventListener('change', () => { applyFees(); sync(); });
    interval.addEventListener('change', sync);
    [years, amountMin, amountMax, buyFee, minFee].forEach((input) => input.addEventListener('input', sync));

    //範例的費用用標的類型的預設值，與換標的類型時相同
    const money0 = (n) => numberFormat(0).format(n);
    initPresets(calculator, meta.dcaPresets, {
      card: (p) => ({
        tag: p.targetName,
        terms: [`${intervalText(p.interval)} ${money0(p.amountMin)}～${money0(p.amountMax)} 元`, `${p.years} 年`, `年化 ${p.rateMin}%～${p.rateMax}%`],
      }),
      values: (p) => {
        const t = dcaTargets.get(p.target) || {};
        return {
          target: p.target, interval: p.interval, years: p.years, amountMin: p.amountMin, amountMax: p.amountMax,
          rateMin: p.rateMin, rateMax: p.rateMax, buyFee: t.buyFee, minFee: t.minFee, sellFee: t.sellFee,
        };
      },
      afterApply: sync,
    });
    applyFees();
    sync();
    return calculator;
  }

  /*
   * ---------- 推薦範例（定期定額、投資計畫） ----------
   * 保守、穩健、積極三個按鈕，選項來自 /api/meta；按一下帶入全部條件並立即試算。
   * 條件與某個範例完全相同時，該按鈕標示「使用中」；改任何欄位、新增提領、恢復預設或還原保存的條件後都會重新比對。
   */
  /**
   * @param card 範例 → { tag: 名稱旁的標籤, terms: [條件摘要，每段各自不斷行] }，說明文字用範例的 description
   * @param values 範例 → { 欄位名稱: 值 }，選單比對字串、其他欄位比對數值；金額欄位填入時補千分位
   * @param extraMatch 欄位以外也要符合的條件（ex. 沒有中途提領）
   * @param beforeApply 填入欄位之前呼叫（ex. 清空提領清單）
   * @param afterApply 填入欄位之後、試算之前呼叫（ex. 更新欄位提示）
   */
  function initPresets(calculator, presets, { card, values, extraMatch = () => true, beforeApply = () => {}, afterApply = () => {} }) {
    const list = $('[data-presets]', calculator.form);
    const items = presets || [];
    list.closest('.presets').hidden = !items.length;
    list.innerHTML = items.map((p) => {
      const c = card(p);
      return `<button type="button" class="preset profile--${escapeHtml(p.code)}" data-preset="${escapeHtml(p.code)}" aria-pressed="false">`
        + `<span class="preset__head"><span class="preset__name">${escapeHtml(p.label)}</span>`
        + `${c.tag ? `<span class="preset__tag">${escapeHtml(c.tag)}</span>` : ''}<span class="preset__current">使用中</span></span>`
        + `<span class="preset__desc">${escapeHtml(p.description)}</span>`
        + `<span class="preset__terms">${c.terms.map((t) => `<span>${escapeHtml(t)}</span>`).join('・')}</span></button>`;
    }).join('');

    const field = (name) => calculator.form.elements.namedItem(name);
    const same = (el, value) => (el.tagName === 'SELECT' ? el.value === String(value) : parseNumber(el.value) === Number(value));
    const mark = () => {
      const extra = extraMatch();
      $$('[data-preset]', list).forEach((button) => {
        const p = items.find((x) => x.code === button.dataset.preset);
        const match = extra && Object.entries(values(p)).every(([name, value]) => {
          const el = field(name);
          return !!el && same(el, value);
        });
        button.setAttribute('aria-pressed', String(match));
      });
    };

    list.addEventListener('click', (e) => {
      const button = e.target.closest('[data-preset]');
      const p = button && items.find((x) => x.code === button.dataset.preset);
      if (!p) return;
      beforeApply(p);
      Object.entries(values(p)).forEach(([name, value]) => {
        const el = field(name);
        if (el) el.value = el.dataset.kind === 'money' ? numberFormat(0).format(value) : String(value);
      });
      afterApply(p);
      refreshHints(calculator.form);
      mark();
      calculator.run();
    });
    //click：新增或刪除提領列；reset 事件發生時欄位還沒還原，等 Calculator 的處理之後再比對
    ['input', 'change', 'click'].forEach((type) => calculator.form.addEventListener(type, mark));
    calculator.form.addEventListener('reset', () => setTimeout(mark));
    //還原保存的條件在各分頁初始化之後才進行，接在原本的 afterRestore 後面
    const afterRestore = calculator.afterRestore;
    calculator.afterRestore = () => {
      afterRestore();
      mark();
    };
    mark();
  }

  /* ---------- 房貸計算 ---------- */
  let rowSequence = 0;

  /** 可增減「期數 + 數值」的清單，用於分段利率、提前還款與中途提領；first 為第一筆的預設期數 */
  function initPeriodList(calculator, key, templateId, defaultValue, first = FIRST_NEW_PERIOD) {
    const box = $(`details[data-list="${key}"]`, calculator.form);
    const rows = $('[data-rows]', box);
    const badge = $('[data-count]', box);
    const template = $(`#${templateId}`);

    const updateCount = () => {
      badge.textContent = rows.children.length;
      badge.hidden = rows.children.length === 0;
    };

    const createRow = () => {
      const row = template.content.firstElementChild.cloneNode(true);
      $$('label[data-for]', row).forEach((label) => {
        const input = $('input', label.closest('.field'));
        input.id = `${key}-${++rowSequence}`;
        label.htmlFor = input.id;
      });
      rows.appendChild(row);
      return row;
    };

    $('[data-add]', box).addEventListener('click', () => {
      const last = rows.lastElementChild;
      const lastPeriod = last ? parseNumber($('[data-kind="period"]', last).value) : first - NEW_PERIOD_STEP;
      const period = Math.min((Number.isInteger(lastPeriod) ? lastPeriod : 1) + NEW_PERIOD_STEP, MAX_PERIOD);
      const row = createRow();
      const [periodInput, valueInput] = $$('input', row);
      periodInput.value = period;
      valueInput.value = defaultValue();
      refreshHints(row);
      updateCount();
      periodInput.focus();
      calculator.edited();
    });

    rows.addEventListener('click', (e) => {
      const button = e.target.closest('[data-remove]');
      if (!button) return;
      button.closest('.period-row').remove();
      updateCount();
      calculator.edited();
    });

    return {
      clear() {
        rows.replaceChildren();
        updateCount();
      },
      /** 還原時先建立空白列，數值由 applyForm 依序填入；有資料就展開 */
      restore(count) {
        rows.replaceChildren();
        for (let i = 0; i < Math.min(count, MAX_LIST_ROWS); i++) createRow();
        updateCount();
        if (count > 0) box.open = true;
      },
    };
  }

  function renderMortgage(d, scope) {
    setOut(scope, 'gracePayment', fmt.twd(d.gracePayment, 0));
    setOut(scope, 'firstPayment', fmt.twd(d.firstPayment, 0));
    setOut(scope, 'totalInterest', fmt.twd(d.totalInterest, 0));
    setOut(scope, 'totalPayment', fmt.twd(d.totalPayment, 0));

    //沒有提前還款時隱藏，把高度留給下方明細
    $('[data-prepay-summary]', scope).hidden = !(d.prepaid > 0);
    const earlier = d.originalPeriods - d.periods;
    setOut(scope, 'term', fmt.term(d.periods));
    setOut(scope, 'prepaid', fmt.twd(d.prepaid, 0));
    setOut(scope, 'savedInterest', fmt.twd(d.savedInterest, 0));
    setOut(scope, 'earlier', earlier > 0 ? fmt.term(earlier) : NO_VALUE);

    const note = $('[data-note]', scope);
    const warn = d.warnings.length > 0;
    note.textContent = warn ? d.warnings.join(' ') : note.dataset.default;
    note.classList.toggle('note--warn', warn);

    setOut(scope, 'rowCount', d.rows.length ? `共 ${d.rows.length} 期` : '');
    const body = $('tbody[data-rows]', scope);
    if (!d.rows.length) {
      body.innerHTML = '<tr><td colspan="8" class="empty">貸款金額為 0，沒有還款明細。</td></tr>';
      return;
    }
    let previousRate = null;
    body.innerHTML = d.rows.map((r) => {
      const rateChanged = previousRate !== null && r[2] !== previousRate;
      previousRate = r[2];
      return `<tr><td>${r[0]}</td><td>${r[1]}</td><td${rateChanged ? ' class="is-accent"' : ''}>${fmt.rate(r[2])}</td>`
        + `<td>${fmt.twd(r[3], 0)}</td><td>${fmt.twd(r[4], 0)}</td><td>${fmt.twd(r[5], 0)}</td>`
        + `<td${r[6] != null ? ' class="is-accent"' : ''}>${r[6] == null ? '' : fmt.twd(r[6], 0)}</td><td>${fmt.twd(r[7], 0)}</td></tr>`;
    }).join('');
  }

  /* ---------- 債券計算 ---------- */
  const BOND_SUMMARY = [
    ['cost', '投入成本', 'money'],
    ['couponTotal', '配息總額', 'money'],
    ['redemption', '到期回收', 'money'],
    ['profit', '總損益', 'money'],
    ['annualCouponReturn', '年配息率', 'percent'],
    ['couponReturn', '配息報酬率', 'percent'],
    ['totalReturn', '總報酬率', 'percent'],
    ['annualReturn', '年化報酬率', 'percent'],
  ];

  function renderBond(d, scope) {
    scope.classList.toggle('is-domestic', !d.foreign);
    //債券幣別不一定是新台幣，不能一律加上 $ 符號
    const twd = (n) => fmt.twd(n, d.decimals);
    const money = d.foreign ? (n) => fmt.amount(n, d.decimals) : twd;
    setOut(scope, 'codeLabel', d.currency);
    setOut(scope, 'codeHead', `${d.currency} ${d.currencyLabel}`);
    setOut(scope, 'ytm', fmt.percent(d.yieldToMaturity));
    setOut(scope, 'bondAnnual', fmt.percent(d.bond.annualReturn));
    setOut(scope, 'homeAnnual', fmt.percent(d.home.annualReturn));
    setOut(scope, 'exchangeGain', twd(d.exchangeGain));

    $('tbody[data-summary-rows]', scope).innerHTML = BOND_SUMMARY.map(([key, label, kind]) => {
      const bond = kind === 'percent' ? fmt.percent(d.bond[key]) : money(d.bond[key]);
      const home = kind === 'percent' ? fmt.percent(d.home[key]) : twd(d.home[key]);
      return `<tr><th scope="row">${label}</th><td>${bond}</td><td data-foreign>${home}</td></tr>`;
    }).join('');

    setOut(scope, 'rowCount', `共 ${d.rows.length} 期`);
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${r[1]}</td><td>${money(r[2])}</td><td${r[3] != null ? ' class="is-accent"' : ''}>${r[3] == null ? '' : money(r[3])}</td>`
      + `<td>${twd(r[4])}</td><td${r[5] != null ? ' class="is-accent"' : ''}>${r[5] == null ? '' : twd(r[5])}</td>`
      + `<td>${twd(r[6])}</td><td>${fmt.percent(r[7])}</td></tr>`).join('');
  }

  /* ---------- 貨幣投資：換匯、依序定存、以預估匯率換回 ---------- */
  const signedPercentFormat = new Intl.NumberFormat('zh-TW', { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: 'exceptZero' });
  const signedPercent = (ratio) => (ratio == null ? NO_VALUE : signedPercentFormat.format(ratio));

  /** 匯率取 6 位有效數字，不用科學記號 ex. 32.1235、0.00645161 */
  const plainRate = (n) => (Number.isFinite(n) && n > 0
    ? Number(n.toPrecision(6)).toLocaleString('en-US', { useGrouping: false, maximumFractionDigits: 10 }) : '');

  /** 「1 USD 可換新台幣」、「1 AUD 可換 USD」 */
  const exchangeTarget = (code) => (code === 'TWD' ? '新台幣' : ` ${code}`);

  function renderCurrency(d, scope) {
    const money = (code, n) => `${code} ${fmt.amount(n, d.decimals)}`;
    setOut(scope, 'amount', money(d.from, d.amount));
    setOut(scope, 'result', money(d.to, d.result));
    setOut(scope, 'crossRate', `1 ${d.from} = ${d.crossRate} ${d.to}`);
    $('[data-deposit-caption]', scope).textContent = `（本金為上方的轉換結果 ${money(d.to, d.result)}）`;

    const dep = d.deposit;
    $('[data-deposit-results]', scope).hidden = !dep;
    $('[data-deposit-empty]', scope).hidden = Boolean(dep);
    if (!dep) return;

    const y = dep.currency;
    const z = dep.settle;
    const inY = (n) => money(y, n);
    const inZ = (n) => money(z, n);
    setOut(scope, 'cost', inZ(dep.cost));
    setOut(scope, 'interest', inY(dep.interest));
    setOut(scope, 'value', inZ(dep.value));
    setOut(scope, 'profit', inZ(dep.profit));
    setOut(scope, 'annualReturn', fmt.percentFixed(dep.annualReturn));
    setOut(scope, 'totalReturn', fmt.percentFixed(dep.totalReturn));
    setOut(scope, 'interestValue', inZ(dep.interestValue));
    setOut(scope, 'exchangeGain', inZ(dep.exchangeGain));
    setOut(scope, 'breakEvenRate', dep.breakEvenRate == null ? NO_VALUE : `1 ${y} = ${dep.breakEvenRate} ${z}`);
    const profitTile = $('[data-profit-tile]', scope);
    profitTile.classList.toggle('tile--loss', dep.profit < 0);
    profitTile.classList.toggle('tile--gain', dep.profit > 0);
    //換回貨幣與定存幣別相同時沒有匯率風險，不顯示匯兌損益與匯率變動試算
    $$('[data-fx]', scope).forEach((el) => { el.hidden = dep.sameCurrency; });

    const term = fmt.term(dep.months);
    const returns = `總報酬 ${inZ(dep.profit)}（${fmt.percentFixed(dep.totalReturn)}，年化 ${fmt.percentFixed(dep.annualReturn)}）`;
    const direction = (ratio) => (ratio == null || Math.abs(ratio) < 0.00005 ? '與目前相同'
      : `${y} ${ratio > 0 ? '升值' : '貶值'} ${fmt.percentFixed(Math.abs(ratio))}`);
    const points = [
      `${d.from === d.to ? '' : `${money(d.from, d.amount)} 換成 `}${inY(dep.principal)}，依序存 ${dep.count} 筆定存、共 ${term}`
        + `${dep.rolloverText ? `，到期後${escapeHtml(dep.rolloverText)}` : ''}，利息合計 ${inY(dep.interest)}，最後拿回 ${inY(dep.finalAmount)}。`,
    ];
    if (dep.sameCurrency) {
      points.push(`本金與利息都以 ${y} 計算，不需換匯：投入 ${inY(dep.cost)}，${returns}。`);
    } else {
      points.push(`到期預估 1 ${y} = ${dep.estimateRate} ${z}（目前 ${dep.nowRate}，${direction(dep.estimateChange)}），`
        + `換回 ${inZ(dep.value)}；投入成本 ${inZ(dep.cost)}，${returns}。`);
      points.push(`總報酬中，利息收益 ${inZ(dep.interestValue)}，匯兌損益 ${inZ(dep.exchangeGain)}。`);
      if (dep.breakEvenRate != null) {
        const change = dep.breakEvenChange;
        points.push(change < 0
          ? `損益兩平匯率 1 ${y} = ${dep.breakEvenRate} ${z}：到期時 ${y} 比目前貶值 ${fmt.percentFixed(-change)} 以內，換回後不會虧損；貶值更多時，利息不足以抵銷匯損。`
          : `損益兩平匯率 1 ${y} = ${dep.breakEvenRate} ${z}：到期時 ${y} 要比目前升值 ${fmt.percentFixed(change)} 以上，換回後才不會虧損。`);
      }
    }
    $('[data-deposit-points]', scope).innerHTML = points.map((p) => `<li>${p}</li>`).join('');

    setOut(scope, 'depositMeta', `金額單位 ${y}，共 ${dep.count} 筆、${term}`);
    $('tbody[data-deposit-rows-out]', scope).innerHTML = dep.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${r[1] === r[2] ? `第 ${r[1]} 個月` : `第 ${r[1]}～${r[2]} 個月`}</td><td>${fmt.term(r[3])}</td><td>${r[4]}%</td>`
      + `<td>${fmt.amount(r[5], d.decimals)}</td><td class="is-accent">${fmt.amount(r[6], d.decimals)}</td>`
      + `<td>${fmt.amount(r[7], d.decimals)}</td><td>${fmt.amount(r[8], d.decimals)}</td>`
      + `<td>${r[10] == null ? `換回 ${z}` : escapeHtml(r[10])}</td></tr>`).join('');

    setOut(scope, 'scenarioMeta', `到期時 1 ${y} 可換多少 ${z}，目前 ${dep.nowRate}`);
    $('tbody[data-scenario-rows]', scope).innerHTML = dep.scenarios.map((s) => {
      const tags = [s.now && '目前匯率', s.estimate && '預估', s.breakEven && '損益兩平'].filter(Boolean)
        .map((t) => `<span class="option__tag">${t}</span>`).join('');
      const down = s.profit < 0 ? ' class="is-down"' : '';
      return `<tr${s.estimate ? ' class="is-recommended"' : ''}><th scope="row">${s.rate}${tags}</th><td>${signedPercent(s.change)}</td>`
        + `<td>${inZ(s.value)}</td><td${down}>${inZ(s.profit)}</td><td${down}>${fmt.percentFixed(s.totalReturn)}</td>`
        + `<td${down}>${fmt.percentFixed(s.annualReturn)}</td></tr>`;
    }).join('');
  }

  /** 定存清單：一筆到期後接著存下一筆，每列為期數（1～12 個月）、年利率與到期後的續存方式 */
  const DEFAULT_DEPOSIT = { term: '12', rate: '3.5', rollover: 'COMPOUND' };

  function initDepositList(calculator) {
    const rows = $('[data-deposit-rows]', calculator.form);
    const addButton = $('[data-add-deposit]', calculator.form);
    const template = $('#tpl-deposit-row');
    const termOf = (row) => $('select[name="depositTerm"]', row);
    const rateOf = (row) => $('input[name="depositRate"]', row);
    const rolloverOf = (row) => $('select[name="depositRollover"]', row);

    /**
     * 標示第幾筆與在第幾個月存入、到期；最後一筆到期後換回，續存選單換成「換回」
     * （選單只是隱藏、仍會送出，後端忽略最後一筆的續存方式）
     */
    const renumber = () => {
      let month = 0;
      const list = $$('.deposit-row', rows);
      list.forEach((row, i) => {
        const term = Number(termOf(row).value) || 0;
        const last = i === list.length - 1;
        $('[data-no]', row).textContent = `第 ${i + 1} 筆`;
        $('[data-span]', row).textContent = term === 1 ? `第 ${month + 1} 個月` : `第 ${month + 1}～${month + term} 個月`;
        $('[data-remove]', row).setAttribute('aria-label', `刪除第 ${i + 1} 筆定存`);
        rolloverOf(row).hidden = last;
        $('[data-end]', row).hidden = !last;
        month += term;
      });
      addButton.disabled = list.length >= MAX_LIST_ROWS;
    };

    const createRow = (values = {}) => {
      const row = template.content.firstElementChild.cloneNode(true);
      $$('label[data-for]', row).forEach((label) => {
        const input = $('.input', label.closest('.field'));
        input.id = `deposit-${++rowSequence}`;
        label.htmlFor = input.id;
      });
      if (values.term != null) termOf(row).value = values.term;
      if (values.rate != null) rateOf(row).value = values.rate;
      if (values.rollover) rolloverOf(row).value = values.rollover;
      rows.appendChild(row);
      return row;
    };

    //新的一筆加在清單最下方，沿用上一筆的期數、利率與續存方式
    addButton.addEventListener('click', () => {
      const last = rows.lastElementChild;
      const row = createRow(last ? { term: termOf(last).value, rate: rateOf(last).value, rollover: rolloverOf(last).value } : DEFAULT_DEPOSIT);
      renumber();
      termOf(row).focus();
      calculator.edited();
    });
    rows.addEventListener('click', (e) => {
      const button = e.target.closest('[data-remove]');
      if (!button) return;
      button.closest('.deposit-row').remove();
      renumber();
      addButton.focus();
      calculator.edited();
    });
    rows.addEventListener('change', renumber);

    const reset = () => {
      rows.replaceChildren();
      createRow(DEFAULT_DEPOSIT);
      renumber();
    };
    reset();
    return {
      reset,
      renumber,
      /** 還原時先建立空白列，數值由 applyForm 依序填入，填完再呼叫 renumber */
      restore(count) {
        rows.replaceChildren();
        for (let i = 0; i < Math.min(count, MAX_LIST_ROWS); i++) createRow();
        renumber();
      },
      /** 舊版（v2.1.0）只有一個共用的續存方式，套用到每一筆 */
      setAllRollovers(value) {
        $$('.deposit-row', rows).forEach((row) => {
          const select = rolloverOf(row);
          if (Array.from(select.options).some((o) => o.value === value)) select.value = value;
        });
      },
    };
  }

  /*
   * ---------- 線上匯率 ----------
   * 瀏覽器直接讀取公開的匯率網站（都允許跨網域讀取，GitHub Pages 也能用），只在按下「取得最新匯率」時連線。
   * 取得的匯率保存在這台裝置（fm.rates），之後換幣別、恢復預設時都改用這組匯率。
   * 伺服器版的 CSP 要允許相同的網址（Http.java 的 RATE_SOURCES）。
   */
  const RATE_SOURCES = [
    {
      id: 'er-api',
      name: '國際匯率服務（ExchangeRate-API）',
      site: 'https://www.exchangerate-api.com',
      urls: ['https://open.er-api.com/v6/latest/TWD'],
      parse(json) {
        if (!json || json.result !== 'success' || !json.rates) throw new Error('format');
        return { perTwd: json.rates, time: Number(json.time_last_update_unix) * 1000 };
      },
    },
    {
      id: 'currency-api',
      name: '開源匯率資料（Currency API）',
      site: 'https://github.com/fawazahmed0/exchange-api',
      urls: ['https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/twd.json',
        'https://latest.currency-api.pages.dev/v1/currencies/twd.json'],
      parse(json) {
        if (!json || !json.twd) throw new Error('format');
        const perTwd = {};
        Object.entries(json.twd).forEach(([code, value]) => { perTwd[code.toUpperCase()] = value; });
        return { perTwd, time: Date.parse(`${json.date}T00:00:00Z`) };
      },
    },
  ];
  const RATES_KEY = 'fm.rates';
  const RATE_SOURCE_KEY = 'fm.rateSource';
  const FETCH_TIMEOUT_MS = 8000;
  /** 與後端相同的匯率範圍（1 單位可換多少新台幣） */
  const MIN_RATE = 0.0001;
  const MAX_RATE = 100000;
  const rateTimeFormat = new Intl.DateTimeFormat('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });

  /* ---------- 選項與匯率：範例匯率由後端提供，線上取得的匯率優先 ---------- */
  const sampleRates = new Map();
  const liveRates = new Map();
  const rateFor = (code) => liveRates.get(code) || sampleRates.get(code) || '1';

  /** 只留下支援的幣別與合理範圍內的匯率；格式不對時回傳 null */
  function cleanRates(rates) {
    if (!rates || typeof rates !== 'object') return null;
    const clean = {};
    sampleRates.forEach((sample, code) => {
      const n = Number(rates[code]);
      if (code !== 'TWD' && n >= MIN_RATE && n <= MAX_RATE) clean[code] = plainRate(n);
    });
    return Object.keys(clean).length ? clean : null;
  }

  /** 讀回上次取得的線上匯率，啟動時在各分頁設定預設匯率之前呼叫 */
  function restoreLiveRates() {
    let info = null;
    try { info = JSON.parse(store.get(RATES_KEY)); } catch (e) { return null; }
    const rates = info && cleanRates(info.rates);
    if (!rates || !RATE_SOURCES.some((s) => s.id === info.source)) return null;
    Object.entries(rates).forEach(([code, rate]) => liveRates.set(code, rate));
    return { ...info, rates };
  }

  async function fetchJson(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
    try {
      const response = await fetch(url, { signal: controller.signal, cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.json();
    } finally {
      clearTimeout(timer);
    }
  }

  /** 依序嘗試來源的每個網址，換成「1 單位可換多少新台幣」 */
  async function loadRates(source) {
    let lastError = new Error('failed');
    for (const url of source.urls) {
      try {
        const { perTwd, time } = source.parse(await fetchJson(url));
        const inverted = {};
        Object.entries(perTwd).forEach(([code, per]) => { if (Number(per) > 0) inverted[code] = 1 / Number(per); });
        const rates = cleanRates(inverted);
        if (!rates) throw new Error('empty');
        return { rates, time: Number.isFinite(time) ? time : null };
      } catch (e) {
        lastError = e;
      }
    }
    throw lastError;
  }

  /**
   * 匯率來源選單、「取得最新匯率」按鈕與狀態文字
   * @param apply 取得成功後呼叫，參數為 {幣別: 1 單位可換多少新台幣}
   */
  function initRateSource(section, saved, apply) {
    const select = $('[data-rate-source]', section);
    const button = $('[data-fetch-rates]', section);
    const label = $('[data-fetch-label]', button);
    const status = $('[data-rate-status]', section);
    //選單在表單裡：設成 defaultSelected，按「恢復預設」時才不會跳回第一個來源
    const savedSource = RATE_SOURCES.some((s) => s.id === store.get(RATE_SOURCE_KEY)) ? store.get(RATE_SOURCE_KEY) : RATE_SOURCES[0].id;
    select.replaceChildren(...RATE_SOURCES.map((s) => new Option(s.name, s.id, s.id === savedSource, s.id === savedSource)));
    select.addEventListener('change', () => {
      store.set(RATE_SOURCE_KEY, select.value);
      Array.from(select.options).forEach((option) => { option.defaultSelected = option.selected; });
    });

    //來源名稱連到該網站；全部用 textContent 組字
    const showInfo = (prefix, info) => {
      const source = RATE_SOURCES.find((s) => s.id === info.source);
      const link = document.createElement('a');
      link.href = source.site;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = source.name;
      const time = info.time ? `（資料時間 ${rateTimeFormat.format(info.time)}）` : '';
      status.classList.remove('is-error');
      status.replaceChildren(prefix, link, `的參考匯率${time}，取得於 ${rateTimeFormat.format(info.fetchedAt)}。`);
    };

    if (saved) showInfo('上次取得：', saved);
    else status.textContent = '目前為範例匯率，可直接輸入銀行的匯率，或按「取得最新匯率」線上讀取參考匯率。';

    button.addEventListener('click', async () => {
      button.disabled = true;
      label.textContent = '取得中…';
      const chosen = RATE_SOURCES.find((s) => s.id === select.value) || RATE_SOURCES[0];
      const failed = [];
      try {
        //選的來源連不上時，自動改用其他來源
        for (const source of [chosen, ...RATE_SOURCES.filter((s) => s !== chosen)]) {
          try {
            const { rates, time } = await loadRates(source);
            const info = { source: source.id, time, fetchedAt: Date.now(), rates };
            store.set(RATES_KEY, JSON.stringify(info));
            liveRates.clear();
            Object.entries(rates).forEach(([code, rate]) => liveRates.set(code, rate));
            showInfo(failed.length ? `${failed.join('、')}無法連線，改用` : '已更新：', info);
            apply(rates);
            return;
          } catch (e) {
            failed.push(source.name);
          }
        }
        status.classList.add('is-error');
        status.textContent = '無法連線到匯率網站，請確認網路連線後再試，或直接輸入匯率。';
      } finally {
        button.disabled = false;
        label.textContent = '取得最新匯率';
      }
    });
  }

  function fillSelects(meta) {
    (meta.currencies || []).forEach((c) => sampleRates.set(c.code, String(c.sampleRate)));
    //清單列範本（ex. 每筆定存的「到期後」）裡的選單也要填入，新增的列才有選項
    const templateSelects = $$('template').flatMap((template) => $$('select[data-options]', template.content));
    [...$$('select[data-options]'), ...templateSelects].forEach((select) => {
      const options = meta[select.dataset.options] || [];
      const preferred = select.dataset.default || (options[0] && options[0].code);
      const isCurrency = select.dataset.options === 'currencies';
      select.replaceChildren(...options.map((o) => {
        const selected = o.code === preferred;
        //defaultSelected 讓「恢復預設」也能還原
        return new Option(isCurrency ? `${o.code} ${o.label}` : o.label, o.code, selected, selected);
      }));
    });
  }

  /* ---------- 外觀設定 ---------- */
  function applyTheme(theme, save) {
    const value = THEMES.includes(theme) ? theme : 'light';
    root.dataset.theme = value;
    if (save) store.set('fm.theme', value);
    const radio = $(`input[name="theme"][value="${value}"]`);
    if (radio) radio.checked = true;
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.content = getComputedStyle(root).getPropertyValue('--surface').trim();
  }

  function applyFont(font, save) {
    const value = FONTS.includes(font) ? font : 'm';
    root.dataset.font = value;
    if (save) store.set('fm.font', value);
    const radio = $(`input[name="font"][value="${value}"]`);
    if (radio) radio.checked = true;
  }

  function initSettings() {
    const toggle = $('#settingsToggle');
    const panel = $('#settingsPanel');
    const setOpen = (open) => {
      panel.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      if (open) {
        const checked = $('input[name="theme"]:checked', panel);
        if (checked) checked.focus();
      }
    };
    toggle.addEventListener('click', () => setOpen(panel.hidden));
    document.addEventListener('click', (e) => {
      if (!panel.hidden && !panel.contains(e.target) && !toggle.contains(e.target)) setOpen(false);
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !panel.hidden) {
        setOpen(false);
        toggle.focus();
      }
    });
    panel.addEventListener('change', (e) => {
      if (e.target.name === 'theme') applyTheme(e.target.value, true);
      if (e.target.name === 'font') applyFont(e.target.value, true);
    });
    applyTheme(root.dataset.theme, false);
    applyFont(root.dataset.font, false);
  }

  /* ---------- 說明視窗：第一次開啟網站時自動顯示一次，之後按右上角「說明」開啟 ---------- */
  function initHelp() {
    const dialog = $('#helpDialog');
    const toggle = $('#helpToggle');
    //不支援 <dialog> 的舊瀏覽器不顯示說明按鈕
    if (typeof dialog.showModal !== 'function') {
      toggle.hidden = true;
      return;
    }
    const open = () => { if (!dialog.open) dialog.showModal(); };
    toggle.addEventListener('click', open);
    //點關閉鈕、視窗外的暗色背景，或點頁籤說明（連結會切換分頁）時關閉
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog || e.target.closest('[data-help-close], [data-help-link]')) dialog.close();
    });
    if (!store.get('fm.helpSeen')) {
      store.set('fm.helpSeen', '1');
      open();
    }
  }

  /*
   * ---------- 下載 PDF ----------
   * 網站的 CSP 只允許同源腳本、不載入外部函式庫，改用瀏覽器的列印功能另存 PDF：
   * 中文字、表格與圖表都是向量，檔案小、文字可選取。版面見 app.css 的 @media print（只印目前分頁、一律淺色）。
   */
  /** 列印時的圖表寬度：A4 扣掉頁邊與卡片內距約 680px */
  const PRINT_CHART_WIDTH = 680;
  const pad2 = (n) => String(n).padStart(2, '0');
  const currentPage = () => $('.page:not([hidden])');

  /** 重畫目前分頁的圖表（列印前後寬度不同） */
  function redrawCharts() {
    if (lastPlan && !lastPlan.scope.hidden) renderPlanCharts(lastPlan.d, lastPlan.scope);
    if (lastDca && !lastDca.scope.hidden) renderDcaChart(lastDca.d, lastDca.scope);
  }

  function initPdf() {
    const button = $('#pdfButton');
    const label = $('[data-pdf-label]', button);
    const time = $('[data-print-time]');
    let restore = null;

    //列印前：標題改成另存 PDF 的預設檔名、有資料的清單展開、沒有資料的不印、圖表以紙張寬度重畫；按 Ctrl+P 也適用
    const prepare = () => {
      const page = currentPage();
      if (restore || !page) return;
      const now = new Date();
      const [y, m, d] = [now.getFullYear(), pad2(now.getMonth() + 1), pad2(now.getDate())];
      const title = document.title;
      document.title = `全算利計算器_${$('h1', page).textContent}_${y}${m}${d}`;
      time.textContent = `產生時間 ${y}/${m}/${d} ${pad2(now.getHours())}:${pad2(now.getMinutes())}`;
      const opened = [];
      const empty = [];
      $$('details', page).forEach((details) => {
        if (!$('.input', details)) {
          details.classList.add('is-print-empty');
          empty.push(details);
        } else if (!details.open) {
          details.open = true;
          opened.push(details);
        }
      });
      printChartWidth = PRINT_CHART_WIDTH;
      redrawCharts();
      restore = () => {
        document.title = title;
        opened.forEach((details) => { details.open = false; });
        empty.forEach((details) => details.classList.remove('is-print-empty'));
        printChartWidth = 0;
        redrawCharts();
      };
    };
    const finish = () => {
      if (!restore) return;
      restore();
      restore = null;
    };
    window.addEventListener('beforeprint', prepare);
    window.addEventListener('afterprint', finish);

    button.addEventListener('click', async () => {
      const page = pages[currentPage().dataset.page];
      if (!page) return; //網站還在載入
      //結果要對應目前的條件：還沒算過、打字後還在等重算、投資計畫改了條件還沒按試算，就先算完。
      //結果已是最新時直接列印，不經過 await，部分瀏覽器才不會因為不是使用者操作而擋下列印視窗
      if (!page.loaded || page.timer || page.results.classList.contains('is-stale')) {
        button.disabled = true;
        label.textContent = '準備中…';
        try {
          await page.run();
        } finally {
          button.disabled = false;
          label.textContent = '下載 PDF';
        }
      }
      //條件有誤時不印，捲到錯誤訊息
      if (!page.alert.hidden) {
        page.alert.scrollIntoView({ block: 'center', behavior: 'smooth' });
        return;
      }
      prepare();
      window.print();
    });
  }

  /* ---------- 分頁切換：網址 #compound、#floating、#mortgage、#bond、#currency、#dca、#plan、#lottery；網址沒指定時回到上次的分頁 ---------- */
  const pages = {};

  function showPage() {
    const requested = location.hash.slice(1) || store.get('fm.page');
    const id = pages[requested] ? requested : 'compound';
    store.set('fm.page', id);
    $$('.page').forEach((page) => { page.hidden = page.dataset.page !== id; });
    $$('.tab').forEach((tab) => {
      if (tab.dataset.page === id) {
        tab.setAttribute('aria-current', 'page');
        tab.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      } else {
        tab.removeAttribute('aria-current');
      }
    });
    document.title = `${$(`#h-${id}`).textContent} - 全算利計算器`;
    pages[id].activate();
  }

  /* ---------- 啟動 ---------- */
  async function start() {
    initSettings();
    initHelp();
    initPdf();
    await loadEngine();

    let meta = {};
    try {
      meta = await callApi('/api/meta', new URLSearchParams());
    } catch (e) {
      $$('.form-alert').forEach((alert) => {
        alert.textContent = '無法載入選項，請重新整理頁面。';
        alert.hidden = false;
      });
    }
    fillSelects(meta);
    //上次線上取得的匯率要在各分頁設定預設匯率之前讀回
    const savedRates = restoreLiveRates();
    showVersion(meta.version);
    watchForUpdate();

    pages.compound = new Calculator('compound', { render: renderCompound });
    pages.floating = initFloating();

    const mortgage = new Calculator('mortgage', {
      render: renderMortgage,
      onReset: () => { rateList.clear(); prepayList.clear(); },
      beforeRestore: (state) => {
        rateList.restore(countField(state, 'ratePeriod'));
        prepayList.restore(countField(state, 'prepayPeriod'));
      },
    });
    const rateList = initPeriodList(mortgage, 'rate', 'tpl-rate-row', () => $('#m-rate').value);
    const prepayList = initPeriodList(mortgage, 'prepay', 'tpl-prepay-row', () => DEFAULT_PREPAYMENT);
    pages.mortgage = mortgage;

    pages.bond = initBond();
    pages.currency = initCurrency(savedRates);
    pages.dca = initDca(meta);
    pages.plan = initPlan(meta);
    pages.lottery = initLottery(meta);
    Object.values(pages).forEach((page) => page.restore());

    window.addEventListener('hashchange', () => {
      showPage();
      window.scrollTo({ top: 0 });
    });
    showPage();
  }

  /* ---------- 版本與更新提示 ---------- */
  /** 頁尾顯示程式版本與部署編號，方便確認讀到的是哪一版 */
  function showVersion(version) {
    const parts = [];
    if (version) parts.push(`v${version}`);
    if (IS_DEPLOYED) parts.push(BUILT_AT ? `版本 ${BUILD}（${BUILT_AT} 部署）` : `版本 ${BUILD}`);
    $('[data-version]').textContent = parts.join(' ');
  }

  /*
   * GitHub Pages 每個檔案都快取 10 分鐘，剛部署後瀏覽器可能還在用舊的頁面。
   * 開啟頁面與切回這個分頁時，略過快取讀 version.json；版本不同就提示重新整理。
   */
  function watchForUpdate() {
    if (!IS_DEPLOYED) return;
    const bar = $('[data-update]');
    $('[data-update-reload]', bar).addEventListener('click', () => window.location.reload());
    let checking = false;
    const check = async () => {
      if (checking || !bar.hidden) return;
      checking = true;
      try {
        const response = await fetch(`version.json?t=${Date.now()}`, { cache: 'no-store' });
        if (response.ok) {
          const latest = await response.json();
          if (latest && latest.build && latest.build !== BUILD) bar.hidden = false;
        }
      } catch (e) {
        /* 離線或讀取失敗時不提示 */
      } finally {
        checking = false;
      }
    };
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') check();
    });
    check();
  }

  function initBond() {
    const currency = $('#b-currency');
    const exchange = $('[data-exchange]');
    //新台幣不需要匯率；換幣別時三個匯率都換成該幣別的範例值
    const sync = (applySample) => {
      const code = currency.value;
      const foreign = code !== 'TWD';
      exchange.hidden = !foreign;
      exchange.disabled = !foreign;
      $('[data-rate-caption]', exchange).textContent = `（1 ${code} 可換多少新台幣）`;
      if (applySample && foreign) $$('[data-rate]', exchange).forEach((input) => { input.value = rateFor(code); });
    };
    $$('[data-rate]', exchange).forEach((input) => { input.defaultValue = rateFor(currency.value); });
    currency.addEventListener('change', () => sync(true));
    sync(false);
    return new Calculator('bond', { render: renderBond, onReset: () => sync(false), afterRestore: () => sync(false) });
  }

  /**
   * 貨幣投資：上方換匯（從 → 到），轉換結果依序定存，到期本息以預估匯率換回指定貨幣。
   * 到期預估匯率預設跟著目前匯率（settleRateAuto=1），使用者自行輸入後就不再自動改變
   */
  function initCurrency(savedRates) {
    const section = $('#page-currency');
    const from = $('#x-from');
    const to = $('#x-to');
    const settle = $('#x-settle');
    const fromRate = $('#x-from-rate');
    const toRate = $('#x-to-rate');
    const settleRate = $('#x-settle-rate');
    const settleNow = $('#x-settle-now-rate');
    const settleNowField = $('[data-settle-now-field]', section);
    const useNow = $('[data-use-now]', section);

    //新台幣的匯率固定為 1，不開放修改；原幣金額標示「從」選的幣別
    const syncRate = (select, applySample) => {
      const input = $(`[data-rate-for="${select.id}"]`);
      const code = select.value;
      if (code === 'TWD') input.value = '1';
      else if (applySample) input.value = rateFor(code);
      input.disabled = code === 'TWD';
      $(`label[for="${input.id}"]`).textContent = `1 ${code} 可換新台幣`;
      if (select === from) {
        $('[data-from-label]', section).textContent = select.selectedOptions[0] ? select.selectedOptions[0].text : code;
        $('[data-from-code]', section).textContent = code;
      }
    };
    const syncBoth = () => { syncRate(from, false); syncRate(to, false); };

    /** 1 單位該貨幣目前可換多少新台幣，讀取畫面上的匯率欄位 */
    const twdRate = (code) => {
      if (code === 'TWD') return 1;
      if (code === from.value) return parseNumber(fromRate.value);
      if (code === to.value) return parseNumber(toRate.value);
      return parseNumber(settleNow.value);
    };
    /** 目前 1 單位定存幣別可換多少換回貨幣 */
    const nowCross = () => {
      const a = twdRate(to.value);
      const b = twdRate(settle.value);
      return a > 0 && b > 0 ? a / b : NaN;
    };

    let auto = null;
    let legacyRollover = null;
    /** 換回貨幣：與定存幣別相同時不需匯率；不是原幣別、定存幣別或新台幣時，另外輸入它的目前匯率 */
    const syncSettle = (applySample) => {
      const y = to.value;
      const z = settle.value;
      const needNow = z !== 'TWD' && z !== from.value && z !== y;
      settleNowField.hidden = !needNow;
      settleNow.disabled = !needNow;
      if (needNow && (applySample || !settleNow.value)) settleNow.value = rateFor(z);
      $(`label[for="${settleNow.id}"]`).textContent = `目前 1 ${z} 可換新台幣`;

      const same = z === y;
      const now = nowCross();
      settleRate.disabled = same;
      $('[data-settle-rate-label]', section).textContent = same ? '到期預估匯率' : `到期 1 ${y} 可換${exchangeTarget(z)}`;
      if (same) settleRate.value = '1';
      else if (auto.value === '1' && Number.isFinite(now)) settleRate.value = plainRate(now);
      $('[data-settle-now]', section).textContent = same ? '同一種貨幣，不需換匯' : (Number.isFinite(now) ? `目前 ${plainRate(now)}` : '');
      useNow.hidden = same || auto.value === '1';
    };

    const calculator = new Calculator('currency', {
      render: renderCurrency,
      //隱藏欄位的 value 就是預設值，表單重設不會還原，要自己設回 1
      onReset: () => {
        syncBoth();
        deposits.reset();
        auto.value = '1';
        syncSettle(true);
      },
      //v2.0 只有幣別轉換、沒有定存欄位時，保留預設的一筆定存；v2.1.0 的續存方式是全部共用一個
      beforeRestore: (state) => {
        legacyRollover = null;
        if (countField(state, 'settle') === 0) return;
        deposits.restore(countField(state, 'depositTerm'));
        if (countField(state, 'depositRollover') === 0) {
          const shared = state.fields.find(([name]) => name === 'rollover');
          if (shared) legacyRollover = shared[1];
        }
      },
      afterRestore: () => {
        syncBoth();
        if (legacyRollover) deposits.setAllRollovers(legacyRollover);
        deposits.renumber();
        syncSettle(false);
      },
    });
    auto = $('input[name="settleRateAuto"]', calculator.form);
    const deposits = initDepositList(calculator);

    [from, to].forEach((select) => {
      $(`[data-rate-for="${select.id}"]`).defaultValue = rateFor(select.value);
      select.addEventListener('change', () => {
        syncRate(select, true);
        auto.value = '1';
        syncSettle(false);
      });
      syncRate(select, false);
    });
    settle.addEventListener('change', () => {
      auto.value = '1';
      syncSettle(true);
    });
    [fromRate, toRate, settleNow].forEach((input) => input.addEventListener('input', () => syncSettle(false)));
    settleRate.addEventListener('input', () => {
      auto.value = '0';
      syncSettle(false);
    });
    useNow.addEventListener('click', () => {
      auto.value = '1';
      syncSettle(false);
      calculator.run();
    });
    $('[data-swap]', section).addEventListener('click', () => {
      [from.value, to.value] = [to.value, from.value];
      [fromRate.value, toRate.value] = [toRate.value, fromRate.value];
      syncBoth();
      auto.value = '1';
      syncSettle(false);
      calculator.run();
    });
    syncSettle(false);

    //線上取得匯率後，換掉畫面上外幣的目前匯率；到期預估匯率若仍跟著目前匯率也一起更新
    initRateSource(section, savedRates, (rates) => {
      [from, to].forEach((select) => {
        if (rates[select.value]) $(`[data-rate-for="${select.id}"]`).value = rates[select.value];
      });
      if (!settleNow.disabled && rates[settle.value]) settleNow.value = rates[settle.value];
      syncSettle(false);
      calculator.run();
    });
    return calculator;
  }

  start();
})();
