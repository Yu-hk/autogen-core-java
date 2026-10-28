package com.example.agentcore.support;

/**
 * "参数不对，改了再来"这一类工具失败——不该算成工具坏了。
 *
 * <p>区分这两种失败是实测逼出来的：{@code edit_file} 的 {@code old_text} 在文件里找不到，
 * 属于模型自己写错了锚点，重读一遍改一下就能成功。但原先它和"工具内部炸了"一样被包成
 * {@code ERROR:}，进同一个熔断计数器——连续两次锚点没对上，工具就被整个会话禁用，
 * 模型只好去写 python 脚本按字节改文件，工作目录里还留一堆临时文件。</p>
 *
 * <p>所以单独一个类型：{@link com.example.agentcore.agent.ToolRegistry} 把它转成带
 * {@code retryable=true} 的 JSON（不是 {@code ERROR} 前缀），熔断器不计数，
 * 重复调用检测也照常放行。</p>
 */
public class RetryableToolError extends RuntimeException {

    public RetryableToolError(String message) {
        super(message);
    }

    public RetryableToolError(String message, Throwable cause) {
        super(message, cause);
    }
}
