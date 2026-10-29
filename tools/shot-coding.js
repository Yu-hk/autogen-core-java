const { chromium } = require('playwright');

// Chrome 路径：优先用环境变量 CHROME，其次找 Playwright 自带的 chromium。
// 写死绝对路径的话，换台机器就找不到浏览器，脚本直接不可用。
const CHROME = process.env.CHROME || require('os').homedir() +
  '/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
// 截图输出目录：默认写到仓库的 docs/ 下（相对当前工作目录），可用 OUT 覆盖。
const OUT = process.env.OUT || 'docs';

/**
 * 在页面上跑一次「写代码」：不点场景卡片，只输入一句话，由后端路由到 coding。
 *
 * 检查两件事：
 *   1. 页面能显示"改文件 / 跑命令"这类工具事件（不是只有一串文字）；
 *   2. 跑完之后时间线上能看到编译、运行退出码，以及最终 PASS。
 *
 * 任务选"修 bug"而不是"从零写"：几十秒能跑完，且改动小、截图信息密度高。
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

  await page.fill('#taskInput',
    'src/Calc.java 里的 average 算错了，现在跑 src/Main.java 会输出 FAIL。先读代码定位原因，改到输出 PASS。只改实现，不要改 Main.java 里的期望值。');
  await page.click('#runBtn');

  // 路由结论
  await page.waitForFunction(() => {
    const items = document.querySelectorAll('#timeline .tl-item');
    return Array.from(items).some(el => el.textContent.includes('自动选择'));
  }, null, { timeout: 60000 }).catch(() => console.log('等待路由事件超时'));

  const routed = await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('#timeline .tl-item'))
      .find(el => el.textContent.includes('自动选择'));
    return item ? item.textContent.replace(/\s+/g, ' ').trim() : '(无)';
  });
  console.log('路由:', routed);

  await page.waitForFunction(() => {
    const phase = document.getElementById('phase')?.textContent || '';
    return phase.includes('已完成') || phase.includes('出错');
  }, null, { timeout: 180000 }).catch(() => console.log('等待完成超时'));

  const state = await page.evaluate(() => ({
    phase: document.getElementById('phase')?.textContent?.trim(),
    tools: Array.from(document.querySelectorAll('#timeline .tl-item.tool'))
      .map(el => el.textContent.replace(/\s+/g, ' ').trim().slice(0, 120)),
    hitPASS: document.getElementById('timeline').textContent.includes('PASS'),
    members: document.getElementById('members')?.textContent?.replace(/\s+/g, ' ').trim(),
  }));

  console.log('阶段:', state.phase);
  console.log('成员:', state.members);
  console.log('工具事件条数:', state.tools.length);
  state.tools.forEach(t => console.log('  -', t));
  console.log('时间线出现 PASS:', state.hitPASS);
  console.log('页面报错:', errors.length ? errors : '无');

  await page.screenshot({ path: `${OUT}/console-coding-run.png` });
  await browser.close();

  // 让脚本退出码能反映结论：UI 上没出现工具事件就等于这条路没通
  if (state.tools.length === 0 || !state.hitPASS || errors.length) {
    process.exitCode = 1;
  }
})();
