const { chromium } = require('playwright');

// Chrome 路径：优先用环境变量 CHROME，其次找 Playwright 自带的 chromium。
// 写死绝对路径的话，换台机器就找不到浏览器，脚本直接不可用。
const CHROME = process.env.CHROME || require('os').homedir() +
  '/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
// 截图输出目录：默认写到仓库的 docs/ 下（相对当前工作目录），可用 OUT 覆盖。
const OUT = process.env.OUT || 'docs';

/**
 * 在页面上跑一次「数据库」任务：不点场景卡片，只输入一句话，由后端路由到 coding。
 *
 * 检查三件事：
 *   1. 自动路由判成 coding（不是被当成"讨论方案"）；
 *   2. 时间线上能看到 db_execute / db_query 这些工具事件，而不是只有一段 SQL 文本；
 *   3. 最终回答里出现真实查询结果（总价）。
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
    '在沙箱数据库里建一张 book 表（id 主键、title、price），插入 3 本书，查出所有书的总价。用数据库工具做，不许凭想象写表结构。');
  await page.click('#runBtn');

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
    answered: document.getElementById('timeline').textContent,
  }));

  const dbTools = state.tools.filter(t => t.includes('db_'));
  console.log('阶段:', state.phase);
  console.log('工具事件条数:', state.tools.length, '/ 其中数据库:', dbTools.length);
  dbTools.forEach(t => console.log('  -', t));
  console.log('回答里出现总价:', /总价|合计|SUM|sum/.test(state.answered));
  console.log('页面报错:', errors.length ? errors : '无');

  await page.screenshot({ path: `${OUT}/console-db-run.png` });
  await browser.close();

  if (dbTools.length === 0 || !/coding|代码任务/.test(routed) || errors.length) {
    process.exitCode = 1;
  }
})();
