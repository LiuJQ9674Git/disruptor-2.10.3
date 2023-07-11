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


public class ForkJoinPool extends AbstractExecutorService {


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
    static final int SWIDTH       = 16;            // width of short
    static final int SMASK        = 0xffff;        // short bits == max index
    static final int MAX_CAP      = 0x7fff;        // max #workers - 1

    // Masks and units for WorkQueue.phase and ctl sp subfield
    static final int UNSIGNALLED  = 1 << 31;       // must be negative
    static final int SS_SEQ       = 1 << 16;       // version count

    // Mode bits and sentinels, some also used in WorkQueue fields
    static final int FIFO         = 1 << 16;       // fifo queue or access mode
    static final int SRC          = 1 << 17;       // set for valid queue ids
    static final int INNOCUOUS    = 1 << 18;       // set for Innocuous workers
    static final int QUIET        = 1 << 19;       // quiescing phase or source
    static final int SHUTDOWN     = 1 << 24;
    static final int TERMINATED   = 1 << 25;
    static final int STOP         = 1 << 31;       // must be negative
    static final int UNCOMPENSATE = 1 << 16;       // tryCompensate return

    /**
     * Initial capacity of work-stealing queue array.  Must be a power
     * of two, at least 2. See above.
     */
    static final int INITIAL_QUEUE_CAPACITY = 1 << 8;

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     */
    

    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     */
    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     */
    static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     */
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     */
    static final int COMMON_PARALLELISM;

    /**
     * Limit on spare thread construction in tryCompensate.
     */
    private static final int COMMON_MAX_SPARES;

    /**
     * Sequence number for creating worker names
     */
    private static volatile int poolIds;

    // static configuration constants

    /**
     * Default idle timeout value (in milliseconds) for the thread
     * triggering quiescence to park waiting for new work
     */
    private static final long DEFAULT_KEEPALIVE = 60_000L;

    /**
     * Undershoot tolerance for idle timeouts
     */
    private static final long TIMEOUT_SLOP = 20L;

    /**
     * The default value for COMMON_MAX_SPARES.  Overridable using the
     * "java.util.concurrent.ForkJoinPool.common.maximumSpares" system
     * property.  The default value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical OS
     * thread limits, so allows JVMs to catch misuse/abuse before
     * running out of resources needed to do so.
     */
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /*
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * RC: Number of released (unqueued) workers minus target parallelism
     * TC: Number of total workers minus target parallelism
     * SS: version count and status of top waiting thread
     * ID: poolIndex of top of Treiber stack of waiters
     */

    // Lower and upper word masks
    private static final long SP_MASK    = 0xffffffffL;
    private static final long UC_MASK    = ~SP_MASK;

    // Release counts
    private static final int  RC_SHIFT   = 48;
    private static final long RC_UNIT    = 0x0001L << RC_SHIFT;
    private static final long RC_MASK    = 0xffffL << RC_SHIFT;

    // Total counts
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign

    // Instance fields

    final long keepAlive;                // milliseconds before dropping if idle
    volatile long stealCount;            // collects worker nsteals
    int scanRover;                       // advances across pollScan calls
    volatile int threadIds;              // for worker thread names
    final int bounds;                    // min, max threads packed as shorts
    volatile int mode;                   // parallelism, runstate, queue mode
    WorkQueue[] queues;                  // main registry
    final ReentrantLock registrationLock;
    Condition termination;               // lazily constructed
    final String workerNamePrefix;       // null for common pool
    final ForkJoinWorkerThreadFactory factory;
    final UncaughtExceptionHandler ueh;  // per-worker UEH
    final Predicate<? super ForkJoinPool> saturate;

    @jdk.internal.vm.annotation.Contended("fjpctl") // segregate
    volatile long ctl;                   // main pool control

    // Support for atomic operations
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

