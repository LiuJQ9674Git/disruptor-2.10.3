
package java.util.concurrent;

import java.io.ObjectStreamField;
import java.math.BigInteger;
import java.security.AccessControlContext;
import java.util.Map;
import java.util.Random;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import jdk.internal.util.random.RandomSupport;
import jdk.internal.util.random.RandomSupport.*;
import jdk.internal.misc.Unsafe;
import jdk.internal.misc.VM;

/**
 * A random number generator (with period 2<sup>64</sup>) isolated
 * to the current thread.  Like the global {@link java.util.Random}
 * generator used by the {@link java.lang.Math} class,
 * a {@code ThreadLocalRandom} is initialized
 * with an internally generated seed that may not otherwise be
 * modified. When applicable, use of {@code ThreadLocalRandom} rather
 * than shared {@code Random} objects in concurrent programs will
 * typically encounter much less overhead and contention.  Use of
 * {@code ThreadLocalRandom} is particularly appropriate when multiple
 * tasks (for example, each a {@link ForkJoinTask}) use random numbers
 * in parallel in thread pools.
 *
 * 与当前线程隔离的随机数生成器（2的64次方）。像Math类使用的全局随机生成器一样，
 * ThreadLocalRandom是用内部生成的种子初始化的，否则可能无法修改。
 * 如果适用，在并发程序中使用ThreadLocalRandom而不是共享Random对象通常会遇到更少的开销和争用。
 * 当多个任务（例如，每个任务一个ForkJoinTask）在线程池中并行使用随机数时，
 * ThreadLocalRandom的使用尤其合适。
 * 
 * <p>Usages of this class should typically be of the form:
 * {@code ThreadLocalRandom.current().nextX(...)} (where
 * {@code X} is {@code Int}, {@code Long}, etc).
 * When all usages are of this form, it is never possible to
 * accidentally share a {@code ThreadLocalRandom} across multiple threads.
 *
 * 此类的用法通常应为：ThreadLocalRandom.current（）.nextX（…）（其中X是Int、Long等）。
 * 当所有使用都是这种形式时，绝不可能意外地在多个线程之间共享ThreadLocalRandom。
 * 
 * <p>This class also provides additional commonly used bounded random
 * generation methods.
 *
 * 此类还提供了其他常用的有界随机生成方法。
 * <p>Instances of {@code ThreadLocalRandom} are not cryptographically
 * secure.  Consider instead using {@link java.security.SecureRandom}
 * in security-sensitive applications. Additionally,
 * default-constructed instances do not use a cryptographically random
 * seed unless the {@linkplain System#getProperty system property}
 * {@code java.util.secureRandomSeed} is set to {@code true}.
 *
 * ThreadLocalRandom的实例在加密方面不安全。
 * 请考虑在安全敏感的应用程序中使用java.security.SecureRandom。
 * 此外，默认构造的实例不使用加密随机种子，除非系统属性java.util.secureRandomSeed设置为true。
 * 
 * @since 1.7
 * @author Doug Lea
 */

