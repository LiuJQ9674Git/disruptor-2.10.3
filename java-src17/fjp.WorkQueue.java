    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     */
    static final class WorkQueue {
        volatile int phase;        // versioned, negative if inactive
        int stackPred;             // pool stack (ctl) predecessor link
        int config;                // index, mode, ORed with SRC after init
        int base;                  // index of next slot for poll
        ForkJoinTask<?>[] array;   // the queued tasks; power of 2 size
        final ForkJoinWorkerThread owner; // owning thread or null if shared

        // segregate fields frequently updated but not read by scans or steals
        @jdk.internal.vm.annotation.Contended("w")
        int top;                   // index of next slot for push
        @jdk.internal.vm.annotation.Contended("w")
        volatile int source;       // source queue id, lock, or sentinel
        @jdk.internal.vm.annotation.Contended("w")
        int nsteals;               // number of steals from other queues

        // Support for atomic operations
        private static final VarHandle QA; // for array slots
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
         */
        WorkQueue(ForkJoinWorkerThread owner, boolean isInnocuous) {
            this.config = (isInnocuous) ? INNOCUOUS : 0;
            this.owner = owner;
        }

        /**
         * Constructor used for external queues.
         */
        WorkQueue(int config) {
            array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
            this.config = config;
            owner = null;
            phase = -1;
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         */
        final int getPoolIndex() {
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         */
        final int queueSize() {
            VarHandle.acquireFence(); // ensure fresh reads by external callers
            int n = top - base;
            return (n < 0) ? 0 : n;   // ignore transient negative
        }

        /**
         * Provides a more conservative estimate of whether this queue
         * has any tasks than does queueSize.
         */
        final boolean isEmpty() {
            return !((source != 0 && owner == null) || top - base > 0);
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.
         *
         * @param task the task. Caller must ensure non-null.
         * @param pool (no-op if null)
         * @throws RejectedExecutionException if array cannot be resized
         */
        final void push(ForkJoinTask<?> task, ForkJoinPool pool) {
            ForkJoinTask<?>[] a = array;
            int s = top++, d = s - base, cap, m; // skip insert if disabled
            if (a != null && pool != null && (cap = a.length) > 0) {
                setSlotVolatile(a, (m = cap - 1) & s, task);
                if (d == m)
                    growArray();
                if (d == m || a[m & (s - 1)] == null)
                    pool.signalWork(); // signal if was empty or resized
            }
        }

        /**
         * Pushes task to a shared queue with lock already held, and unlocks.
         *
         * @return true if caller should signal work
         */
        final boolean lockedPush(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a = array;
            int s = top++, d = s - base, cap, m;
            if (a != null && (cap = a.length) > 0) {
                a[(m = cap - 1) & s] = task;
                if (d == m)
                    growArray();
                source = 0; // unlock
                if (d == m || a[m & (s - 1)] == null)
                    return true;
            }
            return false;
        }

        /**
         * Doubles the capacity of array. Called by owner or with lock
         * held after pre-incrementing top, which is reverted on
         * allocation failure.
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
                    if (owner == null)
                        source = 0; // unlock
                    throw new RejectedExecutionException(
                        "Queue capacity exceeded");
                }
                int newMask = newCap - 1, oldMask = oldCap - 1;
                for (int k = oldCap; k > 0; --k, --s) {
                    ForkJoinTask<?> x;        // poll old, push to new
                    if ((x = getAndClearSlot(oldArray, s & oldMask)) == null)
                        break;                // others already taken
                    newArray[s & newMask] = x;
                }
                VarHandle.releaseFence();     // fill before publish
                array = newArray;
            }
        }

        // Variants of pop

        /**
         * Pops and returns task, or null if empty. Called only by owner.
         */
        private ForkJoinTask<?> pop() {
            ForkJoinTask<?> t = null;
            int s = top, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 && base != s-- &&
                (t = getAndClearSlot(a, (cap - 1) & s)) != null)
                top = s;
            return t;
        }

        /**
         * Pops the given task for owner only if it is at the current top.
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
         * Locking version of tryUnpush.
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
                                for (int j = i; j != s; ) // shift down
                                    a[j & m] = getAndClearSlot(a, ++j & m);
                                top = s;
                            }
                            if (!owned)
                                source = 0;
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
         */
        final ForkJoinTask<?> tryPoll() {
            int cap, b, k; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0) {
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
         */
        final ForkJoinTask<?> nextLocalTask(int cfg) {
            ForkJoinTask<?> t = null;
            int s = top, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0) {
                for (int b, d;;) {
                    if ((d = s - (b = base)) <= 0)
                        break;
                    if (d == 1 || (cfg & FIFO) == 0) {
                        if ((t = getAndClearSlot(a, --s & (cap - 1))) != null)
                            top = s;
                        break;
                    }
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
         */
        final ForkJoinTask<?> nextLocalTask() {
            return nextLocalTask(config);
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
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
            source = 0;
            if ((cfg & INNOCUOUS) != 0)
                ThreadLocalRandom.eraseThreadLocals(Thread.currentThread());
        }

        /**
         * Tries to pop and run tasks within the target's computation
         * until done, not found, or limit exceeded.
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
                        if (owned) {
                            if ((taken = casSlotToNull(a, k, t)))
                                top = s;
                        }
                        else if (tryLock()) {
                            if (top == p && array == a &&
                                (taken = casSlotToNull(a, k, t)))
                                top = s;
                            source = 0;
                        }
                        if (taken)
                            t.doExec();
                        else if (!owned)
                            Thread.yield(); // tryLock failure
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