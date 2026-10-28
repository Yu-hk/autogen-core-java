const { chromium } = require('playwright');

const CHROME = 'C:/Users/14928/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
const OUT = 'D:/workspace/autogen-core-java/docs';

/**
 * 跑一次真实的旅游场景并截图：验证工具事件、两棒分工与最终回答都能正常出现在页面上。
 * 场景取列表最后一个（当前是「分工流水线」：主模型查数据 → 第二套模型写终稿）。
 * 收集侧 10 秒左右，总结侧是本地 20B 纯 CPU，可能要 1~3 分钟，所以等待上限放到 10 分钟。
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForSelector('.scene');

  // 4 个场景里取最后一个：现在是「分工流水线」
  const scenes = await page.$$('.scene');
  await scenes[scenes.length - 1].click();
  await page.waitForTimeout(400);

  await page.fill('#taskInput', '查一下长沙橘子洲和岳麓山的经纬度，再给我 2 个橘子洲附近的美食。');
  await page.click('#runBtn');

  // 等模型跑完：状态变「已完成」，或已有工具事件
  // 注意 waitForFunction 的第二个参数是 arg，options 要放第三个，否则 timeout 不生效（默认 30s）
  await page.waitForFunction(() => {
    const phase = document.getElementById('phase')?.textContent || '';
    const tools = Number(document.getElementById('statTool')?.textContent || '0');
    return phase.includes('已完成') || phase.includes('出错') || tools >= 1;
  }, null, { timeout: 600000 }).catch(() => console.log('等待超时，按当前状态截图'));

  // 工具已经回来时再多等一会儿，让模型把最终回答写完
  await page.waitForFunction(() => {
    const phase = document.getElementById('phase')?.textContent || '';
    return phase.includes('已完成') || phase.includes('出错');
  }, null, { timeout: 600000 }).catch(() => {});

  await page.waitForTimeout(1200);

  const info = await page.evaluate(() => ({
    scene: document.querySelector('.scene.on .t')?.textContent,
    rounds: document.getElementById('maxRounds')?.value,
    phase: document.getElementById('phase')?.textContent,
    tools: document.getElementById('statTool')?.textContent,
    events: document.querySelectorAll('#timeline > *').length,
    text: document.querySelector('#thread .msg-bubble')?.textContent?.slice(0, 300),
  }));
  console.log('场景:', info.scene, '| 轮次:', info.rounds, '| 状态:', info.phase, '| 工具数:', info.tools);
  console.log('时间线节点:', info.events);
  if (info.text) console.log('回答片段:', info.text.replace(/\s+/g, ' '));

  await page.screenshot({ path: `${OUT}/console-pipeline.png`, fullPage: false });
  console.log('截图:', `${OUT}/console-pipeline.png`);
  console.log('页面错误:', errors.length ? errors.slice(0, 3) : '无');

  await browser.close();
})();
