package com.supplypilot.ai.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService aiProviderExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
