/**
     * Implementation Overview
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission queues.
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers. Work-stealing based on
     * randomized scans generally leads to better throughput than
     * "work dealing" in which producers assign tasks to idle threads,
     * in part because threads that have finished other tasks before
     * the signalled thread wakes up (which can be a long time) can
     * take the task instead.  Preference rules give first priority to
     * processing tasks from their own queues (LIFO or FIFO, depending
     * on mode), then to randomized FIFO steals of tasks in other
     * queues.  This framework began as vehicle for supporting
     * tree-structured parallelism using work-stealing.
     *
     * 这个类及其嵌套类为一组工作线程提供了主要功能和控制：
     * 来自非FJ线程的提交进入提交队列。
     * 工作者（线程）接受这些任务会将它们拆分为可能被其他工作者（线程）窃取的子任务。
     * 基于随机扫描的工作窃取通常比生产者将任务分配给空闲线程的“工作处理”能带来更好的吞吐量，
     * 部分原因是在发出信号的线程唤醒（可能需要很长时间）之前完成了其他任务的线程可以接受任务。
     * 偏好规则优先处理自己队列中的任务（LIFO或FIFO，取决于模式），
     * 然后是其他队列中任务的随机FIFO窃取work-stealing。
     * 该框架最初是使用工作窃取来支持树结构并行性的工具。
     * 
     * Over time, its scalability advantages led to extensions and changes to
     * better support more diverse usage contexts.  Because most
     * internal methods and nested classes are interrelated, their
     * main rationale and descriptions are presented here; individual
     * methods and nested classes contain only brief comments about
     * details.
     *
     * 随着时间的推移，它的可扩展性优势导致了扩展和更改，以更好地支持更多样的使用环境。
     * 因为大多数内部方法和嵌套类是相互关联的，所以这里给出了它们的主要原理和描述；
     * 单个方法和嵌套类仅包含有关详细信息的简短注释
     *
     *
     * 工作窃取
     * 对于 ForkJoinPool 来说，任务提交有两种:
     * 一种是直接通过 ForkJoinPool 来提交的外部任务 external/submissions task
     * 第二种是内部 fork 分割的子任务 Worker task
     *
     *  forkJoinPool.submit(forkJoinTask);
     *  
     *  forkJoinTask.fork();
     *  ((ForkJoinWorkerThread)t).workQueue.push(this);
     *
     *  src17 ForkJoinPool:
     *   private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
     *    Thread t; ForkJoinWorkerThread wt; WorkQueue q;
     *    if (task == null)
     *        throw new NullPointerException();
     *    if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
     *        (q = (wt = (ForkJoinWorkerThread)t).workQueue) != null &&
     *        wt.pool == this)
     *        q.push(task, this); //内部 fork 分割的子任务 Worker task
     *   else
     *        externalPush(task); //提交的外部任务 external/submissions task
     *    return task;
     * }
     *
     *  src17 ForkJoinTask:
     *   public final ForkJoinTask<V> fork() {
     *    Thread t; ForkJoinWorkerThread w;
     *    if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
     *        (w = (ForkJoinWorkerThread)t).workQueue.push(this, w.pool);
     *    else
     *        ForkJoinPool.common.externalPush(this);
     *    return this;
     * }
     *  externalPush(task);
     *  
     * WorkQueues
     * ==========
     *
     * Deques: 双端队列
     * 每个工作线程都维护一个工作队列，每个工作线程在运行中产生新任务（通过fork）。
     *
     * 每个工作线程在处理自己工作队列同时，会尝试窃取一个任务。
     * 此任务可能是刚刚提交到池pool中的任务，或者来其它工作线程的工作队列。
     *
     * 窃取任务和执行自己的队列任务的方式是分开的。
     * 
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)
     *
     * 大多数操作都发生在工作窃取队列中（在嵌套类WorkQueue中）。
     * 这些是Deques的特殊形式，只支持四种可能的结束操作中的三种，
     * 即推送push、弹出pop和轮询poll（也称为窃取steal），
     * 但进一步的限制是，推送push和弹出pop只能从所属（owning）线程调用
     * （或者，在锁下情况下扩展），
     * 而轮询poll可以从其他线程调用。
     * （如果你不熟悉它们，
     * 《多处理器编程的艺术》，第16章更详细地描述了这些。）
     * 
     * The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     *
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * 主要的差异最终源于GC的要求，即我们尽快清空占用的插槽，以保持尽可能小的占地面积，
     * 即使在生成大量任务的程序中也是如此。
     * 为了实现这一点，我们将CAS对弹出pop与轮询poll（窃取）的仲裁从位于索引
     * （“底部ase”和“顶部top”）转移到插槽本身。
     *
     * Adding tasks then takes the form of a classic array push(task)
     * in a circular buffer:
     * 然后，在循环缓冲区中以经典数组推送push（task）的形式添加任务：
     *    q.array[q.top++ % length] = task;
     *
     * The actual code needs to null-check and size-check the array,
     * uses masking, not mod, for indexing a power-of-two-sized array,
     * enforces memory ordering, supports resizing, and possibly
     * signals waiting workers to start scanning -- see below.
     *
     * 实际的代码需要对数组进行null检查和大小检查，使用掩码（power-of-two-sized取模），
     * 强制执行内存排序（memory ordering），支持调整大小，并可能通知等待的工作线程开始扫描。
     * 
     * The pop operation (always performed by owner) is of the form:
     * 弹出pop操作（始终由所有者执行owner）的形式如下：
     *   if ((task = getAndSet(q.array, (q.top-1) % length, null)) != null)
     *        decrement top and return task;
     * If this fails, the queue is empty.
     * 如果失败，则队列为空。
     *
     * The poll operation by another stealer thread is, basically:
     * 另一个窃取线程的轮询poll操作基本上是：
     *   if (CAS nonnull task at q.array[q.base % length] to null)
     *       increment base and return task;
     *
     * This may fail due to contention, and may be retried.
     * Implementations must ensure a consistent snapshot of the base
     * index and the task (by looping or trying elsewhere) before
     * trying CAS.  There isn't actually a method of this form,
     * because failure due to inconsistency or contention is handled
     * in different ways in different contexts, normally by first
     * trying other queues. (For the most straightforward example, see
     * method pollScan.) There are further variants for cases
     * requiring inspection of elements before extracting them, so
     * must interleave these with variants of this code.  Also, a more
     * efficient version (nextLocalTask) is used for polls by owners.
     * It avoids some overhead because the queue cannot be growing
     * during call.
     *
     * 
     * 这可能由于争用而失败，并且可能会重试。
     * 在尝试CAS之前，实现必须确保基本索引和任务的快照一致（通过循环或在其他地方尝试）。
     * 实际上没有这种形式的方法，因为在不同的上下文中，
     * 由于不一致或争用而导致的失败以不同的方式处理，通常是先尝试其他队列。
     * （有关最简单的示例，请参阅方法pollScan。）对于需要在提取元素之前检查元素的情况，
     * 还有其他变体，因此必须将这些变体与此代码的变体交织在一起。
     * 此外，所有者（owners）使用更高效的版本（nextLocalTask）进行轮询。
     * 它避免了一些开销，因为在调用过程中队列无法增长。
     *
     * Memory ordering.
     * See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to the one used here.  Inserting and
     * extracting tasks in array slots via volatile or atomic accesses
     * or explicit fences provides primary synchronization.
     *
     * 内存排序（Memory ordering）。
     * 请参阅Le，Pop，Cohen和Nardelli的《弱内存模型的正确有效的工作窃取》，
     * 
     * 了解类似于此处使用的工作窃取算法中内存排序要求的分析。
     * 通过volatile或原子访问atomic或显式围栏在阵列插槽中插入和提取任务提供了
     * 基本同步。
     * 
     * Operations on deque elements require reads and writes of both
     * indices and slots. When possible, we allow these to occur in
     * any order.  Because the base and top indices (along with other
     * pool or array fields accessed in many methods) only imprecisely
     * guide where to extract from, we let accesses other than the
     * element getAndSet/CAS/setVolatile appear in any order, using
     * plain mode. But we must still preface some methods (mainly
     * those that may be accessed externally) with an acquireFence to
     * avoid unbounded staleness.
     *
     * 对deque元素的操作需要读取和写入索引和插槽。
     * 在可能的情况下，我们允许这些以任何顺序发生。
     * 由于底部索引base和顶top索引（以及在许多访问中的其他池pool
     * 或数组array字段的方法）
     * 
     * 只是不精确地定位从何处提取任务时，我们使用普通模式plain mode方式
     * 使用getAndSet/CAS/setVolatile访问元素支持其它的访问以任何序（内存访问.
     * 但我们仍然必须在一些方法（主要是那些可以从外部访问的方法）
     * 前面加上一个acquireFence，以避免无限的过时。
     *
     * array赋值：
     *  WorkQueue： 
     *      growArray
     *          VarHandle.releaseFence();     // fill before publish
     *          array = newArray;
     *      读a = q.array方式
     *          
     *   ForkJoinPool.WorkQueue
     *      读方式：ForkJoinTask<?>[] a = array;
     *          push
     *          lockedPush
     *          growArray
     *          pop
     *          tryUnpush
     *          externalTryUnpush
     *          tryRemove
     *          tryPoll
     *          nextLocalTask
     *          peek
     *          helpComplete
     *          helpAsyncBlocker
     *          
     *   registerWorker(WorkQueue w)
     *      w.array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
     *      读a = q.array方式   
     *          scan
     *          awaitWork
     *          canStop
     *          helpJoin
     *          helpComplete
     *          pollScan
     *
     *   getSlot：(ForkJoinTask<?>)QA.getAcquire(a, i)
     *      ForkJoinPool
     *          scan
     *          helpJoin
     *          helpComplete
     *          pollScan
     *          helpQuiescePool
     *     ForkJoinPool.WorkQueue     
     *          tryPoll
     *          helpAsyncBlocker
     *
     *   casSlotToNull：QA.compareAndSet(a, i, c, null);
     *      ForkJoinPool
     *          scan(WorkQueue, int, int)
     *          helpJoin(ForkJoinTask<?>, WorkQueue, boolean)
     *          helpComplete
     *          pollScan(boolean)
     *          helpQuiescePool(WorkQueue, long, boolean)
     *     ForkJoinPool.WorkQueue
     *          tryUnpush(ForkJoinTask<?>)
     *          externalTryUnpush(ForkJoinTask<?>)
     *          tryRemove(ForkJoinTask<?>, boolean)
     *          tryPoll()
     *          helpComplete(ForkJoinTask<?>, boolean, int)
     *          helpAsyncBlocker(ManagedBlocker)
     *
     *   getAndClearSlot：QA.getAndSet(a, i, null)
     *      ForkJoinPool.WorkQueue
     *          growArray()
     *          pop()
     *          tryRemove(ForkJoinTask<?>, boolean)
     *          nextLocalTask(int)
     *          
     *   setSlotVolatile：QA.setVolatile(a, i, v);
     *      ForkJoinPool.WorkQueue
     *          push
     *          
     * base直接写q.base = nextBase，在如下方法中使用
     * scan、helpJoin、helpQuiescePool
     * 
     * 直接读b = q.base，在如下方法中使用：
     * scan、awaitWork、canStop、helpJoin、helpComplete、pollScan
     * helpQuiescePool、getSurplusQueuedTaskCount
     * 在队列中的使用方法：
     * queueSize、isEmpty、push、lockedPush、pop、tryUnpush、tryRemove
     * tryPoll、nextLocalTask、peek、helpAsyncBlocker
     *
     * 原子操作：setBaseOpaque
     * helpComplete、pollScan
     * 队列方法：tryPoll、nextLocalTask、helpAsyncBlocker
     * 
     * This is equivalent to acting as if
     * callers use an acquiring read of the reference to the pool or
     * queue when invoking the method, even when they do not.
     * We use explicit acquiring reads (getSlot) rather than plain array
     * access when acquire mode is required but not otherwise ensured
     * by context.
     * To reduce stalls by other stealers, we encourage
     * timely writes to the base index by immediately following
     * updates with a write of a volatile field that must be updated
     * anyway, or an Opaque-mode write if there is no such
     * opportunity.
     *
     * 对于array的操作
     * （赋值）相当于对池pool或队列queue的引用的读取（acquiring read）
     * 即使调用方没有这样做。
     * 当需要获取模式acquire mode但上下文无法确保时，使用显式获取读取（getSlot）
     * 而不是纯数组plain array access访问。
     * 
     * 为了减少其他窃取者的暂停，我们鼓励通过如下两种方法及时写入base基本索引，
     * 方法一：在更新后立即写入必须更新的volatile字段，
     * 方法二：或者在没有这种机会的情况下写入不透明模式Opaque-mode。
     *
     * getSlot方法实现访问Task任务数组
     * scan、helpJoin、helpComplete、pollScan、helpQuiescePool和
     * 队列本身的tryPoll、helpAsyncBlocker通过getSlot来获取数组的值。
     *
     * 
     * Because indices and slot contents cannot always be consistent,
     * the emptiness check base == top is only quiescently accurate
     * (and so used where this suffices). Otherwise, it may err on the
     * side of possibly making the queue appear nonempty when a push,
     * pop, or poll have not fully committed, or making it appear
     * empty when an update of top or base has not yet been seen.
     * 
     * Similarly, the check in push for the queue array being full may
     * trigger when not completely full, causing a resize earlier than
     * required.
     *
     * 因为索引和槽内容不可能总是一致的，所以空性检查base==top只是静态准确的
     * （因此在足够的情况下使用）。
     * 否则，当推送push、弹出pop或轮询poll尚未完全提交时，
     * 它可能会错误地使队列显示为非空，
     * 或者当尚未看到顶部或底部的更新时，它会显示为空。
     * 类似地，队列阵列已满的签入推送可能会在未完全满时触发，从而导致提前调整大小。
     *
     * Mainly because of these potential inconsistencies among slots
     * vs indices, the poll operation, considered individually, is not
     * wait-free.
     * One thief cannot successfully continue until another
     * in-progress one (or, if previously empty, a push) visibly
     * completes.
     *
     * 主要是因为插槽slots与索引之间存在这些潜在的不一致性，
     * 单独考虑的轮询poll操作并非免费等待wait-free。
     * 一个盗取（线程）不能成功地继续，直到另一个正在进行的盗取工作者（线程）
     * （或者，如果之前是空的empty，则是推push）明显完成。
     * 
     * This can stall threads when required to consume
     * from a given queue (which may spin).  However, in the
     * aggregate, we ensure probabilistic non-blockingness at least
     * until checking quiescence (which is intrinsically blocking):
     * 
     * If an attempted steal fails, a scanning thief chooses a
     * different victim target to try next. So, in order for one thief
     * to progress, it suffices for any in-progress poll or new push
     * on any empty queue to complete.
     *
     * The worst cases occur when many
     * threads are looking for tasks being produced by a stalled
     * producer.
     *
     * 当需要从给定队列（可能会旋转spin）消耗时，这可能会暂停线程。
     * 然而，总的来说，我们至少在检查静止（本质上是阻塞blocking）之前确保
     * 概率上的非阻塞性：
     * 如果尝试盗窃失败，扫描盗取会选择另一个任务对象（victim）目标进行下一次尝试。
     * 因此，为了让一个小偷（线程）取得进展，任何正在进行的轮询poll或
     * 对任何空队列的新推送push都足以完成。
     * 
     * 最糟糕的情况发生在许多线程正在寻找由停滞的生成器生成的任务时。
     * 
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.
     * This can be useful in
     * message-passing frameworks in which tasks are never joined,
     * although with increased contention among task producers and
     * consumers.
     *
     * 这种方法还支持用户模式，在这种模式下，本地任务处理是按FIFO而不是LIFO顺序进行的，
     * 只需使用轮询poll而不是弹出pop。
     * 这在任务从未加入的消息传递框架中非常有用，尽管任务生产者和消费者之间的争用会增加。
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers.
     * Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.
     *
     * WorkQueues也以类似的方式用于提交到池中的任务。
     * 我们不能将这些任务混合在工作线程使用的相同队列中。
     * 相反，我们使用一种哈希形式，将提交队列与提交线程随机关联。
     *
     * WorkQueue池中包括外部extentalPush(Pool提交)和
     * 内部currentThread.push(this)（Task的fork提交）均在quques数组中
     * 使用线程探索哈希来绑定到线程上，减少竞争。
     * 
     * The ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.
     * In essence, submitters act like workers except that they are
     * restricted to executing local tasks that
     * they submitted (or when known, subtasks thereof).
     *
     * ThreadLocalRandom探测值用作选择现有队列的哈希代码，并且可以在与其他提交者发生争用时
     * 随机重新定位。
     * 本质上，提交者的行为就像工作者（线程），只是他们被限制执行他们提交的本地任务
     * （或者在已知的情况下，执行其子任务subtasks）。
     *
     * Insertion of tasks in shared mode requires a lock. We use only
     * a simple spinlock (using field "source"), because submitters
     * encountering a busy queue move to a different position to use
     * or create other queues.
     * They block only when registering new queues.
     * 
     * 在共享模式下插入任务需要锁定。我们只使用一个简单的spinlock（使用字段“source”），
     * 移动到不同的位置来使用或创建其他队列，提交者会遇到遇到繁忙队列。
     * 它们仅在注册新队列时才会阻止。
     *                                                 
     *  Runs the given (stolen) task if nonnull, as well as remaining
     *  local tasks and/or others available from the given queue.
     *  
     *  final void topLevelExec(ForkJoinTask<?> task, WorkQueue q)
     *
     *      
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.
     * Most non-atomic control is performed by some form
     * of scanning across or within queues.
     * The pool itself creates,
     * activates (enables scanning for and running tasks),
     * deactivates, blocks, and terminates threads, all with minimal
     * central information.
     * 
     * There are only a few properties that we
     * can globally track or maintain, so we pack them into a small
     * number of variables, often maintaining atomicity without
     * blocking or locking.
     *
     * Nearly all essentially atomic control
     * state is held in a few volatile variables that are by far most
     * often read (not written) as status and consistency checks.
     * 
     * We pack as much information into them as we can.
     *
     * 窃取工作的主要吞吐量优势源于分散控制——工作者们（线程）大多以每秒超过10亿的速度
     * 从自己或彼此手中夺走任务。
     * 大多数非原子控制是通过在队列之间或队列内进行某种形式的扫描来执行的。
     * 池本身创建、激活（启用扫描和运行任务）、停用、阻塞和终止线程，
     * 所有这些都只需最少的中心信息。
     * 我们只能全局跟踪或维护少数属性，因此我们将它们打包到少量变量中，
     * 通常在不阻塞或锁定的情况下保持原子性。
     * 
     * 几乎所有本质上的原子控制状态都保存在少数易失性volatile变量中，
     * 这些变量通常作为状态和一致性检查进行读取（而不是写入）。
     * 我们将尽可能多的信息打包到它们中。
     * 
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, enqueue (on an event queue), and
     * dequeue and release workers.  To enable this packing, we
     * restrict maximum parallelism to (1<<15)-1 (which is far in
     * excess of normal operating range) to allow ids, counts, and
     * their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * 字段“ctl”包含64位，其中包含原子决定添加、入队（在事件队列上）、出队和释放
     * 工作者所需的信息。
     * 为了实现这种打包，我们将最大并行度限制为（1<<15）-1（远远超过正常操作范围），
     * 以允许id、计数及其否定（用于阈值处理）适合16位子字段。
     *
     * Field "mode" holds configuration parameters as well as lifetime
     * status, atomically and monotonically setting SHUTDOWN, STOP,
     * and finally TERMINATED bits. It is updated only via bitwise
     * atomics (getAndBitwiseOr).
     *
     * 字段“mode”保存配置参数以及生存期状态，原子地和单调地设置
     * SHUTDOWN、STOP和TERMINATED位。
     * 它仅通过逐位原子（getAndBitwiseOr）进行更新。
     * 
     * Array "queues" holds references to WorkQueues.  It is updated
     * (only during worker creation and termination) under the
     * registrationLock, but is otherwise concurrently readable, and
     * accessed directly (although always prefaced by acquireFences or
     * other acquiring reads). To simplify index-based operations, the
     * array size is always a power of two, and all readers must
     * tolerate null slots.  Worker queues are at odd indices. Worker
     * ids masked with SMASK match their index. Shared (submission)
     * queues are at even indices. Grouping them together in this way
     * simplifies and speeds up task scanning.
     *
     * 数组“queues”包含对WorkQueues的引用。它在registrationLock下更新
     * （仅在工作者者创建和终止期间），
     * 但在其他方面是可并发读取的，并可直接访问（尽管总是以获取围栏或其他获取读取为开头）。
     * 为了简化基于索引的操作，数组大小总是2的幂，并且所有读卡器都必须允许空槽。
     * 工作队列的索引为奇数。
     * 使用SMASK屏蔽的工作ID与其索引匹配。共享（提交）队列的索引是偶数。
     * 以这种方式将它们分组在一起可以简化并加快任务扫描。
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers.
     *
     * However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker or task references that would prevent
     * GC, all accesses to workQueues are via indices into the
     * queues array (which is one source of some of the messy code
     * constructions here). In essence, the queues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * 所有工作线程的创建都是按需的，由任务提交、替换终止的工作线程和/或补偿被阻止的工作线程触发。
     * 
     * 但是，所有其他支持代码都设置为与其他策略配合使用。
     * 为了确保我们不会保留会阻止GC的工作者或任务引用，
     * 所有对workQueues的访问都是通过队列数组中的索引进行的（这是这里一些混乱代码构造的来源之一）。
     * 从本质上讲，队列数组是一种弱引用机制。因此，例如ctl的栈顶子字段存储索引，而不是引用。
     * 
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. These latencies are mainly a
     * function of JVM park/unpark (and underlying OS) performance,
     * which can be slow and variable.  In many usages, ramp-up time
     * is the main limiting factor in overall performance, which is
     * compounded at program start-up by JIT compilation and
     * allocation. On the other hand, throughput degrades when too
     * many threads poll for too few tasks.
     *
     * 正在排队等候空闲工作者。与HPC窃取工作的框架不同，
     * 我们不能让工作证在无法立即找到任务的情况下无限期地扫描任务，
     * 并且除非出现可用任务，否则我们不能启动/恢复工作工作者。
     * 另一方面，当提交或生成新任务时，我们必须迅速促使他们采取行动。
     * 这些延迟主要是JVM park/unpark（和底层操作系统）性能的函数，
     * 这可能是缓慢的且可变的。
     * 在许多用途中，斜坡上升时间是总体性能的主要限制因素，
     * JIT编译和分配在程序启动时会加剧这种情况。
     * 另一方面，当太多的线程轮询太少的任务时，吞吐量会下降。
     *
     * The "ctl" field atomically maintains total and "released"
     * worker counts, plus the head of the available worker queue
     * (actually stack, represented by the lower 32bit subfield of
     * ctl).  Released workers are those known to be scanning for
     * and/or running tasks. Unreleased ("available") workers are
     * recorded in the ctl stack. These workers are made available for
     * signalling by enqueuing in ctl (see method awaitWork).  The
     * "queue" is a form of Treiber stack. This is ideal for
     * activating threads in most-recently used order, and improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.
     *
     * “ctl”字段原子性地维护总的和“释放的”工作人员计数，加上可用工作队列的头
     * （实际上是堆栈，由ctl的低32位子字段表示）。
     * 被释放的工作者是那些已知正在扫描和/或运行任务的工作者。
     * 未释放（“可用”）的工作线程记录在ctl堆栈中。
     * 这些工作者可以通过在ctl中排队来发出信号（请参阅awaitWork方法）。
     * “队列”是Treiber堆栈的一种形式。
     * 这非常适合以最近使用的顺序激活线程，并提高了性能和位置，
     * 超过了易发生争用和无法释放工作线程的缺点，除非它位于堆栈的最顶端。
     * 
     * The top stack state holds the
     * value of the "phase" field of the worker: its index and status,
     * plus a version counter that, in addition to the count subfields
     * (also serving as version stamps) provide protection against
     * Treiber stack ABA effects.
     *
     * 顶部堆栈状态保存工作程序的“阶段”字段的值：(WorkQueue的volatile字段)
     * 它的索引和状态，加上一个版本计数器，除了计数子字段（也用作版本戳）之外，
     * 该计数器还提供针对Treiber堆栈ABA效应的保护。
     * 
     * Creating workers. To create a worker, we pre-increment counts
     * (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. On starting, the new
     * thread first invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the queues array
     * (expanding the array if necessary).  Upon any exception across
     * these steps, or null return from factory, deregisterWorker
     * adjusts counts and records accordingly.  If a null return, the
     * pool continues running with fewer than the target number
     * workers. If exceptional, the exception is propagated, generally
     * to some external caller.
     *
     * 创造工作者（线程）。为了创建一个worker，我们预先增加计数（用作保留），
     * 并尝试通过其工厂构建ForkJoinWorkerThread。
     * 启动时，新线程首先调用registerWorker，在那里它构造一个WorkQueue，
     * 并在queues数组中分配一个索引（如有必要，扩展数组）。
     * 在这些步骤中出现任何异常，或工厂返回null时，取消注册Worker会相应地调整计数和记录。
     * 如果返回null，则池将继续运行，工作人员数量将少于目标数量。
     * 如果出现异常，则通常会将异常传播到某个外部调用方。
     * 
     * WorkQueue field "phase" is used by both workers and the pool to
     * manage and track whether a worker is UNSIGNALLED (possibly
     * blocked waiting for a signal).  When a worker is enqueued its
     * phase field is set negative. Note that phase field updates lag
     * queue CAS releases; seeing a negative phase does not guarantee
     * that the worker is available. When queued, the lower 16 bits of
     * its phase must hold its pool index. So we place the index there
     * upon initialization and never modify these bits.
     *
     * WorkQueue的volatile字段“phase”由工作者线程和池使用，以管理和跟踪工作线程是否未被忽略
     * （可能被阻止等待信号）。
     * 当工作者入队时，其相位字段设置为负值。
     * 请注意，阶段字段更新CAS发布的滞后队列；看到负相位并不能保证工作者是可用的。
     * 当排队时，其相位的较低16位必须保持其池索引。
     * 因此，我们在初始化时将索引放在那里，并且从不修改这些位。
     *
     * The ctl field also serves as the basis for memory
     * synchronization surrounding activation. This uses a more
     * efficient version of a Dekker-like rule that task producers and
     * consumers sync with each other by both writing/CASing ctl (even
     * if to its current value).  However, rather than CASing ctl to
     * its current value in the common case where no action is
     * required, we reduce write contention by ensuring that
     * signalWork invocations are prefaced with a full-volatile memory
     * access (which is usually needed anyway).
     *
     * ctl字段还充当了围绕激活的内存同步的基础。
     * 这使用了一个更高效的类似Dekker的规则版本，
     * 即任务生产者和消费者通过写入/CASing ctl（即使达到其当前值）来相互同步。
     * 然而，与在不需要任何操作的常见情况下将ctl CASing为其当前值不同，
     * 我们通过确保signalWork调用以完全的易失性内存访问（这通常是需要的）为前提来减少写争用。
     * 
     * Signalling. Signals (in signalWork) cause new or reactivated
     * workers to scan for tasks.
     *
     * Method signalWork and its callers
     * try to approximate the unattainable goal of having the right
     * number of workers activated for the tasks at hand, but must err
     * on the side of too many workers vs too few to avoid stalls.  If
     * computations are purely tree structured, it suffices for every
     * worker to activate another when it pushes a task into an empty
     * queue, resulting in O(log(threads)) steps to full activation.
     *
     * 信号。信号（在signalWork中）使新的或重新激活的工作者（线程）扫描scan任务。
     * 方法signalWork及其调用方试图接近为手头的任务激活正确数量的工作人员这一无法实现的目标，
     * 但必须在工作人员过多和过少的情况下犯错，以避免停滞。如果计算是纯树结构的，
     * 那么当每个工作者将任务推入空队列queue时，激活另一个就足够了，
     * 从而导致O(log(threads))步完全激活。
     * 
     * If instead, tasks come in serially from only a single producer,
     * each worker taking its first (since the last quiescence) task
     * from a queue should signal another if there are more tasks in
     * that queue. This is equivalent to, but generally faster than,
     * arranging the stealer take two tasks, re-pushing one on its own
     * queue, and signalling (because its queue is empty), also
     * resulting in logarithmic full activation time. Because we don't
     * know about usage patterns (or most commonly, mixtures), we use
     * both approaches.
     *
     * 相反，如果任务仅从单个生产者串行进入，则每个从队列中获取其第一个（自上次静止以来）
     * 任务的工作者应在该队列中有更多任务时向另一个发出信号。
     * 这相当于，但通常比安排窃取者执行两项任务更快，
     * 在其自己的队列中重新推送一项任务，并发出信号（因为其队列是空的），
     * 这也导致对数完全激活时间。因为我们不知道使用模式（或者最常见的混合模式），
     * 所以我们使用这两种方法。
     * 
     * We approximate the second rule by arranging
     * that workers in scan() do not repeat signals when repeatedly
     * taking tasks from any given queue, by remembering the previous
     * one. There are narrow windows in which both rules may apply,
     * leading to duplicate or unnecessary signals. Despite such
     * limitations, these rules usually avoid slowdowns that otherwise
     * occur when too many workers contend to take too few tasks, or
     * when producers waste most of their time resignalling.  However,
     * contention and overhead effects may still occur during ramp-up,
     * ramp-down, and small computations involving only a few workers.
     *
     * 我们通过记住前一条规则，scan方法中的工作人员在重复从任何给定队列中获取任务时不重复信号，
     * 来近似第二条规则。
     * 在狭窄的窗口中，这两种规则都可能适用，从而导致重复或不必要的信号。
     * 尽管有这些限制，但这些规则通常会避免在太多工人争着承担太少任务时，
     * 或者在生产商浪费大部分时间辞职时出现的放缓。
     * 然而，在上升、下降和仅涉及少数工人的小型计算过程中，仍可能发生争用和开销效应。
     * 
     * Scanning. Method scan performs top-level scanning for (and
     * execution of) tasks.  Scans by different workers and/or at
     * different times are unlikely to poll queues in the same
     * order. Each scan traverses and tries to poll from each queue in
     * a pseudorandom permutation order by starting at a random index,
     * and using a constant cyclically exhaustive stride; restarting
     * upon contention.  (Non-top-level scans; for example in
     * helpJoin, use simpler linear probes because they do not
     * systematically contend with top-level scans.)
     *
     * 正在扫描。方法扫描执行任务的顶层扫描（和执行）。不同工作者（线程）和/或
     * 不同时间的扫描不太可能以相同的顺序轮询队列。
     * 每次扫描从随机索引开始，并使用恒定的循环穷举步幅，以伪随机排列顺序遍历并尝试从每个队列轮询；
     * 在争用时重新启动。
     * （非顶级扫描；例如，在helpJoin中，使用更简单的线性探针，因为它们不会系统地与顶级扫描竞争。）
     * 
     * The pseudorandom generator need not have high-quality statistical properties in
     * the long term. We use Marsaglia XorShifts, seeded with the Weyl
     * sequence from ThreadLocalRandom probes, which are cheap and
     * suffice. Scans do not otherwise explicitly take into account
     * core affinities, loads, cache localities, etc, However, they do
     * exploit temporal locality (which usually approximates these) by
     * preferring to re-poll from the same queue after a successful
     * poll before trying others (see method topLevelExec).  This
     * reduces fairness, which is partially counteracted by using a
     * one-shot form of poll (tryPoll) that may lose to other workers.
     *
     * 从长远来看，伪随机生成器不需要具有高质量的统计特性。
     * 我们使用Marsaglia XorShifts，用ThreadLocalRandom探针的Weyl序列接种，这是便宜且足够的。
     * 扫描不会明确考虑核心相关性、负载、缓存位置等。
     * 但是，它们确实会利用时间位置（通常接近这些位置），
     * 在成功轮询后，在尝试其他轮询之前，更倾向于从同一队列重新轮询（请参阅方法topLevelExec）。
     * 这降低了公平性，而使用一次性投票形式（tryPoll）可能会输给其他员工，这在一定程度上抵消了公平性。
     * 
     * Deactivation. Method scan returns a sentinel when no tasks are
     * found, leading to deactivation (see awaitWork). The count
     * fields in ctl allow accurate discovery of quiescent states
     * (i.e., when all workers are idle) after deactivation. However,
     * this may also race with new (external) submissions, so a
     * recheck is also needed to determine quiescence. Upon apparently
     * triggering quiescence, awaitWork re-scans and self-signals if
     * it may have missed a signal. In other cases, a missed signal
     * may transiently lower parallelism because deactivation does not
     * necessarily mean that there is no more work, only that that
     * there were no tasks not taken by other workers.  But more
     * signals are generated (see above) to eventually reactivate if
     * needed.
     *
     * 停用。scan方法扫描在找不到任务时返回一个sentinel，导致停用（请参阅awaitWork）。
     * ctl中的计数count字段允许在去激活后准确发现静态（即，当所有工作程序都空闲时）。
     * 然而，这也可能与新的（外部）提交竞争，因此还需要重新检查以确定静止。
     * 在明显触发静止后，awaitWork会重新扫描并发出信号（如果它可能错过了信号）。
     * 在其他情况下，丢失的信号可能会暂时降低并行性，因为去激活并不一定意味着没有更多的工作，
     * 只是意味着没有其他工作者（线程）没有完成的任务。
     * 但如果需要的话，会产生更多的信号（见上文）最终重新激活。
     * 
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate if the pool has remained quiescent for
     * period given by field keepAlive.
     *
     * 维护工作者（线程）。要在缺乏使用的时间段后释放资源，当池处于静止状态时开始等待的工作者将超时，
     * 如果池在字段keepAlive给定的时间段内保持静止，则终止。
     * 
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a mode bit. The calling thread,
     * as well as every other worker thereafter terminating, helps
     * terminate others by cancelling their unprocessed tasks, and
     * waking them up. Calls to non-abrupt shutdown() preface this by
     * checking isQuiescent before triggering the "STOP" phase of
     * termination. To conform to ExecutorService invoke, invokeAll,
     * and invokeAny specs, we must track pool status while waiting,
     * and interrupt interruptible callers on termination (see
     * ForkJoinTask.joinForPoolInvoke etc).
     *
     * 关闭和终止。对shutdownow的调用调用tryTerminate以原子方式设置模式位。
     * 调用线程以及此后终止的每个其他工作线程，通过取消其他人未处理的任务并唤醒他们来帮助终止他们。
     * 调用非突然关闭shutdown()，在触发终止的“STOP”阶段之前检查isQuietent。
     * 为了符合ExecutorService invoke、invokeAll和invokeAny规范，
     * 我们必须在等待时跟踪池状态，并在终止时中断可中断的调用方
     * （请参见ForkJoinTask.joinForPoolInvoke等）。
     *
     * 
     * Joining Tasks
     * =============
     *
     * Normally, the first option when joining a task that is not done
     * is to try to unfork it from local queue and run it.  Otherwise,
     * any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * always just let them block (as in Thread.join).  We also cannot
     * just reassign the joiner's run-time stack with another and
     * replace it later, which would be a form of "continuation", that
     * even if possible is not necessarily a good idea since we may
     * need both an unblocked task and its continuation to progress.
     * Instead we combine two tactics:
     *
     * 通常，加入未完成的任务时的第一个选项是尝试将其从本地队列中取消锁定并运行。
     * 否则，当一个工作者（线程）正在等待加入另一个窃取（或始终持有）的任务时，
     * 可能会采取以下任一操作。
     * 因为我们将许多任务多路复用到一个工作线程池中，
     * 所以我们不能总是让它们阻塞（如在Thread.join中）。
     * 我们也不能只是用另一个来重新分配joiner的运行时堆栈，然后再替换它，
     * 这将是一种“延续”形式，即使可能，
     * 这也不一定是一个好主意，因为我们可能需要一个未阻塞的任务和它的延续来进行。
     * 相反，我们结合了两种策略：
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      could be running if the steal had not occurred.
     *  帮助：安排加入者执行一些任务，如果没有发生盗窃，它可能正在运行。
     *  
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     *   补偿：除非已经有足够的活动线程，否则方法tryCompensate()可能会创建或
     *        重新激活一个sparethread来补偿被阻止的连接程序，直到它们被取消阻止。
     *   
     * A third form (implemented via tryRemove) amounts to helping a
     * hypothetical compensator: If we can readily tell that a
     * possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread; although with a
     * (rare) possibility of reduced parallelism because of a
     * transient gap in the queue array.
     *
     * 第三种形式（通过tryRemove实现）相当于帮助一个假设的补偿器：
     * 如果我们可以很容易地判断补偿器的一个可能动作是窃取并执行被连接的任务，
     * 则连接线程可以直接执行，而不需要补偿线程；
     * 尽管由于队列阵列中的瞬态间隙，并行性降低的可能性（罕见）。
     * 
     * Other intermediate forms available for specific task types (for
     * example helpAsyncBlocker) often avoid or postpone the need for
     * blocking or compensation.
     *
     * 可用于特定任务类型的其他中间形式（例如helpAsyncBlocker）通常可以避免或推迟阻塞或补偿的需要。
     * 
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * ManagedBlocker扩展API无法使用帮助，因此仅依赖于方法awaitBlocker中的补偿。
     * 
     * The algorithm in helpJoin entails a form of "linear helping".
     * Each worker records (in field "source") the id of the queue
     * from which it last stole a task.  The scan in method helpJoin
     * uses these markers to try to find a worker to help (i.e., steal
     * back a task from and execute it) that could hasten completion
     * of the actively joined task.  Thus, the joiner executes a task
     * that would be on its own local deque if the to-be-joined task
     * had not been stolen.
     *
     * helpJoin中的算法包含一种“线性帮助”形式。
     * 每个工作进程记录（在“源”source字段中）它上次从中窃取任务的队列的id。
     * scan-in方法helpJoin使用这些标记来尝试找到一个工作者（线程）
     * 来帮助（即，从中偷回任务并执行它），
     * 该工作人员可以加快主动加入任务的完成。
     * 因此，如果要加入的任务没有被窃取，那么加入者将在其自己的本地deque上执行一个任务。
     * 
     * This is a conservative variant of the
     * approach described in Wagner & Calder "Leapfrogging: a portable
     * technique for implementing efficient futures" SIGPLAN Notices,
     * 1993 (http://portal.acm.org/citation.cfm?id=155354).
     * 跳跃：一种实现高效期货的可移植技术
     * 
     * It differs mainly in that we only record queue ids, not full dependency
     * links.  This requires a linear scan of the queues array to
     * locate stealers, but isolates cost to when it is needed, rather
     * than adding to per-task overhead. Also, searches are limited to
     * direct and at most two levels of indirect stealers, after which
     * there are rapidly diminishing returns on increased overhead.
     * Searches can fail to locate stealers when stalls delay
     * recording sources.  Further, even when accurately identified,
     * stealers might not ever produce a task that the joiner can in
     * turn help with. So, compensation is tried upon failure to find
     * tasks to run.
     *
     * 它的不同之处主要在于，我们只记录队列ID，而不是完整的依赖关系链接。
     * 这需要对队列阵列进行线性扫描以定位窃取者，但在需要时隔离成本，而不是增加每个任务的开销。
     * 此外，搜索仅限于直接和最多两个级别的间接窃取，之后，增加的开销会带来快速递减的回报。
     * 当暂停延迟记录源时，搜索可能无法定位窃取程序。
     * 此外，即使被准确识别，窃取者也可能永远不会产生一个参与者可以帮助完成的任务。
     * 因此，在找不到要运行的任务时会尝试进行补偿。
     * 
     * Joining CountedCompleters (see helpComplete) differs from (and
     * is generally more efficient than) other cases because task
     * eligibility is determined by checking completion chains rather
     * than tracking stealers.
     *
     * 加入CountedCompleters（请参阅helpComplete）与其他情况不同
     * （通常比其他情况更高效），
     * 因为任务资格是通过检查完成链而不是跟踪窃取者来确定的。
     * 
     * Joining under timeouts (ForkJoinTask timed get) uses a
     * constrained mixture of helping and compensating in part because
     * pools (actually, only the common pool) may not have any
     * available threads: If the pool is saturated (all available
     * workers are busy), the caller tries to remove and otherwise
     * help; else it blocks under compensation so that it may time out
     * independently of any tasks.
     *
     * 超时加入（ForkJoinTask timed get）使用了帮助和补偿的受限混合，
     * 部分原因是池（实际上只有公共池）可能没有任何可用线程：
     * 如果池饱和（所有可用的工作线程都很忙），调用方会尝试删除并以其他方式提供帮助；
     * 否则它会在补偿下阻塞，从而可以独立于任何任务超时。
     * 
     * Compensation does not by default aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement
     * when they cause longer-term oversubscription.  Rather than
     * impose arbitrary policies, we allow users to override the
     * default of only adding threads upon apparent starvation.  The
     * compensation mechanism may also be bounded.  Bounds for the
     * commonPool (see COMMON_MAX_SPARES) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so.
     *
     * 默认情况下，补偿的目的不是在任何给定时间保持未阻塞线程的目标并行数。
     * 该类的一些早期版本对任何阻塞的联接都采用了即时补偿。
     * 然而，在实践中，绝大多数阻塞都是GC和其他JVM或OS活动的瞬态副产品，
     * 当它们导致长期超额订阅时，替换会使这些活动变得更糟。
     * 我们不强制使用任意的策略，而是允许用户覆盖只在明显饥饿时添加线程的默认设置。
     * 补偿机制也可以是有界的。
     * commonPool的边界（请参阅COMMON_MAX_SPARES）可以更好地使JVM在资源耗尽之
     * 前处理编程错误和滥用。
     * 
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields.
     *
     * 静态公共池在静态初始化后始终存在。由于它（或任何其他创建的池）永远不需要使用，
     * 我们将初始构建开销和占地面积最小化，只需设置十几个字段。
     * 
     * When external threads submit to the common pool, they can
     * perform subtask processing (see helpComplete and related
     * methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * 当外部线程提交到公共池时，它们可以在联接时执行子任务处理
     * （请参阅helpComplete和相关方法）。
     * 此调用方帮助策略使得将公共池并行度级别设置为比可用内核总数少一个（或多个），
     * 或者对于纯调用方运行甚至为零是明智的。
     * 我们不需要记录外部提交是否属于公共池——如果不是，外部帮助方法会快速返回。
     * 否则，这些提交者将被阻止等待完成，因此在不适用的情况下，
     * 额外的工作（通过大量的任务状态检查）相当于在ForkJoinTask.join中阻止之前
     * 的有限旋转等待。
     *
     * Guarantees for common pool parallelism zero are limited to
     * tasks that are joined by their callers in a tree-structured
     * fashion or use CountedCompleters (as is true for jdk
     * parallelStreams). Support infiltrates several methods,
     * including those that retry helping steps until we are sure that
     * none apply if there are no workers.
     *
     * 公共池并行度零的保证仅限于调用方以树结构方式连接或使用CountedCompleters的任务
     * （jdk parallelStreams也是如此）。
     * 支持渗透到几种方法中，包括那些重试帮助步骤的方法，直到我们确定如果没有工作者，则不适用。
     * 
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task.  The associated mechanics
     * may be JVM-dependent and must access particular Thread class
     * fields to achieve this effect.
     *
     * 作为托管环境中更合适的默认值，除非被系统属性覆盖，否则当存在SecurityManager时，
     * 我们使用子类InnocousForkJoinWorkerThread的worker。
     * 这些工作者没有权限集，不属于任何用户定义的ThreadGroup，
     * 并且在执行任何顶级任务后擦除所有ThreadLocals。
     * 相关的机制可能依赖于JVM，并且必须访问特定的线程类字段才能达到这种效果。
     * 
     * Interrupt handling
     * ==================
     *
     * The framework is designed to manage task cancellation
     * (ForkJoinTask.cancel) independently from the interrupt status
     * of threads running tasks. (See the public ForkJoinTask
     * documentation for rationale.)  Interrupts are issued only in
     * tryTerminate, when workers should be terminating and tasks
     * should be cancelled anyway. Interrupts are cleared only when
     * necessary to ensure that calls to LockSupport.park do not loop
     * indefinitely (park returns immediately if the current thread is
     * interrupted). If so, interruption is reinstated after blocking
     * if status could be visible during the scope of any task.  For
     * cases in which task bodies are specified or desired to
     * interrupt upon cancellation, ForkJoinTask.cancel can be
     * overridden to do so (as is done for invoke{Any,All}).
     *
     * 该框架设计用于独立于运行任务的线程的中断状态来管理任务取消（ForkJoinTask.cancel）。
     * （有关基本原理，请参阅公共ForkJoinTask文档。）中断仅在tryTerminate中发出，
     * 此时工作者（线程）应该终止，
     * 任务无论如何都应该取消。只有在必要时才清除中断，
     * 以确保对LockSupport.park的调用不会无限期循环（如果当前线程中断，则park会立即返回）。
     * 如果是这样的话，如果在任何任务的范围内状态都可见，那么在阻塞之后会恢复中断。
     * 对于指定或希望在取消时中断任务体的情况，可以重写ForkJoinTask.cancel来执行此操作
     * （就像invoke｛Any，All｝一样）。
     * 
     * Memory placement
     * ================
     *
     * Performance can be very sensitive to placement of instances of
     * ForkJoinPool and WorkQueues and their queue arrays. To reduce
     * false-sharing impact, the @Contended annotation isolates the
     * ForkJoinPool.ctl field as well as the most heavily written
     * WorkQueue fields. These mainly reduce cache traffic by scanners.
     * WorkQueue arrays are presized large enough to avoid resizing
     * (which transiently reduces throughput) in most tree-like
     * computations, although not in some streaming usages. Initial
     * sizes are not large enough to avoid secondary contention
     * effects (especially for GC cardmarks) when queues are placed
     * near each other in memory. This is common, but has different
     * impact in different collectors and remains incompletely
     * addressed.
     *
     * 性能对ForkJoinPool和WorkQueues实例及其队列阵列的位置非常敏感。
     * 为了减少错误共享的影响，@Contended注释隔离了ForkJoinPool.ctl字段
     * 以及写入量最大的WorkQueue字段。
     * 这些主要通过扫描仪减少缓存流量。WorkQueue数组的预大小足够大，
     * 可以避免在大多数树状计算中调整大小（这会暂时降低吞吐量），
     * 尽管在某些流式使用中不是这样。当队列在内存中彼此靠近时，
     * 初始大小不足以避免二次争用效应（尤其是GC卡标记）。
     * 这是常见的，但在不同的收集器中有不同的影响，并且仍然没有完全解决。
     * 
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on atomic operations (CAS,
     * getAndSet, getAndAdd) along with explicit fences.  This can be
     * awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. All fields are read into locals
     * before use, and null-checked if they are references, even if
     * they can never be null under current usages.  Array accesses
     * using masked indices include checks (that are always true) that
     * the array length is non-zero to avoid compilers inserting more
     * expensive traps.  This is usually done in a "C"-like style of
     * listing declarations at the heads of methods or blocks, and
     * using inline assignments on first encounter.  Nearly all
     * explicit checks lead to bypass/return, not exception throws,
     * because they may legitimately arise during shutdown.
     *
     * 内存排序主要依赖于原子操作（CAS、getAndSet、getAndAdd）以及显式栅栏。
     * 这可能是尴尬和丑陋的，但也反映出需要控制异常情况下的结果，
     * 这些异常情况出现在几乎没有不变量的非常活泼的代码中。
     * 所有字段在使用前都会读取到本地文件中，如果它们是引用，则会检查为null，
     * 即使在当前使用情况下它们永远不会为null。
     * 使用掩码索引的数组访问包括检查（始终为true）数组长度是否为非零，
     * 以避免编译器插入更昂贵的陷阱。
     * 这通常是以“C”风格完成的，在方法或块的头部列出声明，并在第一次遇到时使用内联赋值。
     * 几乎所有的显式检查都会导致旁路/返回，而不是异常抛出，因为它们可能在关闭期间合法出现。
     * 
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables. Some others are artificially broken up to
     * reduce producer/consumer imbalances due to dynamic compilation.
     * There are also other coding oddities (including several
     * unnecessary-looking hoisted null checks) that help some methods
     * perform reasonably even when interpreted (not compiled).
     *
     * 类ForkJoinPool、ForkJoinWorkerThread和ForkJoinTask之间存在大量表示级耦合。
     * WorkQueue的字段维护由ForkJoinPool管理的数据结构，因此可以直接访问。
     * 试图减少这种情况没有什么意义，因为任何相关的未来表示变化都需要伴随着算法的变化。
     * 一些方法本质上是扩张的，因为它们必须积累局部变量中字段的一致读取集。
     * 其他一些则被人为地分解，以减少由于动态汇编而造成的生产者/消费者失衡。
     * 还有其他一些编码上的奇怪之处（包括一些看起来不必要的挂起的null检查），
     * 即使在解释（而不是编译）时，也有助于某些方法合理地执行。
     * 
     * The order of declarations in this file is (with a few exceptions):
     * 此文件中声明的顺序为（除少数例外）：
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     *
     * Revision notes
     * ==============
     *
     * The main sources of differences of January 2020 ForkJoin
     * classes from previous version are:
     *
     * * ForkJoinTask now uses field "aux" to support blocking joins
     *   and/or record exceptions, replacing reliance on builtin
     *   monitors and side tables.
     * * Scans probe slots (vs compare indices), along with related
     *   changes that reduce performance differences across most
     *   garbage collectors, and reduce contention.
     * * Refactoring for better integration of special task types and
     *   other capabilities that had been incrementally tacked on. Plus
     *   many minor reworkings to improve consistency.
     */
public class ForkJoinPool_Comm{
    
    /**
     * Scans for and returns a polled task, if available.  Used only
     * for untracked polls. Begins scan at an index (scanRover)
     * advanced on each call, to avoid systematic unfairness.
     * 扫描并返回轮询任务（如果可用）。仅用于未跟踪的poll调查。
     * 在每次调用时都以高级索引（scanRover）开始扫描，以避免系统性的不公平。
     * 
     * @param submissionsOnly if true, only scan submission queues
     *  参数：submissionsOnly–如果为true，则仅扫描提交队列
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
                    if (q.base != b){
                        scan = true;
                    }
                    else if (t == null){
                        scan |= (q.top != b || a[nextBase & (cap - 1)] != null);
                    }
                    else if (!WorkQueue.casSlotToNull(a, k, t)){
                        scan = true;
                    }
                    else {
                        q.setBaseOpaque(nextBase);
                        return t;
                    }
                }
            }
            if (!scan && queues == qs){
                break;
            }
        }
        return null;
    }
    
     final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        ForkJoinTask<?> t;
        if (w == null || (t = w.nextLocalTask(w.config)) == null)
            t = pollScan(false);
        return t;
    }
    
    static class WorkQueue{
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
    }
    
}
