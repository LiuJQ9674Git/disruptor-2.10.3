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