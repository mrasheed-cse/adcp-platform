package com.adcpplatform.salesagent;

import com.adcpplatform.salesagent.tools.AdcpSalesTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SalesAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesAgentApplication.class, args);
    }

    /**
     * Registers every @Tool method in AdcpSalesTools as an MCP tool.
     * The MCP server starter picks up all ToolCallbackProvider beans automatically.
     */
    @Bean
    ToolCallbackProvider adcpTools(AdcpSalesTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
