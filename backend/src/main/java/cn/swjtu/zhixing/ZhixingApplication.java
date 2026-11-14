package cn.swjtu.zhixing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 交大智行后端服务入口。
 *
 * <p>应用只负责启动 Spring 容器与 Web 服务；数据库迁移由 Maven Flyway 插件以迁移账号执行，
 * 运行账号仅有业务读写权限，因此运行期 Flyway 关闭。</p>
 */
@SpringBootApplication
public class ZhixingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhixingApplication.class, args);
    }
}
