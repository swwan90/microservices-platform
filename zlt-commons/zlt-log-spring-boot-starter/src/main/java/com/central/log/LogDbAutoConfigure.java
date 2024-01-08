package com.central.log;

import com.central.log.properties.LogDbProperties;
import com.zaxxer.hikari.HikariConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 日志数据库配置
 */
@Configuration
@ConditionalOnClass(HikariConfig.class)
@EnableConfigurationProperties(LogDbProperties.class)
public class LogDbAutoConfigure {
}
