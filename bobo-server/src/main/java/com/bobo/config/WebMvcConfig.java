package com.bobo.config;

import com.bobo.config.props.BoboProperties;
import com.bobo.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册 JWT 拦截器与跨域规则。
 *
 * @author bobo
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final BoboProperties properties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * 开发环境前端跑在 5173，和 9080 不同源，必须放行跨域。
     * 生产环境前端与接口同源（都由 nginx 暴露），这里的配置不会生效但留着无害。
     * 注意：allowCredentials(true) 时不能用 allowedOrigins("*")，故用 allowedOriginPatterns。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(properties.getJwt().getHeader())
                .allowCredentials(true)
                .maxAge(3600);
    }
}
