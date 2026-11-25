package com.bobo.ai.vision;

import com.bobo.ai.AiSettingsProvider;
import com.bobo.ai.spi.VisionInvoker;
import com.bobo.config.props.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模态能力装配。
 *
 * <p>和 {@code AiChatConfig} 一样，只暴露一个恒定 Bean，把「能不能用」交给
 * {@link VisionInvoker#available()} 在运行期判断：管理后台改了 Key / 开关后，
 * 下一次请求立即使用新配置，不需要重建 Bean。
 *
 * <p>DeepSeek 的多模态与文本共用同一套 Key / Base URL，因此这里不需要第二套配置；
 * 模型名由 {@code bobo.ai.vision-model} 指定（默认 deepseek-flash）。
 *
 * @author bobo
 */
@Configuration
public class AiVisionConfig {

    @Bean
    public VisionInvoker visionInvoker(AiSettingsProvider settingsProvider,
                                       AiProperties aiProperties,
                                       ObjectMapper objectMapper) {
        return new DeepSeekVisionInvoker(settingsProvider, aiProperties, objectMapper);
    }
}
