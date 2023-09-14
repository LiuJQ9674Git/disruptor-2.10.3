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
 *
 * <p><b>Implementation notes:</b> This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 * 
 *  实现说明：此实现将运行线程的最大数量限制为32767。
 *  尝试创建大于最大数量的池会导致IllegalArgumentException。
 *  
 */
public class ForkJoinPool extends AbstractExecutorService {

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
    
    // Bounds
    static final int SWIDTH       = 16;          // width of short 宽度
    static final int SMASK        = 0xffff;      // short bits == max index
    static final int MAX_CAP      = 0x7fff;      // max #workers - 1

    // Masks and units for WorkQueue.phase and ctl sp subfield
    static final int UNSIGNALLED  = 1 << 31;     // must be negative
    //
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
    // sign
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); 
    
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
    
    /**
     *FIFO:	65536	            Bit->: 1 << 16
     *                                                         1 0000 0000 0000 0000
     *SRC:	131072	Bit->: 1 << 17
     *                                                         10 0000 0000 0000 0000
     *INNOCUOUS:	262144	    Bit->: 1 << 18
     *                                                        100 0000 0000 0000 0000
     *QUIET:	524288	Bit->: 1 << 19
     *                                                       1000 0000 0000 0000 0000
     *SHUTDOWN:	16777216	Bit->:1 << 24
     *                                                1 0000 0000 0000 0000 0000 0000
     *TERMINATED:	33554432	Bit->:1 << 25
     *                                               10 0000 0000 0000 0000 0000 0000
     *STOP:	-2147483648	Bit->:1 << 31
     *1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
     *UNCOMPENSATE:	65536	Bit->:1 << 31
     *                                                          1 0000 0000 0000 0000
     * SMASK:	65535   Bit->:0xffff
     *                                                            1111 1111 1111 1111
     *MAX_CAP:	32767	Bit->:0x7fff
     *                                                             111 1111 1111 1111
     *SS_SEQ:	65536	Bit->:1 << 16
     *                                                          1 0000 0000 0000 0000
     *
     *UNSIGNALLED:	-2147483648	Bit->:1 << 31
     *1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
     *~UNSIGNALLED:	2147483647	
     *                                         111 1111 1111 1111 1111 1111 1111 1111
     *------------------------------------------------------------------------------
     *ctl:	-4222399528566784	
     *1111 1111 1111 0000 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *TC_MASK:	281470681743360  0xffffL<<32
     *                    1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
     *RC_MASK:	-281474976710656 0xff ffL>>48
     *1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *~RC_MASK:	281474976710655
     *                    1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111

     *-------------------------------------------------------------------------------
     *SP_MASK:	4294967295 0xff ff ff ffL int最大数
     *                                        1111 1111 1111 1111 1111 1111 1111 1111
     *UC_MASK:	-4294967296 ~SP_MASK      int最小数
     *1111 1111 1111 1111 1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *RC_UNIT:	281474976710656 Bit->1<<48
     *                   1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *TC_UNIT:	4294967296      Bit->1<<32
     *                                       1 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *ADD_WORKER:	140737488355328 1<<47
     *                     1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     */
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

    // 发送信号
    
    /*
     * Tries to create or release a worker if too few are running.
     * 如果运行的工作线程太少，则尝试创建或释放工作线程。
     * ctl:	-4222399528566784
     *1111 1111 1111 0000 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *TC_MASK:	281470681743360  0xffffL<<32 低32为0
     *                    1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
     *RC_MASK:	-281474976710656 0xff ffL>>48
     *1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *UNSIGNALLED:	-2147483648	Bit->:1 << 31 取负数（取反）和TC_MASK差一位，即32为1
     *1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
     *~UNSIGNALLED:	2147483647	               32位为0
     *                                         111 1111 1111 1111 1111 1111 1111 1111
     *                                         
     *  this.ctl = ((((long)(-corep) << TC_SHIFT) & TC_MASK) | //核数（线程数、工作者数）
     *               (((long)(-p)     << RC_SHIFT) & RC_MASK)); //并发数                                         
     */
    final void signalWork() {
        // ctl:	-4222399528566784
        // ctl->:1111111111110000111111111100000000000000000000000000000000000000
        for (long c = ctl; c < 0L;) {
            int sp, i; WorkQueue[] qs; WorkQueue v;
            // 没有闲着的工作者（线程）
            //UNSIGNALLED
            //1111111111111111111111111111111110000000000000000000000000000000
            //~UNSIGNALLED
            //                                 1111111111111111111111111111111
            // ctl 中后31位，第32位为stop标识
            if ((sp = (int)c & ~UNSIGNALLED) == 0) {// no idle workers
                // 足够的工作者（线程）总数 1<<47 高16（64-48）16 为RC和TC的计数位
                //1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
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
                // RC/TC 计数处1
                if (c == (c = compareAndExchangeCtl(
                              c, ((RC_MASK & (c + RC_UNIT)) | // RC增加
                                  (TC_MASK & (c + TC_UNIT)))))) { // TC增加
                    createWorker();
                    break;
                }
            }
            else if ((qs = queues) == null){
                break;               // unstarted/terminated 未启动/已终止
            }
            // SMASK:	65535	Bit->:1111 1111 1111 1111
            // sp的后16位 size= 32 bit:10 0000
            // Integer.numberOfLeadingZeros,最高位为0的个数 
            // int size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
            // size : 32 
            else if (qs.length <= (i = sp & SMASK)){ //= 0xff ff ff ffL
                break;              // terminated 已终止
            }
            else if ((v = qs[i]) == null){ // i是0xff ff ff ff 16位
                break;             // terminating 正在终止
            }
            else {
                //SP_MASK  f*8 4294967295
                //                                11111111111111111111111111111111
                //UC_MASK: ~SP_MASK f*8 0*8
                //1111111111111111111111111111111100000000000000000000000000000000
                //SMASK:	65535	n1111111111111111
                //UC_MASK:	-4294967296
                //1111111111111111111111111111111100000000000000000000000000000000
                //SP_MASK:	4294967295
                //                                11111111111111111111111111111111
                //RC_UNIT:	281474976710656
                //               1000000000000000000000000000000000000000000000000
                //TC_UNIT:	4294967296
                //                               100000000000000000000000000000000
                // v是取自当前的ctl值
                // stackPred 发送信号中的stackPred 在注册任务和等待任务中写入
                // nc-1
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
            //stackPred线程哈希值，线程同队列数组的对应关系
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
                    int k = n, m = n - 1; //k为主队列长度
                    //奇数，主任务
                    for (; qs[id &= m] != null && k > 0; id -= 2, k -= 2);
                    if (k == 0){
                        id = n | 1;         // resize below 在下面调整大小
                    }
                    // 可发布状态，任务的id值为奇数
                    // config;                // index, mode, ORed with SRC after init
                    // volatile phase;        // versioned, negative if inactive
                    
                    // submissionQueue中phase为-1，config为id（线程哈希）
                    // 此外，内部的ID为奇数，外部的ID为偶数，
                    w.phase = w.config = id | modebits; //now publishable
                    //
                    if (id < n){
                        //主任务在设置seed、phase、config写入id的位置
                        qs[id] = w; //id为主队列从最大位置开始，最后一个没有任务的位置
                    }
                    else {                //expand array 展开数组
                        int an = n << 1, am = an - 1;
                        WorkQueue[] as = new WorkQueue[an];
                        //主任务在设置seed、phase、config写入id的位置
                        as[id & am] = w;
                        for (int j = 1; j < n; j += 2){ //奇数为主工作任务，owner不为空
                            as[j] = qs[j];
                        }
                        // 共享任务队列
                        for (int j = 0; j < n; j += 2) {
                            WorkQueue q;
                            // 共享队列可能会移动
                            if ((q = qs[j]) != null) { //shared queues may move 
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
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * See above for explanation.
     *
     * 工作者（线程）的顶级运行循环，由ForkJoinWorkerThread.run调用。
     * 运行的首个任务是由工作线程执行的。
     * @param w caller's WorkQueue (may be null on failed initialization)
     */
    final void runWorker(WorkQueue w) {
        if (mode >= 0 && w != null) {  // skip on failed init 初始化失败时跳过
            // SRC:	131072	             10 0000 0000 0000 0000
            // SRC          = 1 << 17;
            // INNOCUOUS    = 1 << 18;
            // QUIET        = 1 << 19;
            // STOP         = 1 << 31;       // must be negative
            // UNCOMPENSATE = 1 << 16;       // tryCompensate return
            
            // 主任务启动之后，执行创建的ForkJoinWorkThread生成的WorkQueue
            // 在队列中 config 在写入SRC
            w.config |= SRC;                    // mark as valid source 标记为有效源
            //使用registerWorker中的种子，线程哈希值
            int r = w.stackPred, src = 0;       // use seed from registerWorker 
            do {
                r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
                // 线程探针哈希值，做随机变化之后，在主任务中查找并执行任务队列中的认证
                // r为主任务队列位置
            } while ((src = scan(w, src, r)) >= 0 || //
                     (src = awaitWork(w)) == 0); //当scan值小于0
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
        // r为主队列，step为奇数，
        for (int step = (r >>> 16) | 1, i = n; i > 0; --i, r += step) {
            int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
            // 
            if ((q = qs[j = r & (n - 1)]) != null && // poll at qs[j].array[k]
                (a = q.array) != null && (cap = a.length) > 0) { //a 为为主任务队列r位置任务
                // k为子任务（WorkQueue.array）中的位置，底部base任务
                int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                // nextIndex下一个底部开始子任务（WorkQueue.array）中的位置
                // j位置为线程探针哈希值，src为对seed做SRC标记
                int nextIndex = (cap - 1) & nextBase, src = j | SRC;
                // 底部子任务
                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                // q是r为基础定位的队列
                if (q.base != b)                // inconsistent
                    return prevSrc;
                else if (t != null && WorkQueue.casSlotToNull(a, k, t)) {
                    // 取得任务t不为空，是一个检查
                    q.base = nextBase;
                    // 下一个子任务ForkJoinTask
                    ForkJoinTask<?> next = a[nextIndex];
                    // 主工作任务 j的任务不是参数传入的prevSrc，存在子队列中存在下一个任务
                    // source对本队列w赋值为主队列数组的位置
                    if ((w.source = src) != prevSrc && next != null){
                        //传播/产生任务 调用createWorker生产线程
                        signalWork();           // propagate 
                    }
                    // 队列执行任务t,执行子任务方法1
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

        // SS_SEQ:	65536	1<<16                       -16
        // SS_SEQ 第17位为1，后续是连续的0 1+0*4
        //                                          |     phase段      |
        //                                         1 0000 0000 0000 0000
        //~UNSIGNALLED:	2147483647	       7+f*7 31位    31
        //                        111 1111 1111 1111 1111 1111 1111 1111
        //                                           
        // w.phase + SS_SEQ 使得位标记到17~31位置段，即INT的高16段
        // & ~UNSIGNALLED 执行之后，低16段清零
        int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;        
        // 下面取或|操作之后，w.phase的高32位1，即是负值
        // 当前任务线程队列信息
        //UNSIGNALLED:	-2147483648 f*8 64位
        //                                 31
        //                                 -
        //1111111111111111111111111111111110000000000000000000000000000000
        //                                               10000000000000000
        w.phase = phase | UNSIGNALLED;       // advance phase 推动阶段
        long prevCtl = ctl, c;               // enqueue 入队
        do {
            // ctl 默认是主队列容量RC/TC的负值
            // stackPred更新为ctl，当前任务线程队列信息
            w.stackPred = (int)prevCtl;
            //RC_UNIT: 48
            //1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
            //UC_MASK:                       -32
            //1111111111111111111111111111111100000000000000000000000000000000
            //SP_MASK                         32
            //                                11111111111111111111111111111111
            // 高32位用于计数 phase 用后32位
            // 更新nc-2
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
        //size:	32	H>:	20	S-B->:6 p=16
        //                                                               10 0000
        //seed:	27	H>:	1b	S-B->:5
        //                                                                1 1011
        //phase:	-4222399528566784	H>:	10002	S-B->:17
        //                                                 1 0000 0000 0000 0010
        //      
        //phase:	-4222399528566784	H>:	8001001b	S-B->:32
        //                               1000 0000 0000 0001 0000 0000 0001 1011
        //ac:	    -4222399528566784	H>:	fffffff0	S-B->:32
        //                               1111 1111 1111 1111 1111 1111 1111 0000  
        int ac = (int)(c >> RC_SHIFT), md;
        if ((md = mode) < 0)  {  // pool is terminating 池正在终止
            return -1;
        }
        else if ((md & SMASK) + ac <= 0) {
            //SHUTDOWN:	16777216	H>:	1000000	S-B->:25
            //                                   1 0000 0000 0000 0000 0000 0000
            boolean checkTermination = (md & SHUTDOWN) != 0;
            if ((deadline = System.currentTimeMillis() + keepAlive) == 0L){
                deadline = 1L;               // avoid zero 避免零
            }
            //检查竞争提交    
            WorkQueue[] qs = queues;         // check for racing submission 
            int n = (qs == null) ? 0 : qs.length;
            for (int i = 0; i < n; i += 2) { //共享队列 偶数任务
                WorkQueue q; ForkJoinTask<?>[] a; int cap, b;
                if (ctl != c) {  // already signalled 已发出信号
                    checkTermination = false;
                    break;
                }
                else if ((q = qs[i]) != null &&
                         (a = q.array) != null && (cap = a.length) > 0 &&
                         ((b = q.base) != q.top || a[(cap - 1) & b] != null ||
                          q.source != 0)) { // 非当前任务队列任务
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
            // ctl的后16为SMASK config为seed
            else if (((int)c & SMASK) == (w.config & SMASK) &&
                     //1111111111111111111111111111111100000000000000000000000000000000
                     //TC更新减-2
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
     * helpJoin调用tryCompensate
     * compensatedBlock compensatedBlock由managedBlock调用
     * @param c incoming ctl value 传入ctl值
     * @return UNCOMPENSATE: block then adjust, 0: block, -1 : retry
     */
    private int tryCompensate(long c) {
        Predicate<? super ForkJoinPool> sat;
        int md = mode, b = bounds;
        // counts are signed; centered at parallelism level == 0
        //SMASK:	65535	                             1111111111111111
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
                    // signalWork
                    //long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
                    // awaitWork
                    //c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK); //c值
                    //tryCompensate
                    //long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
                    // nc更新-3
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
                
                //nc:	-16	H>:	ffefffc000000000	S-B->:64
               //1111 1111 1110 1111 1111 1111 1100 0000
               //0000 0000 0000 0000 0000 0000 0000 0000
                //nc 4
                long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
                return compareAndSetCtl(c, nc) ? UNCOMPENSATE : -1;
            }
        }
        if (total < maxTotal) {               // expand pool 扩展池
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
                    // r 基于 w.config的计数，共享队列操作
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
                                       (y.source & SMASK) == wid))); //主任务id
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

    
    //
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
                // RC/TC/SP 更新3
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
        // ctl->:1111111111110000111111111100000000000000000000000000000000000000
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

    //
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
