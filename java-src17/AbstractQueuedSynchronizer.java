package java.util.concurrent.locks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import jdk.internal.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.
 * 提供一个框架，用于实现依赖先进先出（FIFO）等待队列的阻塞锁和相关同步器（信号量、事件等）。
 * 
 * This class is designed to be a useful basis for most kinds of
 * synchronizers that rely on a single atomic {@code int} value to represent state.
 * 基于单个原子的int类型的state状态代表锁的状态，为大多数类型的同步器的有用基础，
 * 
 * Subclasses must define the protected methods that change this state,
 * and which define what that state means in terms of this object being acquired
 * or released.
 * 子类必须定义更改该状态state的受保护方法。并且定义该状态state在获取或释放该对象方面的含义。
 * 
 * Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * 考虑到这些，类中的其他方法执行所有排队和阻塞机制。
 * 子类可以维护其他状态字段，但只有使用方法getState、setState和compareAndSetState操作的
 * 原子更新的int类型的state才会相对于同步进行跟踪。
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * 子类应定义为非公共内部帮助类，用于实现其封闭类的同步属性。
 * 类AbstractQueuedSynchronizer没有实现任何同步接口。
 * 而是它定义诸如acquireInterruptibly之类的方法，
 * 具体锁和相关同步器可以适当地调用这些方法来实现它们的公共方法。
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 *此类支持默认的独占模式和共享模式之一或两者。
 *在独占模式下获取时，其他线程尝试的获取无法成功。
 *多个线程获取共享模式可能（但不一定）成功。通常，实现子类只支持其中一种模式，
 *但两者都可以发挥作用。
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread,
 * method {@link #release} invoked with the current {@link #getState}
 * value fully releases this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.
 *
 * No {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * 此类定义了嵌套的ConditionObject类，该类可由支持独占模式的子类用作Condition实现，
 * 其中方法isHeldExclusive报告同步是否相对于当前线程独占，
 * 
 * release方法调用getState获取getState当前值，并释放此对象
 * acquire方法设置状态state的值。
 * 最终relase重新存储将该对象，即恢复到其先前获取的状态。
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h2>Usage</h2>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li>{@link #tryAcquire}
 * <li>{@link #tryRelease}
 * <li>{@link #tryAcquireShared}
 * <li>{@link #tryReleaseShared}
 * <li>{@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 *｛AbstractOwnableSynchronizer中继承的方法有助于跟踪拥有独占同步器的线程。
 * 我们鼓励您使用它们这使得监视和诊断工具能够帮助用户确定哪些线程持有锁。
 * 
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 *即使此类基于内部FIFO队列，它也不会自动执行FIFO获取策略。独占同步的核心形式如下：
 * <pre>
 * <em>Acquire:</em>
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * <em>Release:</em>
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 * 共享模式类似，但可能涉及级联信号
 * 
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.
 * 
 * 由于获取acquire方法中的检查是在排队enqueuing之前调用的，
 * 因此新的正在获取的线程可能会被队列的其它头的阻塞和排队的线程相冲突
 * （barge:驳停; 冲撞; 乱闯）。
 * 
 * However, you can, if desired, define {@code tryAcquire} and/or
 * {@code tryAcquireShared}
 * to disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * 
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 *
 * 但是，如果需要，您可以定义｛@code tryAcquire｝和/或｛@code-tryAcquireShared｝，
 * 来阻止这种冲突。
 * 具体的方案是：通过内部调用一种或多种检查方法，从而提供公平FIFO采集顺序，来解决这种冲动。
 * 特别是，使用hasQueuedPredessors方法一种专门为公平fair同步器设计的方法返回true，
 * 则大多数公平同步器可以定义tryAcquire中hasQueuedPredecessors返回false。
 *
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * 默认barging冲撞（也称为贪婪放弃和车队规避）策略的吞吐量和可扩展性通常最高
 *
 * 
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.
 *
 * 虽然这不能保证是公平的或无饥饿的，
 * 但允许较早排队的线程在较晚排队的线程之前进行重新保持，
 * 并且每次重新保持都有机会成功应对传入线程。此外，acquires时不旋转；
 * 在通常意义上，他们可以在阻塞之前执行{@code tryAcquire}的多次调用，
 * 而这种tryAcquire调用穿插（带有）其他计算。
 * 
 * This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * 当独占同步仅短暂保持时，这提供了旋转的大部分好处，而当不保持时，则没有承担大部分责任。
 * 如果需要，您可以通过在调用之前前面先使用调用acquire来增强这一点，
 * 即acquire方法是获取具有“快速路径”检查的方法，
 * 可能会预检查hasContended和/或hasQueuedThreads，
 * 以仅在同步器可能不被争用的情况下进行预检查。
 *
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue.
 *
 * When this does not suffice, you can build synchronizers
 * from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * 此类为同步提供了一个高效且可扩展的基础，
 * 部分原因是将其使用范围专门化为可以依赖int类型的状态、acquire获取和
 * release释放参数以及内部FIFO等待队列的同步器。
 *
 * 可以通过低级方法实现自身代码：
 * 可以使用java.util.concurrent.atomic atomic类、
 * java.util.Queue
 * LockSuppor
 *
 * <h2>Usage Examples</h2>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes some instrumentation methods:
 *
 * <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {//设置持有锁
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (!isHeldExclusively())
 *         throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Reports whether in locked state
 *     public boolean isLocked() {
 *       return getState() != 0; //0为没有持有锁
 *     }
 *
 *     public boolean isHeldExclusively() {
 *       // a data race, but safe due to out-of-thin-air guarantees
 *       // 存在数据竞争，但是安全 凭空而来的保证而安全
 *       return getExclusiveOwnerThread() == Thread.currentThread();
 *     }
 *
 *     // Provides a Condition
 *     public Condition newCondition() {
 *       return new ConditionObject();
 *     }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()              { sync.acquire(1); }
 *   public boolean tryLock()        { return sync.tryAcquire(1); }
 *   public void unlock()            { sync.release(1); }
 *   public Condition newCondition() { return sync.newCondition(); }
 *   public boolean isLocked()       { return sync.isLocked(); }
 *   public boolean isHeldByCurrentThread() {
 *     return sync.isHeldExclusively();
 *   }
 *   public boolean hasQueuedThreads() {
 *     return sync.hasQueuedThreads();
 *   }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 * <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     //当状态不是0，即持有锁时，不是零则持有Latch
 *     boolean isSignalled() { return getState() != 0; } 
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1; //
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /*
     * Overview.
     *
     * The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers by
     * including explicit ("prev" and "next") links plus a "status"
     * field that allow nodes to signal successors when releasing
     * locks, and handle cancellation due to interrupts and timeouts.
     * The status field includes bits that track whether a thread
     * needs a signal (using LockSupport.unpark). Despite these
     * additions, we maintain most CLH locality properties.
     *
     * 等待队列是“CLH” 锁定队列的变体。
     * CLH锁通常用于自旋锁spinlocks。我们通过包括显式（“prev”和“next”）
     * 指针和一个“status”字段来实现阻塞同步器，这种结构（status）
     * 使得节点在释放锁时向后续节点发送信号，并处理由于中断和超时而导致的取消。
     * 状态字段包括跟踪线程是否需要信号的位（使用LockSupport.unpark）。
     * 尽管有这些添加，我们仍保留了大多数CLH局部属性。
     *
     * To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you set the head field, so the next eligible
     * waiter becomes first.
     *
     * 要进入CLH锁的队列，可以将其原子性地拼接为新的尾部。
     * 要退出队列，您可以设置head字段，这样下一个符合条件的等候着将成为第一个。
     *  +------+  prev +-------+       +------+
     *  | head | <---- | first | <---- | tail |
     *  +------+       +-------+       +------+
     *
     * Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple point of demarcation
     * from unqueued to queued.
     *
     * 插入CLH队列只需要在“tail”上进行一次原子操作，
     * 因此从未排队到排排进队列有一个简单的分界点。
     *
     *
     * The "next" link of the predecessor is set by the enqueuing thread
     * after successful CAS.
     *
     * 由CAS成功之后排队线程设置前一个线程（前一个任务）的“next”指针
     *
     * Even though non-atomic, this suffices to ensure that any blocked
     * thread is signalled by a predecessor when eligible
     * (although in the case of cancellation, possibly with the assistance of
     * a signal in method cleanQueue).
     *
     * 即使是非原子的，这也足以确保任何被阻塞的线程在符合条件时都由前置线程发出信号
     *（尽管在取消的情况下，可能借助方法cleanQueue中的信号）。
     * 信令部分基于类似Dekker的方案。
     * 
     * Signalling is based in part on a Dekker-like scheme
     *
     * in which the to-be waiting thread indicates
     * WAITING status, then retries acquiring, and then rechecks
     * status before blocking. The signaller atomically clears WAITING
     * status when unparking.
     *
     * 待等待线程指示等待状态WAITING status，然后重试获取acquiring，
     * 然后在阻塞前重新检查状态status。信号器在unparking（取消阻塞）时
     * 自动清除WAITING（等待）状态。
     * 
     * Dequeuing on acquire involves detaching (nulling) a node's
     * "prev" node and then updating the "head".
     * acquire的出队涉及到分离（置零）node节点的“prev”节点，然后更新“head”。
     * 
     * Other threads check if a node is or was dequeued
     * by checking "prev" rather than head.
     * 其他线程通过检查“prev”而不是head来检查节点是否已出列。
     * 
     * We enforce the nulling then setting order by spin-waiting
     * if necessary.
     * 如有必要，我们通过旋转等待spin-waiting来强制nulling然后设置顺序。
     * 
     * Because of this, the lock algorithm is not itself
     * strictly "lock-free" because an acquiring thread may need to
     * wait for a previous acquire to make progress.
     *
     * When used with
     * exclusive locks, such progress is required anyway.
     *
     * However
     * Shared mode may (uncommonly) require a spin-wait before
     * setting head field to ensure proper propagation. (Historical
     * note: This allows some simplifications and efficiencies
     * compared to previous versions of this class.)
     * 
     *
     * 正因为如此，锁定算法本身并不是严格的“lock-free”，
     * 因为获取acquire线程可能需要等待先前线程的获取acquire才能取得进展。
     *
     * A node's predecessor can change due to cancellation while it is
     * waiting, until the node is first in queue, at which point it
     * cannot change.
     * 
     * 节点的前一个任务（线程）在等待时可能会由于取消而更改，
     * 直到该节点位于队列中的第一个节点，
     * 此时它无法更改。
     * 
     * The acquire methods cope with this by rechecking
     * "prev" before waiting.
     * 
     * 获取acquire方法在等待之前通过重新检查“prev”来拷贝。
     *
     * 
     * The prev and next fields are modified
     * only via CAS by cancelled nodes in method cleanQueue.
     *
     * 方法cleanQueue中的取消节点cancelled nodes仅通过CAS修改prev和next字段。
     *
     * The unsplice strategy is reminiscent of Michael-Scott queues in
     * that after a successful CAS to prev field, other threads help
     * fix next fields.
     *
     * prev和next字段的修改仅仅在cleanQueue，通过CAS操作进行
     * 
     * 对prev字段进行cas操作成功之后，其它线程帮助修订next字段。
     *
     * 
     * Because cancellation often occurs in bunches
     * that complicate decisions about necessary signals,
     * each call to cleanQueue traverses the queue until a clean sweep.
     *
     * Nodes that become relinked as first are unconditionally unparked
     * 作为第一个重新链接的节点将释放阻塞（unconditionally unparked）
     * 
     * (sometimes unnecessarily, but those cases are not worth avoiding).
     *
     * 因为取消通常发生在bunches（束、大并发），需要一个复杂的决策：
     * 有关必要信号signals在bunches（束、大并发），即unpark操作发出通知
     * 
     * 所以每次对cleanQueue的调用都会遍历队列，直到进行干净的扫描。
     * 作为第一个重新组链（上链）的节点将无条件地unparked
     *（有时是不必要的，但这些情况不值得避免）。
     *
     * A thread may try to acquire if it is first (frontmost) in the
     * queue, and sometimes before.
     * 如果线程是队列的第一个节点，则可以尝试执行acquire方法
     * 
     * Being first does not guarantee success;
     * 第一次不保证成功  
     *
     * it only gives the right to contend.
     * 这仅仅是给予竞争的权力
     *
     * 
     * We balance throughput, overhead, and fairness
     * by allowing incoming threads to "barge" and
     * acquire the synchronizer while in the process of
     * enqueuing, in which case an awakened first thread may need to
     * rewait.  To counteract possible repeated unlucky rewaits, we
     * exponentially increase retries (up to 256) to acquire each time
     * a thread is unparked. Except in this case, AQS locks do not
     * spin; they instead interleave attempts to acquire with
     * bookkeeping steps. (Users who want spinlocks can use
     * tryAcquire.)
     *
     * 我们权衡吞吐量、负载和公平使得即将进入的线程进行博弈进而获得同步器。
     * 而当在出队列的线程，唤醒第一个线程，但可能需要在此等待
     * 
     *
     * To improve garbage collectibility, fields of nodes not yet on
     * list are null. (It is not rare to create and then throw away a
     * node without using it.) Fields of nodes coming off the list are
     * nulled out as soon as possible. This accentuates the challenge
     * of externally determining the first waiting thread (as in
     * method getFirstQueuedThread). This sometimes requires the
     * fallback of traversing backwards from the atomically updated
     * "tail" when fields appear null. (This is never needed in the
     * process of signalling though.)
     *
     * CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * CLH队列需要一个伪头节点header才能启动。
     * 但我们不会在构造方法中创造它们，
     * 因为如果从来没有数据竞争，那将是徒劳的。
     * 相反的，构建节点，头head和尾tail设置是第一次数据竞争。
     *
     * Shared mode operations differ from Exclusive in that an acquire
     * signals the next waiter to try to acquire if it is also
     * Shared. The tryAcquireShared API allows users to indicate the
     * degree of propagation, but in most applications, it is more
     * efficient to ignore this, allowing the successor to try
     * acquiring in any case.
     *
     * Threads waiting on Conditions use nodes with an additional
     * link to maintain the (FIFO) list of conditions. Conditions only
     * need to link nodes in simple (non-concurrent) linked queues
     * because they are only accessed when exclusively held.
     * 
     * 线程在Conditions上等待。Conditions使用了其它的指针来维护条件的FIFO队列。
     * Conditions只能用于排他持有。
     * 
     * Upon await, a node is inserted into a condition queue.  Upon signal,
     * the node is enqueued on the main queue.  A special status field
     * value is used to track and atomically trigger this.
     * 
     * await，插入一个条件队列
     * signal，出主队列，status字段触发
     * 
     * Accesses to fields head, tail, and state use full Volatile
     * mode, along with CAS.
     *
     * head, tail, state字段 使用full Volatile模型，支持CAS
     *
     * ----------------------------------------------------
     * Node fields status, prev and next also do so
     * while threads may be signallable, but sometimes use weaker
     * modes otherwise.
     * 
     * 节点Node的status、prev和next 也是使用full Volatile的CAS模型
     * 因此线程可以发送信息，但是有时实现weaker模型
     *
     * Accesses to field "waiter" (the thread to be
     * signalled) are always sandwiched between other atomic accesses
     * so are used in Plain mode. We use jdk.internal Unsafe versions
     * of atomic access methods rather than VarHandles to avoid
     * potential VM bootstrap issues.
     * 
     * 访问waiter字段（线程可以发送信息）总是在其他原子访问之间，因此使用一般模型
     * 
     * Most of the above is performed by primary internal method
     * acquire, that is invoked in some way by all exported acquire
     * methods.  (It is usually easy for compilers to optimize
     * call-site specializations when heavily used.)
     *
     * There are several arbitrary decisions about when and how to
     * check interrupts in both acquire and await before and/or after
     * blocking. The decisions are less arbitrary in implementation
     * updates because some users appear to rely on original behaviors
     * in ways that are racy and so (rarely) wrong in general but hard
     * to justify changing.
     *
     * Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */

    // Node status bits, also used as argument and return values
    static final int WAITING   = 1;          // must be 1
    static final int CANCELLED = 0x80000000; // must be negative
    static final int COND      = 2;          // in a condition wait

    /** CLH Nodes */
    abstract static class Node {
        // initially attached via casTail
        volatile Node prev;       
        // visibly nonnull when signallable
        volatile Node next;
         // visibly nonnull when enqueued
        //入队非零
        Thread waiter;           
        
        // written by owner, atomic bit ops by others
        // 由所有者写入，原子位操作由其他人编写
        volatile int status;      

        // methods for atomic operations
        
        // for cleanQueue
        final boolean casPrev(Node c, Node v) {  
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }
        
        // for cleanQueue
        final boolean casNext(Node c, Node v) {  
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }
        
        // for signalling
        // status 更新
        final int getAndUnsetStatus(int v) {     
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }
        
        // for off-queue assignment
        // 用于队列外分配
        final void setPrevRelaxed(Node p) {      
            U.putReference(this, PREV, p);
        }
        
        // for off-queue assignment
        final void setStatusRelaxed(int s) {     
            U.putInt(this, STATUS, s);
        }
        
        // for reducing unneeded signals
        // 减少不必要的内存信号发送
        final void clearStatus() {               
            U.putIntOpaque(this, STATUS, 0);
        }

        private static final long STATUS
            = U.objectFieldOffset(Node.class, "status");
        private static final long NEXT
            = U.objectFieldOffset(Node.class, "next");
        private static final long PREV
            = U.objectFieldOffset(Node.class, "prev");
    }

    // Concrete classes tagged by type
    static final class ExclusiveNode extends Node { }
    static final class SharedNode extends Node { }

    static final class ConditionNode extends Node
        implements ForkJoinPool.ManagedBlocker {
         // link to next waiting node    
        ConditionNode nextWaiter;           

        /**
         * Allows Conditions to be used in ForkJoinPools without
         * risking fixed pool exhaustion. This is usable only for
         * untimed Condition waits, not timed versions.
         *
         * 允许在ForkJoinPools中使用Conditions，
         * 而不会有固定池耗尽的风险。
         * 这仅适用于无计时条件等待，而不适用于计时版本。
         */
        public final boolean isReleasable() {
            return status <= 1 ||
            Thread.currentThread().isInterrupted();
        }

        public final boolean block() {
            while (!isReleasable()) LockSupport.park();
            return true;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.
     * 等待队列的头，延迟初始化。
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue.
     * After initialization, modified only via casTail.
     *
     * 等待队列的尾部。初始化后，仅通过casTail进行修改。
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     *
     * 返回同步状态state的当前值。此操作具有｛volatile｝读取的内存语义。
     * 
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     *
     * 设置同步状态state的值。此操作具有volatil写入的内存语义。
     * 
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * 如果当前状态值等于预期值，则原子化地将同步状态设置为给定的更新值。
     * 此操作具有｛@code volatile｝读写的内存语义。
     * 
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful.
     *  False return indicates that the actual
     *  value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        return U.compareAndSetInt(this, STATE, expect, update);
    }

    // Queuing utilities

    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /** tries once to CAS a new dummy node for head */
    private void tryInitializeHead() {
        Node h = new ExclusiveNode();
        if (U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * Enqueues the node unless null.
     * (Currently used only for ConditionNodes;
     * other cases are interleaved with acquires.)
     */
    final void enqueue(Node node) {
        if (node != null) {
            for (;;) {
                Node t = tail;
                // avoid unnecessary fence
                node.setPrevRelaxed(t);
                // initialize
                if (t == null)                 
                    tryInitializeHead();
                else if (casTail(t, node)) {
                    t.next = node;
                    // wake up to clean link
                    if (t.status < 0)          
                        LockSupport.unpark(node.waiter);
                    break;
                }
            }
        }
    }

    /** Returns true if node is found in traversal from tail */
    final boolean isEnqueued(Node node) {
        for (Node t = tail; t != null; t = t.prev)
            if (t == node)
                return true;
        return false;
    }

    /**
     * Wakes up the successor of given node, if one exists, and unsets its
     * WAITING status to avoid park race. This may fail to wake up an
     * eligible thread when one or more have been cancelled, but
     * cancelAcquire ensures liveness.
     *
     * 如果有后续节点，唤醒给定节点的后续节点，并unsets其等待状态以避免阻塞park竞赛。
     * 当一个或多个线程被取消时，这可能无法唤醒符合条件的线程，但cancelAcquire可确保在线性。
     */
    private static void signalNext(Node h) {
        Node s;
        if (h != null &&
            (s = h.next) != null &&
            s.status != 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    /** Wakes up the given node if in shared mode */
    private static void signalNextIfShared(Node h) {
        Node s;
        if (h != null &&
            (s = h.next) != null &&
            (s instanceof SharedNode) &&
            s.status != 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    /**
     * Main acquire method, invoked by all exported acquire methods.
     *
     * @param node null unless a reacquiring Condition
     *       仅仅ConditionObject中传入非空
     * @param arg the acquire argument
     * @param shared true if shared mode else exclusive
     * @param interruptible if abort and return negative on interrupt
     * @param timed if true use timed waits
     * @param time if timed, the System.nanoTime value to timeout
     * @return positive if acquired, 0 if timed out, negative if interrupted
     */
    final int acquire(Node node, int arg, boolean shared,
                      boolean interruptible, boolean timed, long time) {
        Thread current = Thread.currentThread();
        // retries upon unpark of first thread
        byte spins = 0, postSpins = 0;   
        boolean interrupted = false, first = false;
        // predecessor of node when enqueued
        Node pred = null;                

        /*
         * Repeatedly:
         *  Check if node now first
         *  检查节点是否是第一个节点
         *    if so, ensure head stable, else ensure valid predecessor
         *    如果是第一个节点，则确保头节点稳定，否则确保前面线程（任务predecessor）合法
         *  if node is first or not yet enqueued, try acquiring
         *  如果节点是第一个，或者没有入队，则需要执行重新获取acquiring状态state信号
         *  else if node not yet created, create it
         *  否则如果节点还没有建立，则建立它
         *  else if not yet enqueued, try once to enqueue
         *  否则任然没有入队，则试着第一次入队
         *  else if woken from park, retry (up to postSpins times)
         *  否则如果阻塞，则从park中唤醒
         *  else if WAITING status not set, set and retry
         *  否则状态为WAITING，则重试
         *  else park and clear WAITING status, and check cancellation
         *  否则，发送park并清理status
         */

        for (;;) {//入参node，在不是条件锁，传入为null
            //第一段，对CN处理，并设置first值
            if (!first && //不是第一个节点，first:false
                (pred = (node == null) ? //不是CN则，node:null
                                //node的前面prev节点
                         null : node.prev) != null && 
                !(first = (head == pred)) //first赋值仅此位置
                ) {//不是第一个节点，CN有前面的节点，头节点对象不是pred
                if (pred.status < 0) {//前面节点状态
                    // predecessor cancelled
                    cleanQueue();           
                    continue;
                } else if (pred.prev == null) {
                    // ensure serialization
                    Thread.onSpinWait();    
                    continue;
                }
            }
            
            //第二个逻辑，first是第一节点，前面节点pred
            if (first || pred == null) {
                boolean acquired;
                try {
                    //如果已经设置则大于0
                    if (shared)//共享，如CountDownLatch                        
                        acquired = (tryAcquireShared(arg) >= 0);
                    else //排他，如ReentrantLock
                        acquired = tryAcquire(arg);
                } catch (Throwable ex) {
                    cancelAcquire(node, interrupted, false);
                    throw ex;
                }
                //获得锁，tryAcquireShared和tryAcquire由具体工具类
                //CountDownLatch和ReentrantLock具体实现
                //当获取锁，即state设置之后，大于零，或者返回true
                
                //ReentrantLock的tryAcquire，
                //当前线程原子设置之后statet返回true，在执行lock时，返回
                //其它线程则返回false
                
                //CountDownLatch当状态值为0是返回1，不为0时返回-1
                if (acquired) {
                    if (first) {//
                        node.prev = null;
                        head = node;
                        pred.next = null;
                        node.waiter = null;
                        //获得锁，当前线程（任务）则执行unpark
                        //当是排他的，如lock时，则当前线程中断，让出cpu
                        if (shared)
                            signalNextIfShared(node);
                        if (interrupted)
                            current.interrupt();
                    }
                    return 1;
                }
            }
            
            //第三个逻辑
            // allocate; retry before enqueue
            // 分配，入队之前重试
            if (node == null) {//当不是CN时，则创建节点 
                if (shared)
                    node = new SharedNode();
                else
                    node = new ExclusiveNode();
            }
            // try to enqueue
            // 入队
            else if (pred == null) { //1.设置node
                //创建的节点（传入的为CN）
                //设置等候任务Thread，即当前线程
                node.waiter = current;
                //尾部节点，在站内设置
                Node t = tail;
                // avoid unnecessary fence
                // 设置节点的前节点，不需要其它线程知道，设置
                node.setPrevRelaxed(t);         
                if (t == null)//创建新节点，并原子设置到header变量
                    tryInitializeHead(); //tail=header,即两者都指向新新建节点
                else if (!casTail(t, node)) //原子设置尾部节点为node，
                    node.setPrevRelaxed(null); // back out，t为尾部，将其替换为node
                else //当前线程执行成功，则node节点入链
                    t.next = node; //如果设置成功，则t的下一节点指针next设置为node
            } else if (first && spins != 0) {//2.是第一个时
                // reduce unfairness on rewaits
                --spins;                        
                Thread.onSpinWait();
            } else if (node.status == 0) {//3.设置状态
                // enable signal and recheck
                node.status = WAITING;          
            } else {
                //执行阻塞操作
                long nanos;
                spins = postSpins = (byte)((postSpins << 1) | 1);
                if (!timed)
                    LockSupport.park(this);
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    break;
                node.clearStatus();
                //中断
                if ((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        //清除Acquire
        return cancelAcquire(node, interrupted, interruptible);
    }

    /**
     * Possibly repeatedly traverses from tail,
     * unsplicing cancelled nodes until none are found.
     * 由acquire调用
     * 可能会从尾部重复遍历，取消复制已取消的节点，直到找不到为止。
     * 
     * Unparks nodes that may have been
     * relinked to be next eligible acquirer.
     *
     * 可能会从尾部重复遍历，取消没有拼接复制节点，直到找不到为止。
     *
     * Unparks节点 可能已重新链接为下一个符合条件的acquirer节点。
     */
    private void cleanQueue() {
        for (;;) {  // restart point
            // (p, q, s) triples 三倍
            for (Node q = tail, s = null, p, n;;) { 
                if (q == null || (p = q.prev) == null)
                    // end of list 节点链尾
                    return;                      
                if (s == null ? tail != q : (s.prev != q || s.status < 0))
                    // inconsistent不一致
                    break;
                // cancelled
                if (q.status < 0) {
                    //status被CN设置
                    //status被cancelAcquire设置为CANCELLED
                    if ((s == null ? casTail(q, p) :
                                    s.casPrev(q, p)) &&
                        q.prev == p) {
                        
                        // OK if fails
                        p.casNext(q, s);
                        //没有prev发unpark
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                if ((n = p.next) != q) { // help finish
                    if (n != null && q.prev == p) {
                        p.casNext(n, q);
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                s = q;
                q = q.prev;
            }
        }
    }

    /**
     * Cancels an ongoing attempt to acquire.
     * 被acquire调用
     * @param node the node (may be null if cancelled before enqueuing)
     * @param interrupted true if thread interrupted
     * @param interruptible if should report interruption vs reset
     */
    private int cancelAcquire(Node node, boolean interrupted,
                              boolean interruptible) {
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            if (node.prev != null)
                cleanQueue();
        }
        if (interrupted) {
            if (interruptible)
                return CANCELLED;
            else
                Thread.currentThread().interrupt();
        }
        return 0;
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a {@link ConditionObject} method.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.
     * Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg))
            acquire(null, arg, false, false, false, 0L);
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
        throws InterruptedException {
        if (Thread.interrupted() ||
            (!tryAcquire(arg) && acquire(null, arg, false, true, false, 0L) < 0))
            throw new InterruptedException();
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
        throws InterruptedException {
        if (!Thread.interrupted()) {
            if (tryAcquire(arg))
                return true;
            if (nanosTimeout <= 0L)
                return false;
            int stat = acquire(null, arg, false, true, true,
                               System.nanoTime() + nanosTimeout);
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();
    }

    /**
     * Releases in exclusive mode.
     * Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            signalNext(head);
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            acquire(null, arg, true, false, false, 0L);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
        throws InterruptedException {
        if (Thread.interrupted() ||
            (tryAcquireShared(arg) < 0 &&
             acquire(null, arg, true, true, false, 0L) < 0))
            throw new InterruptedException();
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (!Thread.interrupted()) {
            if (tryAcquireShared(arg) >= 0)
                return true;
            if (nanosTimeout <= 0L)
                return false;
            int stat = acquire(null, arg, true, true, true,
                               System.nanoTime() + nanosTimeout);
            if (stat > 0)
                return true;
            if (stat == 0)
                return false;
        }
        throw new InterruptedException();
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            signalNext(head);
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        for (Node p = tail, h = head;
             p != h && p != null; p = p.prev)
            if (p.status >= 0)
                return true;
        return false;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is, if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        Thread first = null, w; Node h, s;
        if ((h = head) != null && ((s = h.next) == null ||
                                   (first = s.waiter) == null ||
                                   s.prev == null)) {
            // traverse from tail on stale reads
            for (Node p = tail, q;
                 p != null && (q = p.prev) != null;
                 p = q)
                if ((w = p.waiter) != null)
                    first = w;
        }
        return first;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.waiter == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null
                && (s = h.next)  != null
                && !(s instanceof SharedNode)
                && s.waiter != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     * <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread()
     *   && hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer.html#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     * <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        Thread first = null; Node h, s;
        if ((h = head) != null &&
             ((s = h.next) == null ||
             (first = s.waiter) == null ||
             s.prev == null))
            // retry via getFirstQueuedThread
            first = getFirstQueuedThread(); 
        return first != null &&
               first != Thread.currentThread();
    }

    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail;
             p != null;
             p = p.prev) {
            if (p.waiter != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail;
             p != null;
             p = p.prev) {
            Thread t = p.waiter;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!(p instanceof SharedNode)) {
                Thread t = p.waiter;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p instanceof SharedNode) {
                Thread t = p.waiter;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        return super.toString()
            + "[State = " + getState() + ", "
            + (hasQueuedThreads() ? "non" : "") + "empty queue]";
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring system
     * state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    // ----------------以下是ConditionObject--------------------------
    /**
     * Condition implementation for a {@link AbstractQueuedSynchronizer}
     * serving as the basis of a {@link Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */
        private transient ConditionNode firstWaiter;
        /** Last node of condition queue. */
        private transient ConditionNode lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Signalling methods

        /**
         * Removes and transfers one or all waiters to sync queue.
         */
        private void doSignal(ConditionNode first, boolean all) {
            while (first != null) {
                ConditionNode next = first.nextWaiter;
                if ((firstWaiter = next) == null)
                    lastWaiter = null;
                if ((first.getAndUnsetStatus(COND) & COND) != 0) {
                    enqueue(first);
                    if (!all)
                        break;
                }
                first = next;
            }
        }

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            ConditionNode first = firstWaiter;
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            if (first != null)
                doSignal(first, false);
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            ConditionNode first = firstWaiter;
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            if (first != null)
                doSignal(first, true);
        }

        // Waiting methods

        /**
         * Adds node to condition list and releases lock.
         *
         * @param node the node
         * @return savedState to reacquire after wait
         */
        private int enableWait(ConditionNode node) {
            if (isHeldExclusively()) {
                node.waiter = Thread.currentThread();
                node.setStatusRelaxed(COND | WAITING);
                ConditionNode last = lastWaiter;
                if (last == null)
                    firstWaiter = node;
                else
                    last.nextWaiter = node;
                lastWaiter = node;
                int savedState = getState();
                if (release(savedState))
                    return savedState;
            }
            node.status = CANCELLED; // lock not held or inconsistent
            throw new IllegalMonitorStateException();
        }

        /**
         * Returns true if a node that was initially placed on a condition
         * queue is now ready to reacquire on sync queue.
         * @param node the node
         * @return true if is reacquiring
         */
        private boolean canReacquire(ConditionNode node) {
            // check links, not status to avoid enqueue race
            return node != null && node.prev != null && isEnqueued(node);
        }

        /**
         * Unlinks the given node and other non-waiting nodes from
         * condition queue unless already unlinked.
         */
        private void unlinkCancelledWaiters(ConditionNode node) {
            if (node == null || node.nextWaiter != null || node == lastWaiter) {
                ConditionNode w = firstWaiter, trail = null;
                while (w != null) {
                    ConditionNode next = w.nextWaiter;
                    if ((w.status & COND) == 0) {
                        w.nextWaiter = null;
                        if (trail == null)
                            firstWaiter = next;
                        else
                            trail.nextWaiter = next;
                        if (next == null)
                            lastWaiter = trail;
                    } else
                        trail = w;
                    w = next;
                }
            }
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            LockSupport.setCurrentBlocker(this); // for back-compatibility
            boolean interrupted = false, rejected = false;
            while (!canReacquire(node)) {
                if (Thread.interrupted())
                    interrupted = true;
                else if ((node.status & COND) != 0) {
                    try {
                        if (rejected)
                            node.block();
                        else
                            ForkJoinPool.managedBlock(node);
                    } catch (RejectedExecutionException ex) {
                        rejected = true;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                } else
                    Thread.onSpinWait();    // awoke while enqueuing
            }
            LockSupport.setCurrentBlocker(null);
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (interrupted)
                Thread.currentThread().interrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled or interrupted.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            LockSupport.setCurrentBlocker(this); // for back-compatibility
            boolean interrupted = false, cancelled = false, rejected = false;
            while (!canReacquire(node)) {
                if (interrupted |= Thread.interrupted()) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;              // else interrupted after signal
                } else if ((node.status & COND) != 0) {
                    try {
                        if (rejected)
                            node.block();
                        else
                            ForkJoinPool.managedBlock(node);
                    } catch (RejectedExecutionException ex) {
                        rejected = true;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                } else
                    Thread.onSpinWait();    // awoke while enqueuing
            }
            LockSupport.setCurrentBlocker(null);
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (interrupted) {
                if (cancelled) {
                    unlinkCancelledWaiters(node);
                    throw new InterruptedException();
                }
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            long nanos = (nanosTimeout < 0L) ? 0L : nanosTimeout;
            long deadline = System.nanoTime() + nanos;
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                if ((interrupted |= Thread.interrupted()) ||
                    (nanos = deadline - System.nanoTime()) <= 0L) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    LockSupport.parkNanos(this, nanos);
            }
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (cancelled) {
                unlinkCancelledWaiters(node);
                if (interrupted)
                    throw new InterruptedException();
            } else if (interrupted)
                Thread.currentThread().interrupt();
            long remaining = deadline - System.nanoTime(); // avoid overflow
            return (remaining <= nanosTimeout) ? remaining : Long.MIN_VALUE;
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * <li>If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                if ((interrupted |= Thread.interrupted()) ||
                    System.currentTimeMillis() >= abstime) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    LockSupport.parkUntil(this, abstime);
            }
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (cancelled) {
                unlinkCancelledWaiters(node);
                if (interrupted)
                    throw new InterruptedException();
            } else if (interrupted)
                Thread.currentThread().interrupt();
            return !cancelled;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li>If current thread is interrupted, throw InterruptedException.
         * <li>Save lock state returned by {@link #getState}.
         * <li>Invoke {@link #release} with saved state as argument,
         *     throwing IllegalMonitorStateException if it fails.
         * <li>Block until signalled, interrupted, or timed out.
         * <li>Reacquire by invoking specialized version of
         *     {@link #acquire} with saved state as argument.
         * <li>If interrupted while blocked in step 4, throw InterruptedException.
         * <li>If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            ConditionNode node = new ConditionNode();
            int savedState = enableWait(node);
            long nanos = (nanosTimeout < 0L) ? 0L : nanosTimeout;
            long deadline = System.nanoTime() + nanos;
            boolean cancelled = false, interrupted = false;
            while (!canReacquire(node)) {
                if ((interrupted |= Thread.interrupted()) ||
                    (nanos = deadline - System.nanoTime()) <= 0L) {
                    if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                        break;
                } else
                    LockSupport.parkNanos(this, nanos);
            }
            node.clearStatus();
            acquire(node, savedState, false, false, false, 0L);
            if (cancelled) {
                unlinkCancelledWaiters(node);
                if (interrupted)
                    throw new InterruptedException();
            } else if (interrupted)
                Thread.currentThread().interrupt();
            return !cancelled;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<>();
            for (ConditionNode w = firstWaiter; w != null; w = w.nextWaiter) {
                if ((w.status & COND) != 0) {
                    Thread t = w.waiter;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE
        = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "state");
    private static final long HEAD
        = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "head");
    private static final long TAIL
        = U.objectFieldOffset(AbstractQueuedSynchronizer.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}
