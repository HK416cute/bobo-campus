package com.bobo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 异步执行配置。
 *
 * <p>为什么需要：AI 调用是「慢且可能失败」的外部依赖，绝不能挂在用户主链路上。
 * <ul>
 *   <li>写调用流水（{@code bobo_ai_call_log}）——用户不关心，异步写；</li>
 *   <li>内容审核、运营周报——异步跑，失败重试，不阻塞发布/下单。</li>
 * </ul>
 *
 * <p>线程池刻意配小：AI 的瓶颈在远端模型而不是本机线程，排太多线程只会把超时堆在一起；
 * 队列满时由 {@link ThreadPoolExecutor.CallerRunsPolicy} 退回调用线程，
 * 宁可慢一点也不要静默丢任务（丢日志 = 丢成本账）。
 *
 * <p><b>Bean 名不能叫 {@code aiExecutor}</b>：那会与业务组件
 * {@link com.bobo.ai.AiExecutor} 的默认 Bean 名（类名首字母小写）撞名，
 * 启动时抛 {@code BeanDefinitionOverrideException} 直接起不来。
 * 两者是不同层次的东西——一个是线程池，一个是 AI 调用编排器——名字必须能区分开。
 *
 * @author bobo
 */
@Configuration
@EnableAsync
public class AiAsyncConfig {

    /** AI 异步任务线程池的 Bean 名，被 {@code @Async(AiAsyncConfig.AI_TASK_EXECUTOR)} 引用 */
    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    @Bean(name = AI_TASK_EXECUTOR)
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("bobo-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等在途的 AI 任务写完流水，否则成本账会缺记录
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
