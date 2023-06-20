package jdk.vm.ci.code;

/**
 * Constants and intrinsic definition for memory barriers.
 *
 * The {@code JMM_*} constants capture the memory barriers necessary to
 * implement the Java Memory Model with respect to volatile field accesses.
 * 
 * 常量捕获了实现Java内存模型所需的内存屏障，这些屏障涉及volatile字段访问。
 * 
 * <pre>
 * Volatile variables demand their effects be made known to all CPU's in
 * order.  Store buffers on most chips allow reads &amp; writes to reorder;
 *
 * Volatile变量要求所有CPU按顺序知道其影响。大多数芯片上的存储缓冲区允许读写重新排序
 * 
 * the JMM's ReadAfterWrite.java test fails in -Xint mode without some kind of
 * memory barrier (i.e., it's not sufficient that the interpreter does not
 * reorder volatile references, the hardware also must not reorder them).
 *
 * According to the new Java Memory Model (JMM):
 * (1) All volatiles are serialized wrt to each other.
 * ALSO reads &amp; writes act as acquire &amp; release,
 *
 * 所有volatiles相互串行化。同时读取reads/写入writes作为获取acquire/释放release
 * so:
 * (2) A read cannot let unrelated NON-volatile memory refs that happen after
 * the read float up to before the read.  It's OK for non-volatile memory refs
 * that happen before the volatile read to float down below it.
 *
 * 对volatile读之后不相关的NON-volatile内存引用的读取不能浮动到读取volatiles之前。
 * 对volatile读之前的non-volatile引用读取不能漂浮（重排）到volatile读取之后。
 * 
 * (3) Similarly, a volatile write cannot let unrelated NON-volatile memory refs
 * that happen BEFORE the write float down to after the write.  It's OK for
 * non-volatile memory refs that happen after the volatile write to float up
 * before it.
 *
 * 对于volatile写前的NON-volatile写，不能漂移（重排）到volatile引用之后。
 * 对于volatile之后的non-volatile写，不能漂移到其前面
 * 
 * We only put in barriers around volatile refs (they are expensive), not
 * _between_ memory refs (which would require us to track the flavor of the
 * previous memory refs).  Requirements (2) and (3) require some barriers
 * before volatile stores and after volatile loads.  These nearly cover
 * requirement (1) but miss the volatile-store-volatile-load case.  This final
 * case is placed after volatile-stores although it could just as well go
 * before volatile-loads.
 * </pre>
 */
public class MemoryBarriers {

    /**
     * The sequence {@code Load1; LoadLoad; Load2} ensures that {@code Load1}'s
     * data are loaded before data accessed by {@code Load2} and
     * all subsequent load instructions are loaded.
     * In general, explicit {@code LoadLoad} barriers are needed on processors
     * that perform speculative loads and/or out-of-order processing
     * in which waiting load instructions can bypass waiting stores.
     * On processors that guarantee to always preserve load ordering,
     * these barriers amount to no-ops.
     *
     * 序列｛@code Load1；LoadLoad；Load2｝确保在加载Load2访问的数据和
     * 所有后续加载指令之前加载Load1的数据。
     * 通常，在执行推测加载和/或无序处理的处理器上需要显式LoadLoad屏障，
     * 
     * 在这些处理器中，等待加载指令可以绕过等待存储。
     * 在保证始终保持负载load顺序的处理器上，这些屏障相当于没有操作。
     */
    public static final int LOAD_LOAD = 0x0001;

    /**
     * The sequence {@code Load1; LoadStore; Store2} ensures that
     * {@code Load1}'s data are loaded before all data associated
     * with {@code Store2} and subsequent store instructions are flushed.
     * 
     * {@code LoadStore} barriers are needed only
     * on those out-of-order processors in which waiting
     * store instructions can bypass loads.
     *
     * 序列｛@code Load1；LoadStore；Store2｝确保在Store2和后续存储store指令相关联的
     * 所有数据刷新flushed之前加载调用Load1装载数据。
     * 
     * LoadStore只有在那些等待存储指令可以绕过加载的无序处理器上才需要屏障。
     */
    public static final int LOAD_STORE = 0x0002;

