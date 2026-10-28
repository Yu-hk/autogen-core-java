package com.example.agentcore.web;

/**
 * 模型当前不可用（服务没起、模型没拉、缺 Key）。
 *
 * <p>刻意用受检异常：这是"运行条件不满足"，不是程序缺陷，调用方必须给出人话提示，
 * 不能当成 bug 往上抛。</p>
 */
public class ModelUnavailable extends Exception {

    public ModelUnavailable(String message) {
        super(message);
    }
}
