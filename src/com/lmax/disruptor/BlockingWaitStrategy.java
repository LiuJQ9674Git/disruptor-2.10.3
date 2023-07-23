package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.lmax.disruptor.util.Util.getMinimumSequence;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 *
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Lock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();
    private volatile int numWaiters = 0;

    @Override
    public long waitFor(final long sequence, final Sequence cursor,
                        final Sequence[] dependents, final SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        if ((availableSequence = cursor.get()) < sequence)
        {
            lock.lock();
            try
            {
                ++numWaiters;
                while ((availableSequence = cursor.get()) < sequence)
                {
                    barrier.checkAlert();
                    processorNotifyCondition.await(1, MILLISECONDS);
                }
            }
            finally
            {
                --numWaiters;
                lock.unlock();
            }
        }

        if (0 != dependents.length)
        {
            while ((availableSequence = getMinimumSequence(dependents)) < sequence)
            {
                barrier.checkAlert();
            }
        }

        return availableSequence;
    }

    @Override
    public long waitFor(final long sequence, final Sequence cursor,
                        final Sequence[] dependents, final SequenceBarrier barrier,
                        final long timeout, final TimeUnit sourceUnit)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        if ((availableSequence = cursor.get()) < sequence)
        {
            lock.lock();
            try
            {
                ++numWaiters;
                while ((availableSequence = cursor.get()) < sequence)
                {
                    barrier.checkAlert();

                    if (!processorNotifyCondition.await(timeout, sourceUnit))
                    {
                        break;
                    }
                }
            }
            finally
            {
                --numWaiters;
                lock.unlock();
            }
        }

        if (0 != dependents.length)
        {
            while ((availableSequence = getMinimumSequence(dependents)) < sequence)
            {
                barrier.checkAlert();
            }
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        if (0 != numWaiters)
        {
            lock.lock();
            try
            {
                processorNotifyCondition.signalAll();
            }
            finally
            {
                lock.unlock();
            }
        }
    }
}
