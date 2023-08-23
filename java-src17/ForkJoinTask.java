package java.util.concurrent;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.locks.LockSupport;

/**
 * Abstract base class for tasks that run within a {@link ForkJoinPool}.
 * A {@code ForkJoinTask} is a thread-like entity that is much
 * lighter weight than a normal thread.  Huge numbers of tasks and
 * subtasks may be hosted by a small number of actual threads in a
 * ForkJoinPool, at the price of some usage limitations.
 *
 * 在｛@link ForkJoinPool｝中运行的任务的抽象基类。｛ForkJoinTask｝是一个类似线程的实体，其重量比普通线程轻得多。
 * 大量的任务和子任务可能由ForkJoinPool中的少量实际线程托管，代价是一些使用限制。
 * 
 * <p>A "main" {@code ForkJoinTask} begins execution when it is
 * explicitly submitted to a {@link ForkJoinPool}, or, if not already
 * engaged in a ForkJoin computation, commenced in the {@link
 * ForkJoinPool#commonPool()} via {@link #fork}, {@link #invoke}, or
 * related methods.  Once started, it will usually in turn start other
 * subtasks.  As indicated by the name of this class, many programs
 * using {@code ForkJoinTask} employ only methods {@link #fork} and
 * {@link #join}, or derivatives such as {@link
 * #invokeAll(ForkJoinTask...) invokeAll}.  However, this class also
 * provides a number of other methods that can come into play in
 * advanced usages, as well as extension mechanics that allow support
 * of new forms of fork/join processing.
 *
 * “main”｛ForkJoinTask｝在
 * 显式提交到｛ForkJoinPool｝，或者，如果尚未参与ForkJoin计算，
 * 则通过fork、invoke或相关方法在｛fork JoinPool#commonPool（）｝中启动。
 * 一旦启动，它通常会依次启动其他子任务。正如这个类的名称所示，许多使用｛ForkJoinTask｝的程序只使用方法｛fork｝和｛join｝，
 * 或派生方法，如｛invokeAll（ForkJoinTask…）invokeAll｝。
 * 然而，这个类还提供了许多其他可以在高级用法中发挥作用的方法，以及允许支持新形式的fork/join处理的扩展机制。
 *  
 * <p>A {@code ForkJoinTask} is a lightweight form of {@link Future}.
 * The efficiency of {@code ForkJoinTask}s stems from a set of
 * restrictions (that are only partially statically enforceable)
 * reflecting their main use as computational tasks calculating pure
 * functions or operating on purely isolated objects.  The primary
 * coordination mechanisms are {@link #fork}, that arranges
 * asynchronous execution, and {@link #join}, that doesn't proceed
 * until the task's result has been computed.  Computations should
 * ideally avoid {@code synchronized} methods or blocks, and should
 * minimize other blocking synchronization apart from joining other
 * tasks or using synchronizers such as Phasers that are advertised to
 * cooperate with fork/join scheduling. Subdividable tasks should also
 * not perform blocking I/O, and should ideally access variables that
 * are completely independent of those accessed by other running
 * tasks.
 *
 * ｛@code ForkJoinTask｝是｛@link Future｝的轻量级形式。{@codeForkJoinTask}的效率源于一组限制（仅部分静态可执行），
 * 反映了它们主要用作计算纯函数或在纯隔离对象上操作的计算任务。
 * 主要的协调机制是安排异步执行的｛@link#fork｝和在计算任务结果之前不进行的｛@link#join｝。
 * 理想情况下，计算应该避免{@code-synchronized}方法或块，并且除了加入其他任务或使用同步器（如宣传与fork/join调度合作的Phasers）之外，
 * 还应该最大限度地减少其他块同步。可细分任务也不应执行阻塞I/O，理想情况下应访问完全独立于其他运行任务访问的变量的变量。
 *
 * These guidelines are loosely enforced by not permitting
 * checked exceptions such as {@code IOExceptions} to be
 * thrown. However, computations may still encounter unchecked
 * exceptions, that are rethrown to callers attempting to join
 * them. These exceptions may additionally include {@link
 * RejectedExecutionException} stemming from internal resource
 * exhaustion, such as failure to allocate internal task
 * queues. Rethrown exceptions behave in the same way as regular
 * exceptions, but, when possible, contain stack traces (as displayed
 * for example using {@code ex.printStackTrace()}) of both the thread
 * that initiated the computation as well as the thread actually
 * encountering the exception; minimally only the latter.
 *
 * 这些准则是通过不允许抛出｛@code IOExceptions｝等已检查异常来松散地执行的。
 * 然而，计算可能仍然会遇到未检查的异常，这些异常会重新抛出给试图加入它们的调用方。
 * 这些异常可能还包括源于内部资源耗尽的｛@link RejectedExecutionException｝，例如未能分配内部任务队列。
 * Rethrown异常的行为方式与常规异常相同，
 * 但在可能的情况下，包含启动计算的线程和实际遇到异常的线程的堆栈跟踪
 * （例如使用{@code ex.printStackTrace（）}显示）；至少只有后者。
 * 
 * <p>It is possible to define and use ForkJoinTasks that may block,
 * but doing so requires three further considerations: (1) Completion
 * of few if any <em>other</em> tasks should be dependent on a task
 * that blocks on external synchronization or I/O. Event-style async
 * tasks that are never joined (for example, those subclassing {@link
 * CountedCompleter}) often fall into this category.  (2) To minimize
 * resource impact, tasks should be small; ideally performing only the
 * (possibly) blocking action. (3) Unless the {@link
 * ForkJoinPool.ManagedBlocker} API is used, or the number of possibly
 * blocked tasks is known to be less than the pool's {@link
 * ForkJoinPool#getParallelism} level, the pool cannot guarantee that
 * enough threads will be available to ensure progress or good
 * performance.
 *
 * 可以定义和使用可能阻塞的ForkJoinTasks，但这样做需要进一步考虑三个因素：
 * （1）完成少数（如果有的话）<em>其他</em>任务应取决于阻塞外部同步或I/O的任务。
 * 从未加入的事件式异步任务（例如，那些子类化{@linkCountedCompleter}）通常属于这一类。
 * （2） 为了尽量减少对资源的影响，任务应该很小；理想情况下仅执行（可能的）阻塞动作。
 * （3） 除非使用｛@link ForkJoinPool.ManagedBlocker｝API，
 * 或者已知可能被阻止的任务数小于池的｛@link ForkJoinPool#getParallelism｝级别，
 * 否则池无法保证有足够的线程可用以确保进度或良好性能。
 * 
 * <p>The primary method for awaiting completion and extracting
 * results of a task is {@link #join}, but there are several variants:
 * The {@link Future#get} methods support interruptible and/or timed
 * waits for completion and report results using {@code Future}
 * conventions. Method {@link #invoke} is semantically
 * equivalent to {@code fork(); join()} but always attempts to begin
 * execution in the current thread. The "<em>quiet</em>" forms of
 * these methods do not extract results or report exceptions. These
 * may be useful when a set of tasks are being executed, and you need
 * to delay processing of results or exceptions until all complete.
 * Method {@code invokeAll} (available in multiple versions)
 * performs the most common form of parallel invocation: forking a set
 * of tasks and joining them all.
 *
 * 等待完成和提取任务结果的主要方法是{@link#join}，但有几种变体：
 * {@linkFuture#get}方法支持可中断和/或定时等待完成，并使用{@codeFuture}约定报告结果。
 * 方法｛@link#invoke｝在语义上等效于｛@code fork（）；join（）｝，
 * 但总是尝试在当前线程中开始执行。这些方法的“<em>quiet</em>”形式不会提取结果或报告异常。
 * 当一组任务正在执行，并且您需要将结果或异常的处理延迟到全部完成时，这些可能会很有用。
 * 方法｛@code invokeAll｝（有多个版本）执行最常见的并行调用形式：派生一组任务并将它们全部联接。
 * 
 * <p>In the most typical usages, a fork-join pair act like a call
 * (fork) and return (join) from a parallel recursive function. As is
 * the case with other forms of recursive calls, returns (joins)
 * should be performed innermost-first. For example, {@code a.fork();
 * b.fork(); b.join(); a.join();} is likely to be substantially more
 * efficient than joining {@code a} before {@code b}.
 *
 * 在最典型的用法中，fork-join对的作用类似于并行递归函数的调用（fork）和返回（join）。
 * 与其他形式的递归调用一样，返回（联接）应该首先在最里面执行。
 * 例如，{@code a.fork（）；b.fork}。
 * 
 * <p>The execution status of tasks may be queried at several levels
 * of detail: {@link #isDone} is true if a task completed in any way
 * (including the case where a task was cancelled without executing);
 * {@link #isCompletedNormally} is true if a task completed without
 * cancellation or encountering an exception; {@link #isCancelled} is
 * true if the task was cancelled (in which case {@link #getException}
 * returns a {@link CancellationException}); and
 * {@link #isCompletedAbnormally} is true if a task was either
 * cancelled or encountered an exception, in which case {@link
 * #getException} will return either the encountered exception or
 * {@link CancellationException}.
 *
 * 任务的执行状态可以在多个详细级别上查询：如果任务以任何方式完成（包括任务在未执行的情况下被取消），
 * 则{@link#isDone}为true；｛@link#isCompletedNormally｝如果任务在未取消或遇到异常的情况下完成，则为true；
 * 如果任务被取消，则｛@link#isCancelled｝为true
 * （在这种情况下，｛@link#getException｝返回｛@link CancellationException｝）；
 * 如果任务被取消或遇到异常，则｛@link#isCompletedAbnormally｝为true，
 * 在这种情况下，｛@link#getException｝将返回遇到的异常或｛@link CancellationException｝。
 *
 * <p>The ForkJoinTask class is not usually directly subclassed.
 * Instead, you subclass one of the abstract classes that support a
 * particular style of fork/join processing, typically {@link
 * RecursiveAction} for most computations that do not return results,
 * {@link RecursiveTask} for those that do, and {@link
 * CountedCompleter} for those in which completed actions trigger
 * other actions.  Normally, a concrete ForkJoinTask subclass declares
 * fields comprising its parameters, established in a constructor, and
 * then defines a {@code compute} method that somehow uses the control
 * methods supplied by this base class.
 *
 * ForkJoinTask类通常不直接进行子类化。相反，您可以为支持特定类型的fork/join处理的抽象类中的一个进行子类化，
 * 通常｛@link RecursiveAction｝用于大多数不返回结果的计算，
 * ｛@link RecursiveTask｝用于那些返回结果的计算，｛@link CountedCompleter｝用于完成的操作触发其他操作的计算。
 * 通常，一个具体的ForkJoinTask子类声明在构造函数中建立的包含其参数的字段，
 * 然后定义一个{@code-computer}方法，该方法以某种方式使用该基类提供的控制方法。
 * 
 * <p>Method {@link #join} and its variants are appropriate for use
 * only when completion dependencies are acyclic; that is, the
 * parallel computation can be described as a directed acyclic graph
 * (DAG). Otherwise, executions may encounter a form of deadlock as
 * tasks cyclically wait for each other.
 *
 * 方法｛@link#join｝及其变体仅适用于完成依赖关系是非循环的时；
 * 也就是说，并行计算可以被描述为有向无环图（DAG）。
 * 否则，由于任务循环地相互等待，执行可能会遇到某种形式的死锁。
 * 
 * However, this framework
 * supports other methods and techniques (for example the use of
 * {@link Phaser}, {@link #helpQuiesce}, and {@link #complete}) that
 * may be of use in constructing custom subclasses for problems that
 * are not statically structured as DAGs. To support such usages, a
 * ForkJoinTask may be atomically <em>tagged</em> with a {@code short}
 * value using {@link #setForkJoinTaskTag} or {@link
 * #compareAndSetForkJoinTaskTag} and checked using {@link
 * #getForkJoinTaskTag}.
 *
 * 然而，该框架支持其他方法和技术（例如使用{@link-Phaser}、
 * {@link#helpQuiesce}和{@link#complete}），
 * 这些方法和技术可能在为未静态结构化为DAG的问题构建自定义子类时有用。
 * 为了支持这种用法，可以使用｛@link#setForkJoinTaskTag｝或
 * ｛@link#compareAndSetForkJoinTaskTag｝用｛@code short｝
 * 值原子地＜em＞标记ForkJoinTask，并使用｛@link#getForkJoinaskTag｝进行检查。
 *
 * The ForkJoinTask implementation does not use
 * these {@code protected} methods or tags for any purpose, but they
 * may be of use in the construction of specialized subclasses.  For
 * example, parallel graph traversals can use the supplied methods to
 * avoid revisiting nodes/tasks that have already been processed.
 * (Method names for tagging are bulky in part to encourage definition
 * of methods that reflect their usage patterns.)
 *
 * ForkJoinTask实现不将这些{@code-protected}方法或标记用于任何目的，
 * 但它们可能在构建专门的子类时有用。
 * 例如，并行图遍历可以使用提供的方法来避免重新访问已经处理的节点/任务。
 * （标记的方法名称庞大，部分原因是为了鼓励定义反映其使用模式的方法。）
 * 
 * <p>Most base support methods are {@code final}, to prevent
 * overriding of implementations that are intrinsically tied to the
 * underlying lightweight task scheduling framework.  Developers
 * creating new basic styles of fork/join processing should minimally
 * implement {@code protected} methods {@link #exec}, {@link
 * #setRawResult}, and {@link #getRawResult}, while also introducing
 * an abstract computational method that can be implemented in its
 * subclasses, possibly relying on other {@code protected} methods
 * provided by this class.
 *
 * 大多数基本支持方法都是{@code-final}，以防止重写本质上与底层轻量级任务调度框架相关的实现。
 * 创建新的fork/join处理基本样式的开发人员应至少实现｛@code-protected｝方法｛@link#exec｝、
 * ｛@link#setRawResult｝和｛@link#getRawResult｝，
 * 同时引入一种可以在其子类中实现的抽象计算方法，可能依赖于此类提供的其他｛@code protected｝方法。
 * 
 * <p>ForkJoinTasks should perform relatively small amounts of
 * computation. Large tasks should be split into smaller subtasks,
 * usually via recursive decomposition. As a very rough rule of thumb,
 * a task should perform more than 100 and less than 10000 basic
 * computational steps, and should avoid indefinite looping. If tasks
 * are too big, then parallelism cannot improve throughput. If too
 * small, then memory and internal task maintenance overhead may
 * overwhelm processing.
 *
 * ForkJoinTasks应该执行相对少量的计算。大型任务应划分为较小的子任务，通常通过递归分解。
 * 作为一个非常粗略的经验法则，一个任务应该执行100到10000个基本计算步骤，
 * 并且应该避免不确定的循环。如果任务太大，那么并行性就无法提高吞吐量。
 * 如果太小，那么内存和内部任务维护开销可能会使处理不堪重负。
 * 
 * <p>This class provides {@code adapt} methods for {@link Runnable}
 * and {@link Callable}, that may be of use when mixing execution of
 * {@code ForkJoinTasks} with other kinds of tasks. When all tasks are
 * of this form, consider using a pool constructed in <em>asyncMode</em>.
 *
 * 这个类为{@linkRunnable}和{@linkCallable}提供了{@code-adapt}方法，
 * 当将{@codeForkJoinTask}的执行与其他类型的任务混合使用时，
 * 这些方法可能会有用。当所有任务都是这种形式时，请考虑使用在asyncMode中构建的池。
 * 
 * <p>ForkJoinTasks are {@code Serializable}, which enables them to be
 * used in extensions such as remote execution frameworks. It is
 * sensible to serialize tasks only before or after, but not during,
 * execution. Serialization is not relied on during execution itself.
 *
 * ForkJoinTasks是{@codeSerializable}，这使它们能够用于远程执行框架等扩展。
 * 明智的做法是只在执行之前或之后序列化任务，而不是在执行期间序列化。在执行过程中不依赖序列化。
 * 
 * @since 1.7
 * @author Doug Lea
 */
