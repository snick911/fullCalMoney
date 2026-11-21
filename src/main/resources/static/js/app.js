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
      const engine = await import('./engine.js');
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
      + `<li><span>行情好 ${fmt.percentFixed(o.maxRate)}</span><span>${twd(o.maxEnd)}</span><span class="is-reached">達標</span></li></ul>`;
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

    const allocation = d.allocation;
    $('[data-allocation]', scope).hidden = !allocation;
    if (allocation) {
      //CSP 不允許 HTML 裡的 style 屬性，寬度改由 CSSOM 設定
      $('[data-allocation-bar]', scope).replaceChildren(...allocation.parts.map((p) => {
        const segment = document.createElement('span');
        segment.style.width = `${Number(p.percent)}%`;
        return segment;
      }));
      setOut(scope, 'allocationParts', allocation.parts.map((p) => `${p.name} ${p.percent}%`).join('　＋　'));
      setOut(scope, 'allocationText', `${allocation.text}期滿約 ${twd(allocation.endAmount)}。`);
    }

    $('[data-items="warnings"]', scope).innerHTML = d.warnings.map((w) => `<li>${escapeHtml(w)}</li>`).join('');
    $('[data-items="suggestions"]', scope).innerHTML = d.suggestions.map((s) => `<li>${escapeHtml(s)}</li>`).join('');
    $('[data-suggestions]', scope).hidden = d.suggestions.length === 0;

    setOut(scope, 'rowsCaption', `以年化 ${planRate(d.requiredRate)} 計算，共 ${fmt.term(d.months)}`);
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${fmt.term(r[0])}</td><td>${twd(r[1])}</td><td>${twd(r[2])}</td><td>${twd(r[3])}</td><td>${planRate(r[4])}</td></tr>`).join('');
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
    if (meta.version) $('[data-version]').textContent = `v${meta.version}`;

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
