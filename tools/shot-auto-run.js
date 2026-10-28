const { chromium } = require('playwright');

const CHROME = 'C:/Users/14928/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
const OUT = 'D:/workspace/autogen-core-java/docs';

/**
 * 验证"用户只提问"这条路径：页面上没有展开的场景选择，只输入一句话，
 * 由后端判断该用几个角色、挂不挂工具，并把判断依据写进时间线。
 *
 * 同一个脚本截两张图：初始态（说明默认体验）与跑完态（说明自动派活的结果）。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  // 场景节点在折叠区里，不可见——用数量判断加载完，不能用 waitForSelector（它等的是可见）
  await page.waitForFunction(() => document.querySelectorAll('.scene').length >= 6,
    null, { timeout: 30000 });

  // ---- 初始态：默认没有任何场景被选中，场景列表是收起的 ----
  const idle = await page.evaluate(() => ({
    scenesOpen: document.getElementById('advanced')?.open,
    picked: document.querySelectorAll('.scene.on').length,
    members: document.getElementById('members')?.textContent?.trim(),
    placeholder: document.querySelector('#thread .placeholder .big')?.textContent,
    input: document.getElementById('taskInput')?.value,
  }));
  console.log('初始态 | 场景区展开:', idle.scenesOpen, '| 已选场景:', idle.picked);
  console.log('  成员区:', idle.members);
  console.log('  对话区:', idle.placeholder);
  console.log('  输入框:', JSON.stringify(idle.input), '(应为空，不能预填演示任务)');
  await page.screenshot({ path: `${OUT}/console-auto-idle.png` });

  // ---- 只输入一句话就发出去 ----
  await page.fill('#taskInput', '橘子洲的经纬度是多少');
  await page.click('#runBtn');

  await page.waitForFunction(() => {
    const items = document.querySelectorAll('#timeline .tl-item');
    return Array.from(items).some(el => el.textContent.includes('自动选择'));
  }, null, { timeout: 60000 }).catch(() => console.log('等待路由事件超时'));

  await page.waitForFunction(() => {
    const phase = document.getElementById('phase')?.textContent || '';
    return phase.includes('已完成') || phase.includes('出错');
  }, null, { timeout: 300000 }).catch(() => console.log('等待完成超时'));

  await page.waitForTimeout(800);

  const done = await page.evaluate(() => ({
    label: document.getElementById('memberLabel')?.textContent,
    members: document.getElementById('members')?.textContent?.trim(),
    route: Array.from(document.querySelectorAll('#timeline .tl-item.route'))
      .map(el => el.textContent).join(' | '),
    phase: document.getElementById('phase')?.textContent,
    tools: document.getElementById('statTool')?.textContent,
    picked: document.querySelectorAll('.scene.on').length,
    answer: document.querySelector('#thread .msg-bubble')?.textContent?.slice(0, 260),
  }));
  console.log('跑完 | 状态:', done.phase, '| 工具:', done.tools, '| 手动选中项:', done.picked);
  console.log('  成员标签:', done.label);
  console.log('  成员:', done.members?.replace(/\s+/g, ' '));
  console.log('  时间线路由:', done.route?.replace(/\s+/g, ' '));
  if (done.answer) console.log('  回答片段:', done.answer.replace(/\s+/g, ' '));

  await page.screenshot({ path: `${OUT}/console-auto-run.png` });
  console.log('截图:', `${OUT}/console-auto-run.png`);
  console.log('页面错误:', errors.length ? errors.slice(0, 3) : '无');

  await browser.close();
})();
