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