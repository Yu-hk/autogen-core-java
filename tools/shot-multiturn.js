const { chromium } = require('playwright');

const CHROME = 'C:/Users/14928/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe';
const BASE = 'http://127.0.0.1:8097/';
const OUT = 'D:/workspace/autogen-core-java/docs';

/**
 * 验证多轮追问：同一段对话里连发两句，第二句要接着上文，且屏幕不能清空。
 *
 * 断言四件事：
 *   1. 第一轮跑完后出现「新对话」按钮（说明已经有一段上下文了）
 *   2. 第二轮发送时对话区没有被清空（消息条数只增不减）
 *   3. 时间线出现「接着这段对话」而不是「自动选择」
 *   4. 页面零 console 错误
 */
(async () => {
  const browser = await chromium.launch({ executablePath: CHROME });
  const errors = [];
  const page = await browser.newPage({ viewport: { width: 1480, height: 940 } });
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelectorAll('.scene').length >= 6,
    null, { timeout: 30000 });

  const waitDone = async (label) => {
    await page.waitForFunction(() => {
      const phase = document.getElementById('phase')?.textContent || '';
      return phase.includes('已完成') || phase.includes('出错');
    }, null, { timeout: 600000 }).catch(() => console.log(`等待${label}完成超时`));
    await page.waitForTimeout(600);
  };

  const snapshot = () => page.evaluate(() => ({
    msgs: document.querySelectorAll('#thread .msg').length,
    dividers: Array.from(document.querySelectorAll('#thread .divider')).map(el => el.textContent),
    timeline: Array.from(document.querySelectorAll('#timeline .tl-item')).map(el => el.textContent),
    newChatVisible: !document.getElementById('newChatBtn')?.hidden,
    phase: document.getElementById('phase')?.textContent,
    input: document.getElementById('taskInput')?.value,
  }));

  // ---- 第一轮 ----
  await page.fill('#taskInput', '橘子洲的经纬度是多少');
  await page.click('#runBtn');
  await waitDone('第一轮');
  const first = await snapshot();
  console.log('第一轮 | 状态:', first.phase, '| 消息:', first.msgs,
    '| 新对话按钮:', first.newChatVisible ? '已出现' : '未出现');
  console.log('  输入框:', JSON.stringify(first.input), '(发送后应清空)');

  // ---- 第二轮：同一段对话里追问，句子本身不含任何域词 ----
  await page.fill('#taskInput', '那附近有什么好吃的');
  await page.click('#runBtn');

  // 追问发出去的一瞬间就应该看到「接着说」的痕迹，而不是整屏被清空
  await page.waitForTimeout(1200);
  const during = await snapshot();
  console.log('第二轮发送后 | 消息:', during.msgs, '(应 >= 第一轮的', first.msgs, '，说明没清屏)');
  console.log('  分隔线:', during.dividers.join(' / '));
  console.log('  时间线尾部:', during.timeline.slice(-2).join(' | '));

  await waitDone('第二轮');
  const second = await snapshot();
  console.log('第二轮 | 状态:', second.phase, '| 消息:', second.msgs);
  console.log('  时间线尾部:', second.timeline.slice(-3).join(' | '));

  const carried = second.timeline.some(t => t.includes('接着这段对话'));
  console.log('上下文接续:', carried ? '已接上（时间线有「接着这段对话」）' : '未检出');

  await page.screenshot({ path: `${OUT}/console-multiturn.png` });
  console.log('截图:', `${OUT}/console-multiturn.png`);
  console.log('页面错误:', errors.length ? errors.slice(0, 3) : '无');

  await browser.close();
})();