@RandomGeneratorProperties(
        name = "ThreadLocalRandom",
        i = 64, j = 0, k = 0,
        equidistribution = 1
)
public class ThreadLocalRandom extends Random {
    /*
     * This class implements the java.util.Random API (and subclasses
     * Random) using a single static instance that accesses 64 bits of
     * random number state held in class java.lang.Thread (field
     * threadLocalRandomSeed). In doing so, it also provides a home
     * for managing package-private utilities that rely on exactly the
     * same state as needed to maintain the ThreadLocalRandom
     * instances. We leverage the need for an initialization flag
     * field to also use it as a "probe" -- a self-adjusting thread
     * hash used for contention avoidance, as well as a secondary
     * simpler (xorShift) random seed that is conservatively used to
     * avoid otherwise surprising users by hijacking the
     * ThreadLocalRandom sequence.  The dual use is a marriage of
     * convenience, but is a simple and efficient way of reducing
     * application-level overhead and footprint of most concurrent
     * programs. Even more opportunistically, we also define here
     * other package-private utilities that access Thread class
     * fields.
     *
     * 该类使用单个静态实例实现java.util.Random API（并将Random子类化），
     * 该实例访问java.lang.Thread（字段threadLocalRandomSeed）类中的64位随机数状态。
     * 在这样做的过程中，它还为管理包专用实用程序提供了一个主页，
     * 这些实用程序依赖于与维护ThreadLocalRandom实例所需完全相同的状态。
     * 我们利用对初始化标志字段的需求，也将其用作“探针”——一个用于避免争用的自调整线程哈希，
     * 以及一个次要的更简单（xorShift）随机种子，
     * 该种子被保守地用于避免劫持ThreadLocalRandom序列而让用户感到惊讶。
     * 双重使用是一种方便的结合，但却是一种简单有效的方法，
     * 可以减少大多数并发程序的应用程序级开销和占用空间。
     * 更为机会主义的是，我们还在这里定义了访问Thread类字段的其他包专用实用程序。
     * 
     * Even though this class subclasses java.util.Random, it uses the
     * same basic algorithm as java.util.SplittableRandom.  (See its
     * internal documentation for explanations, which are not repeated
     * here.)  Note that ThreadLocalRandom is not a "splittable" generator
     * (it does not support the split method), but it behaves as if
     * one instance of the SplittableRandom algorithm had been
     * created for each thread, each with a distinct gamma parameter
     * (calculated from the thread id).
     *
     * 尽管这个类是java.util.Random的子类，但它使用与java.util.SplitableRandom相同的基本算法，
     * 每个具有不同的gamma参数（根据线程id计算）。
     * 
     * Because this class is in a different package than class Thread,
     * field access methods use Unsafe to bypass access control rules.
     * To conform to the requirements of the Random superclass
     * constructor, the common static ThreadLocalRandom maintains an
     * "initialized" field for the sake of rejecting user calls to
     * setSeed while still allowing a call from constructor.  Note
     * that serialization is completely unnecessary because there is
     * only a static singleton.  But we generate a serial form
     * containing "rnd" and "initialized" fields to ensure
     * compatibility across versions.
     *
     * 由于此类与类Thread在不同的包中，因此字段访问方法使用Unsafe绕过访问控制规则。
     * 为了符合Random超类构造函数的要求，公共静态ThreadLocalRandom维护了一个“initialized”字段，
     * 以拒绝用户对setSeed的调用，同时仍然允许来自构造函数的调用。请注意，序列化是完全不必要的，
     * 因为只有一个静态的singleton。但我们生成了一个包含“rnd”和“initialized”字段的串行表单，
     * 以确保跨版本的兼容性。
     * 
     * Implementations of non-core methods are mostly the same as in
     * SplittableRandom, that were in part derived from a previous
     * version of this class.
     *
     *非核心方法的实现大多与SplitableRandom中的方法相同，
     *它们在一定程度上是从该类的早期版本派生而来的。
     *
     * This implementation of ThreadLocalRandom overrides the
     * definition of the nextGaussian() method in the class Random,
     * and instead uses the ziggurat-based algorithm that is the
     * default for the RandomGenerator interface.
     *
     * ThreadLocalRandom的这个实现覆盖了类Random中nextGaussian（）方法的定义，
     * 而是使用基于ziggurat的算法，这是RandomGenerator接口的默认算法
     */

