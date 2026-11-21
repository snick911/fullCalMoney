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
  const wanFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 4 });

  const fmt = {
    /** 不含貨幣符號，用於新台幣以外的金額 */
    amount: (n, digits = 2) => (n == null ? NO_VALUE : numberFormat(digits).format(n)),
    twd: (n, digits = 2) => (n == null ? NO_VALUE : (n < 0 ? '-$' : '$') + numberFormat(digits).format(Math.abs(n))),
    percent: (ratio) => (ratio == null ? NO_VALUE : percentFormat.format(ratio)),
    /** 銀行利率常到小數第三位 ex. 2.185% */
    rate: (percent) => (percent == null ? NO_VALUE : Number(percent).toFixed(3) + '%'),
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
    constructor(id, { render, onReset }) {
      this.section = $(`#page-${id}`);
      this.form = $('form', this.section);
      this.alert = $('.form-alert', this.form);
      this.results = $('[data-results]', this.section);
      this.endpoint = this.form.dataset.endpoint;
      this.renderResult = render;
      this.onReset = onReset || (() => {});
      this.loaded = false;
      this.timer = 0;
      this.controller = null;

      this.form.addEventListener('input', (e) => { updateHint(e.target); this.schedule(); });
      this.form.addEventListener('change', () => this.schedule());
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

    schedule() {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.run(), DEBOUNCE_MS);
    }

    async run() {
      clearTimeout(this.timer);
      if (this.controller) this.controller.abort();
      const controller = new AbortController();
      this.controller = controller;
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
  function renderCompound(d, scope) {
    setOut(scope, 'totalBaseAmount', fmt.twd(d.totalBaseAmount));
    setOut(scope, 'gains', fmt.twd(d.gains));
    setOut(scope, 'gainRate', fmt.percent(d.gainRate));
    setOut(scope, 'totalSumAmount', fmt.twd(d.totalSumAmount));
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${fmt.twd(r[1])}</td><td>${fmt.twd(r[2])}</td><td>${fmt.twd(r[3])}</td><td>${fmt.twd(r[4])}</td></tr>`).join('');
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

    $('[data-add]', box).addEventListener('click', () => {
      const last = rows.lastElementChild;
      const lastPeriod = last ? parseNumber($('[data-kind="period"]', last).value) : FIRST_NEW_PERIOD - NEW_PERIOD_STEP;
      const period = Math.min((Number.isInteger(lastPeriod) ? lastPeriod : 1) + NEW_PERIOD_STEP, MAX_PERIOD);
      const row = template.content.firstElementChild.cloneNode(true);
      $$('label[data-for]', row).forEach((label) => {
        const input = $('input', label.closest('.field'));
        input.id = `${key}-${++rowSequence}`;
        label.htmlFor = input.id;
      });
      const [periodInput, valueInput] = $$('input', row);
      periodInput.value = period;
      valueInput.value = defaultValue();
      rows.appendChild(row);
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
    const money = d.foreign ? (n) => fmt.amount(n) : (n) => fmt.twd(n);
    setOut(scope, 'codeLabel', d.currency);
    setOut(scope, 'codeHead', `${d.currency} ${d.currencyLabel}`);
    setOut(scope, 'ytm', fmt.percent(d.yieldToMaturity));
    setOut(scope, 'bondAnnual', fmt.percent(d.bond.annualReturn));
    setOut(scope, 'homeAnnual', fmt.percent(d.home.annualReturn));
    setOut(scope, 'exchangeGain', fmt.twd(d.exchangeGain));

    $('tbody[data-summary-rows]', scope).innerHTML = BOND_SUMMARY.map(([key, label, kind]) => {
      const bond = kind === 'percent' ? fmt.percent(d.bond[key]) : money(d.bond[key]);
      const home = kind === 'percent' ? fmt.percent(d.home[key]) : fmt.twd(d.home[key]);
      return `<tr><th scope="row">${label}</th><td>${bond}</td><td data-foreign>${home}</td></tr>`;
    }).join('');

    setOut(scope, 'rowCount', `共 ${d.rows.length} 期`);
    $('tbody[data-rows]', scope).innerHTML = d.rows.map((r) =>
      `<tr><td>${r[0]}</td><td>${r[1]}</td><td>${money(r[2])}</td><td${r[3] != null ? ' class="is-accent"' : ''}>${r[3] == null ? '' : money(r[3])}</td>`
      + `<td>${fmt.twd(r[4])}</td><td${r[5] != null ? ' class="is-accent"' : ''}>${r[5] == null ? '' : fmt.twd(r[5])}</td>`
      + `<td>${fmt.twd(r[6])}</td><td>${fmt.percent(r[7])}</td></tr>`).join('');
  }

  /* ---------- 幣別轉換 ---------- */
  function renderCurrency(d, scope) {
    setOut(scope, 'result', `${d.to} ${fmt.amount(d.result)}`);
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

  /* ---------- 分頁切換：網址 #compound、#mortgage、#bond、#currency ---------- */
  const pages = {};

  function showPage() {
    const requested = location.hash.slice(1);
    const id = pages[requested] ? requested : 'compound';
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

    const mortgage = new Calculator('mortgage', { render: renderMortgage, onReset: () => { rateList.clear(); prepayList.clear(); } });
    const rateList = initPeriodList(mortgage, 'rate', 'tpl-rate-row', () => $('#m-rate').value);
    const prepayList = initPeriodList(mortgage, 'prepay', 'tpl-prepay-row', () => DEFAULT_PREPAYMENT);
    pages.mortgage = mortgage;

    pages.bond = initBond();
    pages.currency = initCurrency();

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
    return new Calculator('bond', { render: renderBond, onReset: () => sync(false) });
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
    const calculator = new Calculator('currency', {
      render: renderCurrency,
      onReset: () => { sync(from, false); sync(to, false); },
    });
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
