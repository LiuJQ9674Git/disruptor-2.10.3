package java.util.concurrent;

import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.Permission;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 *
 * 用于运行ForkJoinTasks的ExecutorService。
 * ForkJoinPool为来自非ForkJoinTask客户端的提交以及管理和监视操作提供了入口点。
 *
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * tasks (eventually blocking waiting for work if none exist). This
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined. All worker threads are initialized
 * with {@link Thread#isDaemon} set {@code true}.
 *
 * ForkJoinPool与其他类型的ExecutorService的区别主要在于采用了工作窃取：
 * 池中的所有线程都试图找到并执行提交到池中和/或由其他活动任务创建的任务
 * （如果不存在，则最终阻止等待工作）。
 * 当大多数任务产生其他子任务时（就像大多数ForkJoinTasks一样），
 * 以及当许多小任务从外部客户端提交到池时，这可以实现高效处理。
 * 特别是当在构造函数中将asyncMode设置为true时，
 * ForkJoinPools可能也适用于从未联接（join）的事件样式任务。
 * 所有工作线程都使用Thread.isDemon设置为true进行初始化。
 * 
 * <p>A static {@link #commonPool()} is available and appropriate for
 * most applications. The common pool is used by any ForkJoinTask that
 * is not explicitly submitted to a specified pool. Using the common
 * pool normally reduces resource usage (its threads are slowly
 * reclaimed during periods of non-use, and reinstated upon subsequent
 * use).
 *
 * 静态commonPool是可用的，适用于大多数应用程序。
 * 公共池（common pool）由任何未明确提交到指定池的ForkJoinTask使用。
 * 使用公共池（common pool）通常会减少资源使用量（其线程在不使用期间会慢慢回收，并在后续使用时恢复）。
 *
 * <p>For applications that require separate or custom pools, a {@code
 * ForkJoinPool} may be constructed with a given target parallelism
 * level; by default, equal to the number of available processors.
 * The pool attempts to maintain enough active (or available) threads
 * by dynamically adding, suspending, or resuming internal worker
 * threads, even if some tasks are stalled waiting to join others.
 * However, no such adjustments are guaranteed in the face of blocked
 * I/O or other unmanaged synchronization. The nested {@link
 * ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated. The default policies may be
 * overridden using a constructor with parameters corresponding to
 * those documented in class {@link ThreadPoolExecutor}.
 *
 * 对于需要单独或自定义池的（require separate or custom pools）应用程序，
 * 可以使用给定的目标并行度级别构建ForkJoinPool；
 * 默认情况下等于可用处理器的数量。池试图通过动态添加、挂起或恢复内部工作线程来
 * 维护足够的活动（或可用）线程，即使某些任务已暂停等待加入其他任务。
 * 但是，面对阻塞的I/O或其他非托管同步，不能保证进行此类调整。
 * 嵌套的ForkJoinPool.ManagedBlocker接口可以扩展所容纳的同步类型。
 * 默认策略可以使用构造函数重写，
 * 该构造函数的参数与ThreadPoolExecutor类中记录的参数相对应。
 * 
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * 除了执行和生命周期控制方法外，这个类还提供了状态检查方法（例如getStealCount），
 * 用于帮助开发、调优和监视fork/join应用程序。
 * 此外，方法toString以一种方便的形式返回池状态的指示，用于非正式监视。
 * 
 * <p>As is the case with other ExecutorServices, there are three
 * main task execution methods summarized in the following table.
 * These are designed to be used primarily by clients not already
 * engaged in fork/join computations in the current pool.  The main
 * forms of these methods accept instances of {@code ForkJoinTask},
 * but overloaded forms also allow mixed execution of plain {@code
 * Runnable}- or {@code Callable}- based activities as well.  However,
 * tasks that are already executing in a pool should normally instead
 * use the within-computation forms listed in the table unless using
 * async event-style tasks that are not usually joined, in which case
 * there is little difference among choice of methods.
 *
 * 与其他ExecutorServices的情况一样，下表中总结了三种主要的任务执行方法。
 * 这些设计主要用于尚未在当前池中参与fork/join计算的客户端。
 * 这些方法的主要形式接受ForkJoinTask的实例，
 * 但重载的形式也允许混合执行普通的基于Runnable或Callable的活动。
 * 然而，已经在池中执行的任务通常应该使用表中列出的内部计算形式，
 * 除非使用通常不连接的异步事件样式任务，在这种情况下，方法的选择几乎没有差异。
 *
 * 三种方法为：
 * 1.Arrange async execution  execute(ForkJoinTask) ForkJoinTask.fork
 *
 * 2.Await and obtain result  invoke(ForkJoinTask)  ForkJoinTask.invoke
 *
 * 3. Arrange exec and obtain Future submit(ForkJoinTask)
 *                                  ForkJoinTask.fork (ForkJoinTasks are Futures)
 *
 * The parameters used to construct the common pool may be controlled
 * by setting the following system properties:
                                 
 * If no thread factory is supplied via a system property, then the
 * common pool uses a factory that uses the system class loader as the
 * {@linkplain Thread#getContextClassLoader() thread context class loader}.
 * In addition, if a {@link SecurityManager} is present, then
 * the common pool uses a factory supplying threads that have no
 * {@link Permissions} enabled.
 *
 * 如果没有通过系统属性提供线程工厂，那么公共池将使用一个使用系统类加载器作为线程上下文类加载器的工厂。
 * 此外，如果存在SecurityManager，则公共池将使用提供未启用“权限”的线程的工厂。
 * 在建立这些设置时出现任何错误时，将使用默认参数。通过将并行度属性设置为零和/或使用可能返回null的工厂，
 * 可以禁用或限制公共池中线程的使用。然而，这样做可能会导致未连接的任务永远无法执行。
 * 
 * Upon any error in establishing these settings, default parameters
 * are used. It is possible to disable or limit the use of threads in
 * the common pool by setting the parallelism property to zero, and/or
 * using a factory that may return {@code null}. However doing so may
 * cause unjoined tasks to never be executed.
 *
 * 在建立这些设置时出现任何错误时，将使用默认参数。
 * 通过将parallelism属性设置为零和/或使用可能返回｛null｝的工厂，
 * 可以禁用或限制公共池中线程的使用。然而，这样做可能会导致未连接的任务永远无法执行。
 *
 * <p><b>Implementation notes:</b> This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 * 
 *  实现说明：此实现将运行线程的最大数量限制为32767。
 *  尝试创建大于最大数量的池会导致IllegalArgumentException。
 *  
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class ForkJoinPool extends AbstractExecutorService {

    /*
     * Implementation Overview
     *
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission queues.
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers. Work-stealing based on
     * randomized scans generally leads to better throughput than
     * "work dealing" in which producers assign tasks to idle threads,
     * in part because threads that have finished other tasks before
     * the signalled thread wakes up (which can be a long time) can
     * take the task instead.  Preference rules give first priority to
     * processing tasks from their own queues (LIFO or FIFO, depending
     * on mode), then to randomized FIFO steals of tasks in other
     * queues.  This framework began as vehicle for supporting
     * tree-structured parallelism using work-stealing.
     *
     * 这个类及其嵌套类为一组工作线程提供了主要功能和控制：
     * 来自非FJ线程的提交进入提交队列。
     * 工作者（线程）接受这些任务会将它们拆分为可能被其他工作者（线程）窃取的子任务。
     * 基于随机扫描的工作窃取通常比生产者将任务分配给空闲线程的“工作处理”能带来更好的吞吐量，
     * 部分原因是在发出信号的线程唤醒（可能需要很长时间）之前完成了其他任务的线程可以接受任务。
     * 偏好规则优先处理自己队列中的任务（LIFO或FIFO，取决于模式），
     * 然后是其他队列中任务的随机FIFO窃取work-stealing。
     * 该框架最初是使用工作窃取来支持树结构并行性的工具。
     * 
     * Over time, its scalability advantages led to extensions and changes to
     * better support more diverse usage contexts.  Because most
     * internal methods and nested classes are interrelated, their
     * main rationale and descriptions are presented here; individual
     * methods and nested classes contain only brief comments about
     * details.
     *
     * 随着时间的推移，它的可扩展性优势导致了扩展和更改，以更好地支持更多样的使用环境。
     * 因为大多数内部方法和嵌套类是相互关联的，所以这里给出了它们的主要原理和描述；
     * 单个方法和嵌套类仅包含有关详细信息的简短注释
     *
     *
     * 工作窃取
     * 对于 ForkJoinPool 来说，任务提交有两种:
     * 一种是直接通过 ForkJoinPool 来提交的外部任务 external/submissions task
     * 第二种是内部 fork 分割的子任务 Worker task
     *
     *  forkJoinPool.submit(forkJoinTask);
     *  
     *  forkJoinTask.fork();
     *  ((ForkJoinWorkerThread)t).workQueue.push(this);
     *
     *  src17 ForkJoinPool:
     *   private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
     *    Thread t; ForkJoinWorkerThread wt; WorkQueue q;
     *    if (task == null)
     *        throw new NullPointerException();
     *    if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
     *        (q = (wt = (ForkJoinWorkerThread)t).workQueue) != null &&
     *        wt.pool == this)
     *        q.push(task, this); //内部 fork 分割的子任务 Worker task
     *   else
     *        externalPush(task); //提交的外部任务 external/submissions task
     *    return task;
     * }
     *
     *  src17 ForkJoinTask:
     *   public final ForkJoinTask<V> fork() {
     *    Thread t; ForkJoinWorkerThread w;
     *    if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
     *        (w = (ForkJoinWorkerThread)t).workQueue.push(this, w.pool);
     *    else
     *        ForkJoinPool.common.externalPush(this);
     *    return this;
     * }
     *  externalPush(task);
     *  
     * WorkQueues
     * ==========
     *
     * Deques: 双端队列
     * 每个工作线程都维护一个工作队列，每个工作线程在运行中产生新任务（通过fork）。
     *
     * 每个工作线程在处理自己工作队列同时，会尝试窃取一个任务。
     * 此任务可能是刚刚提交到池pool中的任务，或者来其它工作线程的工作队列。
     *
     * 窃取任务和执行自己的队列任务的方式是分开的。
     * 
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)
     *
     * 大多数操作都发生在工作窃取队列中（在嵌套类WorkQueue中）。
     * 这些是Deques的特殊形式，只支持四种可能的结束操作中的三种，
     * 即推送push、弹出pop和轮询poll（也称为窃取steal），
     * 但进一步的限制是，推送push和弹出pop只能从所属（owning）线程调用
     * （或者，在锁下情况下扩展），
     * 而轮询poll可以从其他线程调用。
     * （如果你不熟悉它们，
     * 《多处理器编程的艺术》，第16章更详细地描述了这些。）
     * 
     * The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     *
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * 主要的差异最终源于GC的要求，即我们尽快清空占用的插槽，以保持尽可能小的占地面积，
     * 即使在生成大量任务的程序中也是如此。
     * 为了实现这一点，我们将CAS对弹出pop与轮询poll（窃取）的仲裁从位于索引
     * （“基础base”和“顶部top”）转移到插槽本身。
     *
     * Adding tasks then takes the form of a classic array push(task)
     * in a circular buffer:
     * 然后，在循环缓冲区中以经典数组推送push（task）的形式添加任务：
     *    q.array[q.top++ % length] = task;
     *
     * The actual code needs to null-check and size-check the array,
     * uses masking, not mod, for indexing a power-of-two-sized array,
     * enforces memory ordering, supports resizing, and possibly
     * signals waiting workers to start scanning -- see below.
     *
     * 实际的代码需要对数组进行null检查和大小检查，使用掩码（power-of-two-sized取模），
     * 强制执行内存排序（memory ordering），支持调整大小，并可能通知等待的工作人员开始扫描。
     * 
     * The pop operation (always performed by owner) is of the form:
     * 弹出pop操作（始终由所有者执行owner）的形式如下：
     *   if ((task = getAndSet(q.array, (q.top-1) % length, null)) != null)
     *        decrement top and return task;
     * If this fails, the queue is empty.
     * 如果失败，则队列为空。
     *
     * The poll operation by another stealer thread is, basically:
     * 另一个窃取线程的轮询poll操作基本上是：
     *   if (CAS nonnull task at q.array[q.base % length] to null)
     *       increment base and return task;
     *
     * This may fail due to contention, and may be retried.
     * Implementations must ensure a consistent snapshot of the base
     * index and the task (by looping or trying elsewhere) before
     * trying CAS.  There isn't actually a method of this form,
     * because failure due to inconsistency or contention is handled
     * in different ways in different contexts, normally by first
     * trying other queues. (For the most straightforward example, see
     * method pollScan.) There are further variants for cases
     * requiring inspection of elements before extracting them, so
     * must interleave these with variants of this code.  Also, a more
     * efficient version (nextLocalTask) is used for polls by owners.
     * It avoids some overhead because the queue cannot be growing
     * during call.
     *
     * 这可能由于争用而失败，并且可能会重试。
     * 在尝试CAS之前，实现必须确保基本索引和任务的快照一致（通过循环或在其他地方尝试）。
     * 实际上没有这种形式的方法，因为在不同的上下文中，
     * 由于不一致或争用而导致的失败以不同的方式处理，通常是先尝试其他队列。
     * （有关最简单的示例，请参阅方法pollScan。）对于需要在提取元素之前检查元素的情况，
     * 还有其他变体，因此必须将这些变体与此代码的变体交织在一起。
     * 此外，所有者（owners）使用更高效的版本（nextLocalTask）进行轮询。
     * 它避免了一些开销，因为在调用过程中队列无法增长。
     *
     * Memory ordering.
     * See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to the one used here.  Inserting and
     * extracting tasks in array slots via volatile or atomic accesses
     * or explicit fences provides primary synchronization.
     *
     * 内存排序（Memory ordering）。
     * 请参阅Le，Pop，Cohen和Nardelli的《弱内存模型的正确有效的工作窃取》，
     * 
     * 了解类似于此处使用的工作窃取算法中内存排序要求的分析。
     * 通过volatile或原子访问atomic或显式围栏在阵列插槽中插入和提取任务提供了
     * 基本同步。
     * 
     * Operations on deque elements require reads and writes of both
     * indices and slots. When possible, we allow these to occur in
     * any order.  Because the base and top indices (along with other
     * pool or array fields accessed in many methods) only imprecisely
     * guide where to extract from, we let accesses other than the
     * element getAndSet/CAS/setVolatile appear in any order, using
     * plain mode. But we must still preface some methods (mainly
     * those that may be accessed externally) with an acquireFence to
     * avoid unbounded staleness.
     *
     * 对deque元素的操作需要读取和写入索引和插槽。
     * 在可能的情况下，我们允许这些以任何顺序发生。
     * 由于底部索引base和顶top索引（以及在许多访问中的其他池pool
     * 或数组array字段的方法）
     * 
     * 只是不精确地定位从何处提取，
     * 除了getAndSet/CAS/setVolatile访问元素以外，
     * 其它的访问以任何序（内存访问），我们使用普通模式plain mode方式。
     * 但我们仍然必须在一些方法（主要是那些可以从外部访问的方法）
     * 前面加上一个acquireFence，以避免无限的过时。
     * 
     * base直接写q.base = nextBase，在如下方法中使用
     * scan、helpJoin、helpQuiescePool
     * 
     * 直接读b = q.base，在如下方法中使用：
     * scan、awaitWork、canStop、helpJoin、helpComplete、pollScan
     * helpQuiescePool、getSurplusQueuedTaskCount
     * 在队列中的使用方法：
     * queueSize、isEmpty、push、lockedPush、pop、tryUnpush、tryRemove
     * tryPoll、nextLocalTask、peek、helpAsyncBlocker
     *
     * 原子操作：setBaseOpaque
     * helpComplete、pollScan
     * 队列方法：tryPoll、nextLocalTask、helpAsyncBlocker
     * 
     * This is equivalent to acting as if
     * callers use an acquiring read of the reference to the pool or
     * queue when invoking the method, even when they do not.
     * We use explicit acquiring reads (getSlot) rather than plain array
     * access when acquire mode is required but not otherwise ensured
     * by context.
     * To reduce stalls by other stealers, we encourage
     * timely writes to the base index by immediately following
     * updates with a write of a volatile field that must be updated
     * anyway, or an Opaque-mode write if there is no such
     * opportunity.
     *
     * 对于array的操作
     * （赋值）相当于对池pool或队列queue的引用的读取（acquiring read）
     * 即使调用方没有这样做。
     * 当需要获取模式acquire mode但上下文无法确保时，使用显式获取读取（getSlot）
     * 而不是纯数组plain array access访问。
     * 
     * 为了减少其他窃取者的暂停，我们鼓励通过如下两种方法及时写入base基本索引，
     * 方法一：在更新后立即写入必须更新的volatile字段，
     * 方法二：或者在没有这种机会的情况下写入不透明模式Opaque-mode。
     *
     * getSlot方法实现访问Task任务数组
     * scan、helpJoin、helpComplete、pollScan、helpQuiescePool和
     * 队列本身的tryPoll、helpAsyncBlocker通过getSlot来获取数组的值。
     * 
     * Because indices and slot contents cannot always be consistent,
     * the emptiness check base == top is only quiescently accurate
     * (and so used where this suffices). Otherwise, it may err on the
     * side of possibly making the queue appear nonempty when a push,
     * pop, or poll have not fully committed, or making it appear
     * empty when an update of top or base has not yet been seen.
     * 
     * Similarly, the check in push for the queue array being full may
     * trigger when not completely full, causing a resize earlier than
     * required.
     *
     * 因为索引和槽内容不可能总是一致的，所以空性检查base==top只是静态准确的
     * （因此在足够的情况下使用）。
     * 否则，当推送push、弹出pop或轮询poll尚未完全提交时，
     * 它可能会错误地使队列显示为非空，
     * 或者当尚未看到顶部或底部的更新时，它会显示为空。
     * 类似地，队列阵列已满的签入推送可能会在未完全满时触发，从而导致提前调整大小。
     *
     * Mainly because of these potential inconsistencies among slots
     * vs indices, the poll operation, considered individually, is not
     * wait-free.
     * One thief cannot successfully continue until another
     * in-progress one (or, if previously empty, a push) visibly
     * completes.
     *
     * 主要是因为插槽slots与索引之间存在这些潜在的不一致性，
     * 单独考虑的轮询poll操作并非免费等待wait-free。
     * 一个小偷（线程）不能成功地继续，直到另一个正在进行的盗取工作者（线程）
     * （或者，如果之前是空的empty，则是推push）明显完成。
     * 
     * This can stall threads when required to consume
     * from a given queue (which may spin).  However, in the
     * aggregate, we ensure probabilistic non-blockingness at least
     * until checking quiescence (which is intrinsically blocking):
     * 
     * If an attempted steal fails, a scanning thief chooses a
     * different victim target to try next. So, in order for one thief
     * to progress, it suffices for any in-progress poll or new push
     * on any empty queue to complete.
     *
     * The worst cases occur when many
     * threads are looking for tasks being produced by a stalled
     * producer.
     *
     * 当需要从给定队列（可能会旋转spin）消耗时，这可能会暂停线程。
     * 然而，总的来说，我们至少在检查静止（本质上是阻塞blocking）之前确保
     * 概率上的非阻塞性：
     * 如果尝试盗窃失败，扫描小偷会选择另一个受害者victim目标进行下一次尝试。
     * 因此，为了让一个小偷（线程）进步，任何正在进行的轮询poll或
     * 对任何空队列的新推送push都足以完成。
     * 
     * 最糟糕的情况发生在许多线程正在寻找由停滞的生成器生成的任务时。
     * 
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.
     * This can be useful in
     * message-passing frameworks in which tasks are never joined,
     * although with increased contention among task producers and
     * consumers.
     *
     * 这种方法还支持用户模式，在这种模式下，本地任务处理是按FIFO而不是LIFO顺序进行的，
     * 只需使用轮询poll而不是弹出pop。
     * 这在任务从未加入的消息传递框架中非常有用，尽管任务生产者和消费者之间的争用会增加。
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers.
     * Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.
     *
     * WorkQueues也以类似的方式用于提交到池中的任务。
     * 我们不能将这些任务混合在工作线程使用的相同队列中。
     * 相反，我们使用一种哈希形式，将提交队列与提交线程随机关联。
     *
     * WorkQueue池中包括外部extentalPush(Pool提交)和
     * 内部currentThread.push(this)（Task的fork提交）均在quques数组中
     * 使用线程探索哈希来绑定到线程上，减少竞争。
     * 
     * The ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.
     * In essence, submitters act
     * like workers except that they are restricted to executing local
     * tasks that they submitted (or when known, subtasks thereof).
     * Insertion of tasks in shared mode requires a lock. We use only
     * a simple spinlock (using field "source"), because submitters
     * encountering a busy queue move to a different position to use
     * or create other queues.
     * They block only when registering new
     * queues.
     *
     * ThreadLocalRandom探测值用作选择现有队列的哈希代码，并且可以在与其他提交者发生争用时
     * 随机重新定位。
     * 本质上，提交者的行为就像工作者（线程），只是他们被限制执行他们提交的本地任务
     * （或者在已知的情况下，执行其子任务subtasks）。
     * 
     * 在共享模式下插入任务需要锁定。我们只使用一个简单的spinlock（使用字段“source”），
     * 因为遇到繁忙队列的提交者会移动到不同的位置来使用或创建其他队列。
     * 它们仅在注册新队列时才会阻止。
     *
     * bit位：
     *
     * 
     *
     * FIFO:	65536	1 << 16;    // fifo queue or access mode
     *                                              10000000000000000
     * 
     * SRC:	131072	1 << 17;        // set for valid queue ids
     *                                             100000000000000000
     * 
     * INNOCUOUS:	262144	1 << 18; // set for Innocuous workers
     *                                            1000000000000000000
     * 
     * QUIET:	524288	1 << 19;     // quiescing phase or source
     *                                            10000000000000000000
     * SHUTDOWN:	16777216	 1 << 24;
     *                                        100000000000000000000000
     * TERMINATED:	33554432  1 << 25;
     *                                       10000000000000000000000000
     * STOP:	-2147483648	 1 << 31
     * 1111111111111111111111111111111110000000000000000000000000000000
     * 
     * UNCOMPENSATE:	65536	1 << 16;       // tryCompensate return
     *                                                10000000000000000
     *
     * ****************************************************************
     * SWIDTH       = 16;            // width of short
     
     * SMASK:	65535	SMAS = 0xffff;        // short bits == max index
     *                                                  1111111111111111
     * MAX_CAP:	32767	MAX_CAP = 0x7fff;     // max #workers - 1
     *                                                   111111111111111
     *
     * UNSIGNALLED:	-2147483648	UNSIGNALLED= 1 << 31 // must be negative
     * 1111111111111111111111111111111110000000000000000000000000000000
     *
     * SS_SEQ:	65536	SS_SEQ      = 1 << 16;       // version count
     *                                                10000000000000000
     *
     * ****************************************************************
     * ctl:	-4222399528566784	ctl->:
     * 1111111111110000111111111100000000000000000000000000000000000000
     *
     * ****************************************************************
     * TC_MASK:	281470681743360 0xffffL << TC_SHIFT 32
     *                 111111111111111100000000000000000000000000000000
     * RC_MASK:	-281474976710656 0xffffL << RC_SHIFT 48
     * 1111111111111111000000000000000000000000000000000000000000000000
     *
     *UC_MASK:	-4294967296 ~SP_MASK f*8+0*8
     * 1111111111111111111111111111111100000000000000000000000000000000
     *SP_MASK:	4294967295 0xffffffffL（f*8)
     *                                 11111111111111111111111111111111
     *
     * RC_UNIT:	281474976710656
     *                1000000000000000000000000000000000000000000000000
     * TC_UNIT:	4294967296
     *                                100000000000000000000000000000000
     * ADD_WORKER:	140737488355328
     *                 100000000000000000000000000000000000000000000000
     * 
     * 核心信息：
     * 1.int stackPred;             // pool stack (ctl) predecessor link
     *
     * 读
     * signalWork
     *      long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
     *
     * runWorker
     *     int r = w.stackPred, src = 0;       // use seed from registerWorker
     *
     * tryCompensate
     *      long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
     *
     * 写：
     * 
     * registerWorker
     *       w.stackPred = seed;                         // stash for runWorker
     *
     * awaitWork
     *
     *   long prevCtl = ctl, c;               // enqueue
     *   do {
     *       w.stackPred = (int)prevCtl;
     *       c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
     *   } while (prevCtl != (prevCtl = compareAndExchangeCtl(prevCtl, c)));
     *   
     *
     * 2. int config;                // index, mode, ORed with SRC after init
     *
     *  读
     *   registerWorker
     *     int modebits = (mode & FIFO) | w.config;
     *     
     *   deregisterWorker
     *      cfg = w.config;
     *      
     *   awaitWork
     *       else if (((int)c & SMASK) == (w.config & SMASK) &&
     *                compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) |
     *                                     (prevCtl & SP_MASK)))) {
     *           w.config |= QUIET;           // sentinel for deregisterWorker
     *
     *  helpJoin
     *      int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
     *  helpComplete
     *      int r = w.config;
     *
     * 写
     *  registerWorker
     *       w.phase = w.config = id | modebits; // now publishable
     *  runWorker
     *       w.config |= SRC;
     *       
     *  awaitWork
     *      w.config |= QUIET;           // sentinel for deregisterWorker
     *
     *  WorkQueue(ForkJoinWorkerThread owner, boolean isInnocuous)
     *      this.config = (isInnocuous) ? INNOCUOUS : 0;
     *      
     *  WorkQueue(int config)
     *      this.config = config;
     *      
     *
     * volatile int phase;        // versioned, negative if inactive
     *
     *  读
     *      awaitWork
     *             int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *             if (w.phase >= 0)
     *
     *  写
     *      registerWorker
     *           w.phase = w.config = id | modebits; // now publishable
     *
     *      signalWork
     *          (sp = (int)c & ~UNSIGNALLED)
     *          v.phase = sp;
     *
     *      awaitWork
     *          int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *          w.phase = phase | UNSIGNALLED;       // advance phase
     *
     *          w.phase = phase;     // self-signal
     *      
     *      tryCompensate
     *          sp        = (int)c & ~UNSIGNALLED;
     *          v.phase = sp;
     *          
     *
     *      WorkQueue(int config)
     *          phase = -1;
     *
     *
     * volatile int source;       // source queue id, lock, or sentinel
     *  读
     *      awaitWork
     *          q.source != 0)) {
     *      canStop
     *          q.source != 0
     *      helpJoin
     *          int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
     *          int sq = q.source & SMASK, cap, b;
     *           ((sx = (x.source & SMASK)) == wid ||
     *           (y.source & SMASK) == wid)));
     *           else if ((q.source & SMASK) != sq ||
     *
     *
     *  写
     *      scan
     *          int nextIndex = (cap - 1) & nextBase, src = j | SRC;
     *          if ((w.source = src) != prevSrc && next != null)
     *
     *      helpJoin
     *            if ((q = qs[j = r & m]) != null)
     *            int nextBase = b + 1, src = j | SRC, sx;
     *            
     *            w.source = src;
     *            t.doExec();
     *            w.source = wsrc;
     *
     *     WorkQueue
     *      lockedPush
     *      growArray
     *      topLevelExec
     *      
     *      externalTryUnpush
     *      tryRemove
     *      helpComplete
     *          source = 0;
     *
     *  锁方法
     *  tryLock
     *      submissionQueue
     *      WorkQueue
     *          externalTryUnpush
     *          tryRemove
     *          helpComplete
     *      
     *
     *  Runs the given (stolen) task if nonnull, as well as remaining
     *  local tasks and/or others available from the given queue.
     *  
     *  final void topLevelExec(ForkJoinTask<?> task, WorkQueue q)
     *
     *      
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.
     * Most non-atomic control is performed by some form
     * of scanning across or within queues.
     * The pool itself creates,
     * activates (enables scanning for and running tasks),
     * deactivates, blocks, and terminates threads, all with minimal
     * central information.
     * 
     * There are only a few properties that we
     * can globally track or maintain, so we pack them into a small
     * number of variables, often maintaining atomicity without
     * blocking or locking.
     *
     * Nearly all essentially atomic control
     * state is held in a few volatile variables that are by far most
     * often read (not written) as status and consistency checks.
     * 
     * We pack as much information into them as we can.
     *
     * 窃取工作的主要吞吐量优势源于分散控制——工作者们（线程）大多以每秒超过10亿的速度
     * 从自己或彼此手中夺走任务。
     * 大多数非原子控制是通过在队列之间或队列内进行某种形式的扫描来执行的。
     * 池本身创建、激活（启用扫描和运行任务）、停用、阻塞和终止线程，
     * 所有这些都只需最少的中心信息。
     * 我们只能全局跟踪或维护少数属性，因此我们将它们打包到少量变量中，
     * 通常在不阻塞或锁定的情况下保持原子性。
     * 
     * 几乎所有本质上的原子控制状态都保存在少数易失性volatile变量中，
     * 这些变量通常作为状态和一致性检查进行读取（而不是写入）。
     * 我们将尽可能多的信息打包到它们中。
     * 
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, enqueue (on an event queue), and
     * dequeue and release workers.  To enable this packing, we
     * restrict maximum parallelism to (1<<15)-1 (which is far in
     * excess of normal operating range) to allow ids, counts, and
     * their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * 字段“ctl”包含64位，其中包含原子决定添加、入队（在事件队列上）、出队和释放
     * 工作者所需的信息。
     * 为了实现这种打包，我们将最大并行度限制为（1<<15）-1（远远超过正常操作范围），
     * 以允许id、计数及其否定（用于阈值处理）适合16位子字段。
     *
     * Field "mode" holds configuration parameters as well as lifetime
     * status, atomically and monotonically setting SHUTDOWN, STOP,
     * and finally TERMINATED bits. It is updated only via bitwise
     * atomics (getAndBitwiseOr).
     *
     * 字段“mode”保存配置参数以及生存期状态，原子地和单调地设置
     * SHUTDOWN、STOP和TERMINATED位。
     * 它仅通过逐位原子（getAndBitwiseOr）进行更新。
     * 
     * Array "queues" holds references to WorkQueues.  It is updated
     * (only during worker creation and termination) under the
     * registrationLock, but is otherwise concurrently readable, and
     * accessed directly (although always prefaced by acquireFences or
     * other acquiring reads). To simplify index-based operations, the
     * array size is always a power of two, and all readers must
     * tolerate null slots.  Worker queues are at odd indices. Worker
     * ids masked with SMASK match their index. Shared (submission)
     * queues are at even indices. Grouping them together in this way
     * simplifies and speeds up task scanning.
     *
     * 数组“queues”包含对WorkQueues的引用。它在registrationLock下更新
     * （仅在工作者者创建和终止期间），
     * 但在其他方面是可并发读取的，并可直接访问（尽管总是以获取围栏或其他获取读取为开头）。
     * 为了简化基于索引的操作，数组大小总是2的幂，并且所有读卡器都必须允许空槽。
     * 工作队列的索引为奇数。
     * 使用SMASK屏蔽的工作ID与其索引匹配。共享（提交）队列的索引是偶数。
     * 以这种方式将它们分组在一起可以简化并加快任务扫描。
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers.
     *
     * However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker or task references that would prevent
     * GC, all accesses to workQueues are via indices into the
     * queues array (which is one source of some of the messy code
     * constructions here). In essence, the queues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * 所有工作线程的创建都是按需的，由任务提交、替换终止的工作线程和/或补偿被阻止的工作线程触发。
     * 
     * 但是，所有其他支持代码都设置为与其他策略配合使用。
     * 为了确保我们不会保留会阻止GC的工作者或任务引用，
     * 所有对workQueues的访问都是通过队列数组中的索引进行的（这是这里一些混乱代码构造的来源之一）。
     * 从本质上讲，队列数组是一种弱引用机制。因此，例如ctl的栈顶子字段存储索引，而不是引用。
     * 
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. These latencies are mainly a
     * function of JVM park/unpark (and underlying OS) performance,
     * which can be slow and variable.  In many usages, ramp-up time
     * is the main limiting factor in overall performance, which is
     * compounded at program start-up by JIT compilation and
     * allocation. On the other hand, throughput degrades when too
     * many threads poll for too few tasks.
     *
     * 正在排队等候空闲工作者。与HPC窃取工作的框架不同，
     * 我们不能让工作证在无法立即找到任务的情况下无限期地扫描任务，
     * 并且除非出现可用任务，否则我们不能启动/恢复工作工作者。
     * 另一方面，当提交或生成新任务时，我们必须迅速促使他们采取行动。
     * 这些延迟主要是JVM park/unpark（和底层操作系统）性能的函数，
     * 这可能是缓慢的且可变的。
     * 在许多用途中，斜坡上升时间是总体性能的主要限制因素，
     * JIT编译和分配在程序启动时会加剧这种情况。
     * 另一方面，当太多的线程轮询太少的任务时，吞吐量会下降。
     *
     * The "ctl" field atomically maintains total and "released"
     * worker counts, plus the head of the available worker queue
     * (actually stack, represented by the lower 32bit subfield of
     * ctl).  Released workers are those known to be scanning for
     * and/or running tasks. Unreleased ("available") workers are
     * recorded in the ctl stack. These workers are made available for
     * signalling by enqueuing in ctl (see method awaitWork).  The
     * "queue" is a form of Treiber stack. This is ideal for
     * activating threads in most-recently used order, and improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.
     *
     * “ctl”字段原子性地维护总的和“释放的”工作人员计数，加上可用工作队列的头
     * （实际上是堆栈，由ctl的低32位子字段表示）。
     * 被释放的工作者是那些已知正在扫描和/或运行任务的工作者。
     * 未释放（“可用”）的工作线程记录在ctl堆栈中。
     * 这些工作者可以通过在ctl中排队来发出信号（请参阅awaitWork方法）。
     * “队列”是Treiber堆栈的一种形式。
     * 这非常适合以最近使用的顺序激活线程，并提高了性能和位置，
     * 超过了易发生争用和无法释放工作线程的缺点，除非它位于堆栈的最顶端。
     * 
     * The top stack state holds the
     * value of the "phase" field of the worker: its index and status,
     * plus a version counter that, in addition to the count subfields
     * (also serving as version stamps) provide protection against
     * Treiber stack ABA effects.
     *
     * 顶部堆栈状态保存工作程序的“阶段”字段的值：(WorkQueue的volatile字段)
     * 它的索引和状态，加上一个版本计数器，除了计数子字段（也用作版本戳）之外，
     * 该计数器还提供针对Treiber堆栈ABA效应的保护。
     * 
     * Creating workers. To create a worker, we pre-increment counts
     * (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. On starting, the new
     * thread first invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the queues array
     * (expanding the array if necessary).  Upon any exception across
     * these steps, or null return from factory, deregisterWorker
     * adjusts counts and records accordingly.  If a null return, the
     * pool continues running with fewer than the target number
     * workers. If exceptional, the exception is propagated, generally
     * to some external caller.
     *
     * 创造工作者（线程）。为了创建一个worker，我们预先增加计数（用作保留），
     * 并尝试通过其工厂构建ForkJoinWorkerThread。
     * 启动时，新线程首先调用registerWorker，在那里它构造一个WorkQueue，
     * 并在queues数组中分配一个索引（如有必要，扩展数组）。
     * 在这些步骤中出现任何异常，或工厂返回null时，取消注册Worker会相应地调整计数和记录。
     * 如果返回null，则池将继续运行，工作人员数量将少于目标数量。
     * 如果出现异常，则通常会将异常传播到某个外部调用方。
     * 
     * WorkQueue field "phase" is used by both workers and the pool to
     * manage and track whether a worker is UNSIGNALLED (possibly
     * blocked waiting for a signal).  When a worker is enqueued its
     * phase field is set negative. Note that phase field updates lag
     * queue CAS releases; seeing a negative phase does not guarantee
     * that the worker is available. When queued, the lower 16 bits of
     * its phase must hold its pool index. So we place the index there
     * upon initialization and never modify these bits.
     *
     * WorkQueue的volatile字段“phase”由工作者线程和池使用，以管理和跟踪工作线程是否未被忽略
     * （可能被阻止等待信号）。
     * 当工作者入队时，其相位字段设置为负值。
     * 请注意，阶段字段更新CAS发布的滞后队列；看到负相位并不能保证工作者是可用的。
     * 当排队时，其相位的较低16位必须保持其池索引。
     * 因此，我们在初始化时将索引放在那里，并且从不修改这些位。
     *
     * The ctl field also serves as the basis for memory
     * synchronization surrounding activation. This uses a more
     * efficient version of a Dekker-like rule that task producers and
     * consumers sync with each other by both writing/CASing ctl (even
     * if to its current value).  However, rather than CASing ctl to
     * its current value in the common case where no action is
     * required, we reduce write contention by ensuring that
     * signalWork invocations are prefaced with a full-volatile memory
     * access (which is usually needed anyway).
     *
     * ctl字段还充当了围绕激活的内存同步的基础。
     * 这使用了一个更高效的类似Dekker的规则版本，
     * 即任务生产者和消费者通过写入/CASing ctl（即使达到其当前值）来相互同步。
     * 然而，与在不需要任何操作的常见情况下将ctl CASing为其当前值不同，
     * 我们通过确保signalWork调用以完全的易失性内存访问（这通常是需要的）为前提来减少写争用。
     * 
     * Signalling. Signals (in signalWork) cause new or reactivated
     * workers to scan for tasks.
     *
     * Method signalWork and its callers
     * try to approximate the unattainable goal of having the right
     * number of workers activated for the tasks at hand, but must err
     * on the side of too many workers vs too few to avoid stalls.  If
     * computations are purely tree structured, it suffices for every
     * worker to activate another when it pushes a task into an empty
     * queue, resulting in O(log(threads)) steps to full activation.
     *
     * 信号。信号（在signalWork中）使新的或重新激活的工作者（线程）扫描scan任务。
     * 方法signalWork及其调用方试图接近为手头的任务激活正确数量的工作人员这一无法实现的目标，
     * 但必须在工作人员过多和过少的情况下犯错，以避免停滞。如果计算是纯树结构的，
     * 那么当每个工作者将任务推入空队列queue时，激活另一个就足够了，
     * 从而导致O(log(threads))步完全激活。
     * 
     * If instead, tasks come in serially from only a single producer,
     * each worker taking its first (since the last quiescence) task
     * from a queue should signal another if there are more tasks in
     * that queue. This is equivalent to, but generally faster than,
     * arranging the stealer take two tasks, re-pushing one on its own
     * queue, and signalling (because its queue is empty), also
     * resulting in logarithmic full activation time. Because we don't
     * know about usage patterns (or most commonly, mixtures), we use
     * both approaches.
     *
     * 相反，如果任务仅从单个生产者串行进入，则每个从队列中获取其第一个（自上次静止以来）
     * 任务的工作者应在该队列中有更多任务时向另一个发出信号。
     * 这相当于，但通常比安排窃取者执行两项任务更快，
     * 在其自己的队列中重新推送一项任务，并发出信号（因为其队列是空的），
     * 这也导致对数完全激活时间。因为我们不知道使用模式（或者最常见的混合模式），
     * 所以我们使用这两种方法。
     * 
     * We approximate the second rule by arranging
     * that workers in scan() do not repeat signals when repeatedly
     * taking tasks from any given queue, by remembering the previous
     * one. There are narrow windows in which both rules may apply,
     * leading to duplicate or unnecessary signals. Despite such
     * limitations, these rules usually avoid slowdowns that otherwise
     * occur when too many workers contend to take too few tasks, or
     * when producers waste most of their time resignalling.  However,
     * contention and overhead effects may still occur during ramp-up,
     * ramp-down, and small computations involving only a few workers.
     *
     * 我们通过记住前一条规则，scan方法中的工作人员在重复从任何给定队列中获取任务时不重复信号，
     * 来近似第二条规则。
     * 在狭窄的窗口中，这两种规则都可能适用，从而导致重复或不必要的信号。
     * 尽管有这些限制，但这些规则通常会避免在太多工人争着承担太少任务时，
     * 或者在生产商浪费大部分时间辞职时出现的放缓。
     * 然而，在上升、下降和仅涉及少数工人的小型计算过程中，仍可能发生争用和开销效应。
     * 
     * Scanning. Method scan performs top-level scanning for (and
     * execution of) tasks.  Scans by different workers and/or at
     * different times are unlikely to poll queues in the same
     * order. Each scan traverses and tries to poll from each queue in
     * a pseudorandom permutation order by starting at a random index,
     * and using a constant cyclically exhaustive stride; restarting
     * upon contention.  (Non-top-level scans; for example in
     * helpJoin, use simpler linear probes because they do not
     * systematically contend with top-level scans.)
     *
     * 正在扫描。方法扫描执行任务的顶层扫描（和执行）。不同工作者（线程）和/或
     * 不同时间的扫描不太可能以相同的顺序轮询队列。
     * 每次扫描从随机索引开始，并使用恒定的循环穷举步幅，以伪随机排列顺序遍历并尝试从每个队列轮询；
     * 在争用时重新启动。
     * （非顶级扫描；例如，在helpJoin中，使用更简单的线性探针，因为它们不会系统地与顶级扫描竞争。）
     * 
     * The pseudorandom generator need not have high-quality statistical properties in
     * the long term. We use Marsaglia XorShifts, seeded with the Weyl
     * sequence from ThreadLocalRandom probes, which are cheap and
     * suffice. Scans do not otherwise explicitly take into account
     * core affinities, loads, cache localities, etc, However, they do
     * exploit temporal locality (which usually approximates these) by
     * preferring to re-poll from the same queue after a successful
     * poll before trying others (see method topLevelExec).  This
     * reduces fairness, which is partially counteracted by using a
     * one-shot form of poll (tryPoll) that may lose to other workers.
     *
     * 从长远来看，伪随机生成器不需要具有高质量的统计特性。
     * 我们使用Marsaglia XorShifts，用ThreadLocalRandom探针的Weyl序列接种，这是便宜且足够的。
     * 扫描不会明确考虑核心相关性、负载、缓存位置等。
     * 但是，它们确实会利用时间位置（通常接近这些位置），
     * 在成功轮询后，在尝试其他轮询之前，更倾向于从同一队列重新轮询（请参阅方法topLevelExec）。
     * 这降低了公平性，而使用一次性投票形式（tryPoll）可能会输给其他员工，这在一定程度上抵消了公平性。
     * 
     * Deactivation. Method scan returns a sentinel when no tasks are
     * found, leading to deactivation (see awaitWork). The count
     * fields in ctl allow accurate discovery of quiescent states
     * (i.e., when all workers are idle) after deactivation. However,
     * this may also race with new (external) submissions, so a
     * recheck is also needed to determine quiescence. Upon apparently
     * triggering quiescence, awaitWork re-scans and self-signals if
     * it may have missed a signal. In other cases, a missed signal
     * may transiently lower parallelism because deactivation does not
     * necessarily mean that there is no more work, only that that
     * there were no tasks not taken by other workers.  But more
     * signals are generated (see above) to eventually reactivate if
     * needed.
     *
     * 停用。scan方法扫描在找不到任务时返回一个sentinel，导致停用（请参阅awaitWork）。
     * ctl中的计数count字段允许在去激活后准确发现静态（即，当所有工作程序都空闲时）。
     * 然而，这也可能与新的（外部）提交竞争，因此还需要重新检查以确定静止。
     * 在明显触发静止后，awaitWork会重新扫描并发出信号（如果它可能错过了信号）。
     * 在其他情况下，丢失的信号可能会暂时降低并行性，因为去激活并不一定意味着没有更多的工作，
     * 只是意味着没有其他工作者（线程）没有完成的任务。
     * 但如果需要的话，会产生更多的信号（见上文）最终重新激活。
     * 
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate if the pool has remained quiescent for
     * period given by field keepAlive.
     *
     * 维护工作者（线程）。要在缺乏使用的时间段后释放资源，当池处于静止状态时开始等待的工作者将超时，
     * 如果池在字段keepAlive给定的时间段内保持静止，则终止。
     * 
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a mode bit. The calling thread,
     * as well as every other worker thereafter terminating, helps
     * terminate others by cancelling their unprocessed tasks, and
     * waking them up. Calls to non-abrupt shutdown() preface this by
     * checking isQuiescent before triggering the "STOP" phase of
     * termination. To conform to ExecutorService invoke, invokeAll,
     * and invokeAny specs, we must track pool status while waiting,
     * and interrupt interruptible callers on termination (see
     * ForkJoinTask.joinForPoolInvoke etc).
     *
     * 关闭和终止。对shutdownow的调用调用tryTerminate以原子方式设置模式位。
     * 调用线程以及此后终止的每个其他工作线程，通过取消其他人未处理的任务并唤醒他们来帮助终止他们。
     * 调用非突然关闭shutdown()，在触发终止的“STOP”阶段之前检查isQuietent。
     * 为了符合ExecutorService invoke、invokeAll和invokeAny规范，
     * 我们必须在等待时跟踪池状态，并在终止时中断可中断的调用方
     * （请参见ForkJoinTask.joinForPoolInvoke等）。
     *
     * 
     * Joining Tasks
     * =============
     *
     * Normally, the first option when joining a task that is not done
     * is to try to unfork it from local queue and run it.  Otherwise,
     * any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * always just let them block (as in Thread.join).  We also cannot
     * just reassign the joiner's run-time stack with another and
     * replace it later, which would be a form of "continuation", that
     * even if possible is not necessarily a good idea since we may
     * need both an unblocked task and its continuation to progress.
     * Instead we combine two tactics:
     *
     * 通常，加入未完成的任务时的第一个选项是尝试将其从本地队列中取消锁定并运行。
     * 否则，当一个工作者（线程）正在等待加入另一个窃取（或始终持有）的任务时，
     * 可能会采取以下任一操作。
     * 因为我们将许多任务多路复用到一个工作线程池中，
     * 所以我们不能总是让它们阻塞（如在Thread.join中）。
     * 我们也不能只是用另一个来重新分配joiner的运行时堆栈，然后再替换它，
     * 这将是一种“延续”形式，即使可能，
     * 这也不一定是一个好主意，因为我们可能需要一个未阻塞的任务和它的延续来进行。
     * 相反，我们结合了两种策略：
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      could be running if the steal had not occurred.
     *  帮助：安排加入者执行一些任务，如果没有发生盗窃，它可能正在运行。
     *  
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     *   补偿：除非已经有足够的活动线程，否则方法tryCompensate()可能会创建或
     *        重新激活一个sparethread来补偿被阻止的连接程序，直到它们被取消阻止。
     *   
     * A third form (implemented via tryRemove) amounts to helping a
     * hypothetical compensator: If we can readily tell that a
     * possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread; although with a
     * (rare) possibility of reduced parallelism because of a
     * transient gap in the queue array.
     *
     * 第三种形式（通过tryRemove实现）相当于帮助一个假设的补偿器：
     * 如果我们可以很容易地判断补偿器的一个可能动作是窃取并执行被连接的任务，
     * 则连接线程可以直接执行，而不需要补偿线程；
     * 尽管由于队列阵列中的瞬态间隙，并行性降低的可能性（罕见）。
     * 
     * Other intermediate forms available for specific task types (for
     * example helpAsyncBlocker) often avoid or postpone the need for
     * blocking or compensation.
     *
     * 可用于特定任务类型的其他中间形式（例如helpAsyncBlocker）通常可以避免或推迟阻塞或补偿的需要。
     * 
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * ManagedBlocker扩展API无法使用帮助，因此仅依赖于方法awaitBlocker中的补偿。
     * 
     * The algorithm in helpJoin entails a form of "linear helping".
     * Each worker records (in field "source") the id of the queue
     * from which it last stole a task.  The scan in method helpJoin
     * uses these markers to try to find a worker to help (i.e., steal
     * back a task from and execute it) that could hasten completion
     * of the actively joined task.  Thus, the joiner executes a task
     * that would be on its own local deque if the to-be-joined task
     * had not been stolen.
     *
     * helpJoin中的算法包含一种“线性帮助”形式。
     * 每个工作进程记录（在“源”source字段中）它上次从中窃取任务的队列的id。
     * scan-in方法helpJoin使用这些标记来尝试找到一个工作者（线程）
     * 来帮助（即，从中偷回任务并执行它），
     * 该工作人员可以加快主动加入任务的完成。
     * 因此，如果要加入的任务没有被窃取，那么加入者将在其自己的本地deque上执行一个任务。
     * 
     * This is a conservative variant of the
     * approach described in Wagner & Calder "Leapfrogging: a portable
     * technique for implementing efficient futures" SIGPLAN Notices,
     * 1993 (http://portal.acm.org/citation.cfm?id=155354).
     * 跳跃：一种实现高效期货的可移植技术
     * 
     * It differs mainly in that we only record queue ids, not full dependency
     * links.  This requires a linear scan of the queues array to
     * locate stealers, but isolates cost to when it is needed, rather
     * than adding to per-task overhead. Also, searches are limited to
     * direct and at most two levels of indirect stealers, after which
     * there are rapidly diminishing returns on increased overhead.
     * Searches can fail to locate stealers when stalls delay
     * recording sources.  Further, even when accurately identified,
     * stealers might not ever produce a task that the joiner can in
     * turn help with. So, compensation is tried upon failure to find
     * tasks to run.
     *
     * 它的不同之处主要在于，我们只记录队列ID，而不是完整的依赖关系链接。
     * 这需要对队列阵列进行线性扫描以定位窃取者，但在需要时隔离成本，而不是增加每个任务的开销。
     * 此外，搜索仅限于直接和最多两个级别的间接窃取，之后，增加的开销会带来快速递减的回报。
     * 当暂停延迟记录源时，搜索可能无法定位窃取程序。
     * 此外，即使被准确识别，窃取者也可能永远不会产生一个参与者可以帮助完成的任务。
     * 因此，在找不到要运行的任务时会尝试进行补偿。
     * 
     * Joining CountedCompleters (see helpComplete) differs from (and
     * is generally more efficient than) other cases because task
     * eligibility is determined by checking completion chains rather
     * than tracking stealers.
     *
     * 加入CountedCompleters（请参阅helpComplete）与其他情况不同
     * （通常比其他情况更高效），
     * 因为任务资格是通过检查完成链而不是跟踪窃取者来确定的。
     * 
     * Joining under timeouts (ForkJoinTask timed get) uses a
     * constrained mixture of helping and compensating in part because
     * pools (actually, only the common pool) may not have any
     * available threads: If the pool is saturated (all available
     * workers are busy), the caller tries to remove and otherwise
     * help; else it blocks under compensation so that it may time out
     * independently of any tasks.
     *
     * 超时加入（ForkJoinTask timed get）使用了帮助和补偿的受限混合，
     * 部分原因是池（实际上只有公共池）可能没有任何可用线程：
     * 如果池饱和（所有可用的工作线程都很忙），调用方会尝试删除并以其他方式提供帮助；
     * 否则它会在补偿下阻塞，从而可以独立于任何任务超时。
     * 
     * Compensation does not by default aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement
     * when they cause longer-term oversubscription.  Rather than
     * impose arbitrary policies, we allow users to override the
     * default of only adding threads upon apparent starvation.  The
     * compensation mechanism may also be bounded.  Bounds for the
     * commonPool (see COMMON_MAX_SPARES) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so.
     *
     * 默认情况下，补偿的目的不是在任何给定时间保持未阻塞线程的目标并行数。
     * 该类的一些早期版本对任何阻塞的联接都采用了即时补偿。
     * 然而，在实践中，绝大多数阻塞都是GC和其他JVM或OS活动的瞬态副产品，
     * 当它们导致长期超额订阅时，替换会使这些活动变得更糟。
     * 我们不强制使用任意的策略，而是允许用户覆盖只在明显饥饿时添加线程的默认设置。
     * 补偿机制也可以是有界的。
     * commonPool的边界（请参阅COMMON_MAX_SPARES）可以更好地使JVM在资源耗尽之
     * 前处理编程错误和滥用。
     * 
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields.
     *
     * 静态公共池在静态初始化后始终存在。由于它（或任何其他创建的池）永远不需要使用，
     * 我们将初始构建开销和占地面积最小化，只需设置十几个字段。
     * 
     * When external threads submit to the common pool, they can
     * perform subtask processing (see helpComplete and related
     * methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * 当外部线程提交到公共池时，它们可以在联接时执行子任务处理
     * （请参阅helpComplete和相关方法）。
     * 此调用方帮助策略使得将公共池并行度级别设置为比可用内核总数少一个（或多个），
     * 或者对于纯调用方运行甚至为零是明智的。
     * 我们不需要记录外部提交是否属于公共池——如果不是，外部帮助方法会快速返回。
     * 否则，这些提交者将被阻止等待完成，因此在不适用的情况下，
     * 额外的工作（通过大量的任务状态检查）相当于在ForkJoinTask.join中阻止之前
     * 的有限旋转等待。
     *
     * Guarantees for common pool parallelism zero are limited to
     * tasks that are joined by their callers in a tree-structured
     * fashion or use CountedCompleters (as is true for jdk
     * parallelStreams). Support infiltrates several methods,
     * including those that retry helping steps until we are sure that
     * none apply if there are no workers.
     *
     * 公共池并行度零的保证仅限于调用方以树结构方式连接或使用CountedCompleters的任务
     * （jdk parallelStreams也是如此）。
     * 支持渗透到几种方法中，包括那些重试帮助步骤的方法，直到我们确定如果没有工作者，则不适用。
     * 
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task.  The associated mechanics
     * may be JVM-dependent and must access particular Thread class
     * fields to achieve this effect.
     *
     * 作为托管环境中更合适的默认值，除非被系统属性覆盖，否则当存在SecurityManager时，
     * 我们使用子类InnocousForkJoinWorkerThread的worker。
     * 这些工作者没有权限集，不属于任何用户定义的ThreadGroup，
     * 并且在执行任何顶级任务后擦除所有ThreadLocals。
     * 相关的机制可能依赖于JVM，并且必须访问特定的线程类字段才能达到这种效果。
     * 
     * Interrupt handling
     * ==================
     *
     * The framework is designed to manage task cancellation
     * (ForkJoinTask.cancel) independently from the interrupt status
     * of threads running tasks. (See the public ForkJoinTask
     * documentation for rationale.)  Interrupts are issued only in
     * tryTerminate, when workers should be terminating and tasks
     * should be cancelled anyway. Interrupts are cleared only when
     * necessary to ensure that calls to LockSupport.park do not loop
     * indefinitely (park returns immediately if the current thread is
     * interrupted). If so, interruption is reinstated after blocking
     * if status could be visible during the scope of any task.  For
     * cases in which task bodies are specified or desired to
     * interrupt upon cancellation, ForkJoinTask.cancel can be
     * overridden to do so (as is done for invoke{Any,All}).
     *
     * 该框架设计用于独立于运行任务的线程的中断状态来管理任务取消（ForkJoinTask.cancel）。
     * （有关基本原理，请参阅公共ForkJoinTask文档。）中断仅在tryTerminate中发出，
     * 此时工作者（线程）应该终止，
     * 任务无论如何都应该取消。只有在必要时才清除中断，
     * 以确保对LockSupport.park的调用不会无限期循环（如果当前线程中断，则park会立即返回）。
     * 如果是这样的话，如果在任何任务的范围内状态都可见，那么在阻塞之后会恢复中断。
     * 对于指定或希望在取消时中断任务体的情况，可以重写ForkJoinTask.cancel来执行此操作
     * （就像invoke｛Any，All｝一样）。
     * 
     * Memory placement
     * ================
     *
     * Performance can be very sensitive to placement of instances of
     * ForkJoinPool and WorkQueues and their queue arrays. To reduce
     * false-sharing impact, the @Contended annotation isolates the
     * ForkJoinPool.ctl field as well as the most heavily written
     * WorkQueue fields. These mainly reduce cache traffic by scanners.
     * WorkQueue arrays are presized large enough to avoid resizing
     * (which transiently reduces throughput) in most tree-like
     * computations, although not in some streaming usages. Initial
     * sizes are not large enough to avoid secondary contention
     * effects (especially for GC cardmarks) when queues are placed
     * near each other in memory. This is common, but has different
     * impact in different collectors and remains incompletely
     * addressed.
     *
     * 性能对ForkJoinPool和WorkQueues实例及其队列阵列的位置非常敏感。
     * 为了减少错误共享的影响，@Contended注释隔离了ForkJoinPool.ctl字段
     * 以及写入量最大的WorkQueue字段。
     * 这些主要通过扫描仪减少缓存流量。WorkQueue数组的预大小足够大，
     * 可以避免在大多数树状计算中调整大小（这会暂时降低吞吐量），
     * 尽管在某些流式使用中不是这样。当队列在内存中彼此靠近时，
     * 初始大小不足以避免二次争用效应（尤其是GC卡标记）。
     * 这是常见的，但在不同的收集器中有不同的影响，并且仍然没有完全解决。
     * 
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on atomic operations (CAS,
     * getAndSet, getAndAdd) along with explicit fences.  This can be
     * awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. All fields are read into locals
     * before use, and null-checked if they are references, even if
     * they can never be null under current usages.  Array accesses
     * using masked indices include checks (that are always true) that
     * the array length is non-zero to avoid compilers inserting more
     * expensive traps.  This is usually done in a "C"-like style of
     * listing declarations at the heads of methods or blocks, and
     * using inline assignments on first encounter.  Nearly all
     * explicit checks lead to bypass/return, not exception throws,
     * because they may legitimately arise during shutdown.
     *
     * 内存排序主要依赖于原子操作（CAS、getAndSet、getAndAdd）以及显式栅栏。
     * 这可能是尴尬和丑陋的，但也反映出需要控制异常情况下的结果，
     * 这些异常情况出现在几乎没有不变量的非常活泼的代码中。
     * 所有字段在使用前都会读取到本地文件中，如果它们是引用，则会检查为null，
     * 即使在当前使用情况下它们永远不会为null。
     * 使用掩码索引的数组访问包括检查（始终为true）数组长度是否为非零，
     * 以避免编译器插入更昂贵的陷阱。
     * 这通常是以“C”风格完成的，在方法或块的头部列出声明，并在第一次遇到时使用内联赋值。
     * 几乎所有的显式检查都会导致旁路/返回，而不是异常抛出，因为它们可能在关闭期间合法出现。
     * 
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables. Some others are artificially broken up to
     * reduce producer/consumer imbalances due to dynamic compilation.
     * There are also other coding oddities (including several
     * unnecessary-looking hoisted null checks) that help some methods
     * perform reasonably even when interpreted (not compiled).
     *
     * 类ForkJoinPool、ForkJoinWorkerThread和ForkJoinTask之间存在大量表示级耦合。
     * WorkQueue的字段维护由ForkJoinPool管理的数据结构，因此可以直接访问。
     * 试图减少这种情况没有什么意义，因为任何相关的未来表示变化都需要伴随着算法的变化。
     * 一些方法本质上是扩张的，因为它们必须积累局部变量中字段的一致读取集。
     * 其他一些则被人为地分解，以减少由于动态汇编而造成的生产者/消费者失衡。
     * 还有其他一些编码上的奇怪之处（包括一些看起来不必要的挂起的null检查），
     * 即使在解释（而不是编译）时，也有助于某些方法合理地执行。
     * 
     * The order of declarations in this file is (with a few exceptions):
     * 此文件中声明的顺序为（除少数例外）：
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     *
     * Revision notes
     * ==============
     *
     * The main sources of differences of January 2020 ForkJoin
     * classes from previous version are:
     *
     * * ForkJoinTask now uses field "aux" to support blocking joins
     *   and/or record exceptions, replacing reliance on builtin
     *   monitors and side tables.
     * * Scans probe slots (vs compare indices), along with related
     *   changes that reduce performance differences across most
     *   garbage collectors, and reduce contention.
     * * Refactoring for better integration of special task types and
     *   other capabilities that had been incrementally tacked on. Plus
     *   many minor reworkings to improve consistency.
     */

    // Static utilities

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     */
    private static void checkPermission() {
        @SuppressWarnings("removal")
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    @SuppressWarnings("removal")
    static AccessControlContext contextWithPermissions(Permission ... perms) {
        Permissions permissions = new Permissions();
        for (Permission perm : perms)
            permissions.add(perm);
        return new AccessControlContext(
            new ProtectionDomain[] { new ProtectionDomain(null, permissions) });
    }

    // Nested classes

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     *
     * 用于创建新｛@link ForkJoinWorkerThread｝的工厂。
     * 必须为扩展基本功能或初始化具有不同上下文的线程的｛@code ForkJoinWorkerThread｝子类定义
     * 和使用｛@codeForkJoinworkerThreadFactory｝。
     */
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         * Returning null or throwing an exception may result in tasks
         * never being executed.  If this method throws an exception,
         * it is relayed to the caller of the method (for example
         * {@code execute}) causing attempted thread creation. If this
         * method returns null or throws an exception, it is not
         * retried until the next attempted creation (for example
         * another call to {@code execute}).
         *
         * 返回在给定池中操作的新工作线程。返回null或引发异常可能导致任务永远无法执行。
         * 如果此方法抛出异常，则会将其中继到该方法的调用方（例如｛@code execute｝），
         * 从而导致尝试创建线程。如果此方法返回null或引发异常，
         * 则在下一次尝试创建之前不会重试（例如，对{@code execute}的另一次调用）。
         * 
         * @param pool the pool this thread works in
         * @return the new worker thread, or {@code null} if the request
         *         to create a thread is rejected
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread using the system class loader as the
     * thread context class loader.
     * 默认ForkJoinWorkerThreadFactory实现；使用系统类加载器作为线程上下文类加载器创建
     * 一个新的ForkJoinWorkerThread。
     * 
     */
    static final class DefaultForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        // ACC for access to the factory
        @SuppressWarnings("removal")
        private static final AccessControlContext ACC = contextWithPermissions(
            new RuntimePermission("getClassLoader"),
            new RuntimePermission("setContextClassLoader"));
        @SuppressWarnings("removal")
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                new PrivilegedAction<>() {
                    public ForkJoinWorkerThread run() {
                        return new ForkJoinWorkerThread(null, pool, true, false);
                    }},
                ACC);
        }
    }

    /**
     * Factory for CommonPool unless overridden by System property.
     * Creates InnocuousForkJoinWorkerThreads if a security manager is
     * present at time of invocation.  Support requires that we break
     * quite a lot of encapsulation (some via helper methods in
     * ThreadLocalRandom) to access and set Thread fields.
     *
     * CommonPool的工厂，除非被System属性覆盖。如果调用时存在安全管理器，
     * 则创建InnocousForkJoinWorkerThreads。
     * 支持需要我们打破相当多的封装（有些是通过ThreadLocalRandom中的helper方法）
     * 来访问和设置Thread字段。
     * 
     */
    static final class DefaultCommonPoolForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        @SuppressWarnings("removal")
        private static final AccessControlContext ACC = contextWithPermissions(
            modifyThreadPermission,
            new RuntimePermission("enableContextClassLoaderOverride"),
            new RuntimePermission("modifyThreadGroup"),
            new RuntimePermission("getClassLoader"),
            new RuntimePermission("setContextClassLoader"));

        @SuppressWarnings("removal")
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                 new PrivilegedAction<>() {
                     public ForkJoinWorkerThread run() {
                         return System.getSecurityManager() == null ?
                             new ForkJoinWorkerThread(null, pool, true, true):
                             new ForkJoinWorkerThread.
                             InnocuousForkJoinWorkerThread(pool); }},
                 ACC);
        }
    }

    // Constants shared across ForkJoinPool and WorkQueue

    /**
     * FIFO:	65536	
     * 10000000000000000
     * SRC:	131072	Bit->:
     * 100000000000000000
     * INNOCUOUS:	262144	
     * 1000000000000000000
     * QUIET:	524288	
     * 10000000000000000000
     * SHUTDOWN:	16777216	
     * 100000000000000000000000
     * TERMINATED:	33554432
     * 10000000000000000000000000
     * STOP:	-2147483648	
     * 1111111111111111111111111111111110000000000000000000000000000000
     * UNCOMPENSATE:	65536
     * 1000000000000000
     */
    // Bounds
    static final int SWIDTH       = 16;          // width of short 宽度
    //SMASK:	65535	Bit->:1111111111111111 f*4
    static final int SMASK        = 0xffff;      // short bits == max index
    //MAX_CAP:	32767	Bit->: 111111111111111
    static final int MAX_CAP      = 0x7fff;      // max #workers - 1

    // Masks and units for WorkQueue.phase and ctl sp subfield
    //UNSIGNALLED:	-2147483648
    //Bit->:1111111111111111111111111111111110000000000000000000000000000000
    static final int UNSIGNALLED  = 1 << 31;     // must be negative
    //SS_SEQ:	65536	Bit->:10000000000000000
    static final int SS_SEQ       = 1 << 16;     // version count

    // Mode bits and sentinels, some also used in WorkQueue fields
    static final int FIFO         = 1 << 16;     // fifo queue or access mode
    static final int SRC          = 1 << 17;     // set for valid queue ids
    static final int INNOCUOUS    = 1 << 18;     // set for Innocuous workers
    static final int QUIET        = 1 << 19;     // quiescing phase or source
                                                 // 静止阶段或源
    static final int SHUTDOWN     = 1 << 24;
    static final int TERMINATED   = 1 << 25;
    static final int STOP         = 1 << 31;     // must be negative
    static final int UNCOMPENSATE = 1 << 16;     // tryCompensate return

    /**
     * SMASK
     *      signalWork、awaitWork、canStop、tryCompensate、helpJoin
     *      getSurplusQueuedTaskCount、tryTerminate、
     *      ForkJoinPool
     *      getParallelism、getPoolSize、getActiveThreadCount
     *      
     * 
     * SS_SEQ
     *      awaitWork
     *      int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *      c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
     * 
     * QUIET
     *      读取方法：deregisterWorker、awaitWork、helpQuiescePool 无写方法
     *      cfg、source
     *      
     * FIFO:mode、cfg
     * 
     * SRC
     *      cfg config src id
     *      
     * INNOCUOUS
     *      registerWorker、topLevelExec
     *      cfg、mode：initializeInnocuousWorker 、eraseThreadLocals
     *
     * SHUTDOWN
     *      isShutdown、tryTerminate、submissionQueue、awaitWork
     *      mode
     *
     * TERMINATED / STOP
     *      canStop、tryTerminate、
     *      mode
     *
     * UNCOMPENSATE
     *      tryCompensate
     *      
     */
    
    /**
     * Initial capacity of work-stealing queue array.  Must be a power
     * of two, at least 2. See above.
     */
    static final int INITIAL_QUEUE_CAPACITY = 1 << 8;

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     */
    static final class WorkQueue {
        volatile int phase;        // versioned, negative if inactive，
                                   // 版本，如果不活动则为负数
        int stackPred;             // pool stack (ctl) predecessor link，
                                   // 池堆栈（ctl）前置链接
        int config;                // index, mode, ORed with SRC after init，
                                   // 索引，模式，初始化后与SRC进行OR运算
        
        int base;                  // index of next slot for poll，
                                   // 下一个轮询槽的索引
        ForkJoinTask<?>[] array;   // the queued tasks; power of 2 size，
                                   // 排队的任务；2幂
        final ForkJoinWorkerThread owner; // owning thread or null if shared，
                                          // 拥有线程或null（如果共享）

        // segregate fields frequently updated
        // but not read by scans or steals
        //隔离频繁更新但扫描或窃取未读取的字段
        @jdk.internal.vm.annotation.Contended("w")
        int top;   // index of next slot for push,下一个推送插槽的索引
        @jdk.internal.vm.annotation.Contended("w")
        volatile int source;  // source queue id, lock, or sentinel,
                              // 源队列的id、锁或哨兵
        @jdk.internal.vm.annotation.Contended("w")
        int nsteals;  // number of steals from other queues，
                      // 从其他队列窃取的次数

        // Support for atomic operations，支持原子操作
        private static final VarHandle QA; // for array slots，用于阵列插槽
        private static final VarHandle SOURCE;
        private static final VarHandle BASE;
        static final ForkJoinTask<?> getSlot(ForkJoinTask<?>[] a, int i) {
            return (ForkJoinTask<?>)QA.getAcquire(a, i);
        }
        static final ForkJoinTask<?> getAndClearSlot(ForkJoinTask<?>[] a,
                                                     int i) {
            return (ForkJoinTask<?>)QA.getAndSet(a, i, null);
        }
        static final void setSlotVolatile(ForkJoinTask<?>[] a, int i,
                                          ForkJoinTask<?> v) {
            QA.setVolatile(a, i, v);
        }
        static final boolean casSlotToNull(ForkJoinTask<?>[] a, int i,
                                          ForkJoinTask<?> c) {
            return QA.compareAndSet(a, i, c, null);
        }
        final boolean tryLock() {
            return SOURCE.compareAndSet(this, 0, 1);
        }
        final void setBaseOpaque(int b) {
            BASE.setOpaque(this, b);
        }

        /**
         * Constructor used by ForkJoinWorkerThreads. Most fields
         * are initialized upon thread start, in pool.registerWorker.
         *
         * ForkJoinWorkerThread实例化 isInnocuous为false
         */
        WorkQueue(ForkJoinWorkerThread owner, boolean isInnocuous) {
            //INNOCUOUS: 无害的
            //INNOCUOUS    = 1 << 18;  // set for Innocuous workers
            this.config = (isInnocuous) ? INNOCUOUS : 0;
            this.owner = owner; 
        }

        /**
         * Constructor used for external queues.
         * submissionQueue 实例化 传入config为ID（线程哈希|SRC）
         * submissionQueue调用externalPush
         */
        WorkQueue(int config) {
            array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            this.config = config;
            owner = null;
            phase = -1;
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         * 返回一个可导出的索引（由ForkJoinWorkerThread使用）。
         */
        final int getPoolIndex() {
            //忽略奇数/偶数标记位
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         * 返回队列中任务的大致数量。
         */
        final int queueSize() {
            //读取实例变量 top base一般实例变量, 确保外部调用程序进行新的读取，
            // ensure fresh reads by external callers，
            VarHandle.acquireFence(); 
            int n = top - base;
            // 忽略瞬态负数
            return (n < 0) ? 0 : n;   // ignore transient negative ，
        }

        /**
         * Provides a more conservative estimate of whether this queue
         * has any tasks than does queueSize.
         *
         * 提供此队列是否具有比queueSize更保守的任务估计。
         */
        final boolean isEmpty() {
            return !((source != 0 && owner == null) || top - base > 0);
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.
         * 推送任务。仅由非共享队列中的所有者调用。
         * ForkJoinTask调用（ForkJoinTaskThread），如
         *  (w = (ForkJoinWorkerThread)t).workQueue.push(this, w.pool);
         *  
         * @param task the task. Caller must ensure non-null. 调用者确保task非null
         * @param pool (no-op if null) pool为空，无操作
         * @throws RejectedExecutionException if array cannot be resized
         */
        final void push(ForkJoinTask<?> task, ForkJoinPool pool) {
            ForkJoinTask<?>[] a = array;
            int s = top++, d = s - base, cap, m; // skip insert if disabled
            if (a != null && pool != null && (cap = a.length) > 0) {
                //原子更新槽位置数据，即第一个任务task
                setSlotVolatile(a, (m = cap - 1) & s, task);
                if (d == m){ //任务数组的长度-1，定top字段加一 d为top-base
                    growArray();
                }
                if (d == m || a[m & (s - 1)] == null){
                    // 信号，如果为空或调整了大小
                    pool.signalWork(); // signal if was empty or resized
                }
            }
        }

        /**
         * Pushes task to a shared queue with lock already held, and unlocks.
         * 将任务推送到已持有锁的共享队列，然后解锁。
         *
         * 由外部线程调用,在启动任务线程前调用
         * externalPush调用lockedPush，不是ForkJoinTaskThread时的处理
         * @return true if caller should signal work
         *         如果调用者应该发出工作信号，则为true
         */
        final boolean lockedPush(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a = array;
            int s = top++, d = s - base, cap, m;
            if (a != null && (cap = a.length) > 0) {
                // 位置计算：数组长度 与 top取 & 计算 而不是数组长度取模%
                a[(m = cap - 1) & s] = task;  //
                if (d == m){ //宽度等于容量时扩容
                    growArray();
                }
                // lock状态 共享队列
                source = 0; // unlock, 调用方将其设置为1
                // 宽度 top-base m为数组长度-1 或者新的数组没有元素
                // 如果为空或调整了大小
                if (d == m || a[m & (s - 1)] == null){
                    return true;
                }
            }
            return false;
        }

        /**
         * Doubles the capacity of array. Called by owner or with lock
         * held after pre-incrementing top, which is reverted on
         * allocation failure.
         *
         * 使阵列的容量加倍。由所有者调用，或在预先递增top之后持有锁，这将恢复为分配失败。
         */
        final void growArray() {
            ForkJoinTask<?>[] oldArray = array, newArray;
            int s = top - 1, oldCap, newCap;
            if (oldArray != null && (oldCap = oldArray.length) > 0 &&
                (newCap = oldCap << 1) > 0) { // skip if disabled
                try {
                    newArray = new ForkJoinTask<?>[newCap];
                } catch (Throwable ex) {
                    top = s;
                    if (owner == null){ //不是当前线程
                        // lock状态 
                        source = 0; // unlock
                    }
                    throw new RejectedExecutionException(
                        "Queue capacity exceeded");
                }
                int newMask = newCap - 1, oldMask = oldCap - 1;
                for (int k = oldCap; k > 0; --k, --s) {
                    //轮询旧的，推到新的
                    ForkJoinTask<?> x;   // poll old, push to new，
                    if ((x = getAndClearSlot(oldArray, s & oldMask)) == null){
                        //其他线程（工作者）已经服用
                        break;  // others already taken ，
                    }
                    newArray[s & newMask] = x;
                }
                // fill before publish 发布前填写，写入实例变量
                VarHandle.releaseFence(); 
                array = newArray;
            }
        }

        // Variants of pop

        /**
         * Pops and returns task, or null if empty. Called only by owner.
         * 弹出并返回任务，如果为空则返回null。仅由所有者调用。deregisterWorker调用
         */
        private ForkJoinTask<?> pop() {
            ForkJoinTask<?> t = null;
            int s = top, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 && base != s-- &&
                (t = getAndClearSlot(a, (cap - 1) & s)) != null){
                top = s; //
            }
            return t;
        }

        /**
         * Pops the given task for owner only if it is at the current top.
         * 任务拥有者调用，非共享队列
         * 仅当给定任务位于当前顶部时，才为所有者弹出该任务。
         */
        final boolean tryUnpush(ForkJoinTask<?> task) {
            int s = top, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 && base != s-- &&
                casSlotToNull(a, (cap - 1) & s, task)) {
                top = s;
                return true;
            }
            return false;
        }

        /**
         * Locking version of tryUnpush. tryUnpush的锁定版本。
         * 非共享队列
         * public tryUnfork 无调用
         */
        final boolean externalTryUnpush(ForkJoinTask<?> task) {
            boolean taken = false;
            for (;;) {
                int s = top, cap, k; ForkJoinTask<?>[] a;
                if ((a = array) == null || (cap = a.length) <= 0 ||
                    a[k = (cap - 1) & (s - 1)] != task)
                    break;
                if (tryLock()) {
                    if (top == s && array == a) {
                        if (taken = casSlotToNull(a, k, task)) {
                            top = s - 1;
                            // lock状态  共享状态
                            source = 0;
                            break;
                        }
                    }
                    source = 0; // release lock for retry
                }
                Thread.yield(); // trylock failure
            }
            return taken;
        }

        /**
         * Deep form of tryUnpush: Traverses from top and removes task if
         * present, shifting others to fill gap.
         * 深层形式的尝试取消推送：从顶部遍历并删除任务（如果存在），转移其他任务以填补空白。
         *
         * 不是共享队列时需要更新source
         * 
         * ForkJoinTask的awaitDone调用
         */
        final boolean tryRemove(ForkJoinTask<?> task, boolean owned) {
            boolean taken = false;
            int p = top, cap; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
            if ((a = array) != null && task != null && (cap = a.length) > 0) {
                int m = cap - 1, s = p - 1, d = p - base;
                for (int i = s, k; d > 0; --i, --d) {
                    if ((t = a[k = i & m]) == task) {
                        if (owned || tryLock()) {
                            if ((owned || (array == a && top == p)) &&
                                (taken = casSlotToNull(a, k, t))) {
                                for (int j = i; j != s; ){
                                    // shift down 换成低速挡
                                    a[j & m] = getAndClearSlot(a, ++j & m);
                                }
                                top = s;
                            }
                            if (!owned){
                                // lock状态 
                                source = 0;
                            }
                        }
                        break;
                    }
                }
            }
            return taken;
        }

        // variants of poll

        /**
         * Tries once to poll next task in FIFO order, failing on
         * inconsistency or contention.
         * 尝试按FIFO顺序轮询下一个任务一次，由于不一致或争用而失败。
         * 盗取工作者（线程）调用，异步线程调用
         * topLevelExecd 调用
         */
        final ForkJoinTask<?> tryPoll() {
            int cap, b, k; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0) {
                //原子读取槽数据
                ForkJoinTask<?> t = getSlot(a, k = (cap - 1) & (b = base));
                if (base == b++ && t != null && casSlotToNull(a, k, t)) {
                    setBaseOpaque(b);
                    return t;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         * 按模式指定的顺序执行下一个任务（如果存在）。
         */
        final ForkJoinTask<?> nextLocalTask(int cfg) {
            ForkJoinTask<?> t = null;
            int s = top, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0) {
                for (int b, d;;) {
                    if ((d = s - (b = base)) <= 0){
                        break;
                    }
                    if (d == 1 || (cfg & FIFO) == 0) { //FIFO 模式
                        //工作者（线程）更新
                        if ((t = getAndClearSlot(a, --s & (cap - 1))) != null)
                            top = s;
                        break;
                    }
                    //Base的更新，可能由盗取工作者（读取），因此使用非透明方式更新
                    if ((t = getAndClearSlot(a, b++ & (cap - 1))) != null) {
                        setBaseOpaque(b);
                        break;
                    }
                }
            }
            return t;
        }

        /**
         * Takes next task, if one exists, using configured mode.
         * 使用配置模式执行下一个任务（如果存在）。
         */
        final ForkJoinTask<?> nextLocalTask() {
            return nextLocalTask(config); //config初始化为0
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         * 按模式指定的顺序返回下一个任务（如果存在）。
         *
         * peekNextLocalTask调用，无调用
         */
        final ForkJoinTask<?> peek() {
            VarHandle.acquireFence();
            int cap; ForkJoinTask<?>[] a;
            return ((a = array) != null && (cap = a.length) > 0) ?
                a[(cap - 1) & ((config & FIFO) != 0 ? base : top - 1)] : null;
        }

        // specialized execution methods

        /**
         * Runs the given (stolen) task if nonnull, as well as
         * remaining local tasks and/or others available from the
         * given queue.
         *
         * 如果非null，则运行给定（被盗）任务，以及剩余的本地任务和/或给定队列中可用的其他任务。
         *
         * scan调用，runWorker调用scan
         * 
         */
        final void topLevelExec(ForkJoinTask<?> task, WorkQueue q) {
            int cfg = config, nstolen = 1;
            while (task != null) {
                task.doExec();
                if ((task = nextLocalTask(cfg)) == null &&
                    q != null && (task = q.tryPoll()) != null)
                    ++nstolen;
            }
            nsteals += nstolen;
            // lock状态, 共享队列
            source = 0;
            if ((cfg & INNOCUOUS) != 0)
                ThreadLocalRandom.eraseThreadLocals(Thread.currentThread());
        }

        /**
         * Tries to pop and run tasks within the target's computation
         * until done, not found, or limit exceeded.
         *
         * 尝试在目标的计算中弹出并运行任务，直到任务完成、未找到或超出限制。
         *
         * Pool的helpComplete方法调用，Task的awaitDone方法调用
         * 
         * @param task root of CountedCompleter computation
         * @param owned true if owned by a ForkJoinWorkerThread
         * @param limit max runs, or zero for no limit
         * @return task status on exit
         */
        final int helpComplete(ForkJoinTask<?> task, boolean owned, int limit) {
            int status = 0, cap, k, p, s; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
            while (task != null && (status = task.status) >= 0 &&
                   (a = array) != null && (cap = a.length) > 0 &&
                   (t = a[k = (cap - 1) & (s = (p = top) - 1)])
                   instanceof CountedCompleter) {
                CountedCompleter<?> f = (CountedCompleter<?>)t;
                boolean taken = false;
                for (;;) {     // exec if root task is a completer of t
                    if (f == task) {
                        if (owned) { // 拥有者的任务
                            if ((taken = casSlotToNull(a, k, t))){
                                top = s;
                            }
                        }
                        else if (tryLock()) { // 共享任务，non-FJ
                            if (top == p && array == a &&
                                (taken = casSlotToNull(a, k, t))){
                                top = s;
                            }
                            // lock状态 
                            source = 0;
                        }
                        if (taken){ // 有任务则执行
                            t.doExec();
                        }
                        else if (!owned){  // 不是任务拥有者，共享任务
                            Thread.yield(); // tryLock failure
                        }
                        break;
                    }
                    else if ((f = f.completer) == null)
                        break;
                }
                if (taken && limit != 0 && --limit == 0)
                    break;
            }
            return status;
        }

        /**
         * Tries to poll and run AsynchronousCompletionTasks until
         * none found or blocker is released.
         *
         * 尝试轮询并运行AsynchronousCompletionTasks，直到找不到或释放阻止程序为止。
         *
         * helpAsyncBlocker CompletableFuture/SubmissionPublisher
         * @param blocker the blocker
         */
        final void helpAsyncBlocker(ManagedBlocker blocker) {
            int cap, b, d, k; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
            while (blocker != null && (d = top - (b = base)) > 0 &&
                   (a = array) != null && (cap = a.length) > 0 &&
                   (((t = getSlot(a, k = (cap - 1) & b)) == null && d > 1) ||
                    t instanceof
                    CompletableFuture.AsynchronousCompletionTask) &&
                   !blocker.isReleasable()) {
                if (t != null && base == b++ && casSlotToNull(a, k, t)) {
                    setBaseOpaque(b);
                    t.doExec();
                }
            }
        }

        // misc

        /** AccessControlContext for innocuous workers, created on 1st use. */
        @SuppressWarnings("removal")
        private static AccessControlContext INNOCUOUS_ACC;

        /**
         * Initializes (upon registration) InnocuousForkJoinWorkerThreads.
         */
        @SuppressWarnings("removal")
        final void initializeInnocuousWorker() {
            AccessControlContext acc; // racy construction OK
            if ((acc = INNOCUOUS_ACC) == null)
                INNOCUOUS_ACC = acc = new AccessControlContext(
                    new ProtectionDomain[] { new ProtectionDomain(null, null) });
            Thread t = Thread.currentThread();
            ThreadLocalRandom.setInheritedAccessControlContext(t, acc);
            ThreadLocalRandom.eraseThreadLocals(t);
        }

        /**
         * Returns true if owned by a worker thread and not known to be blocked.
         * 是否已经阻塞
         */
        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return ((wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        static {
            try {
                QA = MethodHandles.arrayElementVarHandle(ForkJoinTask[].class);
                MethodHandles.Lookup l = MethodHandles.lookup();
                SOURCE = l.findVarHandle(WorkQueue.class, "source", int.class);
                BASE = l.findVarHandle(WorkQueue.class, "base", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     * 创建一个新的ForkJoinWorkerThread。除非在ForkJoinPool构造函数中重写，否则将使用此工厂
     */
    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     *
     * 可能启动或的方法的调用方所需的权限终止线程。
     */
    static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     *
     * 公用（静态）池。除非是静态构造异常，否则公共使用非null，
     * 但内部使用null检查使用以超自然地避免潜
     * 在的初始化循环，并简化生成的代码。
     */
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     *
     * 公共池并行性。
     * 为了在禁用公共池线程时实现更简单的使用和管理，
     * 我们允许底层的common.paralllelism字段为零，
     * 但在这种情况下，仍然将并行度报告为1，以反映生成的调用程序运行机制。
     */
    static final int COMMON_PARALLELISM;

    /**
     * Limit on spare thread construction in tryCompensate.
     * tryCompensate中对备用线程结构的限制。
     */
    private static final int COMMON_MAX_SPARES;

    /**
     * Sequence number for creating worker names
     * 用于创建工作者名称的序列号
     */
    private static volatile int poolIds;

    // static configuration constants

    /**
     * Default idle timeout value (in milliseconds) for the thread
     * triggering quiescence to park waiting for new work
     *
     * 线程触发暂停以等待新工作的默认空闲超时值（以毫秒为单位）
     */
    private static final long DEFAULT_KEEPALIVE = 60_000L;

    /**
     * Undershoot tolerance for idle timeouts
     * 空闲超时的下冲容差
     */
    private static final long TIMEOUT_SLOP = 20L;

    /**
     * The default value for COMMON_MAX_SPARES.  Overridable using the
     * "java.util.concurrent.ForkJoinPool.common.maximumSpares" system
     * property.  The default value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical OS
     * thread limits, so allows JVMs to catch misuse/abuse before
     * running out of resources needed to do so.
     *
     * COMMON_MAX_SPARES的默认值。
     * 可使用“java.util.concurrent.FukJoinPool.common.maximumSpares”系统属性重写。
     * 默认值远远超过了正常要求，但也远远低于MAX_CAP和典型的操作系统线程限制，
     * 因此允许JVM在耗尽所需资源之前发现误用/滥用。
     */
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /*
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * 字段ctl的位和掩码，包含4个16位子字段：
     * RC: Number of released (unqueued) workers minus target parallelism
     *     已释放（未排队）的工作线程数减去目标并行度
     * TC: Number of total workers minus target parallelism
     *     工作者总数减去目标并行度
     * SS: version count and status of top waiting thread
     *     顶部等待线程的版本计数和状态
     * ID: poolIndex of top of Treiber stack of waiters
     *     poolDrivers候选工作者生堆栈顶部的索引
     *
     * When convenient, we can extract the lower 32 stack top bits
     * (including version bits) as sp=(int)ctl.  The offsets of counts
     * by the target parallelism and the positionings of fields makes
     * it possible to perform the most common checks via sign tests of
     * fields: When ac is negative, there are not enough unqueued
     * workers, when tc is negative, there are not enough total
     * workers.  When sp is non-zero, there are waiting workers.  To
     * deal with possibly negative fields, we use casts in and out of
     * "short" and/or signed shifts to maintain signedness.
     *
     *  在方便的时候，我们可以提取较低的32个栈顶比特（包括版本比特）作为sp=（int）ctl。
     *  通过目标并行度和字段位置的计数偏移，可以通过字段的符号测试来执行最常见的检查：
     *  当ac为负时，没有足够的未排队的工作人员，当tc为负时则没有足够的总工作者。
     *  当sp为非零时，有工作者在等待。为了处理可能的负字段，
     *  我们使用“short”和/或有符号移位的内外转换来保持有符号性。
     *  
     * Because it occupies uppermost bits, we can add one release
     * count using getAndAdd of RC_UNIT, rather than CAS, when
     * returning from a blocked join.  Other updates entail multiple
     * subfields and masking, requiring CAS.
     *
     * 因为它占据了最高位，所以当从阻塞联接返回时，
     * 我们可以使用RC_UNIT的getAndAdd而不是CAS来添加一个发布计数。
     * 其他更新需要多个子字段和屏蔽，需要CAS。
     * 
     * The limits packed in field "bounds" are also offset by the
     * parallelism level to make them comparable to the ctl rc and tc
     * fields.
     * 字段“bounds”中的限制也会被并行级别偏移，使其与ctl-rc和tc字段相当。
     */

    // Lower and upper word masks
    /**
     *FIFO:	65536	Bit->:10000000000000000
     *SRC:	131072	Bit->:100000000000000000
     *INNOCUOUS:	262144	Bit->:1000000000000000000
     *QUIET:	524288	Bit->:10000000000000000000
     *SHUTDOWN:	16777216	Bit->:1000000000000000000000000
     *TERMINATED:	33554432	Bit->:10000000000000000000000000
     *STOP:	-2147483648	Bit->:
     *1111111111111111111111111111111110000000000000000000000000000000
     *UNCOMPENSATE:	65536	Bit->:
     *                                               10000000000000000
     *
     *SMASK:	65535	Bit->:1111111111111111
     *MAX_CAP:	32767	Bit->:111111111111111
     *
     *UNSIGNALLED:	-2147483648	Bit->:
     *1111111111111111111111111111111110000000000000000000000000000000
     *
     *SS_SEQ:	65536	Bit->:
     *                                               10000000000000000
     *ctl:	-4222399528566784	ctl->:
     *1111111111110000111111111100000000000000000000000000000000000000
     *
     *TC_MASK:	281470681743360
     *                111111111111111100000000000000000000000000000000
     *RC_MASK:	-281474976710656
     *1111111111111111000000000000000000000000000000000000000000000000
     *
     *UC_MASK:	-4294967296
     *1111111111111111111111111111111100000000000000000000000000000000
     *SP_MASK:	4294967295
     *                                11111111111111111111111111111111
     *
     *RC_UNIT:	281474976710656
     *               1000000000000000000000000000000000000000000000000
     *TC_UNIT:	4294967296
     *                               100000000000000000000000000000000
     *ADD_WORKER:	140737488355328
     *                100000000000000000000000000000000000000000000000
     */
    // 上下掩码
    private static final long SP_MASK    = 0xffffffffL;
    private static final long UC_MASK    = ~SP_MASK;

    // Release counts 释放计数
    private static final int  RC_SHIFT   = 48;
    private static final long RC_UNIT    = 0x0001L << RC_SHIFT;
    private static final long RC_MASK    = 0xffffL << RC_SHIFT;

    // Total counts 总计数
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign
    
    /**
     * SP_MASK:
     *  deregisterWorker、signalWork 、awaitWork、tryCompensate
     *  ctl nc
     *
     * RC_SHIFT：
     *  awaitWork、canStop、tryCompensate、getSurplusQueuedTaskCount
     *  getActiveThreadCount
     *
     *TC_SHIFT：
     *  tryCompensate、tryTerminate
     *  getPoolSize
     *  ctl
     *
     *ADD_WORKER
     *  signalWork  
     */
    
    //16  15   14   13   12     11    10   9     8   7    6    5     4    3     2   1
    // UNSIGNALLED -2147483648
    //1 1111 1111 11111 11111 11111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
    //~UNSIGNALLED 2147483647
    //                                         111 1111 1111 1111 1111 1111 1111 1111
    //SP_MASK 4294967295 0xff ff ff ffL;
    //                                        1111 1111 1111 1111 1111 1111 1111 1111
    //TC_UNIT:	4294967296
    //                                      1 0000 0000 0000 0000 0000 0000 0000 0000
    //TC_MASK:	281470681743360
    //                    1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
    //ADD_WORKER:	140737488355328
    //                    1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
    
    //RC_UNIT:	281474976710656 48
    //                  1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
    //TC_UNIT:	4294967296 32
    //                                      1 0000 0000 0000 0000 0000 0000 0000 0000
    //ADD_WORKER:	140737488355328 47
    //                    1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
    
    // Instance fields

    // milliseconds before dropping if idle,如果空闲，则在丢弃前的毫秒
    final long keepAlive;      
    // collects worker nsteals 收集盗取工作者总数
    volatile long stealCount;           
    // advances across pollScan calls pollScan调用的进展
    int scanRover;                       
    // for worker thread names 用于工作线程名称
    volatile int threadIds;              
    // min, max threads packed as shorts 最小、最大线程限制
    final int bounds;                    
    volatile int mode;  // parallelism, runstate, queue mode 并行性、运行状态、队列模式
    WorkQueue[] queues;                  // main registry 主任务数组
    final ReentrantLock registrationLock;
    Condition termination;               // lazily constructed 懒散地建造
    final String workerNamePrefix;       // null for common pool
    final ForkJoinWorkerThreadFactory factory;
    final UncaughtExceptionHandler ueh;  // per-worker UEH
    final Predicate<? super ForkJoinPool> saturate;

    @jdk.internal.vm.annotation.Contended("fjpctl") // segregate
    volatile long ctl;                   // main pool control 主池控制

    // Support for atomic operations 支持原子操作
    private static final VarHandle CTL;
    private static final VarHandle MODE;
    private static final VarHandle THREADIDS;
    private static final VarHandle POOLIDS;
    private boolean compareAndSetCtl(long c, long v) {
        return CTL.compareAndSet(this, c, v);
    }
    private long compareAndExchangeCtl(long c, long v) {
        return (long)CTL.compareAndExchange(this, c, v);
    }
    private long getAndAddCtl(long v) {
        return (long)CTL.getAndAdd(this, v);
    }
    private int getAndBitwiseOrMode(int v) {
        return (int)MODE.getAndBitwiseOr(this, v);
    }
    private int getAndAddThreadIds(int x) {
        return (int)THREADIDS.getAndAdd(this, x);
    }
    private static int getAndAddPoolIds(int x) {
        return (int)POOLIDS.getAndAdd(x);
    }

    // Creating, registering and deregistering workers 创建、注册和注销工作者（线程）

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     *
     * 尝试构建并启动一个工作者（线程）。假设总计数已作为保留递增。
     * 在出现任何故障时调用disregisterWorker。
     *
     * 由signalWork和tryCompensate调用
     *
     * signalWork由deregisterWorker、scan和externalPush、push方法调用
     *
     * helpJoin（有Task的awaitDone）和compensatedBlock（由managedBlock）调用
     * 
     * ForkJoinWorkerThread 实例化方法
     *   ForkJoinWorkerThread(ThreadGroup group, ForkJoinPool pool,
     *                    boolean useSystemClassLoader, boolean isInnocuous) {
     *   super(group, null, pool.nextWorkerThreadName(), 0L);
     *   UncaughtExceptionHandler handler = (this.pool = pool).ueh;
     *   this.workQueue = new ForkJoinPool.WorkQueue(this, false);
     *   super.setDaemon(true);
     *   if (handler != null)
     *       super.setUncaughtExceptionHandler(handler);
     *   if (useSystemClassLoader)
     *      super.setContextClassLoader(ClassLoader.getSystemClassLoader());
     * }
     * @return true if successful
     */
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            //ForkJoinWorkerThread.实例化
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start();
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * Provides a name for ForkJoinWorkerThread constructor.
     * 提供ForkJoinWorkerThread构造函数的名称。
     */
    final String nextWorkerThreadName() {
        String prefix = workerNamePrefix;
        int tid = getAndAddThreadIds(1) + 1;
        if (prefix == null) // commonPool has no prefix
            prefix = "ForkJoinPool.commonPool-worker-";
        return prefix.concat(Integer.toString(tid));
    }

    /**
     * Finishes initializing and records owned queue.
     * 完成初始化并记录所属队列。
     *
     * wt调用，ForkJoinWorkerThread.run调用
     * 
     * @param w caller's WorkQueue
     */
    final void registerWorker(WorkQueue w) {
        ReentrantLock lock = registrationLock;
        //本地线程随机数
        ThreadLocalRandom.localInit();
        //获取线程随机数(线程探针哈希)
        int seed = ThreadLocalRandom.getProbe();
        if (w != null && lock != null) {
            
            // mode为parallelism, runstate, queue mode
            // mode 由ForkJoinPool 构造方法希尔 写入
            // 构造方法一：
            //  this.mode = p | (asyncMode ? FIFO : 0);
            // 构造方法二：
            //  int p = Math.min(Math.max(parallelism, 0), MAX_CAP), size;
            //  this.mode = p;
            
            //config
            // index, mode, ORed with SRC after init
            // SRC:1<<17
            // FIFO:1<<16
            // submissionQueue方法写入ID
            //  WorkQueue w = new WorkQueue(id | SRC);
            
            // mode默认为线程核数，
            // 在注册的任务w中config为0，modebits可能为0
            int modebits = (mode & FIFO) | w.config; 
            //任务队列数组
            w.array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            //stackPred为本地线程哈希值
            //stackPred;             // pool stack (ctl) predecessor link
            w.stackPred = seed;            // stash for runWorker runWorker的存储
            // INNOCUOUS    = 1 << 18;       // set for Innocuous workers
            if ((modebits & INNOCUOUS) != 0){
                w.initializeInnocuousWorker();
            }
            //id为线程哈希值 id为奇数    
            int id = (seed << 1) | 1;      // initial index guess 初始索引猜测
            lock.lock();
            try {
                WorkQueue[] qs; int n;    // find queue index 查找队列索引
                if ((qs = queues) != null && (n = qs.length) > 0) {
                    int k = n, m = n - 1;
                    //
                    for (; qs[id &= m] != null && k > 0; id -= 2, k -= 2);
                    if (k == 0){
                        id = n | 1;         // resize below 在下面调整大小
                    }
                    // 可发布状态，任务的id值为奇数
                    // config;                // index, mode, ORed with SRC after init
                    // volatile phase;        // versioned, negative if inactive
                    
                    // submissionQueue中phase为-1，config为id（线程哈希）
                    // 此外，内部的ID为奇数，外部的ID为偶数
                    w.phase = w.config = id | modebits; //now publishable

                    if (id < n)
                        qs[id] = w;
                    else {                //expand array 展开数组
                        int an = n << 1, am = an - 1;
                        WorkQueue[] as = new WorkQueue[an];
                        as[id & am] = w;
                        for (int j = 1; j < n; j += 2){
                            as[j] = qs[j];
                        }
                        for (int j = 0; j < n; j += 2) {
                            WorkQueue q;
                            // 共享队列可能会移动
                            if ((q = qs[j]) != null) {   /shared queues may move 
                                as[q.config & am] = q;
                            }
                        }
                        // 发布栅栏，queues为实例变量
                        VarHandle.releaseFence();       // fill before publish 
                        queues = as;
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Final callback from terminating worker, as well as upon failure
     * to construct or start a worker.  Removes record of worker from
     * array, and adjusts counts. If pool is shutting down, tries to
     * complete termination.
     *
     * 来自终止工作进程以及在构建或启动工作进程失败时的最终回调。
     * 从数组中删除工作者的记录，并调整计数。如果池正在关闭，则尝试完成终止。
     *
     * 由createWorker调用
     * 
     * @param wt the worker thread, or null if construction failed
     * @param ex the exception causing failure, or null if none
     */
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        ReentrantLock lock = registrationLock;
        WorkQueue w = null;
        int cfg = 0;
        if (wt != null && (w = wt.workQueue) != null && lock != null) {
            WorkQueue[] qs; int n, i;
            cfg = w.config;
            long ns = w.nsteals & 0xffffffffL;
            lock.lock();     // remove index from array 从数组中删除索引
            if ((qs = queues) != null && (n = qs.length) > 0 &&
                qs[i = cfg & (n - 1)] == w)
                qs[i] = null;
            stealCount += ns;    // accumulate steals（volatile）累积
            lock.unlock();
            long c = ctl; //线程总数
            // QUIET        = 1 << 19;    // quiescing phase or source
            // 静止阶段或源
            // 除非自发信号，否则递减计数
            // unless self-signalled, decrement counts
            if ((cfg & QUIET) == 0){ 
                // RC_SHIFT   = 48;
                // RC_UNIT    = 0x0001L << RC_SHIFT;
                // RC_MASK    = 0xffffL << RC_SHIFT;
                
                // TC_SHIFT   = 32;
                // TC_UNIT    = 0x0001L << TC_SHIFT;
                // TC_MASK    = 0xffffL << TC_SHIFT;
                
                // SP_MASK    = 0xffffffffL;
                
                //TC_MASK:	281470681743360
                //                111111111111111100000000000000000000000000000000
                //RC_MASK:	-281474976710656
                //1111111111111111000000000000000000000000000000000000000000000000
                //UC_MASK:	-4294967296
                //1111111111111111111111111111111100000000000000000000000000000000
                //SP_MASK:	4294967295
                //                                11111111111111111111111111111111
                //RC_UNIT:	281474976710656
                //               1000000000000000000000000000000000000000000000000
                //TC_UNIT:	4294967296
                //                               100000000000000000000000000000000
                // 减小ctl值
                do {} while (c != (c = compareAndExchangeCtl(
                                       c, ((RC_MASK & (c - RC_UNIT)) |
                                           (TC_MASK & (c - TC_UNIT)) |
                                           (SP_MASK & c)))));
            }
            else if ((int)c == 0){  // was dropped on timeout 超时时被丢弃
                //抑制信号（如果是最后）
                cfg = 0;                             // suppress signal if last 
            }
            for (ForkJoinTask<?> t; (t = w.pop()) != null; ){
                ForkJoinTask.cancelIgnoringExceptions(t); // cancel tasks 取消任务
            }
        } //
        //SRC          = 1 << 17;       // set for valid queue ids
        //SRC:	131072	Bit->:100000000000000000
        if (!tryTerminate(false, false) && w != null && (cfg & SRC) != 0)
            signalWork();  // possibly replace worker 可能更换工作者（线程）
        if (ex != null)
            ForkJoinTask.rethrow(ex);
    }

    /*
     * Tries to create or release a worker if too few are running.
     * 如果运行的工作线程太少，则尝试创建或释放工作线程。
     */
    final void signalWork() {
        for (long c = ctl; c < 0L;) {
            int sp, i; WorkQueue[] qs; WorkQueue v;
            // 没有闲着的工作者（线程）
            //~UNSIGNALLED
            //                                 1111111111111111111111111111111
            //UNSIGNALLED
            //1111111111111111111111111111111110000000000000000000000000000000
            if ((sp = (int)c & ~UNSIGNALLED) == 0) {// no idle workers
                // 足够的工作者（线程）总数
                if ((c & ADD_WORKER) == 0L){ // enough total workers 
                    break;
                }
                //int  RC_SHIFT   = 48;
                //long RC_UNIT    = 0x0001L << RC_SHIFT;
                //long RC_MASK    = 0xffffL << RC_SHIFT
                
                //
                /int  TC_SHIFT   = 32;
                //long TC_UNIT    = 0x0001L << TC_SHIFT;
                //long TC_MASK    = 0xffffL << TC_SHIFT;
                
                //RC_MASK
                //1111111111111111000000000000000000000000000000000000000000000000
                //TC_MASK
                //                111111111111111100000000000000000000000000000000
                //RC_UNIT:	281474976710656
                //               1000000000000000000000000000000000000000000000000
                //TC_UNIT:	4294967296
                //                               100000000000000000000000000000000
                if (c == (c = compareAndExchangeCtl(
                              c, ((RC_MASK & (c + RC_UNIT)) | //
                                  (TC_MASK & (c + TC_UNIT)))))) {
                    createWorker();
                    break;
                }
            }
            else if ((qs = queues) == null){
                break;               // unstarted/terminated 未启动/已终止
            }
            //SMASK:	65535	Bit->:1111111111111111
            else if (qs.length <= (i = sp & SMASK)){ //= 0xff ff ff ffL(1111)
                break;              // terminated 已终止
            }
            else if ((v = qs[i]) == null){
                break;             // terminating 正在终止
            }
            else {
                //SP_MASK f*8 4294967295
                //                                11111111111111111111111111111111
                //UC_MASK:~SP_MASK f*8 0*8
                //1111111111111111111111111111111100000000000000000000000000000000
                //SMASK:	65535	n1111111111111111
                //UC_MASK:	-4294967296
                //1111111111111111111111111111111100000000000000000000000000000000
                //SP_MASK:	4294967295
                //                                11111111111111111111111111111111
                //RC_UNIT:	281474976710656
                //1000000000000000000000000000000000000000000000000
                //TC_UNIT:	4294967296
                //100000000000000000000000000000000
                long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
                Thread vt = v.owner;
                if (c == (c = compareAndExchangeCtl(c, nc))) {
                    v.phase = sp;
                    LockSupport.unpark(vt);  // release idle worker 释放空闲工作者（线程）
                    break;
                }
            }
        }
    }

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * See above for explanation.
     *
     * 工作者（线程）的顶级运行循环，由ForkJoinWorkerThread.run调用。
     *
     * @param w caller's WorkQueue (may be null on failed initialization)
     */
    final void runWorker(WorkQueue w) {
        if (mode >= 0 && w != null) {  // skip on failed init 初始化失败时跳过
            //SRC:	131072	Bit->:100000000000000000
            //SRC          = 1 << 17;
            //INNOCUOUS    = 1 << 18;
            //QUIET        = 1 << 19;
            //STOP         = 1 << 31;       // must be negative
            //UNCOMPENSATE = 1 << 16;       // tryCompensate return
            
            w.config |= SRC;                    // mark as valid source 标记为有效源
            //使用registerWorker中的种子
            int r = w.stackPred, src = 0;       // use seed from registerWorker 
            do {
                r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
            } while ((src = scan(w, src, r)) >= 0 ||
                     (src = awaitWork(w)) == 0);
        }
    }

    /**
     * Scans for and if found executes top-level tasks: Tries to poll
     * each queue starting at a random index with random stride,
     * returning source id or retry indicator if contended or
     * inconsistent.
     *
     * 扫描并执行顶级任务：尝试以随机步长从随机索引开始轮询每个队列，如果有争用或不一致，
     * 则返回源id或重试指示符。
     * 
     * @param w caller's WorkQueue
     * @param prevSrc the previous queue stolen from in current phase, or 0
     *                    当前阶段中从中窃取的上一个队列，或0
     * @param r random seed 随机数
     * @return id of queue if taken, negative if none found, prevSrc for retry
     *         队列id（如果已获取），如果未找到则为负数，prevSrc表示重试
     */
    private int scan(WorkQueue w, int prevSrc, int r) {
        WorkQueue[] qs = queues;
        int n = (w == null || qs == null) ? 0 : qs.length;
        for (int step = (r >>> 16) | 1, i = n; i > 0; --i, r += step) {
            int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
            if ((q = qs[j = r & (n - 1)]) != null && // poll at qs[j].array[k]
                (a = q.array) != null && (cap = a.length) > 0) {
                int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                int nextIndex = (cap - 1) & nextBase, src = j | SRC; //base索引
                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                if (q.base != b)                // inconsistent
                    return prevSrc;
                else if (t != null && WorkQueue.casSlotToNull(a, k, t)) {
                    q.base = nextBase;
                    ForkJoinTask<?> next = a[nextIndex];
                    if ((w.source = src) != prevSrc && next != null){
                        //传播/产生任务 调用createWorker生产线程
                        signalWork();           // propagate 
                    }
                    w.topLevelExec(t, q);
                    return src;
                }
                else if (a[nextIndex] != null)  // revisit 重访问
                    return prevSrc;
            }
        }
        // 可能调整了大小
        return (queues != qs) ? prevSrc: -1;// possibly resized
    }

    /**
     * Advances worker phase, pushes onto ctl stack, and awaits signal
     * or reports termination.
     *
     * 进入工作阶段，推送到ctl堆栈，等待信号或报告终止。
     *
     * @return negative if terminated, else 0
     */
    private int awaitWork(WorkQueue w) {
        if (w == null){
            return -1;                       // already terminated 已终止
        }

        //SS_SEQ:	65536	1<<16
        //                        10000000000000000

        //UNSIGNALLED:	-2147483648	
        //1111111111111111111111111111111110000000000000000000000000000000
        
        //~UNSIGNALLED:	2147483647	
        //                                 1111111111111111111111111111111
        int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
        w.phase = phase | UNSIGNALLED;       // advance phase 推动阶段
        long prevCtl = ctl, c;               // enqueue 入队
        do {
            w.stackPred = (int)prevCtl;
            //RC_UNIT:
            //UC_MASK:
            //1111111111111111111111111111111100000000000000000000000000000000
            //SP_MASK
            //                                11111111111111111111111111111111
            c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK); //c值
            //c值更新
        } while (prevCtl != (prevCtl = compareAndExchangeCtl(prevCtl, c))); 

        Thread.interrupted();                // clear status 清除状态
        //准备阻止（退出也可以）
        LockSupport.setCurrentBlocker(this); // prepare to block (exit also OK)
        //非零（如果可能是静态的）
        long deadline = 0L;
        // nonzero if possibly quiescent
        // RC_SHIFT   = 48;
        int ac = (int)(c >> RC_SHIFT), md;
        if ((md = mode) < 0)  {  // pool is terminating 池正在终止
            return -1;
        }
        else if ((md & SMASK) + ac <= 0) {
            boolean checkTermination = (md & SHUTDOWN) != 0;
            if ((deadline = System.currentTimeMillis() + keepAlive) == 0L){
                deadline = 1L;               // avoid zero 避免零
            }
            //检查竞争提交    
            WorkQueue[] qs = queues;         // check for racing submission 
            int n = (qs == null) ? 0 : qs.length;
            for (int i = 0; i < n; i += 2) {
                WorkQueue q; ForkJoinTask<?>[] a; int cap, b;
                if (ctl != c) {  // already signalled 已发出信号
                    checkTermination = false;
                    break;
                }
                else if ((q = qs[i]) != null &&
                         (a = q.array) != null && (cap = a.length) > 0 &&
                         ((b = q.base) != q.top || a[(cap - 1) & b] != null ||
                          q.source != 0)) {
                    if (compareAndSetCtl(c, prevCtl))
                        w.phase = phase;     // self-signal 自信号
                    checkTermination = false;
                    break;
                }
            }
            if (checkTermination && tryTerminate(false, false))
                return -1;    // trigger quiescent termination 触发静态终止
        }
        // await activation or termination 等待激活或终止
        for (boolean alt = false;;) { 
            if (w.phase >= 0){
                break;
            }
            else if (mode < 0){
                return -1;
            }
            else if ((c = ctl) == prevCtl){
                Thread.onSpinWait(); // signal in progress 进行中的信号
            }
            //在阻塞与调用之间进行检查
            else if (!(alt = !alt)) {         // check between park calls 
                Thread.interrupted();
            }
            else if (deadline == 0L){
                LockSupport.park(); //阻塞
            }
            else if (deadline - System.currentTimeMillis() > TIMEOUT_SLOP){
                LockSupport.parkUntil(deadline);
            }
            //SMASK:	65535
            //                                               1111111111111111
            //TC_UNIT:	4294967296
            //                               100000000000000000000000000000000
            
            //SP_MASK    = 0xffffffffL;
            //UC_MASK    = ~SP_MASK;
            
            //UC_MASK:	-4294967296
            //1111111111111111111111111111111100000000000000000000000000000000
            //SP_MASK:	4294967295
            //                                11111111111111111111111111111111
            
            //                                                        1111111111111111
            else if (((int)c & SMASK) == (w.config & SMASK) &&
                     //1111111111111111111111111111111100000000000000000000000000000000
                     compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) |
                     //                                11111111111111111111111111111111
                                          (prevCtl & SP_MASK)))) {
                //注销工作者（线程）信号
                //QUIET        = 1 << 19
                w.config |= QUIET;           // sentinel for deregisterWorker 
                return -1;                   // drop on timeout
            }
            else if ((deadline += keepAlive) == 0L)
                //不在头部；重启定时器
                deadline = 1L;               // not at head; restart timer 
        }
        return 0;
    }

    // Utilities used by ForkJoinTask //////////////////////////////////////

    /**
     * Returns true if can start terminating if e nabled, or already terminated
     * 如果可以在启用或已终止时开始终止，则返回true
     */
    final boolean canStop() {
        outer: for (long oldSum = 0L;;) { // repeat until stable 重复直到稳定
            int md; WorkQueue[] qs;  long c;
            // STOP         = 1 << 31;
            if ((qs = queues) == null || ((md = mode) & STOP) != 0){
                return true;
            }
            // SMASK        = 0xffff;
            // RC_SHIFT   = 48;
            // 任务数大于0
            //SMASK:	65535
            //1111111111111111
            //RC_SHIFT   = 48;
            if ((md & SMASK) + (int)((c = ctl) >> RC_SHIFT) > 0){
                break;
            }
            long checkSum = c;
            for (int i = 1; i < qs.length; i += 2) { // scan submitters 扫描提交者
                WorkQueue q; ForkJoinTask<?>[] a; int s = 0, cap;
                //
                if ((q = qs[i]) != null && (a = q.array) != null &&
                    (cap = a.length) > 0 &&
                    ((s = q.top) != q.base || a[(cap - 1) & s] != null ||
                     q.source != 0))
                    break outer;
                checkSum += (((long)i) << 32) ^ s;
            }
            if (oldSum == (oldSum = checkSum) && queues == qs){
                return true;
            }
        }
        // 重查模式，返回false
        return (mode & STOP) != 0; // recheck mode on false return 
    }

    /**
     * Tries to decrement counts (sometimes implicitly) and possibly
     * arrange for a compensating worker in preparation for
     * blocking. May fail due to interference, in which case -1 is
     * returned so caller may retry. A zero return value indicates
     * that the caller doesn't need to re-adjust counts when later
     * unblocked.
     *
     * 尝试递减计数（有时是隐式的），并可能安排一名补偿工作者（线程）为阻塞做准备。可能由于干扰而失败，
     * 在这种情况下返回-1，因此调用者可以重试。零返回值表示调用方在以后取消阻止时不需要重新调整计数。
     *
     * @param c incoming ctl value 传入ctl值
     * @return UNCOMPENSATE: block then adjust, 0: block, -1 : retry
     */
    private int tryCompensate(long c) {
        Predicate<? super ForkJoinPool> sat;
        int md = mode, b = bounds;
        // counts are signed; centered at parallelism level == 0
        //SMASK:	65535	1111111111111111
        //UNSIGNALLED:	-2147483648	
        //1111111111111111111111111111111110000000000000000000000000000000
        //~UNSIGNALLED:	2147483647	
        //                                 1111111111111111111111111111111
        int minActive = (short)(b & SMASK),      // SMASK  = 0xffff;
            maxTotal  = b >>> SWIDTH,            // SWIDTH = 16; 
            active    = (int)(c >> RC_SHIFT),    // RC_SHIFT   = 48;
            total     = (short)(c >>> TC_SHIFT), // TC_SHIFT   = 32;
            sp        = (int)c & ~UNSIGNALLED;   // UNSIGNALLED  = 1 << 31; 
        if ((md & SMASK) == 0)
            return 0;                  // cannot compensate if parallelism zero
        else if (total >= 0) {
            if (sp != 0) {                        // activate idle worker
                WorkQueue[] qs; int n; WorkQueue v;
                if ((qs = queues) != null && (n = qs.length) > 0 &&
                    (v = qs[sp & (n - 1)]) != null) {
                    Thread vt = v.owner;
                    //SP_MASK    = 0xffffffffL;
                    //UC_MASK    = ~SP_MASK;
                    //SP_MASK:	4294967295
                    //                               11111111111111111111111111111111
                    //UC_MASK:	-4294967296
                    //1111111111111111111111111111111100000000000000000000000000000000
                    long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
                    if (compareAndSetCtl(c, nc)) {
                        v.phase = sp;
                        LockSupport.unpark(vt);
                        return UNCOMPENSATE;
                    }
                }
                return -1;                        // retry
            }
            else if (active > minActive) { // reduce parallelism 降低并行性
                //RC_SHIFT   = 48;
                //RC_UNIT    = 0x0001L << RC_SHIFT;
                //RC_MASK    = 0xffffL << RC_SHIFT
                //RC_UNIT:	281474976710656
                //               1000000000000000000000000000000000000000000000000
                //RC_MASK:	-281474976710656
                //1111111111111111000000000000000000000000000000000000000000000000
                //~RC_MASK:	281474976710655
                //                111111111111111111111111111111111111111111111111
                long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
                return compareAndSetCtl(c, nc) ? UNCOMPENSATE : -1;
            }
        }
        if (total < maxTotal) {                   // expand pool 扩展池
            long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
            return (!compareAndSetCtl(c, nc) ? -1 :
                    !createWorker() ? 0 : UNCOMPENSATE);
        }
        else if (!compareAndSetCtl(c, c))         // validate 验证
            return -1;
        else if ((sat = saturate) != null && sat.test(this))
            return 0;
        else
            throw new RejectedExecutionException(
                "Thread limit exceeded replacing blocked worker");
    }

    /**
     * Readjusts RC count; called from ForkJoinTask after blocking.
     * 重新调整RC计数；阻止后从ForkJoinTask调用。
     */
    final void uncompensate() {
        getAndAddCtl(RC_UNIT);
    }

    /**
     * Helps if possible until the given task is done.  Scans other
     * queues for a task produced by one of w's stealers; returning
     * compensated blocking sentinel if none are found.
     *
     * 在完成给定任务之前尽可能提供帮助。扫描其他队列以查找w的某个窃取者生成的任务；
     * 如果没有找到，则返回补偿的阻塞哨兵。
     * 
     * @param task the task
     * @param w caller's WorkQueue
     * @param canHelp if false, compensate only canHelp如果为false，则为补偿
     * @return task status on exit, or UNCOMPENSATE for compensated blocking
     */
    final int helpJoin(ForkJoinTask<?> task, WorkQueue w, boolean canHelp) {
        int s = 0;
        if (task != null && w != null) {
            int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
            boolean scan = true;
            long c = 0L;           // track ctl stability 追踪ctl稳定性
            outer: for (;;) {
                if ((s = task.status) < 0)
                    break;
                else if (scan = !scan) {// previous scan was empty 上一次扫描为空
                    if (mode < 0)
                        ForkJoinTask.cancelIgnoringExceptions(task);
                    else if (c == (c = ctl) && (s = tryCompensate(c)) >= 0)
                        break;      // block
                }
                else if (canHelp) { // scan for subtasks 扫描子任务
                    WorkQueue[] qs = queues;
                    int n = (qs == null) ? 0 : qs.length, m = n - 1;
                    for (int i = n; i > 0; i -= 2, r += 2) {
                        int j; WorkQueue q, x, y; ForkJoinTask<?>[] a;
                        if ((q = qs[j = r & m]) != null) {
                            int sq = q.source & SMASK, cap, b;
                            if ((a = q.array) != null && (cap = a.length) > 0) {
                                int k = (cap - 1) & (b = q.base);
                                //SRC          = 1 << 17;
                                int nextBase = b + 1, src = j | SRC, sx;
                                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                                boolean eligible = sq == wid ||
                                    ((x = qs[sq & m]) != null &&   // indirect 间接的
                                     //SMASK        = 0xffff;
                                     //SMASK:	65535	1111111111111111
                                     ((sx = (x.source & SMASK)) == wid ||
                                      ((y = qs[sx & m]) != null && // 2-indirect
                                       (y.source & SMASK) == wid)));
                                if ((s = task.status) < 0){
                                    break outer;
                                }
                                else if ((q.source & SMASK) != sq ||
                                         q.base != b){
                                    scan = true;  // inconsistent 不一致的
                                }
                                else if (t == null){
                                    scan |= (a[nextBase & (cap - 1)] != null ||
                                             q.top != b); // lagging 滞后
                                }
                                else if (eligible) {
                                    if (WorkQueue.casSlotToNull(a, k, t)) {
                                        q.base = nextBase;
                                        w.source = src;
                                        t.doExec();
                                        w.source = wsrc;
                                    }
                                    scan = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return s;
    }

    /**
     * Extra helpJoin steps for CountedCompleters.  Scans for and runs
     * subtasks of the given root task, returning if none are found.
     *
     * 额外帮助加入CountedCompleters的步骤。扫描并运行给定根任务的子任务，如果未找到则返回。
     * 
     * @param task root of CountedCompleter computation CountedCompleter计算的任务根
     * @param w caller's WorkQueue
     * @param owned true if owned by a ForkJoinWorkerThread
     *  如果由ForkJoinWorkerThread所有，则为owned true
     * @return task status on exit
     */
    final int helpComplete(ForkJoinTask<?> task, WorkQueue w, boolean owned) {
        int s = 0;
        if (task != null && w != null) {
            int r = w.config;
            boolean scan = true, locals = true;
            long c = 0L;
            outer: for (;;) {
                if (locals) {  // try locals before scanning 扫描前尝试本地
                    if ((s = w.helpComplete(task, owned, 0)) < 0)
                        break;
                    locals = false;
                }
                else if ((s = task.status) < 0)
                    break;
                else if (scan = !scan) {
                    if (c == (c = ctl))
                        break;
                }
                else {        // scan for subtasks 扫描子任务
                    WorkQueue[] qs = queues;
                    int n = (qs == null) ? 0 : qs.length;
                    for (int i = n; i > 0; --i, ++r) {
                        int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
                        boolean eligible = false;
                        if ((q = qs[j = r & (n - 1)]) != null &&
                            (a = q.array) != null && (cap = a.length) > 0) {
                            int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                            ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                            if (t instanceof CountedCompleter) {
                                CountedCompleter<?> f = (CountedCompleter<?>)t;
                                do {} while (!(eligible = (f == task)) &&
                                             (f = f.completer) != null);
                            }
                            if ((s = task.status) < 0)
                                break outer;
                            else if (q.base != b)
                                scan = true;       // inconsistent 不一致的
                            else if (t == null)
                                scan |= (a[nextBase & (cap - 1)] != null ||
                                         q.top != b);
                            else if (eligible) {
                                if (WorkQueue.casSlotToNull(a, k, t)) {
                                    q.setBaseOpaque(nextBase);
                                    t.doExec();
                                    locals = true;
                                }
                                scan = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return s;
    }

    /**
     * Scans for and returns a polled task, if available.  Used only
     * for untracked polls. Begins scan at an index (scanRover)
     * advanced on each call, to avoid systematic unfairness.
     *
     * 扫描并返回轮询任务（如果可用）。仅用于未跟踪的民意调查。
     * 在每次调用时都以高级索引（scanRover）开始扫描，以避免系统性的不公平。
     * 
     * @param submissionsOnly if true, only scan submission queues
     */
    private ForkJoinTask<?> pollScan(boolean submissionsOnly) {
        VarHandle.acquireFence();
        //增量；比赛还好
        int r = scanRover += 0x61c88647; // Weyl increment; raciness OK Weyl
        if (submissionsOnly)             // even indices only 仅偶数索引
            r &= ~1;
        int step = (submissionsOnly) ? 2 : 1;
        WorkQueue[] qs; int n;
        while ((qs = queues) != null && (n = qs.length) > 0) {
            boolean scan = false;
            for (int i = 0; i < n; i += step) {
                int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
                if ((q = qs[j = (n - 1) & (r + i)]) != null &&
                    (a = q.array) != null && (cap = a.length) > 0) {
                    int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                    ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                    if (q.base != b)
                        scan = true;
                    else if (t == null)
                        scan |= (q.top != b || a[nextBase & (cap - 1)] != null);
                    else if (!WorkQueue.casSlotToNull(a, k, t))
                        scan = true;
                    else {
                        q.setBaseOpaque(nextBase);
                        return t;
                    }
                }
            }
            if (!scan && queues == qs)
                break;
        }
        return null;
    }

    /**
     * Runs tasks until {@code isQuiescent()}. Rather than blocking
     * when tasks cannot be found, rescans until all others cannot
     * find tasks either.
     *
     * 运行任务直到isQuiescent()。当找不到任务时不进行阻止，
     * 而是重新扫描，直到所有其他任务都找不到为止。
     * 
     * @param nanos max wait time (Long.MAX_VALUE if effectively untimed)
     * @param interruptible true if return on interrupt
     * @return positive if quiescent, negative if interrupted, else 0
     */
    final int helpQuiescePool(WorkQueue w, long nanos, boolean interruptible) {
        if (w == null)
            return 0;
        long startTime = System.nanoTime(), parkTime = 0L;
        int prevSrc = w.source, wsrc = prevSrc, cfg = w.config, r = cfg + 1;
        for (boolean active = true, locals = true;;) {
            boolean busy = false, scan = false;
            if (locals) {  // run local tasks before (re)polling
                locals = false;
                for (ForkJoinTask<?> u; (u = w.nextLocalTask(cfg)) != null;)
                    u.doExec();
            }
            WorkQueue[] qs = queues;
            int n = (qs == null) ? 0 : qs.length;
            for (int i = n; i > 0; --i, ++r) {
                int j, b, cap; WorkQueue q; ForkJoinTask<?>[] a;
                if ((q = qs[j = (n - 1) & r]) != null && q != w &&
                    (a = q.array) != null && (cap = a.length) > 0) {
                    int k = (cap - 1) & (b = q.base);
                    //SRC:	131072	Bit->:100000000000000000
                    int nextBase = b + 1, src = j | SRC;
                    ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                    if (q.base != b)
                        busy = scan = true;
                    else if (t != null) {
                        busy = scan = true;
                        if (!active) {    // increment before taking 取出前增量
                            active = true;
                            getAndAddCtl(RC_UNIT);
                        }
                        if (WorkQueue.casSlotToNull(a, k, t)) {
                            q.base = nextBase;
                            w.source = src;
                            t.doExec();
                            w.source = wsrc = prevSrc;
                            locals = true;
                        }
                        break;
                    }
                    else if (!busy) {
                        if (q.top != b || a[nextBase & (cap - 1)] != null)
                            busy = scan = true;
                        else if (q.source != QUIET && q.phase >= 0)
                            busy = true;
                    }
                }
            }
            VarHandle.acquireFence();
            if (!scan && queues == qs) {
                boolean interrupted;
                if (!busy) {
                    w.source = prevSrc;
                    if (!active)
                        getAndAddCtl(RC_UNIT);
                    return 1;
                }
                if (wsrc != QUIET)
                    w.source = wsrc = QUIET;
                if (active) {                 // decrement
                    active = false;
                    parkTime = 0L;
                    getAndAddCtl(RC_MASK & -RC_UNIT);
                }
                else if (parkTime == 0L) {
                    parkTime = 1L << 10; // initially about 1 usec
                    Thread.yield();
                }
                else if ((interrupted = interruptible && Thread.interrupted()) ||
                         System.nanoTime() - startTime > nanos) {
                    getAndAddCtl(RC_UNIT);
                    return interrupted ? -1 : 0;
                }
                else {
                    LockSupport.parkNanos(this, parkTime);
                    if (parkTime < nanos >>> 8 && parkTime < 1L << 20)
                        parkTime <<= 1;  // max sleep approx 1 sec or 1% nanos
                }
            }
        }
    }

    /**
     * Helps quiesce from external caller until done, interrupted, or timeout
     * 帮助从外部调用方暂停直到完成、中断或超时
     *
     * 无调用
     * @param nanos max wait time (Long.MAX_VALUE if effectively untimed)
     * @param interruptible true if return on interrupt
     * @return positive if quiescent, negative if interrupted, else 0
     */
    final int externalHelpQuiescePool(long nanos, boolean interruptible) {
        for (long startTime = System.nanoTime(), parkTime = 0L;;) {
            ForkJoinTask<?> t;
            if ((t = pollScan(false)) != null) {
                t.doExec();
                parkTime = 0L;
            }
            else if (canStop())
                return 1;
            else if (parkTime == 0L) {
                parkTime = 1L << 10;
                Thread.yield();
            }
            else if ((System.nanoTime() - startTime) > nanos)
                return 0;
            else if (interruptible && Thread.interrupted())
                return -1;
            else {
                LockSupport.parkNanos(this, parkTime);
                if (parkTime < nanos >>> 8 && parkTime < 1L << 20)
                    parkTime <<= 1;
            }
        }
    }

    /**
     * Gets and removes a local or stolen task for the given worker.
     * 获取和删除给定工作者（线程）的本地任务或被盗任务。
     *
     * ForkJoinTask的pollTask()调用，但是此方法无调用
     * 
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        ForkJoinTask<?> t;
        if (w == null || (t = w.nextLocalTask(w.config)) == null)
            t = pollScan(false);
        return t;
    }

    // External operations

    /**
     * Finds and locks a WorkQueue for an external submitter, or
     * returns null if shutdown or terminating.
     * 
     * 查找并锁定外部提交程序的工作队列，或者在关闭或终止时返回null。
     * 此方法没有启动任务线程，具体的启动由signalWork视情况触发
     * 由externalPush调用
     */
    final WorkQueue submissionQueue() {
        int r;
        // 获得探针哈希值，使用探针哈希值（哈希线程）将线程核任务池中的不同
        // 元素对应起来
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            // 初始化调用方的探测 
            ThreadLocalRandom.localInit(); // initialize caller's probe
            // 获取本地线程ID
            r = ThreadLocalRandom.getProbe();
        }
        // 仅偶数索引
        for (int id = r << 1;;) { // even indices only
             int md = mode, n, i; WorkQueue q; ReentrantLock lock;
            // volatile int mode; // parallelism, runstate, queue mode
            // 构造方法：
            // this.mode = p | (asyncMode ? FIFO : 0);
            // 构造方法:
            // MAX_CAP      = 0x7fff;        // max #workers - 1
            // int p = Math.min(Math.max(parallelism, 0), MAX_CAP), size;
            // this.mode = p;
            
            // MAX_CAP      = 0x7fff;
            // SHUTDOWN     = 1 << 24;
            WorkQueue[] qs = queues; // 池Pool的队列queues(在池初始化设置大小)
            // SHUTDOWN     = 1 << 24;
            // 
            if ((md & SHUTDOWN) != 0 || qs == null || (n = qs.length) <= 0){
                return null; 
            }
            else if ((q = qs[i = (n - 1) & id]) == null) {// 探针哈希ID的获取任务
                if ((lock = registrationLock) != null) {
                    //SRC:
                    //SRC          = 1 << 17;       // set for valid queue ids
                    //
                    //  Constructor used for external queues.
         
                    // WorkQueue(int config) {
                    //     array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
                    //     this.config = config;
                    //     owner = null;
                    //     phase = -1;
                    // }
                    WorkQueue w = new WorkQueue(id | SRC);
                    lock.lock();                    // install under lock
                    if (qs[i] == null){ //在i处赋值前，再次检查i出是否为有任务
                        // 其他人输掉了竞争；丢弃
                        qs[i] = w;  // 把生成的任务和queues的具体位置绑定
                                    // 没有写入，有则丢掉 else lost race; discard 
                    }
                    lock.unlock();
                }
            }
            //q不为空，当没哟获取锁是则重新获取任务ID
            else if (!q.tryLock()){ // move and restart 移动并重新启动
                id = (r = ThreadLocalRandom.advanceProbe(r)) << 1;
            }
            else{ //此时获得锁，即q.tryLock返回true, source由0更新为1
                return q;
            }
        } //for over
    }

    /**
     * Adds the given task to an external submission queue, or throws
     * exception if shutdown or terminating.
     * 将给定任务添加到外部提交队列，或者在关闭或终止时引发异常。
     *
     * pool的externalSubmit方法 和 Task的fork方法
     * externalSubmit有下面方法调用：
     * invoke、execute、submit
     * @param task the task. Caller must ensure non-null.
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue q;
        if ((q = submissionQueue()) == null){
            // shutdown or disabled 关闭或禁用
            throw new RejectedExecutionException(); 
        }
        else if (q.lockedPush(task)){//提交任务为true，则发布任务信号
            signalWork(); //
        }
    }

    /**
     * Pushes a possibly-external submission.
     * 推动可能的外部提交。
     */
    private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
        Thread t; ForkJoinWorkerThread wt; WorkQueue q;
        if (task == null)
            throw new NullPointerException();
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (q = (wt = (ForkJoinWorkerThread)t).workQueue) != null &&
            wt.pool == this)
            q.push(task, this); // 当前线程绑定任务，内部任务
        else
            externalPush(task); // 外部任务推到Push到池中。
        return task;
    }

    /**
     * Returns common pool queue for an external thread that has
     * possibly ever submitted a common pool task (nonzero probe), or
     * null if none.
     *
     * 返回可能提交过公共池任务（非零探测）的外部线程的公共池队列，如果没有，则返回null。
     * 
     */
    static WorkQueue commonQueue() {
        ForkJoinPool p; WorkQueue[] qs;
        int r = ThreadLocalRandom.getProbe(), n;
        return ((p = common) != null && (qs = p.queues) != null &&
                (n = qs.length) > 0 && r != 0) ?
            qs[(n - 1) & (r << 1)] : null;
    }

    /**
     * Returns queue for an external thread, if one exists
     * 返回外部线程的队列（如果存在）
     *
     * helpAsyncBlocker调用 Task的awaitDone方法
     */
    final WorkQueue externalQueue() {
        WorkQueue[] qs;
        int r = ThreadLocalRandom.getProbe(), n;
        return ((qs = queues) != null && (n = qs.length) > 0 && r != 0) ?
            qs[(n - 1) & (r << 1)] : null;
    }

    /**
     * If the given executor is a ForkJoinPool, poll and execute
     * AsynchronousCompletionTasks from worker's queue until none are
     * available or blocker is released.
     *
     * 如果给定的执行器是ForkJoinPool，则从工作队列轮询并执行AsynchronousCompletionTasks，
     * 直到没有可用的任务或释放了阻塞程序。
     * 
     */
    static void helpAsyncBlocker(Executor e, ManagedBlocker blocker) {
        WorkQueue w = null; Thread t; ForkJoinWorkerThread wt;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) {
            if ((wt = (ForkJoinWorkerThread)t).pool == e)
                w = wt.workQueue;
        }
        else if (e instanceof ForkJoinPool)
            w = ((ForkJoinPool)e).externalQueue();
        if (w != null)
            w.helpAsyncBlocker(blocker);
    }

    /**
     * Returns a cheap heuristic guide for task partitioning when
     * programmers, frameworks, tools, or languages have little or no
     * idea about task granularity.  In essence, by offering this
     * method, we ask users only about tradeoffs in overhead vs
     * expected throughput and its variance, rather than how finely to
     * partition tasks.
     *
     * In a steady state strict (tree-structured) computation, each
     * thread makes available for stealing enough tasks for other
     * threads to remain active. Inductively, if all threads play by
     * the same rules, each thread should make available only a
     * constant number of tasks.
     *
     * The minimum useful constant is just 1. But using a value of 1
     * would require immediate replenishment upon each steal to
     * maintain enough tasks, which is infeasible.  Further,
     * partitionings/granularities of offered tasks should minimize
     * steal rates, which in general means that threads nearer the top
     * of computation tree should generate more than those nearer the
     * bottom. In perfect steady state, each thread is at
     * approximately the same level of computation tree. However,
     * producing extra tasks amortizes the uncertainty of progress and
     * diffusion assumptions.
     *
     * So, users will want to use values larger (but not much larger)
     * than 1 to both smooth over transient shortages and hedge
     * against uneven progress; as traded off against the cost of
     * extra task overhead. We leave the user to pick a threshold
     * value to compare with the results of this call to guide
     * decisions, but recommend values such as 3.
     *
     * When all threads are active, it is on average OK to estimate
     * surplus strictly locally. In steady-state, if one thread is
     * maintaining say 2 surplus tasks, then so are others. So we can
     * just use estimated queue length.  However, this strategy alone
     * leads to serious mis-estimates in some non-steady-state
     * conditions (ramp-up, ramp-down, other stalls). We can detect
     * many of these by further considering the number of "idle"
     * threads, that are known to have zero queued tasks, so
     * compensate by a factor of (#idle/#active) threads.
     */
    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (pool = (wt = (ForkJoinWorkerThread)t).pool) != null &&
            (q = wt.workQueue) != null) {
            //SMASK        = 0xffff;        // short bits == max index
            //int  RC_SHIFT   = 48;
            int p = pool.mode & SMASK;
            int a = p + (int)(pool.ctl >> RC_SHIFT);
            int n = q.top - q.base;
            return n - (a > (p >>>= 1) ? 0 :
                        a > (p >>>= 1) ? 1 :
                        a > (p >>>= 1) ? 2 :
                        a > (p >>>= 1) ? 4 :
                        8);
        }
        return 0;
    }

    // Termination

    /**
     * Possibly initiates and/or completes termination.
     * 可能启动和/或完成终止。
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers
     *         现在，如果为true，则无条件终止，
     *         否则仅在没有工作者（线程）且没有活跃线程的情况下
     * 
     * @param enable if true, terminate when next possible
     *
     *            如果为true，则启用，下一次可能时终止
     * @return true if terminating or terminated
     */
    private boolean tryTerminate(boolean now, boolean enable) {
        int md; // try to set SHUTDOWN, then STOP, then help terminate
        // SHUTDOWN     = 1 << 24;
        if (((md = mode) & SHUTDOWN) == 0) {//
            if (!enable){
                // 不能终止
                return false;
            }
            md = getAndBitwiseOrMode(SHUTDOWN);
        }
        //  STOP         = 1 << 31;       // must be negative
        // 已经终止
        if ((md & STOP) == 0) {
            if (!now && !canStop()){
                return false;
            }
            //设置终止位
            //按位原子更新访问  将获取变量的值并对其执行按位 OR 运算
            md = getAndBitwiseOrMode(STOP);
        }
        for (boolean rescan = true;;) { // repeat until no changes
            boolean changed = false;
            for (ForkJoinTask<?> t; (t = pollScan(false)) != null; ) {
                changed = true;
                ForkJoinTask.cancelIgnoringExceptions(t); // help cancel
            }
            WorkQueue[] qs; int n; WorkQueue q; Thread thread;
            if ((qs = queues) != null && (n = qs.length) > 0) {
                for (int j = 1; j < n; j += 2) { // unblock other workers
                    if ((q = qs[j]) != null && (thread = q.owner) != null &&
                        !thread.isInterrupted()) {
                        changed = true;
                        try {
                            thread.interrupt();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
            ReentrantLock lock; Condition cond; // signal when no workers
            // TERMINATED   = 1 << 25;
            // SMASK        = 0xffff;        // short bits == max index
            // TC_SHIFT   = 32;
            // mode;                   // parallelism, runstate, queue mode
            if (((md = mode) & TERMINATED) == 0 &&
                (md & SMASK) + (short)(ctl >>> TC_SHIFT) <= 0 && //无任务
                (getAndBitwiseOrMode(TERMINATED) & TERMINATED) == 0 &&
                (lock = registrationLock) != null) {
                lock.lock();
                //终止通知
                if ((cond = termination) != null){
                    cond.signalAll();
                }
                lock.unlock();
            }
            if (changed)
                rescan = true;
            else if (rescan)
                rescan = false;
            else
                break;
        }
        return true;
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using defaults for all
     * other parameters (see {@link #ForkJoinPool(int,
     * ForkJoinWorkerThreadFactory, UncaughtExceptionHandler, boolean,
     * int, int, int, Predicate, long, TimeUnit)}).
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
             defaultForkJoinWorkerThreadFactory, null, false,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, using defaults for all other parameters (see {@link
     * #ForkJoinPool(int, ForkJoinWorkerThreadFactory,
     * UncaughtExceptionHandler, boolean, int, int, int, Predicate,
     * long, TimeUnit)}).
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters (using
     * defaults for others -- see {@link #ForkJoinPool(int,
     * ForkJoinWorkerThreadFactory, UncaughtExceptionHandler, boolean,
     * int, int, int, Predicate, long, TimeUnit)}).
     *
     * 使用给定的参数创建一个｛@code ForkJoinPool｝tion 
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     *      parallelism并行级别。对于默认值，使用{@link java.lang.Runtime#availableProcessors}。
     * 
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     *
     *      factory用于创建新线程的工厂。对于默认值，
     *      请使用｛@link#defaultForkJoinWorkerThreadFactory｝。
     *      
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     *
     *      handler由于执行任务时遇到不可恢复的错误而终止的内部工作线程的处理程序。
     *      对于默认值，请使用｛null｝。
     *      
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
     *
     *      asyncMode如果为true，则为从未加入的分支任务建立本地先进先出调度模式。
     *      在工作线程仅处理事件式异步任务的应用程序中，
     *      此模式可能比默认的基于本地堆栈的模式更合适。对于默认值，请使用｛@code false｝。
     *      
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(parallelism, factory, handler, asyncMode,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     *
     *  parallelism并行级别。对于默认值，请使用java.lang.Runtime#available Processors。
     *  
     * @param factory the factory for creating new threads. For
     * default value, use {@link #defaultForkJoinWorkerThreadFactory}.
     *
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while
     * executing tasks. For default value, use {@code null}.
     *
     *      handler由于执行任务时遇到不可恢复的错误而终止的内部工作线程的处理程序
     *
     * @param asyncMode if true, establishes local first-in-first-out
     * scheduling mode for forked tasks that are never joined. This
     * mode may be more appropriate than default locally stack-based
     * mode in applications in which worker threads only process
     * event-style asynchronous tasks.  For default value, use {@code
     * false}.
     *
     * asyncMode如果为true，则为从未加入的分支任务建立本地先进先出调度模式。
     * 在工作线程仅处理事件式异步任务的应用程序中，
     * 此模式可能比默认的基于本地堆栈的模式更合适。对于默认值，请使用｛false｝。
     *
     * @param corePoolSize the number of threads to keep in the pool
     * (unless timed out after an elapsed keep-alive). Normally (and
     * by default) this is the same value as the parallelism level,
     * but may be set to a larger value to reduce dynamic overhead if
     * tasks regularly block. Using a smaller value (for example
     * {@code 0}) has the same effect as the default.
     *
     * corePoolSize要保留在池中的线程数（除非在保持活动状态后超时）。
     * 通常（默认情况下），这与并行度级别的值相同，
     * 但如果任务经常阻塞，则可以设置为更大的值以减少动态开销。
     * 使用较小的值（例如｛0）与默认值具有相同的效果。
     * 
     * @param maximumPoolSize the maximum number of threads allowed.
     * When the maximum is reached, attempts to replace blocked
     * threads fail.  (However, because creation and termination of
     * different threads may overlap, and may be managed by the given
     * thread factory, this value may be transiently exceeded.)  To
     * arrange the same value as is used by default for the common
     * pool, use {@code 256} plus the {@code parallelism} level. (By
     * default, the common pool allows a maximum of 256 spare
     * threads.)  Using a value (for example {@code
     * Integer.MAX_VALUE}) larger than the implementation's total
     * thread limit has the same effect as using this limit (which is
     * the default).
     *
     * maximumPoolSize允许的最大线程数。当达到最大值时，尝试替换阻塞的线程将失败。
     * （但是，由于不同线程的创建和终止可能重叠，并且可能由给定的线程工厂管理，因此可能会暂时超过此值。）
     * 若要安排与公共池默认使用的值相同的值，请使用{@code 256}加上{@code parallelism}级别。
     * （默认情况下，公共池最多允许256个备用线程。）
     * 使用大于实现的线程总数限制的值（例如｛Integer.MAX_value）与使用此限制（默认值）具有相同的效果。
     *
     * @param minimumRunnable the minimum allowed number of core
     * threads not blocked by a join or {@link ManagedBlocker}.  To
     * ensure progress, when too few unblocked threads exist and
     * unexecuted tasks may exist, new threads are constructed, up to
     * the given maximumPoolSize.  For the default value, use {@code
     * 1}, that ensures liveness.  A larger value might improve
     * throughput in the presence of blocked activities, but might
     * not, due to increased overhead.  A value of zero may be
     * acceptable when submitted tasks cannot have dependencies
     * requiring additional threads.
     *
     * minimumRunnable未被联接或｛@link ManagedBlocker｝阻止的核心线程的最小允许数量。
     * 为了确保进度，当存在太少未阻止的线程并且可能存在未执行的任务时，会构造新线程，
     * 最大可达给定的最大PoolSize。
     * 对于默认值，请使用｛1｝，以确保活动性。较大的值可能会在存在阻塞活动的情况下提高吞吐量，
     * 但可能不会，因为增加了开销。
     * 当提交的任务不能具有需要额外线程的依赖关系时，可以接受零值。
     *
     * @param saturate if non-null, a predicate invoked upon attempts
     * to create more than the maximum total allowed threads.  By
     * default, when a thread is about to block on a join or {@link
     * ManagedBlocker}, but cannot be replaced because the
     * maximumPoolSize would be exceeded, a {@link
     * RejectedExecutionException} is thrown.  But if this predicate
     * returns {@code true}, then no exception is thrown, so the pool
     * continues to operate with fewer than the target number of
     * runnable threads, which might not ensure progress.
     *
     * 饱和（如果非null），则在尝试创建超过允许的最大线程总数时调用的谓词。
     * 默认情况下，当线程即将阻止联接或｛ManagedBlocker｝，
     * 但由于将超过最大PoolSize而无法替换时，
     * 将引发｛RejectedExecutionException｝。
     * 但是，如果此谓词返回｛true｝，则不会引发异常，
     * 因此池继续以少于目标数量的可运行线程运行，这可能无法确保进度。
     * 
     * @param keepAliveTime the elapsed time since last use before
     * a thread is terminated (and then later replaced if needed).
     * For the default value, use {@code 60, TimeUnit.SECONDS}.
     *
     * keepAliveTime自上次使用以来线程终止（如果需要，稍后替换）所经过的时间。
     * 对于默认值，请使用｛60，TimeUnit.SECONDS｝。
     * 
     * @param unit the time unit for the {@code keepAliveTime} argument
     *          unit｛keepAliveTime｝参数的时间单位
     *
     * @throws IllegalArgumentException if parallelism is less than or
     *         equal to zero, or is greater than implementation limit,
     *         or if maximumPoolSize is less than parallelism,
     *         of if the keepAliveTime is less than or equal to zero.
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     * @since 9
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode,
                        int corePoolSize,
                        int maximumPoolSize,
                        int minimumRunnable,
                        Predicate<? super ForkJoinPool> saturate,
                        long keepAliveTime,
                        TimeUnit unit) {
        checkPermission();
        //并发数
        int p = parallelism;
        if (p <= 0 || p > MAX_CAP || p > maximumPoolSize || keepAliveTime <= 0L){
            throw new IllegalArgumentException();
        }
        if (factory == null || unit == null){
            throw new NullPointerException();
        }
        this.factory = factory;
        this.ueh = handler;
        this.saturate = saturate;
        //long TIMEOUT_SLOP = 20L;
        this.keepAlive = Math.max(unit.toMillis(keepAliveTime), TIMEOUT_SLOP);
        // Integer.numberOfLeadingZeros,最高位为0的个数
        int size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
        //MAX_CAP      = 0x7fff;        // max #workers - 1
        //MAX_CAP:	32767	Bit->:111111111111111
        //核心线程数
        int corep = Math.min(Math.max(corePoolSize, p), MAX_CAP);
        //最大工作池减去线程数（并发数）
        int maxSpares = Math.min(maximumPoolSize, MAX_CAP) - p;
        int minAvail = Math.min(Math.max(minimumRunnable, 0), MAX_CAP);
        //SMASK:	65535	Bit->:1111111111111111
        this.bounds = ((minAvail - p) & SMASK) | (maxSpares << SWIDTH);
        //FIFO         = 1 << 16;       // fifo queue or access mode
        //FIFO:	65536	Bit->:10000000000000000
        // mode初始化为核数和异步模式的全集（即或|）
        this.mode = p | (asyncMode ? FIFO : 0);
        //SP_MASK    = 0xffffffffL;
        //UC_MASK    = ~SP_MASK;
        
        //TC_SHIFT   = 32;
        //TC_MASK    = 0xffffL << TC_SHIFT;
        
        //RC_SHIFT   = 48;
        //RC_MASK    = 0xffffL << RC_SHIFT;

        //TC_MASK:	281470681743360
        //                111111111111111100000000000000000000000000000000
        //RC_MASK:	-281474976710656
        //1111111111111111000000000000000000000000000000000000000000000000
        //UC_MASK:	-4294967296
        //1111111111111111111111111111111100000000000000000000000000000000
        //SP_MASK:	4294967295
        //11111111111111111111111111111111
        
        //EXP:
        // ctl:	-4222399528566784
        //ctl->:1111111111110000111111111100000000000000000000000000000000000000
        // int p=16;
        // int corePoolSize=64;
        // ctl初始化为负值
        this.ctl = ((((long)(-corep) << TC_SHIFT) & TC_MASK) | //核数（线程数、工作者数）
                    (((long)(-p)     << RC_SHIFT) & RC_MASK)); //并发数
        this.registrationLock = new ReentrantLock();
        this.queues = new WorkQueue[size];
        String pid = Integer.toString(getAndAddPoolIds(1) + 1);
        this.workerNamePrefix = "ForkJoinPool-" + pid + "-worker-";
    }

    // helper method for commonPool constructor
    private static Object newInstanceFromSystemProperty(String property)
        throws ReflectiveOperationException {
        String className = System.getProperty(property);
        return (className == null)
            ? null
            : ClassLoader.getSystemClassLoader().loadClass(className)
            .getConstructor().newInstance();
    }

    /**
     * Constructor for common pool using parameters possibly
     * overridden by system properties
     */
    private ForkJoinPool(byte forCommonPoolOnly) {
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ForkJoinWorkerThreadFactory fac = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            fac = (ForkJoinWorkerThreadFactory) newInstanceFromSystemProperty(
                "java.util.concurrent.ForkJoinPool.common.threadFactory");
            handler = (UncaughtExceptionHandler) newInstanceFromSystemProperty(
                "java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            String pp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.parallelism");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
        } catch (Exception ignore) {
        }
        this.ueh = handler;
        this.keepAlive = DEFAULT_KEEPALIVE;
        this.saturate = null;
        this.workerNamePrefix = null;
        int p = Math.min(Math.max(parallelism, 0), MAX_CAP), size;
        this.mode = p;
        if (p > 0) {
            size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
            //SWIDTH       = 16;            // width of short
            //DEFAULT_COMMON_MAX_SPARES = 256;
           
            //TC_SHIFT   = 32;
            //TC_MASK:	281470681743360
            //                111111111111111100000000000000000000000000000000
            
            //RC_SHIFT   = 48;
            //TC_MASK    = 0xffffL << TC_SHIFT;
            //RC_MASK:	-281474976710656
            //1111111111111111000000000000000000000000000000000000000000000000
            this.bounds = ((1 - p) & SMASK) | (COMMON_MAX_SPARES << SWIDTH);
            this.ctl = ((((long)(-p) << TC_SHIFT) & TC_MASK) |
                        (((long)(-p) << RC_SHIFT) & RC_MASK));
        } else {  // zero min, max, spare counts, 1 slot
            size = 1;
            this.bounds = 0;
            this.ctl = 0L;
        }
        this.factory = (fac != null) ? fac :
            new DefaultCommonPoolForkJoinWorkerThreadFactory();
        this.queues = new WorkQueue[size];
        this.registrationLock = new ReentrantLock();
    }

    /**
     * Returns the common pool instance. This pool is statically
     * constructed; its run state is unaffected by attempts to {@link
     * #shutdown} or {@link #shutdownNow}. However this pool and any
     * ongoing processing are automatically terminated upon program
     * {@link System#exit}.  Any program that relies on asynchronous
     * task processing to complete before program termination should
     * invoke {@code commonPool().}{@link #awaitQuiescence awaitQuiescence},
     * before exit.
     *
     * @return the common pool instance
     * @since 1.8
     */
    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @param <T> the type of the task's result
     * @return the task's result
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> T invoke(ForkJoinTask<T> task) {
        externalSubmit(task);
        return task.joinForPoolInvoke(this);
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(ForkJoinTask<?> task) {
        externalSubmit(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    @Override
    @SuppressWarnings("unchecked")
    public void execute(Runnable task) {
        externalSubmit((task instanceof ForkJoinTask<?>)
                       ? (ForkJoinTask<Void>) task // avoid re-wrap
                       : new ForkJoinTask.RunnableExecuteAction(task));
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        return externalSubmit(task);
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    @Override
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return externalSubmit(new ForkJoinTask.AdaptedCallable<T>(task));
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    @Override
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return externalSubmit(new ForkJoinTask.AdaptedRunnable<T>(task, result));
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    @Override
    @SuppressWarnings("unchecked")
    public ForkJoinTask<?> submit(Runnable task) {
        return externalSubmit((task instanceof ForkJoinTask<?>)
            ? (ForkJoinTask<Void>) task // avoid re-wrap
            : new ForkJoinTask.AdaptedRunnableAction(task));
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f =
                    new ForkJoinTask.AdaptedInterruptibleCallable<T>(t);
                futures.add(f);
                externalSubmit(f);
            }
            for (int i = futures.size() - 1; i >= 0; --i)
                ((ForkJoinTask<?>)futures.get(i)).awaitPoolInvoke(this);
            return futures;
        } catch (Throwable t) {
            for (Future<T> e : futures)
                ForkJoinTask.cancelIgnoringExceptions(e);
            throw t;
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f =
                    new ForkJoinTask.AdaptedInterruptibleCallable<T>(t);
                futures.add(f);
                externalSubmit(f);
            }
            long startTime = System.nanoTime(), ns = nanos;
            boolean timedOut = (ns < 0L);
            for (int i = futures.size() - 1; i >= 0; --i) {
                Future<T> f = futures.get(i);
                if (!f.isDone()) {
                    if (timedOut)
                        ForkJoinTask.cancelIgnoringExceptions(f);
                    else {
                        ((ForkJoinTask<T>)f).awaitPoolInvoke(this, ns);
                        if ((ns = nanos - (System.nanoTime() - startTime)) < 0L)
                            timedOut = true;
                    }
                }
            }
            return futures;
        } catch (Throwable t) {
            for (Future<T> e : futures)
                ForkJoinTask.cancelIgnoringExceptions(e);
            throw t;
        }
    }

    // Task to hold results from InvokeAnyTasks
    static final class InvokeAnyRoot<E> extends ForkJoinTask<E> {
        private static final long serialVersionUID = 2838392045355241008L;
        @SuppressWarnings("serial") // Conditionally serializable
        volatile E result;
        final AtomicInteger count;  // in case all throw
        final ForkJoinPool pool;    // to check shutdown while collecting
        InvokeAnyRoot(int n, ForkJoinPool p) {
            pool = p;
            count = new AtomicInteger(n);
        }
        final void tryComplete(Callable<E> c) { // called by InvokeAnyTasks
            Throwable ex = null;
            boolean failed;
            if (c == null || Thread.interrupted() ||
                (pool != null && pool.mode < 0))
                failed = true;
            else if (isDone())
                failed = false;
            else {
                try {
                    complete(c.call());
                    failed = false;
                } catch (Throwable tx) {
                    ex = tx;
                    failed = true;
                }
            }
            if ((pool != null && pool.mode < 0) ||
                (failed && count.getAndDecrement() <= 1))
                trySetThrown(ex != null ? ex : new CancellationException());
        }
        public final boolean exec()         { return false; } // never forked
        public final E getRawResult()       { return result; }
        public final void setRawResult(E v) { result = v; }
    }

    // Variant of AdaptedInterruptibleCallable with results in InvokeAnyRoot
    static final class InvokeAnyTask<E> extends ForkJoinTask<E> {
        private static final long serialVersionUID = 2838392045355241008L;
        final InvokeAnyRoot<E> root;
        @SuppressWarnings("serial") // Conditionally serializable
        final Callable<E> callable;
        transient volatile Thread runner;
        InvokeAnyTask(InvokeAnyRoot<E> root, Callable<E> callable) {
            this.root = root;
            this.callable = callable;
        }
        public final boolean exec() {
            Thread.interrupted();
            runner = Thread.currentThread();
            root.tryComplete(callable);
            runner = null;
            Thread.interrupted();
            return true;
        }
        public final boolean cancel(boolean mayInterruptIfRunning) {
            Thread t;
            boolean stat = super.cancel(false);
            if (mayInterruptIfRunning && (t = runner) != null) {
                try {
                    t.interrupt();
                } catch (Throwable ignore) {
                }
            }
            return stat;
        }
        public final void setRawResult(E v) {} // unused
        public final E getRawResult()       { return null; }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
        int n = tasks.size();
        if (n <= 0)
            throw new IllegalArgumentException();
        InvokeAnyRoot<T> root = new InvokeAnyRoot<T>(n, this);
        ArrayList<InvokeAnyTask<T>> fs = new ArrayList<>(n);
        try {
            for (Callable<T> c : tasks) {
                if (c == null)
                    throw new NullPointerException();
                InvokeAnyTask<T> f = new InvokeAnyTask<T>(root, c);
                fs.add(f);
                externalSubmit(f);
                if (root.isDone())
                    break;
            }
            return root.getForPoolInvoke(this);
        } finally {
            for (InvokeAnyTask<T> f : fs)
                ForkJoinTask.cancelIgnoringExceptions(f);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        int n = tasks.size();
        if (n <= 0)
            throw new IllegalArgumentException();
        InvokeAnyRoot<T> root = new InvokeAnyRoot<T>(n, this);
        ArrayList<InvokeAnyTask<T>> fs = new ArrayList<>(n);
        try {
            for (Callable<T> c : tasks) {
                if (c == null)
                    throw new NullPointerException();
                InvokeAnyTask<T> f = new InvokeAnyTask<T>(root, c);
                fs.add(f);
                externalSubmit(f);
                if (root.isDone())
                    break;
            }
            return root.getForPoolInvoke(this, nanos);
        } finally {
            for (InvokeAnyTask<T> f : fs)
                ForkJoinTask.cancelIgnoringExceptions(f);
        }
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        int par = mode & SMASK;
        return (par > 0) ? par : 1;
    }

    /**
     * Returns the targeted parallelism level of the common pool.
     *
     * @return the targeted parallelism level of the common pool
     * @since 1.8
     */
    public static int getCommonPoolParallelism() {
        return COMMON_PARALLELISM;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return ((mode & SMASK) + (short)(ctl >>> TC_SHIFT));
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return (mode & FIFO) != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs; WorkQueue q;
        int rc = 0;
        if ((qs = queues) != null) {
            for (int i = 1; i < qs.length; i += 2) {
                if ((q = qs[i]) != null && q.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = (mode & SMASK) + (int)(ctl >> RC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    public boolean isQuiescent() {
        return canStop();
    }

    /**
     * Returns an estimate of the total number of completed tasks that
     * were executed by a thread other than their submitter. The
     * reported value underestimates the actual total number of steals
     * when the pool is not quiescent. This value may be useful for
     * monitoring and tuning fork/join programs: in general, steal
     * counts should be high enough to keep threads busy, but low
     * enough to avoid overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        long count = stealCount;
        WorkQueue[] qs; WorkQueue q;
        if ((qs = queues) != null) {
            for (int i = 1; i < qs.length; i += 2) {
                if ((q = qs[i]) != null)
                    count += (long)q.nsteals & 0xffffffffL;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    public long getQueuedTaskCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs; WorkQueue q;
        int count = 0;
        if ((qs = queues) != null) {
            for (int i = 1; i < qs.length; i += 2) {
                if ((q = qs[i]) != null)
                    count += q.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    public int getQueuedSubmissionCount() {
        VarHandle.acquireFence();
        WorkQueue[] qs; WorkQueue q;
        int count = 0;
        if ((qs = queues) != null) {
            for (int i = 0; i < qs.length; i += 2) {
                if ((q = qs[i]) != null)
                    count += q.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        VarHandle.acquireFence();
        WorkQueue[] qs; WorkQueue q;
        if ((qs = queues) != null) {
            for (int i = 0; i < qs.length; i += 2) {
                if ((q = qs[i]) != null && !q.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        return pollScan(true);
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        for (ForkJoinTask<?> t; (t = pollScan(false)) != null; ) {
            c.add(t);
            ++count;
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        // Use a single pass through queues to collect counts
        int md = mode; // read volatile fields first
        long c = ctl;
        long st = stealCount;
        long qt = 0L, ss = 0L; int rc = 0;
        WorkQueue[] qs; WorkQueue q;
        if ((qs = queues) != null) {
            for (int i = 0; i < qs.length; ++i) {
                if ((q = qs[i]) != null) {
                    int size = q.queueSize();
                    if ((i & 1) == 0)
                        ss += size;
                    else {
                        qt += size;
                        st += (long)q.nsteals & 0xffffffffL;
                        if (q.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }

        //SMASK:	65535	1111111111111111
        int pc = (md & SMASK);
        //TC_SHIFT   = 32
        int tc = pc + (short)(c >>> TC_SHIFT);
        //RC_SHIFT   = 48;
        int ac = pc + (int)(c >> RC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        String level = ((md & TERMINATED) != 0 ? "Terminated" :
                        (md & STOP)       != 0 ? "Terminating" :
                        (md & SHUTDOWN)   != 0 ? "Shutting down" :
                        "Running");
        return super.toString() +
            "[" + level +
            ", parallelism = " + pc +
            ", size = " + tc +
            ", active = " + ac +
            ", running = " + rc +
            ", steals = " + st +
            ", tasks = " + qt +
            ", submissions = " + ss +
            "]";
    }

    /**
     * Possibly initiates an orderly shutdown in which previously
     * submitted tasks are executed, but no new tasks will be
     * accepted. Invocation has no effect on execution state if this
     * is the {@link #commonPool()}, and no additional effect if
     * already shut down.  Tasks that are in the process of being
     * submitted concurrently during the course of this method may or
     * may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        if (this != common)
            tryTerminate(false, true);
    }

    /**
     * Possibly attempts to cancel and/or stop all tasks, and reject
     * all subsequently submitted tasks.  Invocation has no effect on
     * execution state if this is the {@link #commonPool()}, and no
     * additional effect if already shut down. Otherwise, tasks that
     * are in the process of being submitted or executed concurrently
     * during the course of this method may or may not be
     * rejected. This method cancels both existing and unexecuted
     * tasks, in order to permit termination in the presence of task
     * dependencies. So the method always returns an empty list
     * (unlike the case for some other Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        if (this != common)
            tryTerminate(true, true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        return (mode & TERMINATED) != 0;
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for I/O,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        return (mode & (STOP | TERMINATED)) == STOP;
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return (mode & SHUTDOWN) != 0;
    }

    /**
     * Blocks until all tasks have completed execution after a
     * shutdown request, or the timeout occurs, or the current thread
     * is interrupted, whichever happens first. Because the {@link
     * #commonPool()} never terminates until program shutdown, when
     * applied to the common pool, this method is equivalent to {@link
     * #awaitQuiescence(long, TimeUnit)} but always returns {@code false}.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        ReentrantLock lock; Condition cond;
        long nanos = unit.toNanos(timeout);
        boolean terminated = false;
        if (this == common) {
            Thread t; ForkJoinWorkerThread wt; int q;
            if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread &&
                (wt = (ForkJoinWorkerThread)t).pool == this)
                q = helpQuiescePool(wt.workQueue, nanos, true);
            else
                q = externalHelpQuiescePool(nanos, true);
            if (q < 0)
                throw new InterruptedException();
        }
        else if (!(terminated = ((mode & TERMINATED) != 0)) &&
                 (lock = registrationLock) != null) {
            lock.lock();
            try {
                if ((cond = termination) == null)
                    termination = cond = lock.newCondition();
                while (!(terminated = ((mode & TERMINATED) != 0)) && nanos > 0L)
                    nanos = cond.awaitNanos(nanos);
            } finally {
                lock.unlock();
            }
        }
        return terminated;
    }

    /**
     * If called by a ForkJoinTask operating in this pool, equivalent
     * in effect to {@link ForkJoinTask#helpQuiesce}. Otherwise,
     * waits and/or attempts to assist performing tasks until this
     * pool {@link #isQuiescent} or the indicated timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if quiescent; {@code false} if the
     * timeout elapsed.
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        Thread t; ForkJoinWorkerThread wt; int q;
        long nanos = unit.toNanos(timeout);
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread &&
            (wt = (ForkJoinWorkerThread)t).pool == this)
            q = helpQuiescePool(wt.workQueue, nanos, false);
        else
            q = externalHelpQuiescePool(nanos, false);
        return (q > 0);
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * 其它线程服务使用，如CompletableFuture、LinkedTransferQueue、Phaser
     * SubmissionPublisher、SynchronousQueue
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@link #isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@link #block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link
     * ForkJoinPool#managedBlock(ManagedBlocker)}.  The unusual
     * methods in this API accommodate synchronizers that may, but
     * don't usually, block for long periods. Similarly, they allow
     * more efficient internal handling of cases in which additional
     * workers may be, but usually are not, needed to ensure
     * sufficient parallelism.  Toward this end, implementations of
     * method {@code isReleasable} must be amenable to repeated
     * invocation. Neither method is invoked after a prior invocation
     * of {@code isReleasable} or {@code block} returns {@code true}.
     *
     * helpAsyncBlocker 调用
     * managedBlock
     * 
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     * <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     * <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         * @return {@code true} if blocking is unnecessary
         */
        boolean isReleasable();
    }

    /**
     * Runs the given possibly blocking task.  When {@linkplain
     * ForkJoinTask#inForkJoinPool() running in a ForkJoinPool}, this
     * method possibly arranges for a spare thread to be activated if
     * necessary to ensure sufficient parallelism while the current
     * thread is blocked in {@link ManagedBlocker#block blocker.block()}.
     *
     * <p>This method repeatedly calls {@code blocker.isReleasable()} and
     * {@code blocker.block()} until either method returns {@code true}.
     * Every call to {@code blocker.block()} is preceded by a call to
     * {@code blocker.isReleasable()} that returned {@code false}.
     *
     * <p>If not running in a ForkJoinPool, this method is
     * behaviorally equivalent to
     * <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     break;}</pre>
     *
     * If running in a ForkJoinPool, the pool may first be expanded to
     * ensure sufficient parallelism available during the call to
     * {@code blocker.block()}.
     *
     * @param blocker the blocker task
     * @throws InterruptedException if {@code blocker.block()} did so
     */
    public static void managedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        Thread t; ForkJoinPool p;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread &&
            (p = ((ForkJoinWorkerThread)t).pool) != null)
            p.compensatedBlock(blocker);
        else
            unmanagedBlock(blocker);
    }

    /** ManagedBlock for ForkJoinWorkerThreads */
    private void compensatedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        if (blocker == null) throw new NullPointerException();
        for (;;) {
            int comp; boolean done;
            long c = ctl;
            if (blocker.isReleasable())
                break;
            if ((comp = tryCompensate(c)) >= 0) {
                long post = (comp == 0) ? 0L : RC_UNIT;
                try {
                    done = blocker.block();
                } finally {
                    getAndAddCtl(post);
                }
                if (done)
                    break;
            }
        }
    }

    /** ManagedBlock for external threads */
    private static void unmanagedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        if (blocker == null) throw new NullPointerException();
        do {} while (!blocker.isReleasable() && !blocker.block());
    }

    // AbstractExecutorService.newTaskFor overrides rely on
    // undocumented fact that ForkJoinTask.adapt returns ForkJoinTasks
    // that also implement RunnableFuture.

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CTL = l.findVarHandle(ForkJoinPool.class, "ctl", long.class);
            MODE = l.findVarHandle(ForkJoinPool.class, "mode", int.class);
            THREADIDS = l.findVarHandle(ForkJoinPool.class, "threadIds", int.class);
            POOLIDS = l.findStaticVarHandle(ForkJoinPool.class, "poolIds", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;

        int commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        try {
            String p = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.maximumSpares");
            if (p != null)
                commonMaxSpares = Integer.parseInt(p);
        } catch (Exception ignore) {}
        COMMON_MAX_SPARES = commonMaxSpares;

        defaultForkJoinWorkerThreadFactory =
            new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");
        @SuppressWarnings("removal")
        ForkJoinPool tmp = AccessController.doPrivileged(new PrivilegedAction<>() {
            public ForkJoinPool run() {
                return new ForkJoinPool((byte)0); }});
        common = tmp;

        COMMON_PARALLELISM = Math.max(common.mode & SMASK, 1);
    }
}
