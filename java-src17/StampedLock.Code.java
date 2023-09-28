package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.ReservedStackAccess;

public class StampedLock.Code17 implements java.io.Serializable {


    /** The number of bits to use for reader count before overflowing */
    private static final int LG_READERS = 7; // 127 readers       

    // Values for lock state and stamp operations 
    private static final long RUNIT = 1L;
    private static final long WBIT  = 1L << LG_READERS;
    private static final long RBITS = WBIT - 1L;
    private static final long RFULL = RBITS - 1L;
    private static final long ABITS = RBITS | WBIT;
    private static final long SBITS = ~RBITS; // note overlap with ABITS
    // not writing and conservatively non-overflowing
    private static final long RSAFE = ~(3L << (LG_READERS - 1));

    /*
     *ORIGIN:	256	    Hex->:	100
     *Bit-Size->:	                9			Hex-Size->:3
     *                                                                   1 0000 0000
     *WBIT:	    128	    Hex->:	80
     *                  Bit-Size->:	8			Hex-Size->:2
     *                                                                     1000 0000
     *                                                                     
     *RBITS:	127	    Hex->:	7f
     *                  Bit-Size->:	7			Hex-Size->:2
     *                                                                      111 1111
     *                                                                      
     *RFULL:	126	    Hex->:	7e
     *                  Bit-Size->:	7			Hex-Size->:2
     *                                                                      111 1110
     *                                                                      
     *ABITS:	255	    Hex->:	ff
     *                  Bit-Size->:	8			Hex-Size->:2
     *                                                                      1111 1111
     *                                                                      
     *SBITS:	127	    Hex->:	ffffffffffffff80
     *                  Bit-Size->:	64			Hex-Size->:16
     *1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1000 0000
     *
     *RSAFE:	-193	Hex->:	ffffffffffffff3f
     *                  Bit-Size->:	64			Hex-Size->:16
     *1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 0011 1111
     */
            
    /*
     * 3 stamp modes can be distinguished by examining (m = stamp & ABITS):
     * write mode: m == WBIT
     * optimistic read mode: m == 0L (even when read lock is held)
     * read mode: m > 0L && m <= RFULL (the stamp is a copy of state, but the
     * read hold count in the stamp is unused other than to determine mode)
     *
     * This differs slightly from the encoding of state:
     * (state & ABITS) == 0L indicates the lock is currently unlocked.
     * (state & ABITS) == RBITS is a special transient value
     * indicating spin-locked to manipulate reader bits overflow.
     */

    /** Initial value for lock state; avoids failure value zero. */
    private static final long ORIGIN = WBIT << 1;

    // Special value from cancelled acquire methods so caller can throw IE
    private static final long INTERRUPTED = 1L;

    // Bits for Node.status
    static final int WAITING   = 1;
    static final int CANCELLED = 0x80000000; // must be negative

    /** CLH nodes */
    abstract static class Node {
        volatile Node prev;       // initially attached via casTail
        volatile Node next;       // visibly nonnull when signallable
        Thread waiter;            // visibly nonnull when enqueued
        volatile int status;      // written by owner, atomic bit ops by others

        // methods for atomic operations
        final boolean casPrev(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }
        final boolean casNext(Node c, Node v) {  // for cleanQueue
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }
        final int getAndUnsetStatus(int v) {     // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }
        final void setPrevRelaxed(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }
        final void setStatusRelaxed(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }
        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }

        private static final long STATUS
            = U.objectFieldOffset(Node.class, "status");
        private static final long NEXT
            = U.objectFieldOffset(Node.class, "next");
        private static final long PREV
            = U.objectFieldOffset(Node.class, "prev");
    }

    static final class WriterNode extends Node { // node for writers
    }

    static final class ReaderNode extends Node { // node for readers
        volatile ReaderNode cowaiters;           // list of linked readers
        final boolean casCowaiters(ReaderNode c, ReaderNode v) {
            return U.weakCompareAndSetReference(this, COWAITERS, c, v);
        }
        final void setCowaitersRelaxed(ReaderNode p) {
            U.putReference(this, COWAITERS, p);
        }
        private static final long COWAITERS
            = U.objectFieldOffset(ReaderNode.class, "cowaiters");
    }

    /** Head of CLH queue */
    private transient volatile Node head;
    /** Tail (last) of CLH queue */
    private transient volatile Node tail;

