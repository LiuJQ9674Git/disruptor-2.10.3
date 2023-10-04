package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 *
 * Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 * 
 * 即使此类基于内部FIFO队列，它也不会自动执行FIFO获取策略。独占同步的核心采用以下形式：
 * 
 * 
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * 
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * 由于获取中的检查是在排队之前调用的，因此一个新获取线程可能会在其他被阻塞和排队的线程之前阻塞。
 * 但是，如果需要，您可以定义｛@code tryAcquire｝和/或｛@code-tryAcquireShared｝，
 * 通过内部调用一种或多种检查方法来禁用议价，从而提供<em>公平</em>FIFO采集顺序。
 * 特别是，如果｛hasQueuedPredessors｝（一种专门为公平同步器设计的方法）返回｛true｝，
 * 则大多数公平同步器可以定义｛@code-tryAcquire｝返回｛@code false｝。其他变化也是可能的。
 * 
 * Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * 默认barging（也称为贪婪、放弃和车队规避）策略的吞吐量和可扩展性通常最高。
 * 虽然这不能保证是公平的或无饥饿的，但允许较早排队的线程在较晚排队的线程之前进行重新保持，
 * 并且每次重新保持都有机会成功应对传入线程。此外，acquires时不旋转；
 * 在通常意义上，他们可以在阻塞之前执行{tryAcquire}的多次调用，并穿插其他计算。
 * 当独占同步仅短暂保持时，这提供了旋转的大部分好处，而当不保持时，则没有大部分责任。
 * 如果需要，您可以通过前面的调用来增强这一点，以获取具有“快速路径”检查的方法，
 * 可能会预检查｛hasContended｝和/或｛hasQueuedThreads｝，
 * 以便只有在同步器可能不被争用的情况下才这样做。
 *
 * This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * 该类为同步提供了一个高效且可扩展的基础，部分原因是将其使用范围专门化为可以依赖
 * 初始化状态（state）、获取acquire和释放release的参数，
 * 以及内部FIFO等待队列的同步器。
 * 
 * 当这还不够时，您可以使用｛atomic｝类｛java.util.Queue｝类和｛LockSupport｝
 * 阻塞支持从较低级别构建同步器。
 * 
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 * 
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
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
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
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
 * }}
 *
 */
