import { defineConfig } from 'vitest/config';

/**
 * 前端测试配置。
 *
 * <p>单独一份而不是塞进 `vite.config.ts`：那份配置管的是构建产物（输出到后端 static 目录、
 * base、代理），测试一点都不需要。混在一起之后，哪天为了测试改个选项，构建结果可能跟着变。</p>
 *
 * <p>默认环境是 node——大部分用例是纯函数，跑得比 jsdom 快一个数量级。需要
 * localStorage / EventSource 的用例在文件头用 `// @vitest-environment jsdom` 单独声明。</p>
 */
export default defineConfig({
  test: {
    environment: 'node',
    // 分开写两条而不是 {ts,tsx}：大括号写法在这套 vitest 上没匹配到 .tsx，
    // 表现为"渲染测试单跑能过、全量却不在列表里"，排查起来很费时间
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // 默认的 worker threads 在本机会去写系统临时目录并被拦（EPERM），
    // 换进程池之后就不走那条路径；对这类小体量测试没有性能代价
    pool: 'forks',
  },
});
