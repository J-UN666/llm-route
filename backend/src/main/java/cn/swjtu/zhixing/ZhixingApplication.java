package cn.swjtu.zhixing;

import cn.swjtu.zhixing.system.DatabaseMigration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZhixingApplication {

    public static void main(String[] args) {
        if (args.length == 1 && "--migrate".equals(args[0])) {
            DatabaseMigration.run();
            return;
        }
        SpringApplication.run(ZhixingApplication.class, args);
    }
}
