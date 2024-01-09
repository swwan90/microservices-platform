# logback之 AsyncAppender 的原理、源码及避坑建议

## 一、cache vs buffer
在系统设计中通常会有 cache 及 buffer 的设计：

- cache ：设备之间有速度差，高速设备访问低速设备会造成高速设备等待，导致使用率降低，为了减少低速设备对高速设备的影响，在两者之间加入 cache，通过加快访问速度，以提升高速设备的使用效率。
- buffer ：通俗来说就是化零为整，把少量多次变成多量少次；具体来说就是进行流量整形，把突发的大数量较小规模的 I/O 整理成平稳的小数量较大规模的 I/O，以减少响应次数

## 二、 AsyncAppender
### 2.1 AsyncAppender 属于 cache 级的方案
AsyncAppender 关注的重点在于高并发下，把日志写盘 变成 日志写内存，减少写日志的 RT。
### 2.2 AsyncAppender 原理简析
appender 之间构成链，AsyncAppender 接收日志，放入其内部的一个阻塞队列，专开一个线程从阻塞队列中取数据（每次一个）丢给链路下游的 appender（如 FileAppender);

### 2.3 AsyncAppender 原理详解
当 Logging Event 进入 AsyncAppender 后，AsyncAppender 会调用 appender 方法，append 方法中在将 event 填入 BlockingQueue 中前，会先判断当前 buffer 的容量以及丢弃日志特性是否开启，当消费能力不如生产能力时，AsyncAppender 会超出 Buffer 容量的 Logging Event 的级别，进行丢弃，作为消费速度一旦跟不上生产速度，中转 buffer 的溢出处理的一种方案。AsyncAppender 有个线程类 Worker，它是一个简单的线程类，是 AsyncAppender 的后台线程，所要做的工作是：从 buffer 中取出 event 交给对应的 appender 进行后面的日志推送。
AsyncAppender 并不处理日志，只是将日志缓冲到一个 BlockingQueue 里面去，并在内部创建一个工作线程从队列头部获取日志，之后将获取的日志循环记录到附加的其他 appender 上去，从而达到不阻塞主线程的效果。因此 AsynAppender 仅仅充当事件转发器，必须引用另一个 appender 来做事。
## 三、异步的 AsyncAppender 源码分析
### 3.1 继承关系

### 3.2 异步的 AsyncAppender
AsyncAppender 的继承关系是：AsyncAppender -> AsyncAppenderBase -> UnsynchronizedAppenderBase，AsyncAppenderBase 中 append 方法实现如下：

```java
public class AsyncAppenderBase<E> extends UnsynchronizedAppenderBase<E> implements AppenderAttachable<E> {
  BlockingQueue<E> blockingQueue = new ArrayBlockingQueue<E>(queueSize);
  @Override
  protected void append(E eventObject) {
    // 如果队列满，并且允许丢弃，则直接 return
    if (isQueueBelowDiscardingThreshold() && isDiscardable(eventObject)) {
      return;
    }
    preprocess(eventObject);
    put(eventObject);
  }
  private void put(E eventObject) {
    try {
      blockingQueue.put(eventObject);
    } catch (InterruptedException e) {
    }
  }
}

```

append 方法是把日志对象放到了阻塞队列 ArrayBlockingQueue 中。
discardingThreshold 是一个阈值，当队列的剩余容量小于这个阈值并且当前日志 level TRACE, DEBUG or INFO ，则丢弃这些日志。
在压测时候代码配置如上，也就是配置了异步日志，但是还是出现了线程阻塞在打日志的地方了，经查看是阻塞到了日志队列 ArrayBlockingQueue 的 put 方法：

可知 put 方法在队列满时候会挂起当前线程。那么如何解那？
上面介绍了 discardingThreshold，可知本文设置为 0 说明永远不会丢弃日志 level TRACE, DEBUG or INFO 的日志，只要 discardingThreshold>0 则当队列快满时候 level TRACE, DEBUG or INFO 的日志就会丢弃掉，这个貌似可以解决问题。但是如果打印的是 warn 级别的日志那？还是会在 put 的时候阻塞。

如果设置了 neverBlock=true 则写日志队列时候会调用 ArrayBlockingQueue 对的 offer 方法而不是 put,而 offer 是非阻塞的：

可知如果队列满则直接返回，而不是被挂起当前线程（当队列满了，put 阻塞，等有了再加，add 直接报错，offer 返回状态）
所以配置异步 appender 时候如下：