    // Creating, registering and deregistering workers

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     *
     * @return true if successful
     */
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
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
     *
     * @param w caller's WorkQueue
     */
    final void registerWorker(WorkQueue w) {
        ReentrantLock lock = registrationLock;
        ThreadLocalRandom.localInit();
        int seed = ThreadLocalRandom.getProbe();
        if (w != null && lock != null) {
            int modebits = (mode & FIFO) | w.config;
            w.array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            w.stackPred = seed;                         // stash for runWorker
            if ((modebits & INNOCUOUS) != 0)
                w.initializeInnocuousWorker();
            int id = (seed << 1) | 1;                   // initial index guess
            lock.lock();
            try {
                WorkQueue[] qs; int n;                  // find queue index
                if ((qs = queues) != null && (n = qs.length) > 0) {
                    int k = n, m = n - 1;
                    for (; qs[id &= m] != null && k > 0; id -= 2, k -= 2);
                    if (k == 0)
                        id = n | 1;                     // resize below
                    w.phase = w.config = id | modebits; // now publishable

                    if (id < n)
                        qs[id] = w;
                    else {                              // expand array
                        int an = n << 1, am = an - 1;
                        WorkQueue[] as = new WorkQueue[an];
                        as[id & am] = w;
                        for (int j = 1; j < n; j += 2)
                            as[j] = qs[j];
                        for (int j = 0; j < n; j += 2) {
                            WorkQueue q;
                            if ((q = qs[j]) != null)    // shared queues may move
                                as[q.config & am] = q;
                        }
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
            lock.lock();                             // remove index from array
            if ((qs = queues) != null && (n = qs.length) > 0 &&
                qs[i = cfg & (n - 1)] == w)
                qs[i] = null;
            stealCount += ns;                        // accumulate steals
            lock.unlock();
            long c = ctl;
            if ((cfg & QUIET) == 0) // unless self-signalled, decrement counts
                do {} while (c != (c = compareAndExchangeCtl(
                                       c, ((RC_MASK & (c - RC_UNIT)) |
                                           (TC_MASK & (c - TC_UNIT)) |
                                           (SP_MASK & c)))));
            else if ((int)c == 0)                    // was dropped on timeout
                cfg = 0;                             // suppress signal if last
            for (ForkJoinTask<?> t; (t = w.pop()) != null; )
                ForkJoinTask.cancelIgnoringExceptions(t); // cancel tasks
        }

        if (!tryTerminate(false, false) && w != null && (cfg & SRC) != 0)
            signalWork();                            // possibly replace worker
        if (ex != null)
            ForkJoinTask.rethrow(ex);
    }

    /*
     * Tries to create or release a worker if too few are running.
     */
    final void signalWork() {
        for (long c = ctl; c < 0L;) {
            int sp, i; WorkQueue[] qs; WorkQueue v;
            if ((sp = (int)c & ~UNSIGNALLED) == 0) {  // no idle workers
                if ((c & ADD_WORKER) == 0L)           // enough total workers
                    break;
                if (c == (c = compareAndExchangeCtl(
                              c, ((RC_MASK & (c + RC_UNIT)) |
                                  (TC_MASK & (c + TC_UNIT)))))) {
                    createWorker();
                    break;
                }
            }
            else if ((qs = queues) == null)
                break;                                // unstarted/terminated
            else if (qs.length <= (i = sp & SMASK))
                break;                                // terminated
            else if ((v = qs[i]) == null)
                break;                                // terminating
            else {
                long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
                Thread vt = v.owner;
                if (c == (c = compareAndExchangeCtl(c, nc))) {
                    v.phase = sp;
                    LockSupport.unpark(vt);           // release idle worker
                    break;
                }
            }
        }
    }

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * See above for explanation.
     *
     * @param w caller's WorkQueue (may be null on failed initialization)
     */
    final void runWorker(WorkQueue w) {
        if (mode >= 0 && w != null) {           // skip on failed init
            w.config |= SRC;                    // mark as valid source
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
     * @param w caller's WorkQueue
     * @param prevSrc the previous queue stolen from in current phase, or 0
     * @param r random seed
     * @return id of queue if taken, negative if none found, prevSrc for retry
     */
    private int scan(WorkQueue w, int prevSrc, int r) {
        WorkQueue[] qs = queues;
        int n = (w == null || qs == null) ? 0 : qs.length;
        for (int step = (r >>> 16) | 1, i = n; i > 0; --i, r += step) {
            int j, cap, b; WorkQueue q; ForkJoinTask<?>[] a;
            if ((q = qs[j = r & (n - 1)]) != null && // poll at qs[j].array[k]
                (a = q.array) != null && (cap = a.length) > 0) {
                int k = (cap - 1) & (b = q.base), nextBase = b + 1;
                int nextIndex = (cap - 1) & nextBase, src = j | SRC;
                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                if (q.base != b)                // inconsistent
                    return prevSrc;
                else if (t != null && WorkQueue.casSlotToNull(a, k, t)) {
                    q.base = nextBase;
                    ForkJoinTask<?> next = a[nextIndex];
                    if ((w.source = src) != prevSrc && next != null)
                        signalWork();           // propagate
                    w.topLevelExec(t, q);
                    return src;
                }
                else if (a[nextIndex] != null)  // revisit
                    return prevSrc;
            }
        }
        return (queues != qs) ? prevSrc: -1;    // possibly resized
    }

    /**
     * Advances worker phase, pushes onto ctl stack, and awaits signal
     * or reports termination.
     *
     * @return negative if terminated, else 0
     */
    private int awaitWork(WorkQueue w) {
        if (w == null)
            return -1;                       // already terminated
        int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
        w.phase = phase | UNSIGNALLED;       // advance phase
        long prevCtl = ctl, c;               // enqueue
        do {
            w.stackPred = (int)prevCtl;
            c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
        } while (prevCtl != (prevCtl = compareAndExchangeCtl(prevCtl, c)));

        Thread.interrupted();                // clear status
        LockSupport.setCurrentBlocker(this); // prepare to block (exit also OK)
        long deadline = 0L;                  // nonzero if possibly quiescent
        int ac = (int)(c >> RC_SHIFT), md;
        if ((md = mode) < 0)                 // pool is terminating
            return -1;
        else if ((md & SMASK) + ac <= 0) {
            boolean checkTermination = (md & SHUTDOWN) != 0;
            if ((deadline = System.currentTimeMillis() + keepAlive) == 0L)
                deadline = 1L;               // avoid zero
            WorkQueue[] qs = queues;         // check for racing submission
            int n = (qs == null) ? 0 : qs.length;
            for (int i = 0; i < n; i += 2) {
                WorkQueue q; ForkJoinTask<?>[] a; int cap, b;
                if (ctl != c) {              // already signalled
                    checkTermination = false;
                    break;
                }
                else if ((q = qs[i]) != null &&
                         (a = q.array) != null && (cap = a.length) > 0 &&
                         ((b = q.base) != q.top || a[(cap - 1) & b] != null ||
                          q.source != 0)) {
                    if (compareAndSetCtl(c, prevCtl))
                        w.phase = phase;     // self-signal
                    checkTermination = false;
                    break;
                }
            }
            if (checkTermination && tryTerminate(false, false))
                return -1;                   // trigger quiescent termination
        }

        for (boolean alt = false;;) {        // await activation or termination
            if (w.phase >= 0)
                break;
            else if (mode < 0)
                return -1;
            else if ((c = ctl) == prevCtl)
                Thread.onSpinWait();         // signal in progress
            else if (!(alt = !alt))          // check between park calls
                Thread.interrupted();
            else if (deadline == 0L)
                LockSupport.park();
            else if (deadline - System.currentTimeMillis() > TIMEOUT_SLOP)
                LockSupport.parkUntil(deadline);
            else if (((int)c & SMASK) == (w.config & SMASK) &&
                     compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) |
                                          (prevCtl & SP_MASK)))) {
                w.config |= QUIET;           // sentinel for deregisterWorker
                return -1;                   // drop on timeout
            }
            else if ((deadline += keepAlive) == 0L)
                deadline = 1L;               // not at head; restart timer
        }
        return 0;
    }

    // Utilities used by ForkJoinTask

    /**
     * Returns true if can start terminating if enabled, or already terminated
     */
    final boolean canStop() {
        outer: for (long oldSum = 0L;;) { // repeat until stable
            int md; WorkQueue[] qs;  long c;
            if ((qs = queues) == null || ((md = mode) & STOP) != 0)
                return true;
            if ((md & SMASK) + (int)((c = ctl) >> RC_SHIFT) > 0)
                break;
            long checkSum = c;
            for (int i = 1; i < qs.length; i += 2) { // scan submitters
                WorkQueue q; ForkJoinTask<?>[] a; int s = 0, cap;
                if ((q = qs[i]) != null && (a = q.array) != null &&
                    (cap = a.length) > 0 &&
                    ((s = q.top) != q.base || a[(cap - 1) & s] != null ||
                     q.source != 0))
                    break outer;
                checkSum += (((long)i) << 32) ^ s;
            }
            if (oldSum == (oldSum = checkSum) && queues == qs)
                return true;
        }
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
     * @param c incoming ctl value
     * @return UNCOMPENSATE: block then adjust, 0: block, -1 : retry
     */
    private int tryCompensate(long c) {
        Predicate<? super ForkJoinPool> sat;
        int md = mode, b = bounds;
        // counts are signed; centered at parallelism level == 0
        int minActive = (short)(b & SMASK),
            maxTotal  = b >>> SWIDTH,
            active    = (int)(c >> RC_SHIFT),
            total     = (short)(c >>> TC_SHIFT),
            sp        = (int)c & ~UNSIGNALLED;
        if ((md & SMASK) == 0)
            return 0;                  // cannot compensate if parallelism zero
        else if (total >= 0) {
            if (sp != 0) {                        // activate idle worker
                WorkQueue[] qs; int n; WorkQueue v;
                if ((qs = queues) != null && (n = qs.length) > 0 &&
                    (v = qs[sp & (n - 1)]) != null) {
                    Thread vt = v.owner;
                    long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
                    if (compareAndSetCtl(c, nc)) {
                        v.phase = sp;
                        LockSupport.unpark(vt);
                        return UNCOMPENSATE;
                    }
                }
                return -1;                        // retry
            }
            else if (active > minActive) {        // reduce parallelism
                long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
                return compareAndSetCtl(c, nc) ? UNCOMPENSATE : -1;
            }
        }
        if (total < maxTotal) {                   // expand pool
            long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
            return (!compareAndSetCtl(c, nc) ? -1 :
                    !createWorker() ? 0 : UNCOMPENSATE);
        }
        else if (!compareAndSetCtl(c, c))         // validate
            return -1;
        else if ((sat = saturate) != null && sat.test(this))
            return 0;
        else
            throw new RejectedExecutionException(
                "Thread limit exceeded replacing blocked worker");
    }

    /**
     * Readjusts RC count; called from ForkJoinTask after blocking.
     */
    final void uncompensate() {
        getAndAddCtl(RC_UNIT);
    }

    /**
     * Helps if possible until the given task is done.  Scans other
     * queues for a task produced by one of w's stealers; returning
     * compensated blocking sentinel if none are found.
     *
     * @param task the task
     * @param w caller's WorkQueue
     * @param canHelp if false, compensate only
     * @return task status on exit, or UNCOMPENSATE for compensated blocking
     */
    final int helpJoin(ForkJoinTask<?> task, WorkQueue w, boolean canHelp) {
        int s = 0;
        if (task != null && w != null) {
            int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
            boolean scan = true;
            long c = 0L;                          // track ctl stability
            outer: for (;;) {
                if ((s = task.status) < 0)
                    break;
                else if (scan = !scan) {          // previous scan was empty
                    if (mode < 0)
                        ForkJoinTask.cancelIgnoringExceptions(task);
                    else if (c == (c = ctl) && (s = tryCompensate(c)) >= 0)
                        break;                    // block
                }
                else if (canHelp) {               // scan for subtasks
                    WorkQueue[] qs = queues;
                    int n = (qs == null) ? 0 : qs.length, m = n - 1;
                    for (int i = n; i > 0; i -= 2, r += 2) {
                        int j; WorkQueue q, x, y; ForkJoinTask<?>[] a;
                        if ((q = qs[j = r & m]) != null) {
                            int sq = q.source & SMASK, cap, b;
                            if ((a = q.array) != null && (cap = a.length) > 0) {
                                int k = (cap - 1) & (b = q.base);
                                int nextBase = b + 1, src = j | SRC, sx;
                                ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                                boolean eligible = sq == wid ||
                                    ((x = qs[sq & m]) != null &&   // indirect
                                     ((sx = (x.source & SMASK)) == wid ||
                                      ((y = qs[sx & m]) != null && // 2-indirect
                                       (y.source & SMASK) == wid)));
                                if ((s = task.status) < 0)
                                    break outer;
                                else if ((q.source & SMASK) != sq ||
                                         q.base != b)
                                    scan = true;          // inconsistent
                                else if (t == null)
                                    scan |= (a[nextBase & (cap - 1)] != null ||
                                             q.top != b); // lagging
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
     * @param task root of CountedCompleter computation
     * @param w caller's WorkQueue
     * @param owned true if owned by a ForkJoinWorkerThread
     * @return task status on exit
     */
    final int helpComplete(ForkJoinTask<?> task, WorkQueue w, boolean owned) {
        int s = 0;
        if (task != null && w != null) {
            int r = w.config;
            boolean scan = true, locals = true;
            long c = 0L;
            outer: for (;;) {
                if (locals) {                     // try locals before scanning
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
                else {                            // scan for subtasks
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
                                scan = true;       // inconsistent
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
     * @param submissionsOnly if true, only scan submission queues
     */
    private ForkJoinTask<?> pollScan(boolean submissionsOnly) {
        VarHandle.acquireFence();
        int r = scanRover += 0x61c88647; // Weyl increment; raciness OK
        if (submissionsOnly)             // even indices only
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
                    int nextBase = b + 1, src = j | SRC;
                    ForkJoinTask<?> t = WorkQueue.getSlot(a, k);
                    if (q.base != b)
                        busy = scan = true;
                    else if (t != null) {
                        busy = scan = true;
                        if (!active) {    // increment before taking
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
     *
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
     */
    final WorkQueue submissionQueue() {
        int r;
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();           // initialize caller's probe
            r = ThreadLocalRandom.getProbe();
        }
        for (int id = r << 1;;) {                    // even indices only
            int md = mode, n, i; WorkQueue q; ReentrantLock lock;
            WorkQueue[] qs = queues;
            if ((md & SHUTDOWN) != 0 || qs == null || (n = qs.length) <= 0)
                return null;
            else if ((q = qs[i = (n - 1) & id]) == null) {
                if ((lock = registrationLock) != null) {
                    WorkQueue w = new WorkQueue(id | SRC);
                    lock.lock();                    // install under lock
                    if (qs[i] == null)
                        qs[i] = w;                  // else lost race; discard
                    lock.unlock();
                }
            }
            else if (!q.tryLock())                  // move and restart
                id = (r = ThreadLocalRandom.advanceProbe(r)) << 1;
            else
                return q;
        }
    }

    /**
     * Adds the given task to an external submission queue, or throws
     * exception if shutdown or terminating.
     *
     * @param task the task. Caller must ensure non-null.
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue q;
        if ((q = submissionQueue()) == null)
            throw new RejectedExecutionException(); // shutdown or disabled
        else if (q.lockedPush(task))
            signalWork();
    }

    /**
     * Pushes a possibly-external submission.
     */
    private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
        Thread t; ForkJoinWorkerThread wt; WorkQueue q;
        if (task == null)
            throw new NullPointerException();
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (q = (wt = (ForkJoinWorkerThread)t).workQueue) != null &&
            wt.pool == this)
            q.push(task, this);
        else
            externalPush(task);
        return task;
    }

    /**
     * Returns common pool queue for an external thread that has
     * possibly ever submitted a common pool task (nonzero probe), or
     * null if none.
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


    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (pool = (wt = (ForkJoinWorkerThread)t).pool) != null &&
            (q = wt.workQueue) != null) {
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
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers
     * @param enable if true, terminate when next possible
     * @return true if terminating or terminated
     */
    private boolean tryTerminate(boolean now, boolean enable) {
        int md; // try to set SHUTDOWN, then STOP, then help terminate
        if (((md = mode) & SHUTDOWN) == 0) {
            if (!enable)
                return false;
            md = getAndBitwiseOrMode(SHUTDOWN);
        }
        if ((md & STOP) == 0) {
            if (!now && !canStop())
                return false;
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
            if (((md = mode) & TERMINATED) == 0 &&
                (md & SMASK) + (short)(ctl >>> TC_SHIFT) <= 0 &&
                (getAndBitwiseOrMode(TERMINATED) & TERMINATED) == 0 &&
                (lock = registrationLock) != null) {
                lock.lock();
                if ((cond = termination) != null)
                    cond.signalAll();
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
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
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
        int p = parallelism;
        if (p <= 0 || p > MAX_CAP || p > maximumPoolSize || keepAliveTime <= 0L)
            throw new IllegalArgumentException();
        if (factory == null || unit == null)
            throw new NullPointerException();
        this.factory = factory;
        this.ueh = handler;
        this.saturate = saturate;
        this.keepAlive = Math.max(unit.toMillis(keepAliveTime), TIMEOUT_SLOP);
        int size = 1 << (33 - Integer.numberOfLeadingZeros(p - 1));
        int corep = Math.min(Math.max(corePoolSize, p), MAX_CAP);
        int maxSpares = Math.min(maximumPoolSize, MAX_CAP) - p;
        int minAvail = Math.min(Math.max(minimumRunnable, 0), MAX_CAP);
        this.bounds = ((minAvail - p) & SMASK) | (maxSpares << SWIDTH);
        this.mode = p | (asyncMode ? FIFO : 0);
        this.ctl = ((((long)(-corep) << TC_SHIFT) & TC_MASK) |
                    (((long)(-p)     << RC_SHIFT) & RC_MASK));
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

        int pc = (md & SMASK);
        int tc = pc + (short)(c >>> TC_SHIFT);
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