    /** Lock sequence/state */
    private transient volatile long state;
    /** extra reader count when state read count saturated */
    private transient int readerOverflow;

    /**
     * Creates a new lock, initially in unlocked state.
     */
    public StampedLock() {
        //ORIGIN:	256	Hex->:	100 Bit-Size->:	9	Hex-Size->:3
        //1 0000 0000
        state = ORIGIN;
    }

    // internal lock methods

    private boolean casState(long expect, long update) {
        return U.compareAndSetLong(this, STATE, expect, update);
    }

    
    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a write stamp that can be used to unlock or convert mode
     */
    @ReservedStackAccess
    public long writeLock() {
        // try unconditional CAS confirming weak read
        //ORGIN:
        //                            1 0000 0000
        //
        //ABITS: 255	Hex->:	ff Bit-Size->:	8	Hex-Size->:2
        //                              1111 1111
        //~ABITS
        //1111 1111 1111 1111 1111 1111 0000 0000
        long s = U.getLongOpaque(this, STATE) & ~ABITS, nextState;
        //WBIT:	128	Hex->:	80
        //Bit-Size->:	8Hex-Size->:2
        //                              1000 0000
        //                            1 0000 0000
        if (casState(s, nextState = s | WBIT)) {
            U.storeStoreFence();
            return nextState;
        }
        return acquireWrite(false, false, 0L);
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a read stamp that can be used to unlock or convert mode
     */
    @ReservedStackAccess
    public long readLock() {
        // unconditionally optimistically try non-overflow case once
        //
        //256  1 0000 0000
        //ORIGIN:	256	Hex->:	100 Bit-Size->:	9	Hex-Size->:3
        //                            1 0000 0000
        //RSAFE:-193 Hex->:ffffffffffffff3f Bit-Size->:64	Hex-Size->:16
        //1111 1111 1111 1111 1111 1111 0011 1111
        //s:256
        long s = U.getLongOpaque(this, STATE) & RSAFE, nextState;
        if (casState(s, nextState = s + RUNIT))
            return nextState;
        else
            return acquireRead(false, false, 0L);
    }
    
    /**
     * Returns an unlocked state, incrementing the version and
     * avoiding special failure value 0L.
     *
     * @param s a write-locked state (or stamp)
     */
    private static long unlockWriteState(long s) {
        //WBIT:	    128	Hex->:	80  Bit-Size->:	8	Hex-Size->:2
        //  1000 0000
        //ORIGIN:   256	Hex->:	100 Bit-Size->:	9	Hex-Size->:3
        //1 0000 0000
        return ((s += WBIT) == 0L) ? ORIGIN : s;
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    @ReservedStackAccess
    public void unlockRead(long stamp) {
        long s, m;
        //RBITS:	127	Hex->:	7f Bit-Size->:	7	Hex-Size->:2
        //                               111 1111
        //SBITS:	-128 Hex->:	ffffffffffffff80 Bit-Size->: 64	Hex-Size->:16
        //1111 1111 1111 1111 1111 1111 1000 0000
        //RUNIT: 1
        if ((stamp & RBITS) != 0L) {
            while (((s = state) & SBITS) == (stamp & SBITS) &&
                   ((m = s & RBITS) != 0L)) {
                if (m < RFULL) {
                    if (casState(s, s - RUNIT)) {
                        if (m == RUNIT)
                            signalNext(head);
                        return;
                    }
                }
                else if (tryDecReaderOverflow(s) != 0L)
                    return;
            }
        }
        throw new IllegalMonitorStateException();
    }
    
    private long releaseWrite(long s) {
        long nextState = state = unlockWriteState(s);
        signalNext(head);
        return nextState;
    }
    
     /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    @ReservedStackAccess
    public void unlockWrite(long stamp) {
        //stamp:          1 1000 0000
        //WBIT:	128	Hex->:	80 Bit-Size->:	8	Hex-Size->:2
        //                  1000 0000
        if (state != stamp || (stamp & WBIT) == 0L){
            throw new IllegalMonitorStateException();
        }
        releaseWrite(stamp);
    }
    
    // ***************readLock writeLock ******************//
    
    /**
     * Exclusively acquires the lock if it is immediately available.
     *
     * @return a write stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryWriteLock() {
        return tryAcquireWrite();
    }
    
    /**
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a read stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        return tryAcquireRead();
    }
    
    @ReservedStackAccess
    private long tryAcquireWrite() {
        long s, nextState;
        //ABITS:255	Hex->:ff Bit-Size->:8 Hex-Size->:2
        //1111 1111
        //WBIT:	128	Hex->:	80 Bit-Size->:	8	Hex-Size->:2
        //1000 0000
        if (((s = state) & ABITS) == 0L && casState(s, nextState = s | WBIT)) {
            U.storeStoreFence();
            return nextState;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    @ReservedStackAccess
    public boolean tryUnlockWrite() {
        long s;
        if (((s = state) & WBIT) != 0L) {
            releaseWrite(s);
            return true;
        }
        return false;
    }
    
    @ReservedStackAccess
    private long tryAcquireRead() {
        for (long s, m, nextState;;) {
            if ((m = (s = state) & ABITS) < RFULL) {
                if (casState(s, nextState = s + RUNIT))
                    return nextState;
            }
            else if (m == WBIT)
                return 0L;
            else if ((nextState = tryIncReaderOverflow(s)) != 0L)
                return nextState;
        }
    }
    

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    @ReservedStackAccess
    public boolean tryUnlockRead() {
        long s, m;
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if (m < RFULL) {
                if (casState(s, s - RUNIT)) {
                    if (m == RUNIT)
                        signalNext(head);
                    return true;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }
    
    

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a write stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryWriteLock(long time, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            long nextState;
            if ((nextState = tryAcquireWrite()) != 0L)
                return nextState;
            if (nanos <= 0L)
                return 0L;
            nextState = acquireWrite(true, true, System.nanoTime() + nanos);
            if (nextState != INTERRUPTED)
                return nextState;
        }
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a read stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            long nextState;
            if (tail == head && (nextState = tryAcquireRead()) != 0L)
                return nextState;
            if (nanos <= 0L)
                return 0L;
            nextState = acquireRead(true, true, System.nanoTime() + nanos);
            if (nextState != INTERRUPTED)
                return nextState;
        }
        throw new InterruptedException();
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a write stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long writeLockInterruptibly() throws InterruptedException {
        long nextState;
        if (!Thread.interrupted() &&
            ((nextState = tryAcquireWrite()) != 0L ||
             (nextState = acquireWrite(true, false, 0L)) != INTERRUPTED))
            return nextState;
        throw new InterruptedException();
    }
    
    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a read stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long nextState;
        if (!Thread.interrupted() &&
            ((nextState = tryAcquireRead()) != 0L ||
             (nextState = acquireRead(true, false, 0L)) != INTERRUPTED))
            return nextState;
        throw new InterruptedException();
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        U.loadFence();
        return (stamp & SBITS) == (state & SBITS);
    }


    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlock(long stamp) {
        if ((stamp & WBIT) != 0L)
            unlockWrite(stamp);
        else
            unlockRead(stamp);
    }

    /**
     * If the lock state matches the given stamp, atomically performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, nextState;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                if (casState(s, nextState = s | WBIT)) {
                    U.storeStoreFence();
                    return nextState;
                }
            } else if (m == WBIT) {
                if (a != m)
                    break;
                return stamp;
            } else if (m == RUNIT && a != 0L) {
                if (casState(s, nextState = s - RUNIT + WBIT))
                    return nextState;
            } else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, atomically performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a, s, nextState;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((a = stamp & ABITS) >= WBIT) {
                if (s != stamp) // write stamp
                    break;
                nextState = state = unlockWriteState(s) + RUNIT;
                signalNext(head);
                return nextState;
            } else if (a == 0L) { // optimistic read stamp
                if ((s & ABITS) < RFULL) {
                    if (casState(s, nextState = s + RUNIT))
                        return nextState;
                } else if ((nextState = tryIncReaderOverflow(s)) != 0L)
                    return nextState;
            } else { // already a read stamp
                if ((s & ABITS) == 0L)
                    break;
                return stamp;
            }
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, atomically, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a, m, s, nextState;
        U.loadFence();
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((a = stamp & ABITS) >= WBIT) {
                if (s != stamp)   // write stamp
                    break;
                return releaseWrite(s);
            } else if (a == 0L) { // already an optimistic read stamp
                return stamp;
            } else if ((m = s & ABITS) == 0L) { // invalid read stamp
                break;
            } else if (m < RFULL) {
                if (casState(s, nextState = s - RUNIT)) {
                    if (m == RUNIT)
                        signalNext(head);
                    return nextState & SBITS;
                }
            } else if ((nextState = tryDecReaderOverflow(s)) != 0L)
                return nextState & SBITS;
        }
        return 0L;
    }


    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     *
     * @return a valid optimistic read stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }
    
    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Tells whether a stamp represents holding a lock exclusively.
     * This method may be useful in conjunction with
     * {@link #tryConvertToWriteLock}, for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToWriteLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isWriteLockStamp(stamp))
     *     sl.unlockWrite(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     *   write-lock operation
     * @since 10
     */
    public static boolean isWriteLockStamp(long stamp) {
        return (stamp & ABITS) == WBIT;
    }

    /**
     * Tells whether a stamp represents holding a lock non-exclusively.
     * This method may be useful in conjunction with
     * {@link #tryConvertToReadLock}, for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToReadLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isReadLockStamp(stamp))
     *     sl.unlockRead(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     *   read-lock operation
     * @since 10
     */
    public static boolean isReadLockStamp(long stamp) {
        return (stamp & RBITS) != 0L;
    }

    /**
     * Tells whether a stamp represents holding a lock.
     * This method may be useful in conjunction with
     * {@link #tryConvertToReadLock} and {@link #tryConvertToWriteLock},
     * for example: <pre> {@code
     * long stamp = sl.tryOptimisticRead();
     * try {
     *   ...
     *   stamp = sl.tryConvertToReadLock(stamp);
     *   ...
     *   stamp = sl.tryConvertToWriteLock(stamp);
     *   ...
     * } finally {
     *   if (StampedLock.isLockStamp(stamp))
     *     sl.unlock(stamp);
     * }}</pre>
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     *   read-lock or write-lock operation
     * @since 10
     */
    public static boolean isLockStamp(long stamp) {
        return (stamp & ABITS) != 0L;
    }

    /**
     * Tells whether a stamp represents a successful optimistic read.
     *
     * @param stamp a stamp returned by a previous StampedLock operation
     * @return {@code true} if the stamp was returned by a successful
     *   optimistic read operation, that is, a non-zero return from
     *   {@link #tryOptimisticRead()} or
     *   {@link #tryConvertToOptimisticRead(long)}
     * @since 10
     */
    public static boolean isOptimisticReadStamp(long stamp) {
        return (stamp & ABITS) == 0L && stamp != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
            ((s & ABITS) == 0L ? "[Unlocked]" :
             (s & WBIT) != 0L ? "[Write-locked]" :
             "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link Lock#newCondition()}
     * throws {@code UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        if ((v = readLockView) != null) return v;
        return readLockView = new ReadLockView();
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link Lock#newCondition()}
     * throws {@code UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        if ((v = writeLockView) != null) return v;
        return writeLockView = new WriteLockView();
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        if ((v = readWriteLockView) != null) return v;
        return readWriteLockView = new ReadWriteLockView();
    }

    // view classes

    final class ReadLockView implements Lock {
        public void lock() { readLock(); }
        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }
        public boolean tryLock() { return tryReadLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockRead(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class WriteLockView implements Lock {
        public void lock() { writeLock(); }
        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }
        public boolean tryLock() { return tryWriteLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockWrite(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() { return asReadLock(); }
        public Lock writeLock() { return asWriteLock(); }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        releaseWrite(s);
    }

    final void unstampedUnlockRead() {
        long s, m;
        while ((m = (s = state) & RBITS) > 0L) {
            if (m < RFULL) {
                if (casState(s, s - RUNIT)) {
                    if (m == RUNIT)
                        signalNext(head);
                    return;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // overflow handling methods

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) != RFULL)
            Thread.onSpinWait();
        else if (casState(s, s | RBITS)) {
            ++readerOverflow;
            return state = s;
        }
        return 0L;
    }

    /**
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) != RFULL)
            Thread.onSpinWait();
        else if (casState(s, s | RBITS)) {
            int r; long nextState;
            if ((r = readerOverflow) > 0) {
                readerOverflow = r - 1;
                nextState = s;
            }
            else
                nextState = s - RUNIT;
            return state = nextState;
        }
        return 0L;
    }

    // release methods

    /**
     * Wakes up the successor of given node, if one exists, and unsets its
     * WAITING status to avoid park race. This may fail to wake up an
     * eligible thread when one or more have been cancelled, but
     * cancelAcquire ensures liveness.
     */
    static final void signalNext(Node h) {
        Node s;
        if (h != null && (s = h.next) != null && s.status > 0) {
            s.getAndUnsetStatus(WAITING);
            LockSupport.unpark(s.waiter);
        }
    }

    /**
     * Removes and unparks all cowaiters of node, if it exists.
     */
    private static void signalCowaiters(ReaderNode node) {
        if (node != null) {
            for (ReaderNode c; (c = node.cowaiters) != null; ) {
                if (node.casCowaiters(c, c.cowaiters))
                    LockSupport.unpark(c.waiter);
            }
        }
    }

    // queue link methods
    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /** tries once to CAS a new dummy node for head */
    private void tryInitializeHead() {
        Node h = new WriterNode();
        if (U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * For explanation, see above and AbstractQueuedSynchronizer
     * internal documentation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param timed if true use timed waits
     * @param time the System.nanoTime value to timeout at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireWrite(boolean interruptible, boolean timed, long time) {
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        boolean interrupted = false, first = false;
        WriterNode node = null;
        Node pred = null;
        for (long s, nextState;;) {
            if (!first && (pred = (node == null) ? null : node.prev) != null &&
                !(first = (head == pred))) {
                if (pred.status < 0) {
                    cleanQueue();           // predecessor cancelled
                    continue;
                } else if (pred.prev == null) {
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            if ((first || pred == null) && ((s = state) & ABITS) == 0L &&
                casState(s, nextState = s | WBIT)) {
                U.storeStoreFence();
                if (first) {
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    node.waiter = null;
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
                return nextState;
            } else if (node == null) {          // retry before enqueuing
                node = new WriterNode();
            } else if (pred == null) {          // try to enqueue
                Node t = tail;
                node.setPrevRelaxed(t);
                if (t == null)
                    tryInitializeHead();
                else if (!casTail(t, node))
                    node.setPrevRelaxed(null);  // back out
                else
                    t.next = node;
            } else if (first && spins != 0) {   // reduce unfairness
                --spins;
                Thread.onSpinWait();
            } else if (node.status == 0) {      // enable signal
                if (node.waiter == null)
                    node.waiter = Thread.currentThread();
                node.status = WAITING;
            } else {
                long nanos;
                spins = postSpins = (byte)((postSpins << 1) | 1);
                if (!timed)
                    LockSupport.park(this);
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    break;
                node.clearStatus();
                if ((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        return cancelAcquire(node, interrupted);
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param timed if true use timed waits
     * @param time the System.nanoTime value to timeout at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireRead(boolean interruptible, boolean timed, long time) {
        boolean interrupted = false;
        ReaderNode node = null;
        /*
         * Loop:
         *   if empty, try to acquire
         *   if tail is Reader, try to cowait; restart if leader stale or cancels
         *   else try to create and enqueue node, and wait in 2nd loop below
         */
        for (;;) {
            ReaderNode leader; long nextState;
            Node tailPred = null, t = tail;
            if ((t == null || (tailPred = t.prev) == null) &&
                (nextState = tryAcquireRead()) != 0L) // try now if empty
                return nextState;
            else if (t == null)
                tryInitializeHead();
            else if (tailPred == null || !(t instanceof ReaderNode)) {
                if (node == null)
                    node = new ReaderNode();
                if (tail == t) {
                    node.setPrevRelaxed(t);
                    if (casTail(t, node)) {
                        t.next = node;
                        break; // node is leader; wait in loop below
                    }
                    node.setPrevRelaxed(null);
                }
            } else if ((leader = (ReaderNode)t) == tail) { // try to cowait
                for (boolean attached = false;;) {
                    if (leader.status < 0 || leader.prev == null)
                        break;
                    else if (node == null)
                        node = new ReaderNode();
                    else if (node.waiter == null)
                        node.waiter = Thread.currentThread();
                    else if (!attached) {
                        ReaderNode c = leader.cowaiters;
                        node.setCowaitersRelaxed(c);
                        attached = leader.casCowaiters(c, node);
                        if (!attached)
                            node.setCowaitersRelaxed(null);
                    } else {
                        long nanos = 0L;
                        if (!timed)
                            LockSupport.park(this);
                        else if ((nanos = time - System.nanoTime()) > 0L)
                            LockSupport.parkNanos(this, nanos);
                        interrupted |= Thread.interrupted();
                        if ((interrupted && interruptible) ||
                            (timed && nanos <= 0L))
                            return cancelCowaiter(node, leader, interrupted);
                    }
                }
                if (node != null)
                    node.waiter = null;
                long ns = tryAcquireRead();
                signalCowaiters(leader);
                if (interrupted)
                    Thread.currentThread().interrupt();
                if (ns != 0L)
                    return ns;
                else
                    node = null; // restart if stale, missed, or leader cancelled
            }
        }

        // node is leader of a cowait group; almost same as acquireWrite
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        boolean first = false;
        Node pred = null;
        for (long nextState;;) {
            if (!first && (pred = node.prev) != null &&
                !(first = (head == pred))) {
                if (pred.status < 0) {
                    cleanQueue();           // predecessor cancelled
                    continue;
                } else if (pred.prev == null) {
                    Thread.onSpinWait();    // ensure serialization
                    continue;
                }
            }
            if ((first || pred == null) &&
                (nextState = tryAcquireRead()) != 0L) {
                if (first) {
                    node.prev = null;
                    head = node;
                    pred.next = null;
                    node.waiter = null;
                }
                signalCowaiters(node);
                if (interrupted)
                    Thread.currentThread().interrupt();
                return nextState;
            } else if (first && spins != 0) {
                --spins;
                Thread.onSpinWait();
            } else if (node.status == 0) {
                if (node.waiter == null)
                    node.waiter = Thread.currentThread();
                node.status = WAITING;
            } else {
                long nanos;
                spins = postSpins = (byte)((postSpins << 1) | 1);
                if (!timed)
                    LockSupport.park(this);
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else
                    break;
                node.clearStatus();
                if ((interrupted |= Thread.interrupted()) && interruptible)
                    break;
            }
        }
        return cancelAcquire(node, interrupted);
    }

    // Cancellation support

    /**
     * Possibly repeatedly traverses from tail, unsplicing cancelled
     * nodes until none are found. Unparks nodes that may have been
     * relinked to be next eligible acquirer.
     */
    private void cleanQueue() {
        for (;;) {                               // restart point
            for (Node q = tail, s = null, p, n;;) { // (p, q, s) triples
                if (q == null || (p = q.prev) == null)
                    return;                      // end of list
                if (s == null ? tail != q : (s.prev != q || s.status < 0))
                    break;                       // inconsistent
                if (q.status < 0) {              // cancelled
                    if ((s == null ? casTail(q, p) : s.casPrev(q, p)) &&
                        q.prev == p) {
                        p.casNext(q, s);         // OK if fails
                        if (p.prev == null)
                            signalNext(p);
                    }
                    break;
                }
                if ((n = p.next) != q) {         // help finish
                    if (n != null && q.prev == p && q.status >= 0) {
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
     * If leader exists, possibly repeatedly traverses cowaiters,
     * unsplicing the given cancelled node until not found.
     */
    private void unlinkCowaiter(ReaderNode node, ReaderNode leader) {
        if (leader != null) {
            while (leader.prev != null && leader.status >= 0) {
                for (ReaderNode p = leader, q; ; p = q) {
                    if ((q = p.cowaiters) == null)
                        return;
                    if (q == node) {
                        p.casCowaiters(q, q.cowaiters);
                        break;  // recheck even if succeeded
                    }
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue, wakes up any cowaiters, and possibly wakes up successor
     * to recheck status.
     *
     * @param node the waiter (may be null if not yet enqueued)
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelAcquire(Node node, boolean interrupted) {
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            cleanQueue();
            if (node instanceof ReaderNode)
                signalCowaiters((ReaderNode)node);
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    /**
     * If node non-null, forces cancel status and unsplices from
     * leader's cowaiters list unless/until it is also cancelled.
     *
     * @param node if non-null, the waiter
     * @param leader if non-null, the node heading cowaiters list
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelCowaiter(ReaderNode node, ReaderNode leader,
                                boolean interrupted) {
        if (node != null) {
            node.waiter = null;
            node.status = CANCELLED;
            unlinkCowaiter(node, leader);
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE
        = U.objectFieldOffset(StampedLock.class, "state");
    private static final long HEAD
        = U.objectFieldOffset(StampedLock.class, "head");
    private static final long TAIL
        = U.objectFieldOffset(StampedLock.class, "tail");

    static {
        Class<?> ensureLoaded = LockSupport.class;
    }
}
