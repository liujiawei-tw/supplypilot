package com.supplypilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(ProductionConfigurationProperties.class)
class ProductionConfiguration {
}

