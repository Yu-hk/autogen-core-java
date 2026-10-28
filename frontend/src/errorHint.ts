import type { ErrorCode } from './types';

/**
 * 失败之后的下一步建议。
 *
 * 以前所有失败都只有一句红字，用户只能靠猜：重发？改输入？还是去重启服务？
 * 三件事的成本差着量级——重发点一下就完，改输入得想清楚哪里错了，重启服务得有人去机房。
 * 把这件事交给用户猜，等于让他在最没有信息的时候做选择。
 *
 * 文案只回答一个问题：**我现在该做什么**。原因交给 message 原文，
 * 那是给排查用的，不是给操作的人看的。
 */
export interface ErrorHint {
  /** 状态栏与分隔线上的短标题，替代原来笼统的「出错了」 */
  title: string;
  /** 下一步该做什么，一句话 */
  hint: string;
  /** 原样重发有没有可能成功；false 时按钮旁会提示「先解决再发」 */
  retryable: boolean;
}

const HINTS: Record<ErrorCode, ErrorHint> = {
  model_unavailable: {
    title: '模型不可用',
    hint: '服务端连不上配置的模型。重发同样会失败，需要先确认 Ollama 已启动、或 model.* 配置改对了再试。',
    retryable: false,
  },
  timeout: {
    title: '运行超时',
    hint: '这一轮跑太久了，通常是任务太大或模型卡住。把问题缩小（少给几个文件、限定一个模块）再发一次。',
    retryable: true,
  },
  bad_request: {
    title: '参数不成立',
    hint: '这次的文件、工作目录或参数没通过校验。改掉之后再发，原样重发还是同样的错。',
    retryable: false,
  },
  internal: {
    title: '服务内部错误',
    hint: '可以重发一次；如果反复出现，把下面的请求 ID 给运维，能直接定位到这次运行的日志。',
    retryable: true,
  },
};

/**
 * 按 code 取建议。
 *
 * 未知 code（服务端先上线了新类型、页面还是旧静态资源）一律退回 `internal`：
 * 少一句精准引导不算事故，页面因为一个没有分支的值而不显示任何东西才是。
 */
export function hintFor(code?: string): ErrorHint {
  if (code && code in HINTS) {
    return HINTS[code as ErrorCode];
  }
  return HINTS.internal;
}

/**
 * 拼出给用户看的完整一段：标题 + 建议 + （可选）请求 ID。
 *
 * 请求 ID 放在最后而不是最前，是因为它对用户没有意义，只在对客服报障那一刻才有用——
 * 需要它的人会往下找，不需要的人可以忽略。
 */
export function describeFailure(code: string | undefined, requestId?: string): string {
  const { title, hint } = hintFor(code);
  const tail = requestId ? `（请求 ID ${requestId}）` : '';
  return `${title}：${hint}${tail}`;
}
