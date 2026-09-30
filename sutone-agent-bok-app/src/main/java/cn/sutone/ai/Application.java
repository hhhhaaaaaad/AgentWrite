package cn.sutone.ai;

import cn.sutone.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Configurable
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        // 必须把 args 传给 SpringApplication：单参重载 run(Class) 等价于 run(Class, new String[0])，
        // 会丢弃 main 收到的命令行参数，导致 --spring.profiles.active=eval 等一律不生效。
        SpringApplication.run(Application.class, args);
    }

    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder().toolObjects(testService).build();
    }

}
