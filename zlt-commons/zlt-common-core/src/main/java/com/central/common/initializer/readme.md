# SpringBoot之ApplicationContextInitializer的理解和使用 

## 一、 ApplicationContextInitializer 介绍
- 用于在spring容器刷新之前初始化Spring ConfigurableApplicationContext的回调接口。（在容器刷新之前调用该类的 initialize 方法。并将 ConfigurableApplicationContext 类的实例传递给该方法）
- 通常用于需要对应用程序上下文进行编程初始化的web应用程序中。例如，根据上下文环境注册属性源或激活配置文件等。
- 可排序的（实现Ordered接口，或者添加@Order注解）

## 二、三种实现方式
1. main函数中添加
2. 配置文件中配置
3. SpringBoot的SPI扩展---META-INF/spring.factories中配置