

    /** Dual Queue */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        /** Node class for TransferQueue. */
        static final class QNode implements ForkJoinPool.ManagedBlocker {
            volatile QNode next;          // next node in queue
            volatile Object item;         // CAS'ed to or from null
            volatile Thread waiter;       // to control park/unpark
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    QNEXT.compareAndSet(this, cmp, val);
            }

            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    QITEM.compareAndSet(this, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             */
            boolean tryCancel(Object cmp) {
                return QITEM.compareAndSet(this, cmp, this);
            }

            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             */
            boolean isOffList() {
                return next == this;
            }

            void forgetWaiter() {
                QWAITER.setOpaque(this, null);
            }

            boolean isFulfilled() {
                Object x;
                return isData == ((x = item) == null) || x == this;
            }

            public final boolean isReleasable() {
                Object x;
                return isData == ((x = item) == null) || x == this ||
                    Thread.currentThread().isInterrupted();
            }

            public final boolean block() {
                while (!isReleasable()) LockSupport.park();
                return true;
            }

            // VarHandle mechanics
            private static final VarHandle QITEM;
            private static final VarHandle QNEXT;
            private static final VarHandle QWAITER;
            static {
                try {
                    MethodHandles.Lookup l = MethodHandles.lookup();
                    QITEM = l.findVarHandle(QNode.class, "item", Object.class);
                    QNEXT = l.findVarHandle(QNode.class, "next", QNode.class);
                    QWAITER = l.findVarHandle(QNode.class, "waiter", Thread.class);
                } catch (ReflectiveOperationException e) {
                    throw new ExceptionInInitializerError(e);
                }
            }
        }

        /** Head of queue */
        transient volatile QNode head;
        /** Tail of queue */
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         */
        transient volatile QNode cleanMe;

        TransferQueue() {
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         */
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                QHEAD.compareAndSet(this, h, nh))
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         */
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                QTAIL.compareAndSet(this, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                QCLEANME.compareAndSet(this, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */

            QNode s = null;                  // constructed/reused as needed
            boolean isData = (e != null);
            for (;;) {
                QNode t = tail, h = head, m, tn;         // m is node to fulfill
                if (t == null || h == null)
                    ;                                    // inconsistent
                else if (h == t || t.isData == isData) { // empty or same-mode
                    if (t != tail)                       // inconsistent
                        ;
                    else if ((tn = t.next) != null)      // lagging tail
                        advanceTail(t, tn);
                    else if (timed && nanos <= 0L)       // can't wait
                        return null;
                    else if (t.casNext(null, (s != null) ? s :
                                       (s = new QNode(e, isData)))) {
                        advanceTail(t, s);
                        long deadline = timed ? System.nanoTime() + nanos : 0L;
                        Thread w = Thread.currentThread();
                        int stat = -1; // same idea as TransferStack
                        Object item;
                        while ((item = s.item) == e) {
                            if ((timed &&
                                 (nanos = deadline - System.nanoTime()) <= 0) ||
                                w.isInterrupted()) {
                                if (s.tryCancel(e)) {
                                    clean(t, s);
                                    return null;
                                }
                            } else if ((item = s.item) != e) {
                                break;                   // recheck
                            } else if (stat <= 0) {
                                if (t.next == s) {
                                    if (stat < 0 && t.isFulfilled()) {
                                        stat = 0;        // yield once if first
                                        Thread.yield();
                                    }
                                    else {
                                        stat = 1;
                                        s.waiter = w;
                                    }
                                }
                            } else if (!timed) {
                                LockSupport.setCurrentBlocker(this);
                                try {
                                    ForkJoinPool.managedBlock(s);
                                } catch (InterruptedException cannotHappen) { }
                                LockSupport.setCurrentBlocker(null);
                            }
                            else if (nanos > SPIN_FOR_TIMEOUT_THRESHOLD)
                                LockSupport.parkNanos(this, nanos);
                        }
                        if (stat == 1)
                            s.forgetWaiter();
                        if (!s.isOffList()) {            // not already unlinked
                            advanceHead(t, s);           // unlink if head
                            if (item != null)            // and forget fields
                                s.item = s;
                        }
                        return (item != null) ? (E)item : e;
                    }

                } else if ((m = h.next) != null && t == tail && h == head) {
                    Thread waiter;
                    Object x = m.item;
                    boolean fulfilled = ((isData == (x == null)) &&
                                         x != m && m.casItem(x, e));
                    advanceHead(h, m);                    // (help) dequeue
                    if (fulfilled) {
                        if ((waiter = m.waiter) != null)
                            LockSupport.unpark(waiter);
                        return (x != null) ? (E)x : e;
                    }
                }
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         */
        void clean(QNode pred, QNode s) {
            s.forgetWaiter();
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            while (pred.next == s) { // Return early if already unlinked
                QNode h = head;
                QNode hn = h.next;   // Absorb cancelled first node as head
                if (hn != null && hn.isCancelled()) {
                    advanceHead(h, hn);
                    continue;
                }
                QNode t = tail;      // Ensure consistent read for tail
                if (t == h)
                    return;
                QNode tn = t.next;
                if (t != tail)
                    continue;
                if (tn != null) {
                    advanceTail(t, tn);
                    continue;
                }
                if (s != t) {        // If not tail, try to unsplice
                    QNode sn = s.next;
                    if (sn == s || pred.casNext(s, sn))
                        return;
                }
                QNode dp = cleanMe;
                if (dp != null) {    // Try unlinking previous cancelled node
                    QNode d = dp.next;
                    QNode dn;
                    if (d == null ||               // d is gone or
                        d == dp ||                 // d is off list or
                        !d.isCancelled() ||        // d not cancelled or
                        (d != t &&                 // d not tail and
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced
                        casCleanMe(dp, null);
                    if (dp == pred)
                        return;      // s is already saved node
                } else if (casCleanMe(null, pred))
                    return;          // Postpone cleaning s
            }
        }

        // VarHandle mechanics
        private static final VarHandle QHEAD;
        private static final VarHandle QTAIL;
        private static final VarHandle QCLEANME;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                QHEAD = l.findVarHandle(TransferQueue.class, "head",
                                        QNode.class);
                QTAIL = l.findVarHandle(TransferQueue.class, "tail",
                                        QNode.class);
                QCLEANME = l.findVarHandle(TransferQueue.class, "cleanMe",
                                           QNode.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }