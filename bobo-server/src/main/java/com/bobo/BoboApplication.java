package com.bobo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

/**
 * bobo 校园二手交易平台 —— 后端启动类。
 *
 * <p><b>为什么排除 Spring AI 的 DeepSeek 自动配置</b>：它无条件创建
 * {@code DeepSeekApi} / {@code DeepSeekChatModel}，而 {@code DeepSeekApi} 的构造器
 * 对空 api-key 直接 {@code Assert.hasText} 抛异常——结果是「没配 key 应用就起不来」，
 * 与本项目「AI 是可选增强」的设计前提正好相反（实测确认过：不排除则启动失败）。
 *
 * <p>本项目改为在 {@code com.bobo.ai.AiChatConfig} 里按条件手工装配
 * （{@code @ConditionalOnProperty} + {@code NoopChatInvoker} 兜底），
 * 既保住「没 key 也能跑全站业务」，也让 Spring AI 的类型只出现在一个文件里。
 *
 * @author bobo
 */
@SpringBootApplication(exclude = {
        org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration.class
})
@ConfigurationPropertiesScan("com.bobo.config.props")
@MapperScan("com.bobo.mapper")
public class BoboApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(BoboApplication.class, args).getEnvironment();
        // 端口从环境里读，而不是写死 9080：容器/本地都可能用 SERVER_PORT 覆盖，
        // 写死的话启动日志会指向一个根本没在监听的端口（误导排查）。
        String port = env.getProperty("server.port", env.getProperty("SERVER_PORT", "9080"));
        boolean docsEnabled = env.getProperty("springdoc.api-docs.enabled", Boolean.class, true);
        System.out.printf("""

                ==========================================================
                  bobo 校园二手交易平台 后端已启动
                  监听端口: %s
                  接口文档: %s
                  运行环境: %s
                ==========================================================
                %n""",
                port,
                docsEnabled ? "http://localhost:" + port + "/swagger-ui.html (生产由 nginx 默认关闭)" : "已关闭",
                String.join(",", env.getActiveProfiles()));
    }
}
