package com.bobo.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * bobo 业务配置项（对应 application.yml 的 bobo.* ）。
 *
 * @author bobo
 */
@Data
@ConfigurationProperties(prefix = "bobo")
public class BoboProperties {

    private Jwt jwt = new Jwt();
    private Upload upload = new Upload();
    private Admin admin = new Admin();
    private Demo demo = new Demo();

    @Data
    public static class Jwt {
        private String secret;
        private long expireMinutes = 10080;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Data
    public static class Upload {
        private String path = "./data/upload";
        private String urlPrefix = "/upload";
        private long maxSizeMb = 10;
        private String allowExt = "jpg,jpeg,png,webp,gif";
    }

    @Data
    public static class Admin {
        /** 管理员注册邀请码，为空则不开放管理员注册 */
        private String registerCode;
    }

    @Data
    public static class Demo {
        private boolean enabled = false;
    }
}
