const { chromium } = require('playwright');

// Chrome 路径：优先用环境变量 CHROME，其次找 Playwright 自带的 chromium。
// 写死绝对路径的话，换台机器就找不到浏览器，脚本直接不可用。
const CHROME = process.env.CHROME || require('os').homedir() +
  '/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
// 截图输出目录：默认写到仓库的 docs/ 下（相对当前工作目录），可用 OUT 覆盖。
const OUT = process.env.OUT || 'docs';

/**
 * 工作目录选择器：不跑模型，只验证"用户能选到本机目录"这条交互。
 *
 * 检查四件事：
 *   1. 打开弹层能看到起点（含被标红的主目录 / 盘符根）；
 *   2. 输入绝对路径能进去，子目录带 git / maven 标记；
 *   3. 选中后左栏按钮变成实际路径，并存进 localStorage；
 *   4. 整个过程中页面零 JS 报错。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelectorAll('.scene').length >= 7,
    null, { timeout: 30000 });
  await page.screenshot({ path: `${OUT}/console-workspace-idle.png` });

  // 1. 打开选择器：默认沙箱 + 起点列表
  await page.click('#wsPath');
  await page.waitForSelector('#wsModal:not([hidden]) .ws-item', { timeout: 10000 });
  const roots = await page.$$eval('#wsModal .ws-item', els =>
    els.map(el => el.textContent.replace(/\s+/g, ' ').trim()));
  console.log('起点:', roots.slice(0, 6).join(' | '));
  const blockedRoots = await page.$$eval('#wsModal .ws-item.blocked', els => els.length);
  console.log('被拦的起点数:', blockedRoots);
  await page.screenshot({ path: `${OUT}/console-workspace-picker.png` });

  // 1.5 一键选它：不点进目录、不手输路径，直接把这行定为工作目录
  {
    const before = await page.evaluate(() => localStorage.getItem('ws.path'));
    const target = await page.$$eval('#wsModal .ws-item:not(.blocked) .ws-pick', els => {
      // 起点列表没有 git/maven 标记，直接点第一个可选行
      if (els.length) { els[0].click(); return els[0].closest('.ws-item').querySelector('.n').textContent; }
      return null;
    });
    await page.waitForTimeout(300);
    const after = await page.evaluate(() => localStorage.getItem('ws.path'));
    const modalHidden = await page.evaluate(() => document.querySelector('#wsModal').hidden);
    console.log('一键选它:', target, '| localStorage:', before, '->', after, '| 弹层已关:', modalHidden);
    if (!target || after === before || !modalHidden) throw new Error('选它按钮没生效');
    // 还原成默认状态，让后面的步骤从干净状态开始
    await page.evaluate(() => localStorage.removeItem('ws.path'));
    await page.reload({ waitUntil: 'networkidle' });
    await page.click('#wsPath');
    await page.waitForSelector('#wsModal:not([hidden]) .ws-item', { timeout: 10000 });
    await page.fill('#wsInput', process.env.WS_ROOT || 'D:/workspace');
    await page.click('#wsGo');
    await page.waitForFunction(() =>
      document.querySelector('#wsCur').textContent.includes('D:/workspace'),
      null, { timeout: 10000 });
    await page.waitForTimeout(400);
  }

  // 2. 输入 D:/workspace：看 git / maven 标记
  await page.fill('#wsInput', process.env.WS_ROOT || 'D:/workspace');
  await page.click('#wsGo');
  await page.waitForFunction(() =>
    document.querySelector('#wsCur').textContent.includes('D:/workspace'),
    null, { timeout: 10000 });
  await page.waitForTimeout(400);
  const items = await page.$$eval('#wsModal .ws-item', els =>
    els.map(el => ({
      name: el.querySelector('.n')?.textContent || '',
      tags: Array.from(el.querySelectorAll('.tag')).map(t => t.textContent).join(',')
    })));
  const withGit = items.filter(i => i.tags.includes('git'));
  const withMaven = items.filter(i => i.tags.includes('maven'));
  console.log(`目录数=${items.length} 带git=${withGit.length} 带maven=${withMaven.length}`);
  console.log('样例:', items.slice(0, 4).map(i => `${i.name}[${i.tags}]`).join(' | '));
  await page.screenshot({ path: `${OUT}/console-workspace-list.png` });

  // 3. 进入目标目录并确认选它。目录名可用 WS_NAME 覆盖（默认 demo-project）：
  // 这个脚本要有一个真实存在的目录才能跑，名字写死别人的项目名不合适。
  const wsName = process.env.WS_NAME || 'demo-project';
  const target = items.find(i => i.name === wsName);
  if (!target) throw new Error('起点目录下没有 ' + wsName + '（用 WS_NAME 指定）');
  await page.click(`#wsModal .ws-item:has(.n:text-is("${wsName}"))`);
  await page.waitForFunction((name) =>
    document.querySelector('#wsCur').textContent.includes(name),
    wsName, { timeout: 10000 });
  await page.waitForTimeout(300);
  const warn = await page.textContent('#wsWarn');
  console.log('当前目录提示:', warn.trim());
  await page.screenshot({ path: `${OUT}/console-workspace-${wsName}.png` });

  await page.click('#wsUse');
  await page.waitForTimeout(300);
  const chip = await page.textContent('#wsPath');
  const stored = await page.evaluate(() => localStorage.getItem('ws.path'));
  console.log('左栏显示:', chip.trim());
  console.log('localStorage:', stored);
  await page.screenshot({ path: `${OUT}/console-workspace-chosen.png` });

  console.log('页面错误:', errors.length ? errors : '(无)');
  await browser.close();

  if (!stored || !stored.replace(/\\/g, '/').endsWith(wsName)) {
    throw new Error('选择没有落到 localStorage：' + stored);
  }
  if (errors.length) {
    throw new Error('页面有 JS 报错：' + errors.join(' / '));
  }
  console.log('OK');
})();
