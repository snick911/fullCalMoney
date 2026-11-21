/* 在畫面繪製前套用主題與字體大小，避免先閃一下預設樣式 */
(function () {
  var THEMES = ['light', 'eye-dark', 'dark', 'sepia', 'ocean', 'sakura'];
  var FONTS = ['s', 'm', 'l', 'xl'];
  var root = document.documentElement;
  var theme = null;
  var font = null;
  try {
    theme = localStorage.getItem('fm.theme');
    font = localStorage.getItem('fm.font');
  } catch (e) {
    /* 私密瀏覽或封鎖網站資料時讀不到，改用預設值 */
  }
  if (THEMES.indexOf(theme) < 0) {
    theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'eye-dark' : 'light';
  }
  if (FONTS.indexOf(font) < 0) {
    font = 'm';
  }
  root.setAttribute('data-theme', theme);
  root.setAttribute('data-font', font);
})();
