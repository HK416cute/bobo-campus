package com.bobo.tools;

import com.bobo.security.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 密码散列生成工具（以命令行参数方式运行，不需要连数据库）。
 * <p>
 * 为什么做成 ApplicationRunner 而不是 main 方法：
 * 打包后是 Spring Boot 的 fat jar，业务类都在 {@code BOOT-INF/classes} 下，
 * {@code java -cp app.jar com.bobo.tools.PasswordTool} 这种写法会 ClassNotFound
 * （jar 布局变了，classpath 不是扁平结构）。走 Spring Boot 启动器最稳妥，
 * 而且容器里不需要额外挂载或复制任何东西。
 * <p>
 * 用法：
 * <pre>
 *   # 本机（在 bobo-server 目录）
 *   java -jar target/bobo-server.jar --bobo.password-tool=新密码 --bobo.password-user=admin
 *
 *   # 容器里（用 run 起一个临时容器，不影响正在跑的服务；进程打印完就退出）
 *   docker compose run --rm --no-deps bobo-server \
 *     --bobo.password-tool=新密码 --bobo.password-user=admin
 * </pre>
 * 输出里会直接给出可执行的 UPDATE 语句。
 * <p>
 * 两个实用参数（本机 9080 常被别的服务占用时会用到）：
 * <pre>
 *   --spring.main.web-application-type=none   不启动 Tomcat，纯命令行执行
 *   --spring.main.banner-mode=off             关掉启动 banner，输出更干净
 * </pre>
 *
 * @author bobo
 */
@Slf4j
@Component
public class PasswordTool implements ApplicationRunner {

    private final PasswordEncoder passwordEncoder;

    public PasswordTool(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("bobo.password-tool")) {
            return;
        }

        List<String> values = args.getOptionValues("bobo.password-tool");
        String rawPassword = values == null || values.isEmpty() ? null : values.get(0);
        if (rawPassword == null || rawPassword.isBlank()) {
            printUsage();
            return;
        }

        List<String> users = args.getOptionValues("bobo.password-user");
        String username = users == null || users.isEmpty() ? null : users.get(0);
        String encoded = passwordEncoder.encode(rawPassword);

        System.out.println();
        System.out.println("=========== bobo 密码散列生成 ===========");
        System.out.println("密码散列（PBKDF2-HMAC-SHA256，迭代 120000）：");
        System.out.println(encoded);
        if (username != null && !username.isBlank()) {
            System.out.println();
            System.out.println("可直接执行的 SQL：");
            System.out.println("UPDATE `bobo_user` SET `password` = '" + encoded
                    + "' WHERE `username` = '" + username + "';");
        } else {
            System.out.println();
            System.out.println("提示：加上 --bobo.password-user=<账号> 会顺带打印可直接执行的 SQL。");
        }
        System.out.println("========================================");
        System.out.println();
    }

    private void printUsage() {
        System.out.println("""

                =========== bobo 密码散列生成 ===========
                用法：
                  java -jar bobo-server.jar --bobo.password-tool=<新密码> [--bobo.password-user=<账号>]

                示例：
                  java -jar bobo-server.jar --bobo.password-tool=bobo@2026 --bobo.password-user=admin
                =========================================
                """);
    }
}
