package com.supplypilot.agentic.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "supplypilot.agentic.sql", name = "enabled", havingValue = "true")
public class AgenticSqlEngineConfiguration {
    @Bean(destroyMethod = "close")
    ExecutorService agenticSqlModelExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