    /**
     * The sequence {@code Store1; StoreLoad; Load2} ensures that
     * {@code Store1}'s data are made visible to other processors
     * (i.e., flushed to main memory) before data accessed by
     * {@code Load2} and all subsequent load instructions are loaded.
     *
     * 在加载Load2访问的数据和所有后续加载指令之前，Store1的数据对其他处理器可见（
     * 即刷新到主内存）
     * 
     * {@code StoreLoad} barriers protect against a subsequent load
     * incorrectly using {@code Store1}'s data value rather than
     * that from a more recent store to the same location performed
     * by a different processor.
     * 
     * 屏障使用Store1的数据值而不是由不同的处理器执行的从最近的存储到同一位置（地址）的数据值
     * 来防止后续错误加载。
     * 
     * Because of this, on the processors discussed below,
     * a {@code StoreLoad} is strictly necessary only for separating stores from
     * subsequent loads of the same location(s) as were stored
     * before the barrier.
     *
     *因此，在下面讨论的处理器上，StoreLoad仅在将存储与屏障之前存储的相同位置的
     *后续加载分离时才是严格必要的。
     *
     * {@code StoreLoad} barriers are needed on nearly all recent
     * multiprocessors, and are usually the most expensive kind.
     * Part of the reason they are expensive is that they must disable mechanisms
     * that ordinarily bypass cache to satisfy loads
     * from write-buffers.
     * This might be implemented by letting the buffer fully flush, among other
     * possible stalls.
     *
     * StoreLoad屏障在几乎所有最近的多处理器上都是需要的，并且通常是最昂贵的一种。
     * 它们昂贵的部分原因是，它们必须禁用通常绕过缓存以满足写缓冲区负载的机制。
     * 这可以通过让缓冲区完全刷新以及其他可能的暂停来实现。
     */
    public static final int STORE_LOAD = 0x0004;

    /**
     * The sequence {@code Store1; StoreStore; Store2} ensures that
     * {@code Store1}'s data are visible to other processors (i.e., flushed to memory)
     * before the data associated with {@code Store2} and all subsequent store
     * instructions. In general, {@code StoreStore} barriers
     * are needed on processors that do not otherwise guarantee strict ordering of
     * flushes from
     * write buffers and/or caches to other processors or main memory.
     *
     * 序列｛Store1；StoreStore；Store2｝确保在与｛Store2｝关联的数据和所有后续存储指令之前，
     * ｛Store｝的数据对其他处理器可见（即刷新到内存）。
     * 通常，{@codeStoreStore}障碍在不保证从写缓冲区和/或高速缓存到
     * 其他处理器或主存储器的刷新的严格顺序的处理器上需要。
     */
    public static final int STORE_STORE = 0x0008;

    public static final int JMM_PRE_VOLATILE_WRITE = LOAD_STORE | STORE_STORE;
    public static final int JMM_POST_VOLATILE_WRITE = STORE_LOAD | STORE_STORE;
    public static final int JMM_PRE_VOLATILE_READ = 0;
    public static final int JMM_POST_VOLATILE_READ = LOAD_LOAD | LOAD_STORE;

    public static String barriersString(int barriers) {
        StringBuilder sb = new StringBuilder();
        sb.append((barriers & LOAD_LOAD) != 0 ? "LOAD_LOAD " : "");
        sb.append((barriers & LOAD_STORE) != 0 ? "LOAD_STORE " : "");
        sb.append((barriers & STORE_LOAD) != 0 ? "STORE_LOAD " : "");
        sb.append((barriers & STORE_STORE) != 0 ? "STORE_STORE " : "");
        return sb.toString().trim();
    }
}