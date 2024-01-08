//package com.central.common.initializer;
//
//import org.springframework.context.ApplicationContextInitializer;
//import org.springframework.context.ConfigurableApplicationContext;
//import org.springframework.core.Ordered;
//import org.springframework.core.annotation.Order;
//
//@Order(Ordered.LOWEST_PRECEDENCE)
//public class ShowBeanInfoInitializer implements ApplicationContextInitializer {
//    @Override
//    public void initialize(ConfigurableApplicationContext applicationContext) {
//
//        System.out.println("bean count ==>" + applicationContext.getBeanDefinitionCount());
//
//        System.out.println(applicationContext.getBeanDefinitionCount() + "个Bean的名字如下：");
//        for (String name : applicationContext.getBeanDefinitionNames()) {
//            System.out.println(name);
//        }
//    }
//}
