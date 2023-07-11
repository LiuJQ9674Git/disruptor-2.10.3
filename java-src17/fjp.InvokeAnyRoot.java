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