    private static int mix32(long z) {
        //1111 1111 0101 0001 1010 1111 1101 0111 1110 1101 0101 0101 1000 1100 1100 1101
        //Bit-Size->:	64			Hex-Size->:16
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        //Bit-Size->:	64			Hex-Size->:16
        //1100 0100 1100 1110 1011 1001 1111 1110 0001 1010 1000 0101 1110 1100 0101 0011
        return (int)(((z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L) >>> 32);
    }

    /**
     * Field used only during singleton initialization.
     * True when constructor completes.
     * 仅在单例初始化期间使用的字段。构造函数完成时为True。
     */
    boolean initialized;

    /** Constructor used only for static singleton */
    private ThreadLocalRandom() {
        initialized = true; // false during super() call
    }

    /**
     * Initialize Thread fields for the current thread.  Called only
     * when Thread.threadLocalRandomProbe is zero, indicating that a
     * thread local seed value needs to be generated. Note that even
     * though the initialization is purely thread-local, we need to
     * rely on (static) atomic generators to initialize the values.
     *
     * 初始化当前线程的线程字段。仅当Thread.threadLocalRandomProbe为零时调用，
     * 这表示需要生成线程本地种子值。请注意，即使初始化是纯线程本地的，
     * 我们也需要依赖（静态）原子生成器来初始化值。
     */
    static final void localInit() {
        int p = probeGenerator.addAndGet(PROBE_INCREMENT);
        int probe = (p == 0) ? 1 : p; // skip 0
        long seed = RandomSupport.mixMurmur64(seeder.getAndAdd(SEEDER_INCREMENT));
        Thread t = Thread.currentThread();
        U.putLong(t, SEED, seed);
        U.putInt(t, PROBE, probe);
    }

    /**
     * Returns the current thread's {@code ThreadLocalRandom} object.
     * Methods of this object should be called only by the current thread,
     * not by other threads.
     *
     * 返回当前线程的｛ThreadLocalRandom｝对象。
     * 此对象的方法只能由当前线程调用，而不能由其他线程调用。
     * 
     * @return the current thread's {@code ThreadLocalRandom}
     */
    public static ThreadLocalRandom current() {
        if (U.getInt(Thread.currentThread(), PROBE) == 0)
            localInit();
        return instance;
    }

    /**
     * Throws {@code UnsupportedOperationException}.  Setting seeds in
     * this generator is not supported.
     * 
     * 抛出｛@code UnsupportedOperationException｝。不支持在此生成器中设置种子。
     * @throws UnsupportedOperationException always
     */
    public void setSeed(long seed) {
        // only allow call from super() constructor
        if (initialized)
            throw new UnsupportedOperationException();
    }

    /**
     * Update the thread local seed value by adding to it the sum
     * of {@code GOLDEN_GAMMA} (an odd value) and twice the thread id.
     * This sum is always odd (to guarantee that the generator
     * has maximum period) and is different for different threads.
     * Because thread id values are allocated consecutively starting
     * from 0, the high 32 bits of this sum will be the same as the
     * high 32 bits of {@code GOLDEN_GAMMA} unless an extremely large
     * number of threads have been created, and so the overall
     * value added to the thread local seed value will have at least
     * fourteen 01 and 10 transitions (see the documentation for the
     * method {@code mixGamma} in class {@code SplittableRandom}),
     * which should provide adequate statistical quality for
     * applications likely to use {@code ThreadLocalRandom}.
     *
     * 更新线程本地种子值，方法是将{GOLDEN_GAMMA}（奇数）和线程id的两倍相加。
     * 这个和总是奇数（以确保生成器具有最大周期），并且对于不同的线程是不同的。
     * 因为线程id值是从0开始连续分配的，所以除非已经创建了极其大量的线程，
     * 否则该和的高32位将与{GOLDEN_GAMMA}的高32比特相同，
     * 因此，添加到线程本地种子值的总值将至少有14个01和10个转换（
     * 请参阅类{SplitableRandom}中方法{mixGamma}的文档），
     * 这应该为可能使用{ThreadLocalRandom}的应用程序提供足够的统计质量。
     */
    final long nextSeed() {
        Thread t; long r; // read and update per-thread seed
        U.putLong(t = Thread.currentThread(), SEED,
                  r = U.getLong(t, SEED) + (t.getId() << 1) + GOLDEN_GAMMA);
        return r;
    }

    /**
     * Generates a pseudorandom number with the indicated number of
     * low-order bits.  Because this class has no subclasses, this
     * method cannot be invoked or overridden.
     *
     * @param  bits random bits
     * @return the next pseudorandom value from this random number
     *         generator's sequence
     */
    protected int next(int bits) {
        return nextInt() >>> (32 - bits);
    }

    // Within-package utilities

    /*
     * Descriptions of the usages of the methods below can be found in
     * the classes that use them. Briefly, a thread's "probe" value is
     * a non-zero hash code that (probably) does not collide with
     * other existing threads with respect to any power of two
     * collision space. When it does collide, it is pseudo-randomly
     * adjusted (using a Marsaglia XorShift). The nextSecondarySeed
     * method is used in the same contexts as ThreadLocalRandom, but
     * only for transient usages such as random adaptive spin/block
     * sequences for which a cheap RNG suffices and for which it could
     * in principle disrupt user-visible statistical properties of the
     * main ThreadLocalRandom if we were to use it.
     *
     * 下面方法用法的描述可以在使用它们的类中找到。
     * 简言之，线程的“探测”值是一个非零哈希代码，
     * 它（可能）不会与其他现有线程在任何二次方冲突空间中发生冲突。
     * 当它确实发生碰撞时，它会被伪随机调整（使用Marsaglia XorShift）。
     * nextSecondarySeed方法在与ThreadLocalRandom相同的上下文中使用，
     * 但仅用于临时用途，如随机自适应旋转/块序列，廉价的RNG就足够了，
     * 如果我们使用它，原则上它可以破坏主ThreadLocalRandom.的用户可见统计特性。
     * 
     * Note: Because of package-protection issues, versions of some
     * these methods also appear in some subpackage classes.
     */

    /**
     * Returns the probe value for the current thread without forcing
     * initialization. Note that invoking ThreadLocalRandom.current()
     * can be used to force initialization on zero return.
     */
    static final int getProbe() {
        return U.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        U.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Returns the pseudo-randomly initialized or updated secondary seed.
     */
    static final int nextSecondarySeed() {
        int r;
        Thread t = Thread.currentThread();
        if ((r = U.getInt(t, SECONDARY)) != 0) {
            r ^= r << 13;   // xorshift
            r ^= r >>> 17;
            r ^= r << 5;
        }
        else if ((r = mix32(seeder.getAndAdd(SEEDER_INCREMENT))) == 0)
            r = 1; // avoid zero
        U.putInt(t, SECONDARY, r);
        return r;
    }

    // Support for other package-private ThreadLocal access

    /**
     * Erases ThreadLocals by nulling out Thread maps.
     */
    static final void eraseThreadLocals(Thread thread) {
        U.putReference(thread, THREADLOCALS, null);
        U.putReference(thread, INHERITABLETHREADLOCALS, null);
    }

    static final void setInheritedAccessControlContext(Thread thread,
        @SuppressWarnings("removal") AccessControlContext acc) {
        U.putReferenceRelease(thread, INHERITEDACCESSCONTROLCONTEXT, acc);
    }

    // Serialization support

    private static final long serialVersionUID = -5851777807851030925L;

    /**
     * @serialField rnd long
     *              seed for random computations
     * @serialField initialized boolean
     *              always true
     */
    private static final ObjectStreamField[] serialPersistentFields = {
        new ObjectStreamField("rnd", long.class),
        new ObjectStreamField("initialized", boolean.class),
    };

    /**
     * Saves the {@code ThreadLocalRandom} to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {

        java.io.ObjectOutputStream.PutField fields = s.putFields();
        fields.put("rnd", U.getLong(Thread.currentThread(), SEED));
        fields.put("initialized", true);
        s.writeFields();
    }

    /**
     * Returns the {@link #current() current} thread's {@code ThreadLocalRandom}.
     * @return the {@link #current() current} thread's {@code ThreadLocalRandom}
     */
    private Object readResolve() {
        return current();
    }

    // Static initialization

    /**
     * The seed increment.  This must be an odd value for the generator to
     * have the maximum period (2 to the 64th power).
     *
     * The value 0x9e3779b97f4a7c15L is odd, and moreover consists of the
     * first 64 bits of the fractional part of the golden ratio,
     * which is known to generate good Weyl sequences.
     */
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    /**
     * The increment for generating probe values.
     */
    private static final int PROBE_INCREMENT = 0x9e3779b9;

    /**
     * The increment of seeder per new instance.
     */
    private static final long SEEDER_INCREMENT = 0xbb67ae8584caa73bL;

    // IllegalArgumentException messages
    static final String BAD_BOUND = "bound must be positive";
    static final String BAD_RANGE = "bound must be greater than origin";
    static final String BAD_SIZE  = "size must be non-negative";

    // Unsafe mechanics
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long SEED
        = U.objectFieldOffset(Thread.class, "threadLocalRandomSeed");
    private static final long PROBE
        = U.objectFieldOffset(Thread.class, "threadLocalRandomProbe");
    private static final long SECONDARY
        = U.objectFieldOffset(Thread.class, "threadLocalRandomSecondarySeed");
    private static final long THREADLOCALS
        = U.objectFieldOffset(Thread.class, "threadLocals");
    private static final long INHERITABLETHREADLOCALS
        = U.objectFieldOffset(Thread.class, "inheritableThreadLocals");
    private static final long INHERITEDACCESSCONTROLCONTEXT
        = U.objectFieldOffset(Thread.class, "inheritedAccessControlContext");

    /** Generates per-thread initialization/probe field */
    private static final AtomicInteger probeGenerator = new AtomicInteger();

    /** The common ThreadLocalRandom */
    private static final ThreadLocalRandom instance = new ThreadLocalRandom();

    /**
     * The next seed for default constructors.
     */
    private static final AtomicLong seeder
        = new AtomicLong(RandomSupport.mixMurmur64(System.currentTimeMillis()) ^
                         RandomSupport.mixMurmur64(System.nanoTime()));

    // at end of <clinit> to survive static initialization circularity
    static {
        String sec = VM.getSavedProperty("java.util.secureRandomSeed");
        if (Boolean.parseBoolean(sec)) {
            byte[] seedBytes = java.security.SecureRandom.getSeed(8);
            long s = (long)seedBytes[0] & 0xffL;
            for (int i = 1; i < 8; ++i)
                s = (s << 8) | ((long)seedBytes[i] & 0xffL);
            seeder.set(s);
        }
    }

    @SuppressWarnings("serial")
    private static final class ThreadLocalRandomProxy extends Random {
        static final Random PROXY = new ThreadLocalRandomProxy();

        public int nextInt() {
            return ThreadLocalRandom.current().nextInt();
        }

        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean nextBoolean() {
        return super.nextBoolean();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int nextInt() {
        return mix32(nextSeed());
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public int nextInt(int bound) {
        return super.nextInt(bound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public int nextInt(int origin, int bound) {
        return super.nextInt(origin, bound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long nextLong() {
        return RandomSupport.mixMurmur64(nextSeed());
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public long nextLong(long bound) {
        return super.nextLong(bound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    @Override
    public long nextLong(long origin, long bound) {
        return super.nextLong(origin, bound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float nextFloat() {
        return super.nextFloat();
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @implNote {@inheritDoc}
     */
    @Override
    public float nextFloat(float bound) {
         return super.nextFloat(bound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @implNote {@inheritDoc}
     */
    @Override
    public float nextFloat(float origin, float bound) {
        return super.nextFloat(origin, bound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double nextDouble() {
        return super.nextDouble();
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @implNote {@inheritDoc}
     */
    @Override
    public double nextDouble(double bound) {
        return super.nextDouble(bound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @implNote {@inheritDoc}
     */
    @Override
    public double nextDouble(double origin, double bound) {
        return super.nextDouble(origin, bound);
    }
    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public IntStream ints(long streamSize) {
        return AbstractSpliteratorGenerator.ints(ThreadLocalRandomProxy.PROXY, streamSize);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code ints(Long.MAX_VALUE)}.
     * @since 1.8
     */
    @Override
    public IntStream ints() {
        return AbstractSpliteratorGenerator.ints(ThreadLocalRandomProxy.PROXY);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound) {
        return AbstractSpliteratorGenerator.ints(ThreadLocalRandomProxy.PROXY,
            streamSize, randomNumberOrigin, randomNumberBound);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code ints(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}.
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public IntStream ints(int randomNumberOrigin, int randomNumberBound) {
        return AbstractSpliteratorGenerator.ints(ThreadLocalRandomProxy.PROXY,
                                                 randomNumberOrigin, randomNumberBound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public LongStream longs(long streamSize) {
        return AbstractSpliteratorGenerator.longs(ThreadLocalRandomProxy.PROXY, streamSize);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code longs(Long.MAX_VALUE)}.
     * @since 1.8
     */
    @Override
    public LongStream longs() {
        return AbstractSpliteratorGenerator.longs(ThreadLocalRandomProxy.PROXY);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound) {
        return AbstractSpliteratorGenerator.longs(ThreadLocalRandomProxy.PROXY, streamSize,
                                                  randomNumberOrigin, randomNumberBound);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code longs(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}.
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public LongStream longs(long randomNumberOrigin, long randomNumberBound) {
        return AbstractSpliteratorGenerator.longs(ThreadLocalRandomProxy.PROXY,
                                                  randomNumberOrigin, randomNumberBound);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public DoubleStream doubles(long streamSize) {
        return AbstractSpliteratorGenerator.doubles(ThreadLocalRandomProxy.PROXY, streamSize);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code doubles(Long.MAX_VALUE)}.
     * @since 1.8
     */
    @Override
    public DoubleStream doubles() {
        return AbstractSpliteratorGenerator.doubles(ThreadLocalRandomProxy.PROXY);
    }

    /**
     * {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public DoubleStream doubles(long streamSize, double randomNumberOrigin, double randomNumberBound) {
        return AbstractSpliteratorGenerator.doubles(ThreadLocalRandomProxy.PROXY,
                                                    streamSize, randomNumberOrigin, randomNumberBound);
    }

    /**
     * {@inheritDoc}
     * @implNote This method is implemented to be equivalent to
     *           {@code doubles(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}.
     * @throws IllegalArgumentException {@inheritDoc}
     * @since 1.8
     */
    @Override
    public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound) {
        return AbstractSpliteratorGenerator.doubles(ThreadLocalRandomProxy.PROXY,
                                                    randomNumberOrigin, randomNumberBound);
    }

}
