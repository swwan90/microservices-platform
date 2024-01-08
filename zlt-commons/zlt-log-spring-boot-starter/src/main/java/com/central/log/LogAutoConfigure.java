package com.central.log;

import com.central.log.properties.AuditLogProperties;
import com.central.log.properties.TraceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * 日志自动配置
 *
 * @author zlt
 * @date 2019/8/13
 */
@ComponentScan
// 该注解的作用是使 LogAutoConfigure 这个类上标注的 @ConfigurationProperties 注解生效,并且会自动将这个类注入到 IOC 容器中
// TraceProperties,AuditLogProperties  有了 @EnableConfigurationProperties 注解之后该实体类就不需要加上 @Component 注解了
@EnableConfigurationProperties({TraceProperties.class, AuditLogProperties.class})
public class LogAutoConfigure {
}
