package com.example.agentcore.support;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * 本地模型接入的必要修复：让回环地址绕过系统代理。
 *
 * <p>JDK 的 {@code HttpClient} 默认使用 {@link ProxySelector#getDefault()}，而 Windows 上这个默认实现
 * 会读取系统（IE/注册表）代理设置。如果机器上配了全局代理（企业网关、抓包工具、本地代理客户端），
 * 发往 {@code 127.0.0.1:11434} 的请求也会被送到代理服务器，表现为：连接看似建立、但永远收不到响应，
 * 或直接抛 {@code ConnectException}。</p>
 *
 * <p>{@code curl} 不会复现，因为它对 localhost 有内建豁免——这就是"命令行能通、Java 不通"的原因。</p>
 *
 * <p>这里只对回环地址禁代理，其余地址继续交给原选择器，因此云端模型仍可走代理访问。</p>
 */
public final class ProxySupport {

    private static boolean installed;

    private ProxySupport() {
    }

    /** 幂等安装；必须在创建 HTTP 客户端之前调用。 */
    public static synchronized void bypassProxyForLocalhost() {
        if (installed) {
            return;
        }
        ProxySelector original = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (isLoopback(uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return original == null ? List.of(Proxy.NO_PROXY) : original.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                if (original != null) {
                    original.connectFailed(uri, address, failure);
                }
            }
        });
        installed = true;
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || host.startsWith("127.");
    }
}
