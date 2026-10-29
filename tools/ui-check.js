const { chromium } = require('playwright');

// 截图输出目录：默认写到仓库的 target/ 下（相对当前工作目录），可用 OUT 覆盖。
const OUT = process.env.OUT || 'target';
// Chrome 路径：优先用环境变量 CHROME，其次找 Playwright 自带的 chromium。
const CHROME = process.env.CHROME || require('os').homedir() +
  '/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';

/**
 * 页面联调：现在页面只有真实模型一条路，所以每次运行都要等模型回答。
 * 为了别把联调拖成几分钟，先把轮次压到 1、任务换成一句话。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];

  const page = await browser.newPage({ viewport: { width: 1480, height: 920 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelectorAll('.scene').length >= 6,
    null, { timeout: 30000 });

  const ready = await page.evaluate(() => ({
    chip: document.getElementById('modelChip').textContent,
    health: document.getElementById('healthText').textContent,
    runDisabled: document.getElementById('runBtn').disabled
  }));
  if (ready.runDisabled) {
    errors.push('模型未就绪，无法运行：' + ready.health);
  }

  // 1. 桌面：跑一轮，抓完整对话
  await page.fill('#taskInput', '用一句话说明订单导出为什么要异步化。');
  await page.click('#runBtn');
  try {
    await page.waitForFunction(
      () => document.getElementById('phase').textContent === '已完成',
      { timeout: 240000 }
    );
  } catch {
    errors.push('首轮运行超时（模型可能还在加载）');
  }
  await page.waitForTimeout(800);
  await page.screenshot({ path: OUT + '/web-desktop.png' });

  const stats = await page.evaluate(() => ({
    round: document.getElementById('statRound').textContent,
    msg: document.getElementById('statMsg').textContent,
    delta: document.getElementById('statDelta').textContent,
    tool: document.getElementById('statTool').textContent,
    bubbles: document.querySelectorAll('.msg').length,
    timeline: document.querySelectorAll('.tl-item').length
  }));

  // 2. 移动：检查响应式（整页滚动），不必等一次完整运行
  const mobile = await browser.newPage({ viewport: { width: 430, height: 900 } });
  await mobile.goto(BASE, { waitUntil: 'networkidle' });
  await mobile.waitForFunction(() => document.querySelectorAll('.scene').length >= 6,
    null, { timeout: 30000 });
  await mobile.screenshot({ path: OUT + '/web-mobile.png', fullPage: true });

  // 3. 运行中：抓打字机进行中的一帧
  const live = await browser.newPage({ viewport: { width: 1480, height: 920 } });
  await live.goto(BASE, { waitUntil: 'networkidle' });
  await live.waitForFunction(() => document.querySelectorAll('.scene').length >= 6,
    null, { timeout: 30000 });
  await live.fill('#taskInput', '用一句话说明游标分页相比 offset 的优势。');
  await live.click('#runBtn');

  let streamingShot = false;
  try {
    await live.waitForSelector('.bubble.streaming', { timeout: 240000 });
    await live.waitForTimeout(900);
    await live.screenshot({ path: OUT + '/web-streaming.png' });
    streamingShot = true;
  } catch {
    errors.push('流式截图超时');
  }

  await browser.close();
  console.log(JSON.stringify({ ready, stats, streamingShot, errors }, null, 2));
})();