```xml
<appender name ="asyncFileAppender" class= "ch.qos.logback.classic.AsyncAppender">
    <!-- 如果队列的80%已满,则会丢弃TRACT、DEBUG、INFO级别的日志 -->
    <discardingThreshold >20</discardingThreshold>
    <!-- 更改默认的队列的深度,该值会影响性能.默认值为256 -->
    <queueSize>512</queueSize>
    <!-- 队列满了不阻塞调用者-->
    <neverBlock>true</neverBlock>
    <!-- 添加附加的appender,最多只能添加一个 -->
    <appender-ref ref ="file"/>
</appender>

<springProfile name="default,dev">
    <root level="info">
        <appender-ref ref="consoleWithSwitch"/>
        <appender-ref ref="asyncFileAppender"/>
    </root>
</springProfile>
<springProfile name="pro,prd,stg,test,uat,fit,fat,sit">
    <root level="info">
        <!--<appender-ref ref="consoleWithSwitch"/>-->
        <appender-ref ref="catAppender"/>
        <appender-ref ref="asyncFileAppender"/>
    </root>
</springProfile>

```

那么何时把队列中的数据存入日志文件呢？AsyncAppenderBase 中有一个 Worker 对象，负责从队列中取数据并调用 AppenderAttachableImpl 来处理：（这里一次只取一个进行追加的方式，效率有点低啊）

```java
    public void run() {
      AsyncAppenderBase<E> parent = AsyncAppenderBase.this;
      AppenderAttachableImpl<E> aai = parent.aai;

      // loop while the parent is started
      while (parent.isStarted()) {
        try {
          E e = parent.blockingQueue.take();
          aai.appendLoopOnAppenders(e);
        } catch (InterruptedException ie) {
          break;
        }
      }

      addInfo("Worker thread will flush remaining events before exiting. ");
      for (E e : parent.blockingQueue) {
        aai.appendLoopOnAppenders(e);
      }

      aai.detachAndStopAllAppenders();
    }
  }

```

这里的 AppenderAttachableImpl 也就是 logback.xml 里配置的 appender-ref 对象
四、使用需注意
在使用 AsyncAppender 的时候，有些选项还是要注意的。由于使用了 BlockingQueue 来缓存日志，因此就会出现队列满的情况。正如上面原理中所说的，在这种情况下，AsyncAppender 会做出一些处理：默认情况下，如果队列 80%已满，AsyncAppender 将丢弃 TRACE、DEBUG 和 INFO 级别的 event，从这点就可以看出，该策略有一个惊人的对 event 丢失的代价性能的影响。另外其他的一些选项信息，也会对性能产生影响，下面列出常用的几个属性配置信息：

queueSize

BlockingQueue 的最大容量，默认情况下，大小为 256，这个值需调整


discardingThreshold

默认情况下，当 BlockingQueue 还有 20%容量，他将丢弃 TRACE、DEBUG 和 INFO 级别的 event，只保留 WARN 和 ERROR 级别的 event。为了保持所有的 events，可设置该值为 0。


includeCallerData

提取调用者数据的代价是相当昂贵的。为了提升性能，默认情况下，当 event 被加入到 queue 时，event 关联的调用者数据不会被提取。默认情况下，只有"cheap"的数据，如线程名。



内部所用 BlockingQueue 的性能很一般，若对性能有更高的要求，可考虑使用其他的更高性能的队列（如JCTools)替换之。


# logback-spring.xml 范例
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--日志级别 TRACE < DEBUG < INFO < WARN < ERROR < FATAL 级别低的会打印比它高的日志，反向不会  -->
<!-- scan:当此属性设置为true时，配置文档如果发生改变，将会被重新加载，默认值为true -->
<!-- scanPeriod:设置监测配置文档是否有修改的时间间隔，如果没有给出时间单位，默认单位是毫秒。当scan为true时，此属性生效。默认的时间间隔为1分钟。 -->
<!-- debug:当此属性设置为true时，将打印出logback内部日志信息，实时查看logback运行状态。默认值为false。 -->
<configuration scan="true" scanPeriod="60 seconds">
   <contextName>logback</contextName>

   <!-- name的值是变量的名称，value的值时变量定义的值。通过定义的值会被插入到logger上下文中。定义后，可以使“${}”来使用变量。 -->
   <!-- 从 Spring Boot 配置文件中，读取 spring.application.name 应用名 -->
   <springProperty name="applicationName" scope="context" source="spring.application.name"/>
