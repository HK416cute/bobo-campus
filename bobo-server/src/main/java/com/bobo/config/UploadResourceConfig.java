package com.bobo.config;

import com.bobo.config.props.BoboProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 上传目录的静态映射。
 * <p>
 * 生产环境由 nginx 直接返回 /upload/**（性能更好、不占后端线程）；
 * 本机直接跑后端时靠这个映射也能正常预览图片。
 *
 * @author bobo
 */
@Configuration
@RequiredArgsConstructor
public class UploadResourceConfig implements WebMvcConfigurer {

    private final BoboProperties properties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(properties.getUpload().getPath()).toAbsolutePath().normalize();
        registry.addResourceHandler(properties.getUpload().getUrlPrefix() + "/**")
                .addResourceLocations("file:" + dir + "/")
                .setCachePeriod(3600);
    }
}
