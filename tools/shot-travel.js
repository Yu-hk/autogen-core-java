const { chromium } = require('playwright');

const CHROME = 'C:/Users/14928/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
const OUT = 'D:/workspace/autogen-core-java/docs';

/**
 * 只截图，不跑模型：验证第四个场景「旅游规划」是否出现在页面上、
 * 成员卡片是否正确显示 7 个工具、未配 Key 时提示是否生效。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForSelector('.scene');

  const scenes = await page.$$eval('.scene .t', els => els.map(e => e.textContent.trim()));
  console.log('场景:', scenes.join(' / '));

  // 点最后一个场景（旅游规划）
  const last = (await page.$$('.scene')).pop();
  await last.click();
  await page.waitForTimeout(600);

  const info = await page.evaluate(() => ({
    active: document.querySelector('.scene.on .t')?.textContent,
    member: document.querySelector('.member .n')?.textContent,
    badge: document.querySelector('.member .badge')?.textContent || '(无)',
    hint: document.getElementById('taskHint').textContent,
    rounds: document.getElementById('maxRounds').value,
  }));
  console.log('当前场景:', info.active);
  console.log('成员:', info.member, '徽章:', info.badge, '轮次:', info.rounds);
  console.log('提示:', info.hint.slice(0, 80));

  await page.screenshot({ path: `${OUT}/travel-console.png`, fullPage: false });
  console.log('截图:', `${OUT}/travel-console.png`);
  console.log('页面错误:', errors.length ? errors : '无');

  await browser.close();
})();