<!--    定义在服务器端打印的日志路径-->
   <property name="logPath" value="/var/log/${applicationName}"/>

   <!--0. 日志格式和颜色渲染 -->
   <!-- 彩色日志依赖的渲染类 -->
   <conversionRule conversionWord="clr" converterClass="org.springframework.boot.logging.logback.ColorConverter"/>
   <conversionRule conversionWord="wex"
                   converterClass="org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter"/>
   <conversionRule conversionWord="wEx"
                   converterClass="org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter"/>
   <!-- 彩色日志格式 -->
   <property name="CONSOLE_LOG_PATTERN"
             value="${CONSOLE_LOG_PATTERN:-%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}}"/>

   <!--1. 输出到控制台-->
   <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
       <!--此日志appender是为开发使用，只配置最底级别，控制台输出的日志级别是大于或等于此级别的日志信息-->
       <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
           <level>debug</level>
       </filter>
       <encoder>
           <Pattern>${CONSOLE_LOG_PATTERN}</Pattern>
           <!-- 设置字符集 -->
           <charset>UTF-8</charset>
       </encoder>
   </appender>

   <!--2. 输出到文档-->
   <!-- 2.1 level为 DEBUG 日志，时间滚动输出  -->
   <appender name="DEBUG_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <!-- 正在记录的日志文档的路径及文档名 -->
       <file>${logPath}/debug.log</file>
       <!--日志文档输出格式-->
       <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
           <charset>UTF-8</charset> <!-- 设置字符集 -->
       </encoder>
       <!-- 日志记录器的滚动策略，按日期，按大小记录 -->
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <!-- 日志归档 -->
           <fileNamePattern>${logPath}/debug-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
           <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
               <maxFileSize>100MB</maxFileSize>
           </timeBasedFileNamingAndTriggeringPolicy>
           <!--日志文档保留天数-->
           <maxHistory>15</maxHistory>
       </rollingPolicy>
       <!-- 此日志文档只记录debug级别的 -->
       <filter class="ch.qos.logback.classic.filter.LevelFilter">
           <level>debug</level>
           <onMatch>ACCEPT</onMatch>
           <onMismatch>DENY</onMismatch>
       </filter>
   </appender>

   <!-- 2.2 level为 INFO 日志，时间滚动输出  -->
   <appender name="INFO_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <!-- 正在记录的日志文档的路径及文档名 -->
       <file>${logPath}/info.log</file>
       <!--日志文档输出格式-->
       <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
           <charset>UTF-8</charset>
       </encoder>
       <!-- 日志记录器的滚动策略，按日期，按大小记录 -->
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <!-- 每天日志归档路径以及格式 -->
           <fileNamePattern>${logPath}/info-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
           <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
               <maxFileSize>100MB</maxFileSize>
           </timeBasedFileNamingAndTriggeringPolicy>
           <!--日志文档保留天数-->
           <maxHistory>15</maxHistory>
       </rollingPolicy>
       <!-- 此日志文档只记录info级别的 -->
       <filter class="ch.qos.logback.classic.filter.LevelFilter">
           <level>info</level>
           <onMatch>ACCEPT</onMatch>
           <onMismatch>DENY</onMismatch>
       </filter>
   </appender>

   <!-- 2.3 level为 WARN 日志，时间滚动输出  -->
   <appender name="WARN_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <!-- 正在记录的日志文档的路径及文档名 -->
       <file>${logPath}/warn.log</file>
       <!--日志文档输出格式-->
       <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
           <charset>UTF-8</charset> <!-- 此处设置字符集 -->
       </encoder>
       <!-- 日志记录器的滚动策略，按日期，按大小记录 -->
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <fileNamePattern>${logPath}/warn-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
           <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
               <maxFileSize>100MB</maxFileSize>
           </timeBasedFileNamingAndTriggeringPolicy>
           <!--日志文档保留天数-->
           <maxHistory>15</maxHistory>
       </rollingPolicy>
       <!-- 此日志文档只记录warn级别的 -->
       <filter class="ch.qos.logback.classic.filter.LevelFilter">
           <level>warn</level>
           <onMatch>ACCEPT</onMatch>
           <onMismatch>DENY</onMismatch>
       </filter>
   </appender>

   <!-- 2.4 level为 ERROR 日志，时间滚动输出  -->
   <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <!-- 正在记录的日志文档的路径及文档名 -->
       <file>${logPath}/error.log</file>
       <!--日志文档输出格式-->
       <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
           <charset>UTF-8</charset> <!-- 此处设置字符集 -->
       </encoder>
       <!-- 日志记录器的滚动策略，按日期，按大小记录 -->
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <fileNamePattern>${logPath}/error-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
           <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
               <maxFileSize>100MB</maxFileSize>
           </timeBasedFileNamingAndTriggeringPolicy>
           <!--日志文档保留天数-->
           <maxHistory>15</maxHistory>
       </rollingPolicy>
       <!-- 此日志文档只记录ERROR级别的 -->
       <filter class="ch.qos.logback.classic.filter.LevelFilter">
           <level>ERROR</level>
           <onMatch>ACCEPT</onMatch>
           <onMismatch>DENY</onMismatch>
       </filter>
   </appender>

   <!-- 2.5 所有 除了DEBUG级别的其它高于DEBUG的 日志，记录到一个文件  -->
   <appender name="ALL_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
       <!-- 正在记录的日志文档的路径及文档名 -->
       <file>${logPath}/all.log</file>
       <!--日志文档输出格式-->
       <encoder>
           <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
           <charset>UTF-8</charset> <!-- 此处设置字符集 -->
       </encoder>
       <!-- 日志记录器的滚动策略，按日期，按大小记录 -->
       <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
           <fileNamePattern>${logPath}/all-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
           <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
               <maxFileSize>100MB</maxFileSize>
           </timeBasedFileNamingAndTriggeringPolicy>
           <!--日志文档保留天数-->
           <maxHistory>15</maxHistory>
       </rollingPolicy>
       <!-- 此日志文档记录除了DEBUG级别的其它高于DEBUG的 -->
       <filter class="ch.qos.logback.classic.filter.LevelFilter">
           <level>DEBUG</level>
           <onMatch>DENY</onMatch>
           <onMismatch>ACCEPT</onMismatch>
       </filter>
   </appender>

   <!--
       <logger>用来设置某一个包或者具体的某一个类的日志打印级别、
       以及指定<appender>。<logger>仅有一个name属性，
       一个可选的level和一个可选的addtivity属性。
       name:用来指定受此logger约束的某一个包或者具体的某一个类。
       level:用来设置打印级别，大小写无关：TRACE, DEBUG, INFO, WARN, ERROR, ALL 和 OFF，
             还有一个特殊值INHERITED或者同义词NULL，代表强制执行上级的级别。
             如果未设置此属性，那么当前logger将会继承上级的级别。
       addtivity:是否向上级logger传递打印信息。默认是true。
       <logger name="org.springframework.web" level="info"/>
       <logger name="org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor" level="INFO"/>
   -->

   <!--
       使用mybatis的时候，sql语句是debug下才会打印，而这里我们只配置了info，所以想要查看sql语句的话，有以下两种操作：
       第一种把<root level="info">改成<root level="DEBUG">这样就会打印sql，不过这样日志那边会出现很多其他消息
       第二种就是单独给dao下目录配置debug模式，代码如下，这样配置sql语句会打印，其他还是正常info级别：
       【logging.level.org.mybatis=debug logging.level.dao=debug】
    -->

   <!--
       root节点是必选节点，用来指定最基础的日志输出级别，只有一个level属性
       level:用来设置打印级别，大小写无关：TRACE, DEBUG, INFO, WARN, ERROR, ALL 和 OFF，
       不能设置为INHERITED或者同义词NULL。默认是DEBUG
       可以包含零个或多个元素，标识这个appender将会添加到这个logger。
   -->

   <springProfile name="dev">
       <root level="info">
           <appender-ref ref="ALL_FILE"/>
       </root>
       <!-- 开发环境, 指定某包日志为debug级 -->
       <logger name="com.smile.ssm" level="debug"/>
   </springProfile>

   <springProfile name="test">
       <root level="info">
           <appender-ref ref="CONSOLE"/>
           <appender-ref ref="DEBUG_FILE"/>
           <appender-ref ref="INFO_FILE"/>
           <appender-ref ref="WARN_FILE"/>
           <appender-ref ref="ERROR_FILE"/>
           <appender-ref ref="ALL_FILE"/>
       </root>
       <!-- 测试环境, 指定某包日志为info级 -->
       <logger name="com.smile.ssm" level="info"/>
   </springProfile>

   <springProfile name="pro">
       <root level="info">
           <!-- 生产环境最好不配置console写文件 -->
           <appender-ref ref="DEBUG_FILE"/>
           <appender-ref ref="INFO_FILE"/>
           <appender-ref ref="WARN_FILE"/>
           <appender-ref ref="ERROR_FILE"/>
           <appender-ref ref="ALL_FILE"/>
       </root>
       <!-- 生产环境, 指定某包日志为warn级 -->
       <logger name="com.smile.ssm" level="warn"/>
       <!-- 特定某个类打印info日志, 比如application启动成功后的提示语 -->
       <logger name="com.smile.ssm.SsmApplication" level="info"/>
   </springProfile>

</configuration>

```