public abstract class ForkJoinTask<V> implements Future<V>, Serializable {

    /*
     * See the internal documentation of class ForkJoinPool for a
     * general implementation overview.  ForkJoinTasks are mainly
     * responsible for maintaining their "status" field amidst relays
     * to methods in ForkJoinWorkerThread and ForkJoinPool.
     *
     * 有关一般实现概述，请参阅ForkJoinPool类的内部文档。
     * ForkJoinTasks主要负责在ForkJoinWorkerThread和ForkJoinPool中的方法中继中维护其“状态”字段。
     * 
     * The methods of this class are more-or-less layered into
     * 这个类的方法或多或少被分层为
     * (1) basic status maintenance 基本状态维护
     * (2) execution and awaiting completion 执行和等待完成
     * (3) user-level methods that additionally report results. 额外报告结果的用户级方法。
     * This is sometimes hard to see because this file orders exported
     * methods in a way that flows well in javadocs.
     *
     * 这有时很难看到，因为该文件以在javadocs中良好流动的方式对导出的方法进行排序。
     *
     * Revision notes: The use of "Aux" field replaces previous
     * reliance on a table to hold exceptions and synchronized blocks
     * and monitors to wait for completion.
     */

    /**
     * Nodes for threads waiting for completion, or holding a thrown
     * exception (never both). Waiting threads prepend nodes
     * Treiber-stack-style.  Signallers detach and unpark
     * waiters. Cancelled waiters try to unsplice.
     *
     * 等待完成或持有抛出异常的线程的节点（决不能两者都有）。
     * 等待线程准备节点Treeber堆栈样式。信号员将线程分开，并为其解封。取消服务的服务员试图解开。
     */
    static final class Aux {
        final Thread thread;
        final Throwable ex;  // null if a waiter 如果服务者（线程）为null
        
