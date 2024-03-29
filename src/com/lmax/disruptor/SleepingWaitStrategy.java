package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.lmax.disruptor.util.Util.getMinimumSequence;

/**
 * Sleeping strategy that initially spins, then uses a Thread.yield(),
 * and eventually for the minimum number of nanos
 * the OS and JVM will allow while the
 * {@link com.lmax.disruptor.EventProcessor}s are waiting on a barrier.
 *
 * This strategy is a good compromise between performance and CPU resource.
 * Latency spikes can occur after quiet periods.
 */
public final class SleepingWaitStrategy implements WaitStrategy
{
    private static final int RETRIES = 200;

    @Override
    public long waitFor(final long sequence, final Sequence cursor,
                        final Sequence[] dependents, final SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        int counter = RETRIES;

        if (0 == dependents.length)
        {
            while ((availableSequence = cursor.get()) < sequence)
            {
                counter = applyWaitMethod(barrier, counter);
            }
        }
        else
        {
            while ((availableSequence = getMinimumSequence(dependents)) < sequence)
            {
                counter = applyWaitMethod(barrier, counter);
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
        final long timeoutMs = sourceUnit.toMillis(timeout);
        final long startTime = System.currentTimeMillis();
        long availableSequence;
        int counter = RETRIES;

        if (0 == dependents.length)
        {
            while ((availableSequence = cursor.get()) < sequence)
            {
                counter = applyWaitMethod(barrier, counter);

                final long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > timeoutMs)
                {
                    break;
                }
            }
        }
        else
        {
            while ((availableSequence = getMinimumSequence(dependents)) < sequence)
            {
                counter = applyWaitMethod(barrier, counter);

                final long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > timeoutMs)
                {
                    break;
                }
            }
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
        throws AlertException
    {
        barrier.checkAlert();

        if (counter > 100)
        {
            --counter;
        }
        else if (counter > 0)
        {
            --counter;
            Thread.yield();
        }
        else
        {
            LockSupport.parkNanos(1L);
        }

        return counter;
    }
}
