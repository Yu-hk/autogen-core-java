const { chromium } = require('playwright');

const CHROME = 'C:/Users/14928/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:' + (process.env.PORT || '8097') + '/';
const OUT = 'D:/workspace/autogen-core-java/docs';
const WS = process.env.WS || 'D:/workspace/_tmp_agent_ws/abrspro2_probe';

/**
 * 在页面上问一句「分析一下这个项目」，验证"分析类问题也能看见文件"。
 *
 * 这句话不含任何技术域词，路由必然落到 general。修复前 general 一个工具都没有，
 * 回答是"我这次没有文件系统和工具权限，读不到"；修复后它应该立刻 list_files。
 *
 * 断言三件事：路由落到 general、时间线上出现只读工具调用、请求里没有写工具。
 * 截图给的是"人看到的证据"：左栏工作目录、成员徽标、时间线上的工具行。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  // 等价于"用户在弹层里选了这个目录"：写 localStorage 后刷新
  await page.evaluate(ws => localStorage.setItem('ws.path', ws), WS);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelectorAll('.scene').length >= 7,
    null, { timeout: 30000 });

  const wsLabel = await page.textContent('#wsPath');
  console.log('左栏工作目录:', wsLabel.trim());

  await page.fill('#taskInput', '分析一下这个项目');
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
  }, null, { timeout: 600000 }).catch(() => console.log('等待完成超时'));

  const state = await page.evaluate(() => ({
    phase: document.getElementById('phase')?.textContent?.trim(),
    members: document.getElementById('members')?.textContent?.replace(/\s+/g, ' ').trim(),
    tools: Array.from(document.querySelectorAll('#timeline .tl-item.tool'))
      .map(el => el.textContent.replace(/\s+/g, ' ').trim().slice(0, 100)),
    answer: document.getElementById('timeline').textContent.replace(/\s+/g, ' ').trim().slice(-600),
  }));

  console.log('阶段:', state.phase);
  console.log('成员:', state.members);
  console.log('工具调用条数:', state.tools.length);
  state.tools.slice(0, 8).forEach(t => console.log('  -', t));
  console.log('回答结尾:', state.answer.slice(-260));

  await page.screenshot({ path: `${OUT}/console-analysis-readonly.png`, fullPage: false });

  const wrote = state.tools.some(t => /write_file|edit_file|run_command/.test(t));
  const read = state.tools.some(t => /list_files|read_file|read_dir|search_code/.test(t));
  console.log('有只读工具调用:', read, '/ 出现写工具:', wrote);
  console.log('页面错误:', errors.length ? errors : '(无)');
  await browser.close();
  process.exit(read && !wrote ? 0 : 1);
})();