        // 仅通过内存获取链访问
        Aux next;            // accessed only via memory-acquire chains
        Aux(Thread thread, Throwable ex) {
            this.thread = thread;
            this.ex = ex;
        }
        
        // 仅用于取消
        final boolean casNext(Aux c, Aux v) { // used only in cancellation
            return NEXT.compareAndSet(this, c, v);
        }
        private static final VarHandle NEXT;
        static {
            try {
                NEXT = MethodHandles.lookup()
                    .findVarHandle(Aux.class, "next", Aux.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /*
     * The status field holds bits packed into a single int to ensure
     * atomicity.  Status is initially zero, and takes on nonnegative
     * values until completed, upon which it holds (sign bit) DONE,
     * possibly with ABNORMAL (cancelled or exceptional) and THROWN
     * (in which case an exception has been stored). A value of
     * ABNORMAL without DONE signifies an interrupted wait.  These
     * control bits occupy only (some of) the upper half (16 bits) of
     * status field. The lower bits are used for user-defined tags.
     *
     * 状态字段将位打包到单个int中，以确保原子性。
     * 状态最初为零，在完成之前采用非负值，在此基础上保持（符号位）DONE，可能带有ABNORMAL（已取消或异常）和THROWN（在这种情况下已存储异常）。
     * 值ABNORMAL without DONE表示等待中断。这些控制位仅占据状态字段的上半部分（16位）。低位用于用户定义的标记。
     */
    private static final int DONE         = 1 << 31; // must be negative 必须为负数
    private static final int ABNORMAL     = 1 << 16;
    private static final int THROWN       = 1 << 17;
    private static final int SMASK        = 0xffff;  // short bits for tags 标签的短位
    private static final int UNCOMPENSATE = 1 << 16; // helpJoin return sentinel helpJoin返回哨兵

    // Fields
    volatile int status;                // accessed directly by pool and workers 池和工作者(线程)直接访问
    private transient volatile Aux aux; // either waiters or thrown Exception 服务者（线程）或引发异常

    // Support for atomic operations
    private static final VarHandle STATUS;
    private static final VarHandle AUX;
    private int getAndBitwiseOrStatus(int v) {
        return (int)STATUS.getAndBitwiseOr(this, v);
    }
    private boolean casStatus(int c, int v) {
        return STATUS.compareAndSet(this, c, v);
    }
    private boolean casAux(Aux c, Aux v) {
        return AUX.compareAndSet(this, c, v);
    }

    /**
     * Removes and unparks waiters
     * 删除和取消标记服务者（线程）
    */
    private void signalWaiters() {
        for (Aux a; (a = aux) != null && a.ex == null; ) {
            if (casAux(a, null)) {             // detach entire list 分离整个列表
                for (Thread t; a != null; a = a.next) {
                    if ((t = a.thread) != Thread.currentThread() && t != null)
                        LockSupport.unpark(t); // don't self-signal 不要自发信号
                }
                break;
            }
        }
    }

    /**
     * Sets DONE status and wakes up threads waiting to join this task.
     * @return status on exit
     */
    private int setDone() {
        int s = getAndBitwiseOrStatus(DONE) | DONE;
        signalWaiters();
        return s;
    }

    /**
     * Sets ABNORMAL DONE status unless already done, and wakes up threads
     * waiting to join this task.
     *
     * 设置ABNORMAL DONE状态，除非已经完成，并唤醒等待加入此任务的线程。
     * 
     * @return status on exit
     */
    private int trySetCancelled() {
        int s;
        do {} while ((s = status) >= 0 && !casStatus(s, s |= (DONE | ABNORMAL)));
        signalWaiters();
        return s;
    }

    /**
     * Records exception and sets ABNORMAL THROWN DONE status unless
     * already done, and wakes up threads waiting to join this task.
     * If losing a race with setDone or trySetCancelled, the exception
     * may be recorded but not reported.
     *
     * 记录异常并设置异常抛出完成状态（除非已经完成），并唤醒等待加入此任务的线程。
     * 如果使用setDone或trySetCancelled输掉比赛，则可能会记录但不会报告异常。
     * 
     * @return status on exit
     */
    final int trySetThrown(Throwable ex) {
        Aux h = new Aux(Thread.currentThread(), ex), p = null;
        boolean installed = false;
        int s;
        while ((s = status) >= 0) {
            Aux a;
            if (!installed && ((a = aux) == null || a.ex == null) &&
                (installed = casAux(a, h)))
                p = a; // list of waiters replaced by h
            if (installed && casStatus(s, s |= (DONE | ABNORMAL | THROWN)))
                break;
        }
        for (; p != null; p = p.next)
            LockSupport.unpark(p.thread);
        return s;
    }

    /**
     * Records exception unless already done. Overridable in subclasses.
     * 记录异常，除非已完成。在子类中可重写。
     * @return status on exit
     */
    int trySetException(Throwable ex) {
        return trySetThrown(ex);
    }

    /**
     * Constructor for subclasses to call.
     */
    public ForkJoinTask() {}

    static boolean isExceptionalStatus(int s) {  // needed by subclasses
        return (s & THROWN) != 0;
    }

    /**
     * Unless done, calls exec and records status if completed, but
     * doesn't wait for completion otherwise.
     *
     * 除非完成，否则调用exec并在完成时记录状态，否则不等待完成。
     *
     * @return status on exit from this method
     */
    final int doExec() {
        int s; boolean completed;
        if ((s = status) >= 0) {
            try {
                completed = exec();
            } catch (Throwable rex) {
                s = trySetException(rex);
                completed = false;
            }
            if (completed)
                s = setDone();
        }
        return s;
    }

    /**
     * Helps and/or waits for completion from join, get, or invoke;
     * called from either internal or external threads.
     *
     * 从join、get或invoke帮助和/或等待完成；从内部或外部线程调用。
     * 
     * @param pool if nonnull, known submitted pool, else assumes current pool
     *          池如果为非null，则为已知已提交池，否则为当前池
     *          
     * @param ran true if task known to have been exec'd
     *          如果已知任务已执行，则运行true
     *          
     * @param interruptible true if park interruptibly when external
     *          可中断true如果驻车可中断当外部
     *          
     * @param timed true if use timed wait
     *          timed true如果使用timed wait
     *          
     * @param nanos if timed, timeout value
     *          nanos如果超时，则超时值
     *          
     * @return ABNORMAL if interrupted, else status on exit
     */
    private int awaitDone(ForkJoinPool pool, boolean ran,
                          boolean interruptible, boolean timed,
                          long nanos) {
        ForkJoinPool p; boolean internal; int s; Thread t;
        ForkJoinPool.WorkQueue q = null;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) {
            ForkJoinWorkerThread wt = (ForkJoinWorkerThread)t;
            p = wt.pool;
            if (pool == null)
                pool = p;
            if (internal = (pool == p))
                q = wt.workQueue;
        }
        else {
            internal = false;
            p = ForkJoinPool.common;
            if (pool == null)
                pool = p;
            if (pool == p && p != null)
                q = p.externalQueue();
        }
        if (interruptible && Thread.interrupted())
            return ABNORMAL;
        if ((s = status) < 0)
            return s;
        long deadline = 0L;
        if (timed) {
            if (nanos <= 0L)
                return 0;
            else if ((deadline = nanos + System.nanoTime()) == 0L)
                deadline = 1L;
        }
        boolean uncompensate = false;
        if (q != null && p != null) {  // try helping
            // help even in timed mode if pool has no parallelism
            // 如果池没有并行性，即使在定时模式下也有帮助
            boolean canHelp = !timed || (p.mode & SMASK) == 0;
            if (canHelp) {
                if ((this instanceof CountedCompleter) &&
                    (s = p.helpComplete(this, q, internal)) < 0)
                    return s;
                if (!ran && ((!internal && q.externalTryUnpush(this)) ||
                             q.tryRemove(this, internal)) && (s = doExec()) < 0)
                    return s;
            }
            if (internal) {
                if ((s = p.helpJoin(this, q, canHelp)) < 0)
                    return s;
                if (s == UNCOMPENSATE)
                    uncompensate = true;
            }
        }
        // block until done or cancelled wait
        // 阻止，直到完成或取消等待
        boolean interrupted = false, queued = false;
        boolean parked = false, fail = false;
        Aux node = null;
        while ((s = status) >= 0) {
            Aux a; long ns;
            if (fail || (fail = (pool != null && pool.mode < 0)))
                casStatus(s, s | (DONE | ABNORMAL)); // try to cancel
            else if (parked && Thread.interrupted()) {
                if (interruptible) {
                    s = ABNORMAL;
                    break;
                }
                interrupted = true;
            }
            else if (queued) {
                if (deadline != 0L) {
                    if ((ns = deadline - System.nanoTime()) <= 0L)
                        break;
                    LockSupport.parkNanos(ns);
                }
                else
                    LockSupport.park();
                parked = true;
            }
            else if (node != null) {
                if ((a = aux) != null && a.ex != null)
                    Thread.onSpinWait();     // exception in progress
                else if (queued = casAux(node.next = a, node))
                    LockSupport.setCurrentBlocker(this);
            }
            else {
                try {
                    node = new Aux(Thread.currentThread(), null);
                } catch (Throwable ex) {     // cannot create
                    fail = true;
                }
            }
        }
        if (pool != null && uncompensate)
            pool.uncompensate();

        if (queued) {
            LockSupport.setCurrentBlocker(null);
            // 取消类似于AbstractQueuedSynchronizer
            if (s >= 0) { // cancellation similar to AbstractQueuedSynchronizer
                outer: for (Aux a; (a = aux) != null && a.ex == null; ) {
                    for (Aux trail = null;;) {
                        Aux next = a.next;
                        if (a == node) {
                            if (trail != null)
                                trail.casNext(trail, next);
                            else if (casAux(a, next))
                                break outer; // cannot be re-encountered 无法再次遇到
                            break;           // restart
                        } else {
                            trail = a;
                            if ((a = next) == null)
                                break outer;
                        }
                    }
                }
            }
            else {
                signalWaiters();             // help clean or signal 帮助清洁或发出信号
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
        return s;
    }

    /**
     * Cancels, ignoring any exceptions thrown by cancel.  Cancel is
     * spec'ed not to throw any exceptions, but if it does anyway, we
     * have no recourse, so guard against this case.
     */
    static final void cancelIgnoringExceptions(Future<?> t) {
        if (t != null) {
            try {
                t.cancel(true);
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Returns a rethrowable exception for this task, if available.
     * To provide accurate stack traces, if the exception was not
     * thrown by the current thread, we try to create a new exception
     * of the same type as the one thrown, but with the recorded
     * exception as its cause. If there is no such constructor, we
     * instead try to use a no-arg constructor, followed by initCause,
     * to the same effect. If none of these apply, or any fail due to
     * other exceptions, we return the recorded exception, which is
     * still correct, although it may contain a misleading stack
     * trace.
     *
     * @return the exception, or null if none
     */
    private Throwable getThrowableException() {
        Throwable ex; Aux a;
        if ((a = aux) == null)
            ex = null;
        else if ((ex = a.ex) != null && a.thread != Thread.currentThread()) {
            try {
                Constructor<?> noArgCtor = null, oneArgCtor = null;
                for (Constructor<?> c : ex.getClass().getConstructors()) {
                    Class<?>[] ps = c.getParameterTypes();
                    if (ps.length == 0)
                        noArgCtor = c;
                    else if (ps.length == 1 && ps[0] == Throwable.class) {
                        oneArgCtor = c;
                        break;
                    }
                }
                if (oneArgCtor != null)
                    ex = (Throwable)oneArgCtor.newInstance(ex);
                else if (noArgCtor != null) {
                    Throwable rx = (Throwable)noArgCtor.newInstance();
                    rx.initCause(ex);
                    ex = rx;
                }
            } catch (Exception ignore) {
            }
        }
        return ex;
    }

    /**
     * Returns exception associated with the given status, or null if none.
     */
    private Throwable getException(int s) {
        Throwable ex = null;
        if ((s & ABNORMAL) != 0 &&
            ((s & THROWN) == 0 || (ex = getThrowableException()) == null))
            ex = new CancellationException();
        return ex;
    }

    /**
     * Throws exception associated with the given status, or
     * CancellationException if none recorded.
     */
    private void reportException(int s) {
        ForkJoinTask.<RuntimeException>uncheckedThrow(
            (s & THROWN) != 0 ? getThrowableException() : null);
    }

    /**
     * Throws exception for (timed or untimed) get, wrapping if
     * necessary in an ExecutionException.
     */
    private void reportExecutionException(int s) {
        Throwable ex = null;
        if (s == ABNORMAL)
            ex = new InterruptedException();
        else if (s >= 0)
            ex = new TimeoutException();
        else if ((s & THROWN) != 0 && (ex = getThrowableException()) != null)
            ex = new ExecutionException(ex);
        ForkJoinTask.<RuntimeException>uncheckedThrow(ex);
    }

    /**
     * A version of "sneaky throw" to relay exceptions in other
     * contexts.
     */
    static void rethrow(Throwable ex) {
        ForkJoinTask.<RuntimeException>uncheckedThrow(ex);
    }

    /**
     * The sneaky part of sneaky throw, relying on generics
     * limitations to evade compiler complaints about rethrowing
     * unchecked exceptions. If argument null, throws
     * CancellationException.
     */
    @SuppressWarnings("unchecked") static <T extends Throwable>
    void uncheckedThrow(Throwable t) throws T {
        if (t == null)
            t = new CancellationException();
        throw (T)t; // rely on vacuous cast
    }

    // public methods

    /**
     * Arranges to asynchronously execute this task in the pool the
     * current task is running in, if applicable, or using the {@link
     * ForkJoinPool#commonPool()} if not {@link #inForkJoinPool}.  While
     * it is not necessarily enforced, it is a usage error to fork a
     * task more than once unless it has completed and been
     * reinitialized.  Subsequent modifications to the state of this
     * task or any data it operates on are not necessarily
     * consistently observable by any thread other than the one
     * executing it unless preceded by a call to {@link #join} or
     * related methods, or a call to {@link #isDone} returning {@code
     * true}.
     *
     * @return {@code this}, to simplify usage
     */
    public final ForkJoinTask<V> fork() {
        Thread t; ForkJoinWorkerThread w;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            (w = (ForkJoinWorkerThread)t).workQueue.push(this, w.pool);
        else
            ForkJoinPool.common.externalPush(this);
        return this;
    }

    /**
     * Returns the result of the computation when it
     * {@linkplain #isDone is done}.
     * This method differs from {@link #get()} in that abnormal
     * completion results in {@code RuntimeException} or {@code Error},
     * not {@code ExecutionException}, and that interrupts of the
     * calling thread do <em>not</em> cause the method to abruptly
     * return by throwing {@code InterruptedException}.
     *
     * @return the computed result
     */
    public final V join() {
        int s;
        if ((s = status) >= 0)
            s = awaitDone(null, false, false, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }

    /**
     * Commences performing this task, awaits its completion if
     * necessary, and returns its result, or throws an (unchecked)
     * {@code RuntimeException} or {@code Error} if the underlying
     * computation did so.
     *
     * @return the computed result
     */
    public final V invoke() {
        int s;
        if ((s = doExec()) >= 0)
            s = awaitDone(null, true, false, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }

    /**
     * Forks the given tasks, returning when {@code isDone} holds for
     * each task or an (unchecked) exception is encountered, in which
     * case the exception is rethrown. If more than one task
     * encounters an exception, then this method throws any one of
     * these exceptions. If any task encounters an exception, the
     * other may be cancelled. However, the execution status of
     * individual tasks is not guaranteed upon exceptional return. The
     * status of each task may be obtained using {@link
     * #getException()} and related methods to check if they have been
     * cancelled, completed normally or exceptionally, or left
     * unprocessed.
     *
     * @param t1 the first task
     * @param t2 the second task
     * @throws NullPointerException if any task is null
     */
    public static void invokeAll(ForkJoinTask<?> t1, ForkJoinTask<?> t2) {
        int s1, s2;
        if (t1 == null || t2 == null)
            throw new NullPointerException();
        t2.fork();
        if ((s1 = t1.doExec()) >= 0)
            s1 = t1.awaitDone(null, true, false, false, 0L);
        if ((s1 & ABNORMAL) != 0) {
            cancelIgnoringExceptions(t2);
            t1.reportException(s1);
        }
        else if (((s2 = t2.awaitDone(null, false, false, false, 0L)) & ABNORMAL) != 0)
            t2.reportException(s2);
    }

    /**
     * Forks the given tasks, returning when {@code isDone} holds for
     * each task or an (unchecked) exception is encountered, in which
     * case the exception is rethrown. If more than one task
     * encounters an exception, then this method throws any one of
     * these exceptions. If any task encounters an exception, others
     * may be cancelled. However, the execution status of individual
     * tasks is not guaranteed upon exceptional return. The status of
     * each task may be obtained using {@link #getException()} and
     * related methods to check if they have been cancelled, completed
     * normally or exceptionally, or left unprocessed.
     *
     * @param tasks the tasks
     * @throws NullPointerException if any task is null
     */
    public static void invokeAll(ForkJoinTask<?>... tasks) {
        Throwable ex = null;
        int last = tasks.length - 1;
        for (int i = last; i >= 0; --i) {
            ForkJoinTask<?> t;
            if ((t = tasks[i]) == null) {
                ex = new NullPointerException();
                break;
            }
            if (i == 0) {
                int s;
                if ((s = t.doExec()) >= 0)
                    s = t.awaitDone(null, true, false, false, 0L);
                if ((s & ABNORMAL) != 0)
                    ex = t.getException(s);
                break;
            }
            t.fork();
        }
        if (ex == null) {
            for (int i = 1; i <= last; ++i) {
                ForkJoinTask<?> t;
                if ((t = tasks[i]) != null) {
                    int s;
                    if ((s = t.status) >= 0)
                        s = t.awaitDone(null, false, false, false, 0L);
                    if ((s & ABNORMAL) != 0 && (ex = t.getException(s)) != null)
                        break;
                }
            }
        }
        if (ex != null) {
            for (int i = 1; i <= last; ++i)
                cancelIgnoringExceptions(tasks[i]);
            rethrow(ex);
        }
    }

    /**
     * Forks all tasks in the specified collection, returning when
     * {@code isDone} holds for each task or an (unchecked) exception
     * is encountered, in which case the exception is rethrown. If
     * more than one task encounters an exception, then this method
     * throws any one of these exceptions. If any task encounters an
     * exception, others may be cancelled. However, the execution
     * status of individual tasks is not guaranteed upon exceptional
     * return. The status of each task may be obtained using {@link
     * #getException()} and related methods to check if they have been
     * cancelled, completed normally or exceptionally, or left
     * unprocessed.
     *
     * @param tasks the collection of tasks
     * @param <T> the type of the values returned from the tasks
     * @return the tasks argument, to simplify usage
     * @throws NullPointerException if tasks or any element are null
     */
    public static <T extends ForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks) {
        if (!(tasks instanceof RandomAccess) || !(tasks instanceof List<?>)) {
            invokeAll(tasks.toArray(new ForkJoinTask<?>[0]));
            return tasks;
        }
        @SuppressWarnings("unchecked")
        List<? extends ForkJoinTask<?>> ts =
            (List<? extends ForkJoinTask<?>>) tasks;
        Throwable ex = null;
        int last = ts.size() - 1;  // nearly same as array version
        for (int i = last; i >= 0; --i) {
            ForkJoinTask<?> t;
            if ((t = ts.get(i)) == null) {
                ex = new NullPointerException();
                break;
            }
            if (i == 0) {
                int s;
                if ((s = t.doExec()) >= 0)
                    s = t.awaitDone(null, true, false, false, 0L);
                if ((s & ABNORMAL) != 0)
                    ex = t.getException(s);
                break;
            }
            t.fork();
        }
        if (ex == null) {
            for (int i = 1; i <= last; ++i) {
                ForkJoinTask<?> t;
                if ((t = ts.get(i)) != null) {
                    int s;
                    if ((s = t.status) >= 0)
                        s = t.awaitDone(null, false, false, false, 0L);
                    if ((s & ABNORMAL) != 0 && (ex = t.getException(s)) != null)
                        break;
                }
            }
        }
        if (ex != null) {
            for (int i = 1; i <= last; ++i)
                cancelIgnoringExceptions(ts.get(i));
            rethrow(ex);
        }
        return tasks;
    }

    /**
     * Attempts to cancel execution of this task. This attempt will
     * fail if the task has already completed or could not be
     * cancelled for some other reason. If successful, and this task
     * has not started when {@code cancel} is called, execution of
     * this task is suppressed. After this method returns
     * successfully, unless there is an intervening call to {@link
     * #reinitialize}, subsequent calls to {@link #isCancelled},
     * {@link #isDone}, and {@code cancel} will return {@code true}
     * and calls to {@link #join} and related methods will result in
     * {@code CancellationException}.
     *
     * <p>This method may be overridden in subclasses, but if so, must
     * still ensure that these properties hold. In particular, the
     * {@code cancel} method itself must not throw exceptions.
     *
     * <p>This method is designed to be invoked by <em>other</em>
     * tasks. To terminate the current task, you can just return or
     * throw an unchecked exception from its computation method, or
     * invoke {@link #completeExceptionally(Throwable)}.
     *
     * @param mayInterruptIfRunning this value has no effect in the
     * default implementation because interrupts are not used to
     * control cancellation.
     *
     * @return {@code true} if this task is now cancelled
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        return (trySetCancelled() & (ABNORMAL | THROWN)) == ABNORMAL;
    }

    public final boolean isDone() {
        return status < 0;
    }

    public final boolean isCancelled() {
        return (status & (ABNORMAL | THROWN)) == ABNORMAL;
    }

    /**
     * Returns {@code true} if this task threw an exception or was cancelled.
     *
     * @return {@code true} if this task threw an exception or was cancelled
     */
    public final boolean isCompletedAbnormally() {
        return (status & ABNORMAL) != 0;
    }

    /**
     * Returns {@code true} if this task completed without throwing an
     * exception and was not cancelled.
     *
     * @return {@code true} if this task completed without throwing an
     * exception and was not cancelled
     */
    public final boolean isCompletedNormally() {
        return (status & (DONE | ABNORMAL)) == DONE;
    }

    /**
     * Returns the exception thrown by the base computation, or a
     * {@code CancellationException} if cancelled, or {@code null} if
     * none or if the method has not yet completed.
     *
     * @return the exception, or {@code null} if none
     */
    public final Throwable getException() {
        return getException(status);
    }

    /**
     * Completes this task abnormally, and if not already aborted or
     * cancelled, causes it to throw the given exception upon
     * {@code join} and related operations. This method may be used
     * to induce exceptions in asynchronous tasks, or to force
     * completion of tasks that would not otherwise complete.  Its use
     * in other situations is discouraged.  This method is
     * overridable, but overridden versions must invoke {@code super}
     * implementation to maintain guarantees.
     *
     * @param ex the exception to throw. If this exception is not a
     * {@code RuntimeException} or {@code Error}, the actual exception
     * thrown will be a {@code RuntimeException} with cause {@code ex}.
     */
    public void completeExceptionally(Throwable ex) {
        trySetException((ex instanceof RuntimeException) ||
                        (ex instanceof Error) ? ex :
                        new RuntimeException(ex));
    }

    /**
     * Completes this task, and if not already aborted or cancelled,
     * returning the given value as the result of subsequent
     * invocations of {@code join} and related operations. This method
     * may be used to provide results for asynchronous tasks, or to
     * provide alternative handling for tasks that would not otherwise
     * complete normally. Its use in other situations is
     * discouraged. This method is overridable, but overridden
     * versions must invoke {@code super} implementation to maintain
     * guarantees.
     *
     * @param value the result value for this task
     */
    public void complete(V value) {
        try {
            setRawResult(value);
        } catch (Throwable rex) {
            trySetException(rex);
            return;
        }
        setDone();
    }

    /**
     * Completes this task normally without setting a value. The most
     * recent value established by {@link #setRawResult} (or {@code
     * null} by default) will be returned as the result of subsequent
     * invocations of {@code join} and related operations.
     *
     * @since 1.8
     */
    public final void quietlyComplete() {
        setDone();
    }

    /**
     * Waits if necessary for the computation to complete, and then
     * retrieves its result.
     *
     * @return the computed result
     * @throws CancellationException if the computation was cancelled
     * @throws ExecutionException if the computation threw an
     * exception
     * @throws InterruptedException if the current thread is not a
     * member of a ForkJoinPool and was interrupted while waiting
     */
    public final V get() throws InterruptedException, ExecutionException {
        int s = awaitDone(null, false, true, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    /**
     * Waits if necessary for at most the given time for the computation
     * to complete, and then retrieves its result, if available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the computed result
     * @throws CancellationException if the computation was cancelled
     * @throws ExecutionException if the computation threw an
     * exception
     * @throws InterruptedException if the current thread is not a
     * member of a ForkJoinPool and was interrupted while waiting
     * @throws TimeoutException if the wait timed out
     */
    public final V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        int s = awaitDone(null, false, true, true, nanos);
        if (s >= 0 || (s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    /**
     * Joins this task, without returning its result or throwing its
     * exception. This method may be useful when processing
     * collections of tasks when some have been cancelled or otherwise
     * known to have aborted.
     */
    public final void quietlyJoin() {
        if (status >= 0)
            awaitDone(null, false, false, false, 0L);
    }


    /**
     * Commences performing this task and awaits its completion if
     * necessary, without returning its result or throwing its
     * exception.
     */
    public final void quietlyInvoke() {
        if (doExec() >= 0)
            awaitDone(null, true, false, false, 0L);
    }

    // Versions of join/get for pool.invoke* methods that use external,
    // possibly-non-commonPool submits

    final void awaitPoolInvoke(ForkJoinPool pool) {
        awaitDone(pool, false, false, false, 0L);
    }
    final void awaitPoolInvoke(ForkJoinPool pool, long nanos) {
        awaitDone(pool, false, true, true, nanos);
    }
    final V joinForPoolInvoke(ForkJoinPool pool) {
        int s = awaitDone(pool, false, false, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportException(s);
        return getRawResult();
    }
    final V getForPoolInvoke(ForkJoinPool pool)
        throws InterruptedException, ExecutionException {
        int s = awaitDone(pool, false, true, false, 0L);
        if ((s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }
    final V getForPoolInvoke(ForkJoinPool pool, long nanos)
        throws InterruptedException, ExecutionException, TimeoutException {
        int s = awaitDone(pool, false, true, true, nanos);
        if (s >= 0 || (s & ABNORMAL) != 0)
            reportExecutionException(s);
        return getRawResult();
    }

    /**
     * Possibly executes tasks until the pool hosting the current task
     * {@linkplain ForkJoinPool#isQuiescent is quiescent}.  This
     * method may be of use in designs in which many tasks are forked,
     * but none are explicitly joined, instead executing them until
     * all are processed.
     */
    public static void helpQuiesce() {
        Thread t; ForkJoinWorkerThread w; ForkJoinPool p;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread &&
            (p = (w = (ForkJoinWorkerThread)t).pool) != null)
            p.helpQuiescePool(w.workQueue, Long.MAX_VALUE, false);
        else
            ForkJoinPool.common.externalHelpQuiescePool(Long.MAX_VALUE, false);
    }

    /**
     * Resets the internal bookkeeping state of this task, allowing a
     * subsequent {@code fork}. This method allows repeated reuse of
     * this task, but only if reuse occurs when this task has either
     * never been forked, or has been forked, then completed and all
     * outstanding joins of this task have also completed. Effects
     * under any other usage conditions are not guaranteed.
     * This method may be useful when executing
     * pre-constructed trees of subtasks in loops.
     *
     * <p>Upon completion of this method, {@code isDone()} reports
     * {@code false}, and {@code getException()} reports {@code
     * null}. However, the value returned by {@code getRawResult} is
     * unaffected. To clear this value, you can invoke {@code
     * setRawResult(null)}.
     */
    public void reinitialize() {
        aux = null;
        status = 0;
    }

    /**
     * Returns the pool hosting the current thread, or {@code null}
     * if the current thread is executing outside of any ForkJoinPool.
     *
     * <p>This method returns {@code null} if and only if {@link
     * #inForkJoinPool} returns {@code false}.
     *
     * @return the pool, or {@code null} if none
     */
    public static ForkJoinPool getPool() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) ?
                ((ForkJoinWorkerThread) t).pool : null);
    }

    /**
     * Returns {@code true} if the current thread is a {@link
     * ForkJoinWorkerThread} executing as a ForkJoinPool computation.
     *
     * @return {@code true} if the current thread is a {@link
     * ForkJoinWorkerThread} executing as a ForkJoinPool computation,
     * or {@code false} otherwise
     */
    public static boolean inForkJoinPool() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread;
    }

    /**
     * Tries to unschedule this task for execution. This method will
     * typically (but is not guaranteed to) succeed if this task is
     * the most recently forked task by the current thread, and has
     * not commenced executing in another thread.  This method may be
     * useful when arranging alternative local processing of tasks
     * that could have been, but were not, stolen.
     *
     * @return {@code true} if unforked
     */
    public boolean tryUnfork() {
        Thread t; ForkJoinPool.WorkQueue q;
        return ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            ? (q = ((ForkJoinWorkerThread)t).workQueue) != null
               && q.tryUnpush(this)
            : (q = ForkJoinPool.commonQueue()) != null
               && q.externalTryUnpush(this);
    }

    /**
     * Returns an estimate of the number of tasks that have been
     * forked by the current worker thread but not yet executed. This
     * value may be useful for heuristic decisions about whether to
     * fork other tasks.
     *
     * @return the number of tasks
     */
    public static int getQueuedTaskCount() {
        Thread t; ForkJoinPool.WorkQueue q;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            q = ((ForkJoinWorkerThread)t).workQueue;
        else
            q = ForkJoinPool.commonQueue();
        return (q == null) ? 0 : q.queueSize();
    }

    /**
     * Returns an estimate of how many more locally queued tasks are
     * held by the current worker thread than there are other worker
     * threads that might steal them, or zero if this thread is not
     * operating in a ForkJoinPool. This value may be useful for
     * heuristic decisions about whether to fork other tasks. In many
     * usages of ForkJoinTasks, at steady state, each worker should
     * aim to maintain a small constant surplus (for example, 3) of
     * tasks, and to process computations locally if this threshold is
     * exceeded.
     *
     * @return the surplus number of tasks, which may be negative
     */
    public static int getSurplusQueuedTaskCount() {
        return ForkJoinPool.getSurplusQueuedTaskCount();
    }

    // Extension methods

    /**
     * Returns the result that would be returned by {@link #join}, even
     * if this task completed abnormally, or {@code null} if this task
     * is not known to have been completed.  This method is designed
     * to aid debugging, as well as to support extensions. Its use in
     * any other context is discouraged.
     *
     * @return the result, or {@code null} if not completed
     */
    public abstract V getRawResult();

    /**
     * Forces the given value to be returned as a result.  This method
     * is designed to support extensions, and should not in general be
     * called otherwise.
     *
     * @param value the value
     */
    protected abstract void setRawResult(V value);

    /**
     * Immediately performs the base action of this task and returns
     * true if, upon return from this method, this task is guaranteed
     * to have completed. This method may return false otherwise, to
     * indicate that this task is not necessarily complete (or is not
     * known to be complete), for example in asynchronous actions that
     * require explicit invocations of completion methods. This method
     * may also throw an (unchecked) exception to indicate abnormal
     * exit. This method is designed to support extensions, and should
     * not in general be called otherwise.
     *
     * @return {@code true} if this task is known to have completed normally
     */
    protected abstract boolean exec();

    /**
     * Returns, but does not unschedule or execute, a task queued by
     * the current thread but not yet executed, if one is immediately
     * available. There is no guarantee that this task will actually
     * be polled or executed next. Conversely, this method may return
     * null even if a task exists but cannot be accessed without
     * contention with other threads.  This method is designed
     * primarily to support extensions, and is unlikely to be useful
     * otherwise.
     *
     * @return the next task, or {@code null} if none are available
     */
    protected static ForkJoinTask<?> peekNextLocalTask() {
        Thread t; ForkJoinPool.WorkQueue q;
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            q = ((ForkJoinWorkerThread)t).workQueue;
        else
            q = ForkJoinPool.commonQueue();
        return (q == null) ? null : q.peek();
    }

    /**
     * Unschedules and returns, without executing, the next task
     * queued by the current thread but not yet executed, if the
     * current thread is operating in a ForkJoinPool.  This method is
     * designed primarily to support extensions, and is unlikely to be
     * useful otherwise.
     *
     * @return the next task, or {@code null} if none are available
     */
    protected static ForkJoinTask<?> pollNextLocalTask() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) ?
                ((ForkJoinWorkerThread)t).workQueue.nextLocalTask() : null);
    }

    /**
     * If the current thread is operating in a ForkJoinPool,
     * unschedules and returns, without executing, the next task
     * queued by the current thread but not yet executed, if one is
     * available, or if not available, a task that was forked by some
     * other thread, if available. Availability may be transient, so a
     * {@code null} result does not necessarily imply quiescence of
     * the pool this task is operating in.  This method is designed
     * primarily to support extensions, and is unlikely to be useful
     * otherwise.
     *
     * @return a task, or {@code null} if none are available
     */
    protected static ForkJoinTask<?> pollTask() {
        Thread t; ForkJoinWorkerThread w;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) ?
                (w = (ForkJoinWorkerThread)t).pool.nextTaskFor(w.workQueue) :
                null);
    }

    /**
     * If the current thread is operating in a ForkJoinPool,
     * unschedules and returns, without executing, a task externally
     * submitted to the pool, if one is available. Availability may be
     * transient, so a {@code null} result does not necessarily imply
     * quiescence of the pool.  This method is designed primarily to
     * support extensions, and is unlikely to be useful otherwise.
     *
     * @return a task, or {@code null} if none are available
     * @since 9
     */
    protected static ForkJoinTask<?> pollSubmission() {
        Thread t;
        return (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) ?
                ((ForkJoinWorkerThread)t).pool.pollSubmission() : null);
    }

    // tag operations

    /**
     * Returns the tag for this task.
     *
     * @return the tag for this task
     * @since 1.8
     */
    public final short getForkJoinTaskTag() {
        return (short)status;
    }

    /**
     * Atomically sets the tag value for this task and returns the old value.
     *
     * @param newValue the new tag value
     * @return the previous value of the tag
     * @since 1.8
     */
    public final short setForkJoinTaskTag(short newValue) {
        for (int s;;) {
            if (casStatus(s = status, (s & ~SMASK) | (newValue & SMASK)))
                return (short)s;
        }
    }

    /**
     * Atomically conditionally sets the tag value for this task.
     * Among other applications, tags can be used as visit markers
     * in tasks operating on graphs, as in methods that check: {@code
     * if (task.compareAndSetForkJoinTaskTag((short)0, (short)1))}
     * before processing, otherwise exiting because the node has
     * already been visited.
     *
     * @param expect the expected tag value
     * @param update the new tag value
     * @return {@code true} if successful; i.e., the current value was
     * equal to {@code expect} and was changed to {@code update}.
     * @since 1.8
     */
    public final boolean compareAndSetForkJoinTaskTag(short expect, short update) {
        for (int s;;) {
            if ((short)(s = status) != expect)
                return false;
            if (casStatus(s, (s & ~SMASK) | (update & SMASK)))
                return true;
        }
    }

    /**
     * Adapter for Runnables. This implements RunnableFuture
     * to be compliant with AbstractExecutorService constraints
     * when used in ForkJoinPool.
     */
    static final class AdaptedRunnable<T> extends ForkJoinTask<T>
        implements RunnableFuture<T> {
        @SuppressWarnings("serial") // Conditionally serializable
        final Runnable runnable;
        @SuppressWarnings("serial") // Conditionally serializable
        T result;
        AdaptedRunnable(Runnable runnable, T result) {
            if (runnable == null) throw new NullPointerException();
            this.runnable = runnable;
            this.result = result; // OK to set this even before completion
        }
        public final T getRawResult() { return result; }
        public final void setRawResult(T v) { result = v; }
        public final boolean exec() { runnable.run(); return true; }
        public final void run() { invoke(); }
        public String toString() {
            return super.toString() + "[Wrapped task = " + runnable + "]";
        }
        private static final long serialVersionUID = 5232453952276885070L;
    }

    /**
     * Adapter for Runnables without results.
     */
    static final class AdaptedRunnableAction extends ForkJoinTask<Void>
        implements RunnableFuture<Void> {
        @SuppressWarnings("serial") // Conditionally serializable
        final Runnable runnable;
        AdaptedRunnableAction(Runnable runnable) {
            if (runnable == null) throw new NullPointerException();
            this.runnable = runnable;
        }
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void v) { }
        public final boolean exec() { runnable.run(); return true; }
        public final void run() { invoke(); }
        public String toString() {
            return super.toString() + "[Wrapped task = " + runnable + "]";
        }
        private static final long serialVersionUID = 5232453952276885070L;
    }

    /**
     * Adapter for Runnables in which failure forces worker exception.
     */
    static final class RunnableExecuteAction extends ForkJoinTask<Void> {
        @SuppressWarnings("serial") // Conditionally serializable
        final Runnable runnable;
        RunnableExecuteAction(Runnable runnable) {
            if (runnable == null) throw new NullPointerException();
            this.runnable = runnable;
        }
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void v) { }
        public final boolean exec() { runnable.run(); return true; }
        int trySetException(Throwable ex) { // if a handler, invoke it
            int s; Thread t; java.lang.Thread.UncaughtExceptionHandler h;
            if (isExceptionalStatus(s = trySetThrown(ex)) &&
                (h = ((t = Thread.currentThread()).
                      getUncaughtExceptionHandler())) != null) {
                try {
                    h.uncaughtException(t, ex);
                } catch (Throwable ignore) {
                }
            }
            return s;
        }
        private static final long serialVersionUID = 5232453952276885070L;
    }

    /**
     * Adapter for Callables.
     */
    static final class AdaptedCallable<T> extends ForkJoinTask<T>
        implements RunnableFuture<T> {
        @SuppressWarnings("serial") // Conditionally serializable
        final Callable<? extends T> callable;
        @SuppressWarnings("serial") // Conditionally serializable
        T result;
        AdaptedCallable(Callable<? extends T> callable) {
            if (callable == null) throw new NullPointerException();
            this.callable = callable;
        }
        public final T getRawResult() { return result; }
        public final void setRawResult(T v) { result = v; }
        public final boolean exec() {
            try {
                result = callable.call();
                return true;
            } catch (RuntimeException rex) {
                throw rex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        public final void run() { invoke(); }
        public String toString() {
            return super.toString() + "[Wrapped task = " + callable + "]";
        }
        private static final long serialVersionUID = 2838392045355241008L;
    }

    static final class AdaptedInterruptibleCallable<T> extends ForkJoinTask<T>
        implements RunnableFuture<T> {
        @SuppressWarnings("serial") // Conditionally serializable
        final Callable<? extends T> callable;
        @SuppressWarnings("serial") // Conditionally serializable
        transient volatile Thread runner;
        T result;
        AdaptedInterruptibleCallable(Callable<? extends T> callable) {
            if (callable == null) throw new NullPointerException();
            this.callable = callable;
        }
        public final T getRawResult() { return result; }
        public final void setRawResult(T v) { result = v; }
        public final boolean exec() {
            Thread.interrupted();
            runner = Thread.currentThread();
            try {
                if (!isDone()) // recheck
                    result = callable.call();
                return true;
            } catch (RuntimeException rex) {
                throw rex;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                runner = null;
                Thread.interrupted();
            }
        }
        public final void run() { invoke(); }
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
        public String toString() {
            return super.toString() + "[Wrapped task = " + callable + "]";
        }
        private static final long serialVersionUID = 2838392045355241008L;
    }

    /**
     * Returns a new {@code ForkJoinTask} that performs the {@code run}
     * method of the given {@code Runnable} as its action, and returns
     * a null result upon {@link #join}.
     *
     * @param runnable the runnable action
     * @return the task
     */
    public static ForkJoinTask<?> adapt(Runnable runnable) {
        return new AdaptedRunnableAction(runnable);
    }

    /**
     * Returns a new {@code ForkJoinTask} that performs the {@code run}
     * method of the given {@code Runnable} as its action, and returns
     * the given result upon {@link #join}.
     *
     * @param runnable the runnable action
     * @param result the result upon completion
     * @param <T> the type of the result
     * @return the task
     */
    public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new AdaptedRunnable<T>(runnable, result);
    }

    /**
     * Returns a new {@code ForkJoinTask} that performs the {@code call}
     * method of the given {@code Callable} as its action, and returns
     * its result upon {@link #join}, translating any checked exceptions
     * encountered into {@code RuntimeException}.
     *
     * @param callable the callable action
     * @param <T> the type of the callable's result
     * @return the task
     */
    public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new AdaptedCallable<T>(callable);
    }

    /**
     * Returns a new {@code ForkJoinTask} that performs the {@code call}
     * method of the given {@code Callable} as its action, and returns
     * its result upon {@link #join}, translating any checked exceptions
     * encountered into {@code RuntimeException}.  Additionally,
     * invocations of {@code cancel} with {@code mayInterruptIfRunning
     * true} will attempt to interrupt the thread performing the task.
     *
     * @param callable the callable action
     * @param <T> the type of the callable's result
     * @return the task
     *
     * @since 17
     */
    // adaptInterruptible deferred to its own independent change
    // https://bugs.openjdk.java.net/browse/JDK-8246587
    /* TODO: public */ private static <T> ForkJoinTask<T> adaptInterruptible(Callable<? extends T> callable) {
        return new AdaptedInterruptibleCallable<T>(callable);
    }

    // Serialization support

    private static final long serialVersionUID = -7721805057305804111L;

    /**
     * Saves this task to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData the current run status and the exception thrown
     * during execution, or {@code null} if none
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        Aux a;
        s.defaultWriteObject();
        s.writeObject((a = aux) == null ? null : a.ex);
    }

    /**
     * Reconstitutes this task from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        Object ex = s.readObject();
        if (ex != null)
            trySetThrown((Throwable)ex);
    }

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATUS = l.findVarHandle(ForkJoinTask.class, "status", int.class);
            AUX = l.findVarHandle(ForkJoinTask.class, "aux", Aux.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

}
