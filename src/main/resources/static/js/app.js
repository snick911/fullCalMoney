/* FullMoney 理財試算：條件一改變就呼叫後端 API 重算，計算全部在 Java 端 */
'use strict';

(() => {
  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  const NO_VALUE = '—';
  const THEMES = ['light', 'eye-dark', 'dark', 'sepia', 'ocean', 'sakura'];
  const FONTS = ['s', 'm', 'l', 'xl'];
  const MONTHS_PER_YEAR = 12;
  const MAX_PERIOD = 600;
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
      if (!Number.isInteger(period) || period < 1 || period > MAX_PERIOD) return '';
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
  class Calculator {
    /**
     * @param beforeRun 每次試算前呼叫（保存與送出之前）
     * @param beforeRestore 還原保存的條件前呼叫，可先建立清單列
     * @param afterRestore 還原後呼叫，用來同步依欄位值顯示的區塊
     */
    constructor(id, { render, onReset, beforeRun, beforeRestore, afterRestore }) {
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
      this.loaded = false;
      this.timer = 0;
      this.controller = null;

      this.form.addEventListener('input', (e) => { updateHint(e.target); this.schedule(); });
      //勾選框（金額不顯示小數）不必等打字結束，立即重算
      this.form.addEventListener('change', (e) => (e.target.type === 'checkbox' ? this.run() : this.schedule()));
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

    schedule() {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.run(), DEBOUNCE_MS);
    }

    async run() {
      clearTimeout(this.timer);
      if (this.controller) this.controller.abort();
      const controller = new AbortController();
      this.controller = controller;
      this.beforeRun();
      //條件有誤也照樣保存，下次開啟時看到的是使用者最後輸入的內容
      saveForm(this.id, this.form);
      this.results.setAttribute('aria-busy', 'true');
      try {
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
        if (this.controller === controller) this.results.removeAttribute('aria-busy');
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

    $('[data-options]', scope).innerHTML = d.options.map((o) => optionCard(o, twd, d.tier)).join('');

    //資產配置：保守型、穩健型、積極型各自的最佳配置（每 5% 一級搜尋、蒙地卡羅模擬）
    setOut(scope, 'portfolioSummary', d.portfolioSummary);
    const body = $('tbody[data-portfolio-rows]', scope);
    body.innerHTML = d.portfolios.map((p) => {
      const [chanceText, chanceClass] = CHANCES[p.chance];
      const badge = p.recommended ? ' <span class="option__tag">建議</span>' : '';
      const tags = `<span class="option__tag${chanceClass}">${chanceText}</span>`
        + (p.horizonOk ? '' : ` <span class="option__tag option__tag--warn">期間短於 ${escapeHtml(p.horizon)}</span>`);
      return `<tr${p.recommended ? ' class="is-recommended"' : ''}><th scope="row">${profilePill(p)}${badge}`
        + `<span class="portfolio__count">${p.candidates.toLocaleString('zh-TW')} 種中約 ${p.reachable.toLocaleString('zh-TW')} 種機率過半</span></th>`
        + `<td>${allocationPie(p, 48)}</td><td>${fmt.percentFixed(p.typicalRate)}</td><td>${fmt.percentFixed(p.volatility)}</td>`
        + `<td><span class="portfolio__chance">${chancePercent(p.probability)}</span><span class="portfolio__tags">${tags}</span></td>`
        + `<td>${twd(p.median)}<span class="portfolio__count">${twd(p.pessimistic)}～${twd(p.optimistic)}</span></td></tr>`;
    }).join('');

    $('[data-items="warnings"]', scope).innerHTML = d.warnings.map((w) => `<li>${escapeHtml(w)}</li>`).join('');
    $('[data-items="suggestions"]', scope).innerHTML = d.suggestions.map((s) => `<li>${escapeHtml(s)}</li>`).join('');
    $('[data-suggestions]', scope).hidden = d.suggestions.length === 0;

    //三種配置的逐年試算：各資產金額與獲利，旁邊對照需要的報酬率下的目標進度（d.rows 的金額）
    $('[data-allocation-years]', scope).innerHTML = d.portfolios.map((p) => allocationYears(p, d.rows, twd)).join('');
    renderPlanCharts(d, scope);
  }

  /* ---------- 投資計畫圖表：手寫 SVG（網站的 CSP 只允許同源腳本，不載入外部圖表函式庫） ---------- */

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
    const width = Math.max(box.clientWidth, 280);
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
      + `${grid}${area}${axis}${lines}${endLabels}`
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
        { value: twd(invested[index]), label: '累積投入', key: 'legend-area' }]
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
    const width = Math.max(box.clientWidth, 280);
    const narrow = width < 480;
    const rowH = 48;
    const m = { top: 24, right: 18, bottom: 26, left: narrow ? 58 : 70 };
    const height = m.top + rowH * d.portfolios.length + m.bottom;
    const plotW = width - m.left - m.right;
    const values = d.portfolios.flatMap((p) => [p.pessimistic, p.optimistic]).concat(d.target);
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
      const cy = m.top + rowH * i + rowH / 2;
      return `<g class="profile--${p.code}">`
        + `<rect class="chart__row-hit" data-row="${i}" x="0" y="${cy - rowH / 2}" width="${width}" height="${rowH}"></rect>`
        + `<circle class="chart__dot" cx="10" cy="${cy}" r="4"></circle>`
        + `<text class="chart__label" x="20" y="${cy + 4}">${escapeHtml(p.name)}</text>`
        + `<line class="chart__range" x1="${x(p.pessimistic)}" x2="${x(p.optimistic)}" y1="${cy}" y2="${cy}"></line>`
        + `<circle class="chart__median" cx="${x(p.median)}" cy="${cy}" r="6"></circle>`
        + `<text class="chart__value" x="${x(p.median)}" y="${cy - 11}" text-anchor="middle">${compactMoney(p.median)}</text>`
        + '</g>';
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
        tip.show(`${p.name}・達成機率 ${chancePercent(p.probability)}`, [
          { value: twd(p.optimistic), label: '樂觀（最好 10%）', key },
          { value: twd(p.median), label: '中位數', key },
          { value: twd(p.pessimistic), label: '悲觀（最差 10%）', key },
          { value: twd(d.target), label: '目標', key: 'legend-line legend-line--target' },
        ], x(p.optimistic) * scale, (m.top + rowH * Number(hit.dataset.row)) * scale);
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
      + '<span><i class="legend-line legend-line--target"></i>目標進度</span><span><i class="legend-area"></i>累積投入</span>';
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
   * 一種配置的逐年試算表；p.rows 每列為 [月, 累積投入, 定存、公債、公司債、股票的金額（4 欄）, 四者的獲利（4 欄）, 總金額, 累積獲利]
   */
  function allocationYears(p, targetRows, twd) {
    const count = ASSET_NAMES.length;
    const asset = (r, i) => (p.weights[i] === 0 ? `<td class="is-muted">${NO_VALUE}</td>`
      : `<td>${twd(r[2 + i])}<span class="asset-gain">獲利 ${twd(r[2 + count + i])}</span></td>`);
    const body = p.rows.map((r, i) => {
      const total = r[2 + count * 2];
      const target = targetRows[i] ? targetRows[i][2] : null;
      const ahead = target == null || total >= target;
      return `<tr><td>${fmt.term(r[0])}</td><td>${twd(r[1])}</td>${ASSET_NAMES.map((name, j) => asset(r, j)).join('')}`
        + `<td class="${ahead ? 'is-accent' : 'is-down'}">${twd(total)}</td><td>${twd(r[3 + count * 2])}</td>`
        + `<td>${target == null ? NO_VALUE : twd(target)}</td></tr>`;
    }).join('');
    const badge = p.recommended ? '<span class="option__tag">建議</span>' : '';
    //欄位標題前加上與圓餅相同顏色的小方塊
    const head = (i) => `<th scope="col"><span class="asset-head"><i class="swatch-dot asset-${i + 1}"></i>${ASSET_NAMES[i]}</span>`
      + (i === 3 && p.stockLevelName ? `<span class="asset-gain">${escapeHtml(p.stockLevelName)}</span>` : '') + '</th>';
    return `<div class="card card--flush profile-card profile--${escapeHtml(p.code)}">`
      + `<div class="card__head"><h2>${profilePill(p)} 逐年試算${badge}</h2>`
      + `<span class="profile-card__summary">${allocationPie(p, 56)}<span class="card__meta">達成機率 ${chancePercent(p.probability)}</span></span></div>`
      + `<div class="table-wrap" tabindex="0" aria-label="${escapeHtml(p.name)}逐年試算，可捲動"><table class="data-table">`
      + `<thead><tr><th scope="col">期間</th><th scope="col">累積投入</th>${ASSET_NAMES.map((name, i) => head(i)).join('')}`
      + '<th scope="col">總金額</th><th scope="col">累積獲利</th><th scope="col">目標進度</th></tr></thead>'
      + `<tbody>${body}</tbody></table></div></div>`;
  }

  /* ---------- 房貸計算 ---------- */
  let rowSequence = 0;

  /** 可增減「期數 + 數值」的清單，用於分段利率與提前還款 */
  function initPeriodList(calculator, key, templateId, defaultValue) {
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
      const lastPeriod = last ? parseNumber($('[data-kind="period"]', last).value) : FIRST_NEW_PERIOD - NEW_PERIOD_STEP;
      const period = Math.min((Number.isInteger(lastPeriod) ? lastPeriod : 1) + NEW_PERIOD_STEP, MAX_PERIOD);
      const row = createRow();
      const [periodInput, valueInput] = $$('input', row);
      periodInput.value = period;
      valueInput.value = defaultValue();
      refreshHints(row);
      updateCount();
      periodInput.focus();
      calculator.run();
    });

    rows.addEventListener('click', (e) => {
      const button = e.target.closest('[data-remove]');
      if (!button) return;
      button.closest('.period-row').remove();
      updateCount();
      calculator.run();
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

  /* ---------- 幣別轉換 ---------- */
  function renderCurrency(d, scope) {
    setOut(scope, 'result', `${d.to} ${fmt.amount(d.result, d.decimals)}`);
    setOut(scope, 'crossRate', `1 ${d.from} = ${d.crossRate} ${d.to}`);
  }

  /* ---------- 選項與範例匯率（由後端提供） ---------- */
  const sampleRates = new Map();
  const sampleRate = (code) => sampleRates.get(code) || '1';

  function fillSelects(meta) {
    (meta.currencies || []).forEach((c) => sampleRates.set(c.code, String(c.sampleRate)));
    $$('select[data-options]').forEach((select) => {
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

  /* ---------- 分頁切換：網址 #compound、#floating、#mortgage、#bond、#currency、#plan；網址沒指定時回到上次的分頁 ---------- */
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
    document.title = `${$(`#h-${id}`).textContent} - FullMoney 理財試算`;
    pages[id].activate();
  }

  /* ---------- 啟動 ---------- */
  async function start() {
    initSettings();
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
    pages.currency = initCurrency();
    pages.plan = new Calculator('plan', { render: renderPlan });
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
      if (applySample && foreign) $$('[data-rate]', exchange).forEach((input) => { input.value = sampleRate(code); });
    };
    $$('[data-rate]', exchange).forEach((input) => { input.defaultValue = sampleRate(currency.value); });
    currency.addEventListener('change', () => sync(true));
    sync(false);
    return new Calculator('bond', { render: renderBond, onReset: () => sync(false), afterRestore: () => sync(false) });
  }

  function initCurrency() {
    const from = $('#x-from');
    const to = $('#x-to');
    //新台幣的匯率固定為 1，不開放修改
    const sync = (select, applySample) => {
      const input = $(`[data-rate-for="${select.id}"]`);
      const code = select.value;
      if (code === 'TWD') input.value = '1';
      else if (applySample) input.value = sampleRate(code);
      input.disabled = code === 'TWD';
      $(`label[for="${input.id}"]`).textContent = `1 ${code} 可換新台幣`;
    };
    [from, to].forEach((select) => {
      $(`[data-rate-for="${select.id}"]`).defaultValue = sampleRate(select.value);
      select.addEventListener('change', () => sync(select, true));
      sync(select, false);
    });
    const syncBoth = () => { sync(from, false); sync(to, false); };
    const calculator = new Calculator('currency', { render: renderCurrency, onReset: syncBoth, afterRestore: syncBoth });
    $('[data-swap]').addEventListener('click', () => {
      const fromRate = $('#x-from-rate');
      const toRate = $('#x-to-rate');
      [from.value, to.value] = [to.value, from.value];
      [fromRate.value, toRate.value] = [toRate.value, fromRate.value];
      sync(from, false);
      sync(to, false);
      calculator.run();
    });
    return calculator;
  }

  start();
})();