public abstract class AbstractQueuedSynchronizer.Comment.8
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.
     *
     * 等待队列是“CLH”（Craig、Landin和Hagersten）锁队列的变体。
     * CLH锁通常用于自旋锁spinlocks。我们将它们用于阻塞同步器，但使用相同的基本策略，
     * 即在其节点的前继节点中保留有关线程的一些控制信息。
     * 
     * A "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread.
     *
     * 每个节点中的“状态status”字段跟踪线程是否应该阻塞。
     * 当节点的前一个释放releases时，节点会发出（释放阻塞）信号。
     * 否则，队列的每个节点都充当一个特定的通知样式监视器，其中包含一个等待线程。
     * 
     * The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     * 
     * 状态status字段并不控制线程是否被授予锁等。
     * 如果node/thread是第一个节点，那么线程尝试获取锁（许可、资质）
     * 但它只赋予了争用的权利，不能保证成功；
     * 因此，目前发布的竞争者线程可能需要重新等待。
     *
     * To enqueue into a CLH lock, you atomically splice it in as new tail.
     * 将其排入CLH锁，然后将其原子性地拼接为新的尾部tail。
     * To dequeue, you just set the head field.
     * 要退出队列，只需设置head字段。
     * 
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     *
     * Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * 插入CLH队列只需要在“tail”上执行单个原子操作，因此从未排队到排队有一个简单的原子分界点。
     * 同样，出列只涉及更新“头”。
     * 然而，节点需要做更多的工作来确定他们的后继，部分原因是要处理由于超时和中断可能导致的取消。
     * 
     * The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * “prev”链接（不用于原始CLH锁），主要用于处理取消。
     * 如果节点被取消，则其后续节点（通常）会重新链接到未取消的前置节点。
     * 关于自旋锁情况下的解释，请参阅Scott和Scherer在
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.
     *
     * 我们还使用“next”链接来实现阻塞机制。每个节点的线程id都保存在自己的节点中，
     * 因此前置节点通过遍历下一个链接来确定它是哪个线程，从而向下一个节点发出唤醒信号。
     * 
     * Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     * 
     * 确定后续节点必须避免与新排队的节点竞争，以设置其前置节点的“下一个next”字段。
     * 必要时，当节点的后续节点显示为null时，通过从原子更新的“尾节点tail”向后检查来解决此问题。
     * （或者，换言之，下一个链接是一个优化，这样我们通常不需要反向扫描scan。）
     * 
     * Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * 取消为基本算法引入了一些保守性。由于我们必须轮询其他节点的取消，
     * 我们可能会忽略被取消的节点是在我们前面还是后面。
     * 这是通过在取消时总是取消对后继者的标记来处理的，允许他们稳定在新的前任上，
     * 除非我们能确定一个将承担这一责任的未被取消的前继。
     * 
     * CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     * 
     * CLH队列需要一个伪头节点才能启动。但我们不会在构造方法中创造它们，
     * 因为如果从来没有争用，那将是浪费精力。
     * 相反，构造节点，并在第一次争用时设置头指针和尾指针。
     * 
     * Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * 在Conditions上等待的线程使用相同的节点，但使用额外的链接。
     * 条件只需要链接简单（非并发）链接队列中的节点，因为它们只有在独占时才能访问。
     * 在等待时，一个节点被插入到一个条件队列中。
     * 发出信号后，节点被转移到主队列。状态字段的一个特殊值用于标记节点所在的队列。
     * 
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /**
         * Marker to indicate a node is waiting in shared mode
         * 用于指示节点正在共享模式下等待的标记
        */
        static final Node SHARED = new Node();
        /**
         * Marker to indicate a node is waiting in exclusive mode
         * 用于指示节点正在独占模式下等待的标记
        */
        static final Node EXCLUSIVE = null;

        /**
         * waitStatus value to indicate thread has cancelled
         * waitStatus值，指示线程已取消
        */
        static final int CANCELLED =  1;
        /**
         * waitStatus value to indicate successor's thread needs unparking
         * waitStatus值，指示后续线程需要unparking标记
        */
        static final int SIGNAL    = -1;
        /**
         * waitStatus value to indicate thread is waiting on condition
         * waitStatus值，指示线程正在等待条件
        */
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         *
         * waitStatus值，指示下一个acquireShared应无条件传播
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *               
         *               此节点的继任者是（或将很快成为）阻止（通过park），
         *               因此当前节点必须在发布或取消。为了避免种族，获取方法必须
         *               首先指示他们需要信号，然后重试原子获取，发生故障时，阻止。
         *               
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *
         *               由于超时或中断，此节点被取消。节点永远不会离开此状态。
         *               特别地，具有取消节点的线程再也不会阻塞。
         *               
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *
         *               此节点当前在条件队列中。它不会用作同步队列节点直到转移，
         *               此时状态将设置为0。（
         *               此处使用此值与的其他用途无关字段，但简化了机制。）
         *               
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *
         *               releaseShared应传播到其他节点。这是在中设置的（仅适用于头节点）
         *               doReleaseShared以确保传播即使其他操作此后进行了干预。
         *               
         *   0:          None of the above
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * 这些值以数字形式排列，以简化使用。非负值表示节点不需要信号。
         * 因此，大多数代码不需要检查值，仅用于符号。
         * 
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         *
         * 对于正常同步节点，该字段初始化为0，并且条件节点的CONDITION。
         * 使用CAS进行修改（或者在可能的情况下，无条件的volatile写入）。
         */
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         *
         * 链接到当前节点/线程所依赖的前置节点用于检查waitStatus。
         * 在排队期间分配，并为null只有在出队时才能出队（为了GC）。
         * 此外，在取消前一个，我们短路short-circuit找到一个未取消的，它将永远存在
         * 因为头节点从未被取消：节点变成头部只是成功收购的结果。
         * A节点被取消的线程永远不会成功获取，并且只有一个线程取消自身，而不是任何其他节点。
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         *
         * 链接到当前节点/线程所在的后续节点发布release时取消阻塞unpark。
         * 排队时分配，已调整绕过已取消的前置任务，当出队时将其清空时（对于为了GC）。
         * enq操作不会分配前任的下一个字段直到以后设置此值，
         * 所以看到下一个next字段为空并不一定意味着节点
         * 在队列的末尾。但是，如果出现下一个next字段要为null，
         * 我们可以从尾节点tail开始遍历prev，并使用double-check。
         * 取消节点的下一个next字段设置为指向节点本身而不是null，
         * 以创建生命isOnSyncQueue更容易。
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         *
         * 将此节点排入队列的线程。在构造函数初始化并在使用后清空。
         */
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         *
         * 链接到等待条件下的下一个next节点，或特殊值SHARED。
         * 因为仅访问条件队列在独占模式下持有时，我们只需要链接队列以在节点等待时容纳节点条件。
         * 然后将它们转移到队列中重新获取许可（re-acquire）。因为条件只能是排他性的，
         * 我们通过使用特殊值来保存字段以表示共享模式。
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         * 如果节点正在共享模式下等待，则返回true。
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         * 返回上一个节点，如果为null则抛出NullPointerException。
         * 前置任务不能为null时使用。空检查可能被忽略，但存在是为了帮助VM。
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     *
     * 等待队列的头，已延迟初始化。除了初始化时，它只能通过方法setHead进行修改。
     * 注：如果头存在，则保证其waitStatus不会已取消。
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.
     * Modified only via method enq to add new wait node.
     *
     * 等待队列的尾部，已延迟初始化。仅通过修改方法enq添加新的等待节点。
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     * 同步状态。
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     *
     * 返回同步状态的当前值。此操作具有｛@code volatile｝读取的内存语义。
     * 
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * 设置同步状态的值。此操作具有｛volatile｝写入的内存语义。
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
     * 原子化地将同步状态设置为给定的更新值，如果当前状态值等于预期值。
     * 此操作具有｛volatile｝读取的内存语义和写作。
     * 
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * 将节点插入队列，必要时进行初始化。
     * 
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            if (t == null) { // Must initialize
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     * 线程写入队列/没有执行park
     * 
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node; //尾部添加
                return node;
            }
        }
        //入队
        enq(node);
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * 将队列的头设置为节点，从而出列。仅由调用获取方法。
     * 为了GC，还将未使用的字段清空并且抑制不必要的信号和遍历。
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * Wakes up node's successor, if one exists.
     * release是否锁方法调用
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
            /*
             * If status is negative (i.e., possibly needing signal) try
             * to clear in anticipation of signalling.  It is OK if this
             * fails or if status is changed by waiting thread.
             *
             * 如果状态为阴性（即，可能需要信号），则尝试清除信号。
             * 如果失败或等待线程更改了状态，则可以。
             */
            int ws = node.waitStatus;
            if (ws < 0)
                compareAndSetWaitStatus(node, ws, 0);
    
            /*
             * Thread to unpark is held in successor, which is normally
             * just the next node.  But if cancelled or apparently null,
             * traverse backwards from tail to find the actual
             * non-cancelled successor.
             *
             * 要取消标记的线程保存在后续节点中，后者通常只是下一个节点。
             * 但如果被取消或明显为空，则从尾部向后遍历以找到实际的未取消的后续。
             */
            Node s = node.next;
            if (s == null || s.waitStatus > 0) {
                s = null;
                for (Node t = tail; t != null && t != node; t = t.prev)
                    if (t.waitStatus <= 0)
                        s = t;
            }
            if (s != null){
                LockSupport.unpark(s.thread);
            }
        }
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     * 共享模式的释放操作——发出后续信号并确保传播。
     * （注意：对于独占模式，如果需要信号，
     * 释放就相当于调用head的unparkSuccessor。）
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         * 确保一个发布传播，即使还有其他正在进行的获取/发布。
         * 如果需要信号，这将以通常的方式进行，尝试打开头部的继承器。
         * 但如果没有，则将状态设置为“传播”，以确保在发布时继续传播。
         * 此外，我们必须循环，以防在执行此操作时添加新节点。
         * 此外，与unparkSuccessor的其他使用不同，
         * 我们需要知道CAS重置状态是否失败，如果是，则重新检查。
         */
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);
                }
                else if (ws == 0 &&
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     * 设置队列头，并检查后续队列是否在共享模式下等待，
     * 如果是，则在设置了propagate>0或propagate状态时进行传播。
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         * 如果出现以下情况，请尝试发出下一个排队节点的信号：
         *   Propagation was indicated by caller,
         *   传播由呼叫者指示，
         *     or was recorded (as h.waitStatus either before
         *     之前记录为（作为h.waitStatus
         *     or after setHead) by a previous operation
         *     通过以前的操作
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         *      （注意：这使用waitStatus的符号检查，
         *      因为PROPAGATE状态可能转换为SIGNAL。）
         * and
         *   The next node is waiting in shared mode,
         *   下一个节点正在共享模式中等待，
         *     or we don't know, because it appears null
         *     我们不知道，因为它看起来是空的
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         *
         * 这两种检查中的保守性可能会导致不必要的唤醒，但只有当有多个比赛获取/发布时，
         * 所以大多数人现在或很快都需要信号。
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null){
            return;
        }
        
        node.thread = null;
        // Skip cancelled predecessors  跳过已取消的前置任务
        
        Node pred = node.prev;
        while (pred.waitStatus > 0){
            node.prev = pred = pred.prev;
        }

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        //predNext是要取消拼接的明显节点。以下CASes
        //如果没有失败，在这种情况下，我们输掉了竞争，而另一竞争取消了
        //因此不需要进一步的动作。
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        //可以在此处使用无条件写入而不是CAS。
        //在这个原子步骤之后，其他节点可以跳过我们。
        //以前，我们不受其他线程的干扰。
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        // 如果我们是尾节点tail，就把自己移开。
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            // 如果继任者需要信号，请尝试设置pred的下一个链接
            // 所以它会得到一个。否则会唤醒它进行传播。
            int ws;
            if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL ||
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0){
                    compareAndSetNext(pred, predNext, next);
                }
            } else {
                unparkSuccessor(node);
            }
            node.next = node; // help GC
        }
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     * 
     * 检查并更新无法获取的节点的状态。如果线程应该阻塞，则返回true。这是主信号
      * 控制所有采集循环。要求pred==node.prev。
      * 
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             * 此节点已设置请求发布的状态给它发信号，这样它就可以安全地停车。
             */
            return true;
        if (ws > 0) {
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             * 前置任务已取消。跳过前置任务和指示重试。
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             * waitStatus必须为0或PROPAGATE。表明我们需要信号，但不要park。呼叫者需要
             * 请重试以确保它在park前无法获取。
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     * 中断当前线程的方便方法。
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     * 执行中断后的线程阻塞与检查
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     * 
     * 各种风格的acquire，在独家/共享和控制模式。每个基本上都一样，
     * 但令人恼火不同。由于异常机制的交互作用（包括确保如果tryAcquire抛出异常，则取消）
     * 和其他控件，位于至少在不太影响表现的情况下。
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     * 独占不可中断模式 线程已经入队列
     * 
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    //设置头部
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                //获取状态
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) //执行park
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     * 以共享不间断模式获取。
     * 
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
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
        if (!tryAcquire(arg) && //调用子类实现
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
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
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
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
            doAcquireShared(arg);
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
            doReleaseShared();
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
     * 查询是否有线程正在等待获取。
     * 请注意因为可能会发生由于中断和超时而导致的取消
     * 在任何时候，｛@code true｝返回都不能保证其他线程将永远获得。
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * 查询是否有任何线程争用获取此同步器；也就是说，如果一个acquire方法曾经被阻止。
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
     * 返回队列中第一个（等待时间最长的）线程，或者｛@code null｝，如果当前没有线程排队。
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * 在此实现中，此操作通常返回恒定时间，但如果其他线程同时修改队列。
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         * 
         * 第一个节点通常是head.next thread字段，确保读取一致：
         * 如果thread字段为null或s.prev不再为head，
         * 则其他一些线程在中同时执行setHead在我们的一些阅读之间。
         * 我们以前试过两次采用遍历。
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         * 
         * Head的下一个字段可能还没有设置，或者可能已经在setHead之后未设置。
         * 所以我们必须检查一下尾tail实际上是第一个节点。
         * 如果没有，我们继续，安全地从尾部向后遍历到头部以首先找到，保证终止。
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
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
            if (p.thread == thread)
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
     *
     * 如果明显的第一个排队线程，如果存在，正在以独占模式等待。
     * 如果此方法返回｛@code true｝，并且当前线程正试图在中获取共享模式（
     * 即，此方法从｛tryAcquireShared｝），则保证当前线程不是第一个排队的线程。
     * 仅用作中的启发式重新输入读写锁定。
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     * 
     * 查询是否有线程等待获取的时间更长而不是当前线程。
     * 
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *
     * 此方法的调用等效于（但可能是效率高于）：
     * 
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * 请注意，由于中断和超时可能随时发生，｛true｝返回不会保证某些其他线程将
     * 在当前线程之前获取。同样，另一个线程也有可能赢得在该方法
     * 返回{false}后进行排队竞争，由于队列为空。
     * 
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     * 此方法设计用于公平同步器避免“AbstractQueuedSynchronizer#barging”。
     * 这样的同步器的｛tryAcquire｝方法应该返回｛false｝，
     * 其｛tryAcquireShared｝方法应该如果此方法返回｛@code true｝，
     * 则返回负值（除非这是可重入获取）。
     * 例如，｛tryAcquire｝方法，用于公平、可重入、独占模式同步器可能如下所示：
     * 
     *  <pre> {@code
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
     *
     *         如果在当前线程，返回true
     *         如果当前线程为｛false｝位于队列的开头或队列为空
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        
        // 此操作的正确性取决于正在初始化的头在尾部和头部之前。
        // 如果当前线程是队列中的第一个。
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * 返回等待的线程数的估计值。该值只是一个估计值，因为当该方法遍历时，
     * 线程可能会动态更改内部数据结构。
     * 此方法设计用于监视系统状态，不用于同步控制。
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
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
     * 返回包含可能正在等待的线程的集合。因为实际的线程集可能会发生变化在构造此结果时
     * 只是一个最大努力的估计。返回的集合没有特殊的顺序。
     * 这种方法是旨在促进子类的构建更广泛的监测设施。
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
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
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
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
     * 返回包含可能正在等待的线程的集合以共享模式获取。
     * 它具有相同的属性作为{getQueuedThreads}，除了它只返回那些由于共享获取而等待的线程。
     * 
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
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
     * 返回标识此同步器及其状态的字符串。括号中的状态包括字符串｛“state=”｝
     * 后跟{getState}的当前值，并且根据队列为空。
     * 
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }

    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
