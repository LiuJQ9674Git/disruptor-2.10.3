package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An unbounded {@link TransferQueue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out) with respect
 * to any given producer.  The <em>head</em> of the queue is that
 * element that has been on the queue the longest time for some
 * producer.  The <em>tail</em> of the queue is that element that has
 * been on the queue the shortest time for some producer.
 *
 * 基于链接节点的无边界TransferQueue。该队列对任何给定生产者的元素FIFO（先进先出）。
 * 队列的头是某个生产者在队列中停留时间最长的元素。队列的尾部是某个生产者在队列中停留时间最短的元素。
 * 
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 *
 * 注意，与大多数集合不同，｛size｝方法不是一个常量时间操作。由于这些队列的异步性质，
 * 确定当前元素数量需要遍历元素，因此如果在遍历过程中修改此集合，则可能会报告不准确的结果。
 * 
 * <p>Bulk operations that add, remove, or examine multiple elements,
 * such as {@link #addAll}, {@link #removeIf} or {@link #forEach},
 * are <em>not</em> guaranteed to be performed atomically.
 * For example, a {@code forEach} traversal concurrent with an {@code
 * addAll} operation might observe only some of the added elements.
 *
 * 添加、删除或检查多个元素的大容量操作，如｛link#addAll｝、｛link#removeIf｝
 * 或｛link#forEach｝，不能保证以原子方式执行。例如，与{addAll}操作
 * 并行的{forEach}遍历可能只观察到一些添加的元素。
 * 
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Collection} and {@link Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent
 * collections, actions in a thread prior to placing an object into a
 * {@code LinkedTransferQueue}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code LinkedTransferQueue} in another thread.
 *
 * 内存一致性影响：与其他并发集合一样，将对象放入LinkedTransferQueue的动作happen-before
 * （对后者操作可见）在另一个线程中从LinkedTransfer Queue访问或删除该元素。
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @since 1.7
 * @author Doug Lea
 * @param <E> the type of elements held in this queue
 */
public class LinkedTransferQueue<E> extends AbstractQueue<E>
    implements TransferQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * *** Overview of Dual Queues with Slack ***
     *
     * Dual Queues, introduced by Scherer and Scott
     * (http://www.cs.rochester.edu/~scott/papers/2004_DISC_dual_DS.pdf)
     * are (linked) queues in which nodes may represent either data or
     * requests.  When a thread tries to enqueue a data node, but
     * encounters a request node, it instead "matches" and removes it;
     * and vice versa for enqueuing requests. Blocking Dual Queues
     * arrange that threads enqueuing unmatched requests block until
     * other threads provide the match.
     *
     *  双队列，由Scherer和Scott介绍是（链接的）队列，其中节点可以表示数据或请求。
     *  当线程试图将数据节点排入队列，但遇到请求节点时，它会“匹配”并删除它；
     *  反之亦然。阻塞双队列安排将不匹配的请求排入队列的线程阻塞，直到其他线程提供匹配为止。
     *  
     * Dual Synchronous Queues (see
     * Scherer, Lea, & Scott
     * http://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf)
     * additionally arrange that threads enqueuing unmatched data also
     * block.  Dual Transfer Queues support all of these modes, as
     * dictated by callers.
     *
     * 双同步队列另外安排将不匹配的数据入队列的线程也阻塞。根据调用方的指示，
     * 双传输队列支持所有这些模式。
     * 
     * A FIFO dual queue may be implemented using a variation of the
     * Michael & Scott (M&S) lock-free queue algorithm
     * (http://www.cs.rochester.edu/~scott/papers/1996_PODC_queues.pdf).
     * It maintains two pointer fields, "head", pointing to a
     * (matched) node that in turn points to the first actual
     * (unmatched) queue node (or null if empty); and "tail" that
     * points to the last node on the queue (or again null if
     * empty). For example, here is a possible queue with four data
     * elements:
     *
     * 指针字段“head”，指向一个（匹配的）节点，
     * 该节点又指向第一个实际（不匹配的）队列节点（如果为空，则为null）；
     *
     * 指向队列上最后一个节点的“tail”（如果为空，则为null）。
     * 例如，这里有一个可能包含四个数据元素的队列：
     *
     *  head                tail
     *    |                   |
     *    v                   v
     *    M -> U -> U -> U -> U
     *
     * The M&S queue algorithm is known to be prone to scalability and
     * overhead limitations when maintaining (via CAS) these head and
     * tail pointers. This has led to the development of
     * contention-reducing variants such as elimination arrays.
     * However, the nature of dual queues enables a simpler tactic for
     * improving M&S-style implementations when dual-ness is needed.
     *
     * 众所周知，M&S队列算法在维护（通过CAS）这些头和尾指针时容易受到可伸缩性和开销限制。
     * 这导致了诸如消除数组之类的减少争用的变体的发展。
     * 然而，当需要双重性时，双重队列的性质使改进M&S风格实现的策略变得更简单。
     * 
     * In a dual queue, each node must atomically maintain its match
     * status. While there are other possible variants, we implement
     * this here as: for a data-mode node, matching entails CASing an
     * "item" field from a non-null data value to null upon match, and
     * vice-versa for request nodes, CASing from null to a data
     * value. (Note that the linearization properties of this style of
     * queue are easy to verify -- elements are made available by
     * linking, and unavailable by matching.) Compared to plain M&S
     * queues, this property of dual queues requires one additional
     * successful atomic operation per enq/deq pair. But it also
     * enables lower cost variants of queue maintenance mechanics. (A
     * variation of this idea applies even for non-dual queues that
     * support deletion of interior elements, such as
     * j.u.c.ConcurrentLinkedQueue.)
     *
     * 在双队列中，每个节点必须原子地保持其匹配状态。虽然还有其他可能的变体，
     * 但我们在这里实现为：对于数据模式节点，匹配需要在匹配时将“项itm”字段
     * 从非null数据值CASing为null，反之亦然，请求节点从null CASing为数据值。
     * （请注意，这种类型队列的线性化属性很容易验证——元素通过链接可用，而通过匹配不可用。）
     * 与普通M&S队列相比，双队列的这种属性需要每个enq/deq对额外进行一次成功的原子操作。
     * 但它也实现了队列维护机制的低成本变体。
     * （这个想法的变体甚至适用于支持删除内部元素的非双重队列，
     * 如*j.u.c.ConcurrentLinkedQueue。）
     * 
     * Once a node is matched, its match status can never again
     * change.  We may thus arrange that the linked list of them
     * contain a prefix of zero or more matched nodes, followed by a
     * suffix of zero or more unmatched nodes. (Note that we allow
     * both the prefix and suffix to be zero length, which in turn
     * means that we do not use a dummy header.)  If we were not
     * concerned with either time or space efficiency, we could
     * correctly perform enqueue and dequeue operations by traversing
     * from a pointer to the initial node; CASing the item of the
     * first unmatched node on match and CASing the next field of the
     * trailing node on appends.  While this would be a terrible idea
     * in itself, it does have the benefit of not requiring ANY atomic
     * updates on head/tail fields.
     *
     * 一旦节点匹配，其匹配状态就不会再出现改变因此，
     * 我们可以安排他们的链接列表linked list包含零个或多个匹配节点的前继，
     * 后跟零个或更多个不匹配节点的后继。
     * （请注意，我们允许前继和后继都为零长度，这反过来意味着我们不使用伪标头。）
     * 如果我们不关心时间或空间效率，我们可以通过从指针遍历到初始节点来正确执行入队和出队操作；
     * 在匹配时对第一个不匹配节点的项item进行CAS处理，在追加时对后面节点的下一个字段进行CAS处理。
     * 虽然这本身就是一个糟糕的想法，但它的好处是不需要对头/尾字段进行任何原子更新。
     * 
     * We introduce here an approach that lies between the extremes of
     * never versus always updating queue (head and tail) pointers.
     * This offers a tradeoff between sometimes requiring extra
     * traversal steps to locate the first and/or last unmatched
     * nodes, versus the reduced overhead and contention of fewer
     * updates to queue pointers. For example, a possible snapshot of
     * a queue is:
     *
     * 我们在这里介绍了一种介于从不更新队列（头和尾）指针与始终更新队列指针之间的方法。
     * 这在有时需要额外的遍历步骤来定位第一个和/或最后一个不匹配的节点，
     * 与减少队列指针更新的开销overhead和contention竞争之间提供了一种折衷。例如，队列的可能快照是：
     * 
     *  head           tail
     *    |              |
     *    v              v
     *    M -> M -> U -> U -> U -> U
     *
     * The best value for this "slack" (the targeted maximum distance
     * between the value of "head" and the first unmatched node, and
     * similarly for "tail") is an empirical matter. We have found
     * that using very small constants in the range of 1-3 work best
     * over a range of platforms. Larger values introduce increasing
     * costs of cache misses and risks of long traversal chains, while
     * smaller values increase CAS contention and overhead.
     *
     * 这个“松弛”的最佳值（“头部”的值和第一个不匹配节点之间的目标最大距离，类似于“尾部”）
     * 是一个经验问题。我们发现，使用1-3范围内的非常小的常数在一系列平台上效果最好。
     * 较大的值会增加缓存未命中的成本和长遍历链的风险，而较小的值则会增加CAS争用和开销。
     * 
     * Dual queues with slack differ from plain M&S dual queues by
     * virtue of only sometimes updating head or tail pointers when
     * matching, appending, or even traversing nodes; in order to
     * maintain a targeted slack.  The idea of "sometimes" may be
     * operationalized in several ways. The simplest is to use a
     * per-operation counter incremented on each traversal step, and
     * to try (via CAS) to update the associated queue pointer
     * whenever the count exceeds a threshold. Another, that requires
     * more overhead, is to use random number generators to update
     * with a given probability per traversal step.
     *
     * 具有松弛的双队列与普通M&S双队列的不同之处在于，在匹配、附加甚至遍历节点时，
     * 有时只需要更新头指针或尾指针；以便保持目标松弛。
     * “有时”的概念可以通过几种方式加以实施。
     * 最简单的方法是在每个遍历步骤上使用递增的per-operation计数器，
     * 并在计数超过阈值时尝试（通过CAS）更新相关的队列指针。
     * 另一个需要更多开销的方法是使用随机数生成器在每个遍历步骤中以给定的概率进行更新。
     *
     * 
     * In any strategy along these lines, because CASes updating
     * fields may fail, the actual slack may exceed targeted slack.
     * However, they may be retried at any time to maintain targets.
     * Even when using very small slack values, this approach works
     * well for dual queues because it allows all operations up to the
     * point of matching or appending an item (hence potentially
     * allowing progress by another thread) to be read-only, thus not
     * introducing any further contention.  As described below, we
     * implement this by performing slack maintenance retries only
     * after these points.
     *
     * 在沿着这些路线的任何策略中，由于CAS更新字段可能会失败，因此实际松弛可能会超过目标松弛。
     * 但是，可以随时重试它们以维护目标。即使在使用非常小的松弛值时，这种方法也适用于双队列，
     * 因为它允许直到匹配或附加项目（因此可能允许另一个线程进行）的所有操作都是只读的，
     * 因此不会引入任何进一步的争用。
     * 如下所述，我们通过仅在这些点之后执行松弛维护重试来实现这一点.
     * 
     * As an accompaniment to such techniques, traversal overhead can
     * be further reduced without increasing contention of head
     * pointer updates: Threads may sometimes shortcut the "next" link
     * path from the current "head" node to be closer to the currently
     * known first unmatched node, and similarly for tail. Again, this
     * may be triggered with using thresholds or randomization.
     *
     * 伴随着这些技术，遍历开销可以进一步减少，而不会增加头指针更新的争用：
     * 线程有时可能会从当前的“头”节点缩短“下一个”链接路径，
     * 使其更接近当前已知的第一个不匹配节点，尾部也是如此。
     * 同样，这可以通过使用阈值或随机化来触发.
     *
     * 
     * These ideas must be further extended to avoid unbounded amounts
     * of costly-to-reclaim garbage caused by the sequential "next"
     * links of nodes starting at old forgotten head nodes:
     *
     * 必须进一步扩展这些思想，以避免从旧的被遗忘的头节点开始的节点的
     * 顺序“下一个”链接所导致的无限制的垃圾回收成本：
     * 
     * if a GC
     * delays noticing that any arbitrarily old node has become
     * garbage, all newer dead nodes will also be unreclaimed.
     * (Similar issues arise in non-GC environments.)  To cope with
     * this in our implementation, upon CASing to advance the head
     * pointer, we set the "next" link of the previous head to point
     * only to itself; thus limiting the length of chains of dead nodes.
     * (We also take similar care to wipe out possibly garbage
     * retaining values held in other Node fields.)  However, doing so
     * adds some further complexity to traversal: If any "next"
     * pointer links to itself, it indicates that the current thread
     * has lagged behind a head-update, and so the traversal must
     * continue from the "head".  Traversals trying to find the
     * current tail starting from "tail" may also encounter
     * self-links, in which case they also continue at "head".
     *
     * 如果GC延迟注意到任何任意旧的节点都变成了垃圾，那么所有新的死节点也将不被回收。
     * （在非GC环境中也会出现类似的问题。）
     * 为了在我们的实现中解决这个问题，在CASing推进头指针时，
     * 我们将前一个头的“下一个”链接设置为仅指向它自己；
     * 从而限制了死节点的链的长度。
     * （我们也会采取类似的措施来清除其他Node字段中可能存在的垃圾保留值。）
     * 然而，这样做会增加遍历的复杂性：如果任何“下一个”指针链接到它自己，
     * 则表明当前线程落后于头部更新，因此遍历必须从“头部”开始继续。
     * 试图从“尾部”开始寻找当前尾部的遍历也可能遇到自链接，
     * 在这种情况下，它们也会在“头部”继续。
     * 
     * It is tempting in slack-based scheme to not even use CAS for
     * updates (similarly to Ladan-Mozes & Shavit). However, this
     * cannot be done for head updates under the above link-forgetting
     * mechanics because an update may leave head at a detached node.
     * And while direct writes are possible for tail updates, they
     * increase the risk of long retraversals, and hence long garbage
     * chains, which can be much more costly than is worthwhile
     * considering that the cost difference of performing a CAS vs
     * write is smaller when they are not triggered on each operation
     * (especially considering that writes and CASes equally require
     * additional GC bookkeeping ("write barriers") that are sometimes
     * more costly than the writes themselves because of contention).
     *
     * 在基于松弛的方案中，甚至不使用CAS进行更新是很诱人的（类似于Ladan-Mozes和Shavit）。
     * 然而，在上述链接遗忘机制下，这不能用于头部更新，因为更新可能会将头部留在分离的节点。
     * 虽然直接写入可以用于尾部更新，但它们增加了长回溯的风险，从而增加了长垃圾链，
     * 考虑到执行CAS与写入的成本差异在每次操作未触发时较小
     * （特别是考虑到写入和CAS同样需要额外的GC记账（“写入障碍”），
     * 有时由于争用而比写入本身成本更高）。
     * 
     * *** Overview of implementation ***
     *
     * We use a threshold-based approach to updates, with a slack
     * threshold of two -- that is, we update head/tail when the
     * current pointer appears to be two or more steps away from the
     * first/last node. The slack value is hard-wired: a path greater
     * than one is naturally implemented by checking equality of
     * traversal pointers except when the list has only one element,
     * in which case we keep slack threshold at one. Avoiding tracking
     * explicit counts across method calls slightly simplifies an
     * already-messy implementation. Using randomization would
     * probably work better if there were a low-quality dirt-cheap
     * per-thread one available, but even ThreadLocalRandom is too
     * heavy for these purposes.
     *
     * 我们使用基于阈值的方法进行更新，两个松弛阈值——也就是说，
     * 当当前指针看起来距离第一个/最后一个节点两步或更多时，
     * 我们更新头/尾。松弛值是硬连接的：大于1的路径自然是通过检查遍历指针的相等性来实现的，
     * 除非列表只有一个元素，在这种情况下，我们将松弛阈值保持为1。
     * 避免在方法调用之间跟踪显式计数会稍微简化本已混乱的实现。
     * 如果有低质量、低成本的每线程，使用随机化可能会更好，
     * 但即使ThreadLocalRandom对于这些目的来说也太重了。
     *
     * With such a small slack threshold value, it is not worthwhile
     * to augment this with path short-circuiting (i.e., unsplicing
     * interior nodes) except in the case of cancellation/removal (see
     * below).
     *
     * 在这样一个小的松弛阈值的情况下，除了在取消/移除的情况下（见下文），
     * 不值得用路径短路（即，解开内部节点，从节点下链）来增加这一点。
     * 
     * All enqueue/dequeue operations are handled by the single method
     * "xfer" with parameters indicating whether to act as some form
     * of offer, put, poll, take, or transfer (each possibly with
     * timeout). The relative complexity of using one monolithic
     * method outweighs the code bulk and maintenance problems of
     * using separate methods for each case.
     *
     * 所有入队/出队操作都由单一方法“xfer”处理，参数指示是否作为某种形式的
     * offer、put、poll、take或transfer（每个可能都有超时）。
     * 使用单一方法的相对复杂性超过了在每种情况下使用单独方法的代码量和维护问题。
     * 
     * Operation consists of up to two phases. The first is implemented
     * in method xfer, the second in method awaitMatch.
     * 操作最多包括两个阶段。第一个在xfer方法中实现，第二个在awaitMatch方法中实现。
     * 
     * 1. Traverse until matching or appending (method xfer)
     *    遍历直到匹配或追加（方法xfer）
     *    
     *    Conceptually, we simply traverse all nodes starting from head.
     *    If we encounter an unmatched node of opposite mode, we match
     *    it and return, also updating head (by at least 2 hops) to
     *    one past the matched node (or the node itself if it's the
     *    pinned trailing node).  Traversals also check for the
     *    possibility of falling off-list, in which case they restart.
     *    
     *    从概念上讲，我们只是从头开始遍历所有节点。如果我们遇到相反模式的不匹配节点，
     *    我们将其匹配并返回，同时将head（至少2跳）更新为一个超过匹配节点的节点
     *    （或节点本身，如果它是固定的尾随节点）。
     *    遍历还检查从列表中掉下来的可能性，在这种情况下，它们会重新启动
     *    
     *    If the trailing node of the list is reached, a match is not
     *    possible.  If this call was untimed poll or tryTransfer
     *    (argument "how" is NOW), return empty-handed immediately.
     *    Else a new node is CAS-appended.  On successful append, if
     *    this call was ASYNC (e.g. offer), an element was
     *    successfully added to the end of the queue and we return.
     *
     *    如果已到达列表的尾部节点，则无法进行匹配。
     *    如果此调用是无计时轮询或tryTransfer（参数“how”是NOW），请立即空手而归。
     *    否则，将附加一个新节点CAS。在成功追加时，如果此调用是ASYNC（例如offer），
     *    则会将一个元素成功添加到队列的末尾，然后返回
     *    
     *    Of course, this naive traversal is O(n) when no match is
     *    possible.  We optimize the traversal by maintaining a tail
     *    pointer, which is expected to be "near" the end of the list.
     *    It is only safe to fast-forward to tail (in the presence of
     *    arbitrary concurrent changes) if it is pointing to a node of
     *    the same mode, even if it is dead (in this case no preceding
     *    node could still be matchable by this traversal).  If we
     *    need to restart due to falling off-list, we can again
     *    fast-forward to tail, but only if it has changed since the
     *    last traversal (else we might loop forever).  If tail cannot
     *    be used, traversal starts at head (but in this case we
     *    expect to be able to match near head).  As with head, we
     *    CAS-advance the tail pointer by at least two hops.
     *
     *    当然，当不可能匹配时，这种天真的遍历是O（n）。
     *    我们通过维护一个尾部指针来优化遍历，该指针应该“接近”列表的末尾。
     *    只有当它指向相同模式的节点时（在存在任意并发更改的情况下），
     *    即使它已经死了（在这种情况下，前面的节点仍然无法通过这种遍历进行匹配），
     *    才可以安全地快进到尾部。如果我们因为从列表中掉下来而需要重新启动，
     *    我们可以再次快进到尾部，但前提是它自上次遍历以来发生了变化（
     *    否则我们可能会永远循环）。如果不能使用tail，
     *    遍历从head开始（但在这种情况下，我们希望能够在head附近匹配）。
     *    与head一样，我们CAS将尾部指针向前移动至少两个跳跃。
     *    
     * 2. Await match or cancellation (method awaitMatch)
     *    等待匹配或取消（方法awaitMatch）
     *
     *    Wait for another thread to match node; instead cancelling if
     *    the current thread was interrupted or the wait timed out. To
     *    improve performance in common single-source / single-sink
     *    usages when there are more tasks that cores, an initial
     *    Thread.yield is tried when there is apparently only one
     *    waiter.  In other cases, waiters may help with some
     *    bookkeeping, then park/unpark.
     *
     *    等待另一个线程匹配节点；而是在当前线程被中断或等待超时时取消。
     *    当有更多的任务核心化时，为了提高常见的单源/单汇使用的性能，
     *    当显然只有一个等候线程时，会尝试初始的Thread.feld。
     *    在其他情况下，等候线程（waiters）可能会帮忙记账，然后停车/停车。
     *
     * ** Unlinking removed interior nodes **
     *
     * In addition to minimizing garbage retention via self-linking
     * described above, we also unlink removed interior nodes. These
     * may arise due to timed out or interrupted waits, or calls to
     * remove(x) or Iterator.remove.  Normally, given a node that was
     * at one time known to be the predecessor of some node s that is
     * to be removed, we can unsplice s by CASing the next field of
     * its predecessor if it still points to s (otherwise s must
     * already have been removed or is now offlist). But there are two
     * situations in which we cannot guarantee to make node s
     * unreachable in this way:
     *
     * 除了通过上述自链接最大限度地减少垃圾保留外，我们还取消了已删除的内部节点的链接。
     * 这些可能是由于超时或中断的等待，或调用remove（x）或Iterator.remove而导致的。
     * 通常，给定一个节点曾经是要删除的某个节点s的前代节点，如果它仍然指向s，
     * 我们可以通过对其前代节点的下一个字段进行CASing来取消复制s
     *（否则s必须已经被删除或现在处于脱机状态）。
     * 但有两种情况我们不能保证通过这种方式使节点s不可达：
     *
     * (1) If s is the trailing node of list
     * (i.e., with null next), then it is pinned as the target node
     * for appends, so can only be removed later after other nodes are
     * appended.
     * 如果s是列表的尾部节点（即下一个为null），则它被固定为附加的目标节点，
     * 因此只能在附加其他节点后才能删除。
     * 
     * (2) We cannot necessarily unlink s given a
     * predecessor node that is matched (including the case of being
     * cancelled): the predecessor may already be unspliced, in which
     * case some previous reachable node may still point to s.
     *.Although, in both
     * cases, we can rule out the need for further action if either s
     * or its predecessor are (or can be made to be) at, or fall off
     * from, the head of list.
     *
     * 给定匹配的前置节点（包括被取消的情况），我们不一定要取消s的链接：
     * 前置节点可能已经被取消了链接，在这种情况下，一些先前可到达的节点可能仍然指向s。
     * 尽管在这两种情况下，如果s或其前置节点位于（或可以被设置为）
     * 列表的头部或从列表的头部脱落，我们可以排除需要进一步操作的可能性。
     * 
     * Without taking these into account, it would be possible for an
     * unbounded number of supposedly removed nodes to remain reachable.
     * Situations leading to such buildup are uncommon but can occur
     * in practice; for example when a series of short timed calls to
     * poll repeatedly time out at the trailing node but otherwise
     * never fall off the list because of an untimed call to take() at
     * the front of the queue.
     *
     * 如果不考虑这些，无限数量的假定已删除的节点将有可能保持可达。
     * 导致这种堆积的情况并不常见，但在实践中可能会发生；
     * 例如，当一系列短时间的轮询调用在尾部节点重复超时，
     * 但由于队列前面有一个未计时的take（）调用，因此永远不会从列表中掉出来。
     * 
     * When these cases arise, rather than always retraversing the
     * entire list to find an actual predecessor to unlink (which
     * won't help for case (1) anyway), we record the need to sweep the
     * next time any thread would otherwise block in awaitMatch. Also,
     * because traversal operations on the linked list of nodes are a
     * natural opportunity to sweep dead nodes, we generally do so,
     * including all the operations that might remove elements as they
     * traverse, such as removeIf and Iterator.remove.  This largely
     * eliminates long chains of dead interior nodes, except from
     * cancelled or timed out blocking operations.
     *
     * 当出现这些情况时，我们不是总是重新转换整个列表以找到要取消链接的实际前置线程
     * （无论如何，这对情况（1）都没有帮助），
     * 而是记录下下次任何线程在awaitMatch中阻塞时需要清除的情况。
     * 此外，由于节点链表上的遍历操作是清除死节点的自然机会，
     * 我们通常会这样做，包括所有可能在遍历时删除元素的操作，
     * 如removeIf和Iterator.remove。
     * 这在很大程度上消除了死内部节点的长链，除了取消或超时的阻塞操作。
     * 
     * Note that we cannot self-link unlinked interior nodes during
     * sweeps. However, the associated garbage chains terminate when
     * some successor ultimately falls off the head of the list and is
     * self-linked.
     * 请注意，我们不能在扫描过程中自链接未链接的内部节点。
     * 然而，当某个后续的垃圾链最终从列表的头上掉下来并自链接时，相关的垃圾链就会终止。
     */

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     * Using a power of two minus one simplifies some comparisons.
     *
     * 旋转比使用定时泊车更快的纳秒数。粗略估计就足够了。使用二减一的幂简化了一些比较
     */
    static final long SPIN_FOR_TIMEOUT_THRESHOLD = 1023L;

    /**
     * The maximum number of estimated removal failures (sweepVotes)
     * to tolerate before sweeping through the queue unlinking
     * cancelled nodes that were not unlinked upon initial
     * removal. See above for explanation. The value must be at least
     * two to avoid useless sweeps when removing trailing nodes.
     */
    static final int SWEEP_THRESHOLD = 32;

    /**
     * Queue nodes. Uses Object, not E, for items to allow forgetting
     * them after use.  Writes that are intrinsically ordered wrt
     * other accesses or CASes use simple relaxed forms.
     *
     * 队列节点。使用Object而不是E作为项目，以允许在使用后忘记它们。
     * 与其他访问或CAS相比，本质上有序的写入使用简单的放松形式。
     */
    static final class Node implements ForkJoinPool.ManagedBlocker {
        final boolean isData;   // false if this is a request node
        volatile Object item;   // initially non-null if isData; CASed to match
        volatile Node next;
        volatile Thread waiter; // null when not waiting for a match

        /**
         * Constructs a data node holding item if item is non-null,
         * else a request node.  Uses relaxed write because item can
         * only be seen after piggy-backing publication via CAS.
         *
         * 如果项为非null，则构造一个数据节点来保存项，否则为请求节点。
         * 使用轻松写入，因为项目只能在通过CAS进行piggy-back发布后才能看到。
         */
        Node(Object item) {
            ITEM.set(this, item);
            isData = (item != null);
        }

        /** Constructs a (matched data) dummy node. */
        Node() {
            isData = true;
        }

        final boolean casNext(Node cmp, Node val) {
            // assert val != null;
            return NEXT.compareAndSet(this, cmp, val);
        }

        final boolean casItem(Object cmp, Object val) {
            // assert isData == (cmp != null);
            // assert isData == (val == null);
            // assert !(cmp instanceof Node);
            return ITEM.compareAndSet(this, cmp, val);
        }

        /**
         * Links node to itself to avoid garbage retention.  Called
         * only after CASing head field, so uses relaxed write.
         *
         * 将节点链接到自身以避免垃圾保留。仅在CASing头节点head字段之后调用，因此使用轻松写入。
         */
        final void selfLink() {
            // assert isMatched();
            NEXT.setRelease(this, this);
        }

        final void appendRelaxed(Node next) {
            // assert next != null;
            // assert this.next == null;
            NEXT.setOpaque(this, next);
        }

        /**
         * Returns true if this node has been matched, including the
         * case of artificial matches due to cancellation.
         */
        final boolean isMatched() {
            return isData == (item == null);
        }

        /** Tries to CAS-match this node; if successful, wakes waiter. */
        final boolean tryMatch(Object cmp, Object val) {
            if (casItem(cmp, val)) {
                LockSupport.unpark(waiter);
                return true;
            }
            return false;
        }

        /**
         * Returns true if a node with the given mode cannot be
         * appended to this node because this node is unmatched and
         * has opposite data mode.
         */
        final boolean cannotPrecede(boolean haveData) {
            boolean d = isData;
            return d != haveData && d != (item == null);
        }

        public final boolean isReleasable() {
            return (isData == (item == null)) ||
                Thread.currentThread().isInterrupted();
        }

        public final boolean block() {
            while (!isReleasable()) LockSupport.park();
            return true;
        }

        private static final long serialVersionUID = -3375979862319811754L;
    }

    /**
     * A node from which the first live (non-matched) node (if any)
     * can be reached in O(1) time.
     * Invariants:
     * - all live nodes are reachable from head via .next
     * - head != null
     * - (tmp = head).next != tmp || tmp != head
     * Non-invariants:
     * - head may or may not be live
     * - it is permitted for tail to lag behind head, that is, for tail
     *   to not be reachable from head!
     */
    transient volatile Node head;

    /**
     * A node from which the last node on list (that is, the unique
     * node with node.next == null) can be reached in O(1) time.
     * Invariants:
     * - the last node is always reachable from tail via .next
     * - tail != null
     * Non-invariants:
     * - tail may or may not be live
     * - it is permitted for tail to lag behind head, that is, for tail
     *   to not be reachable from head!
     * - tail.next may or may not be self-linked.
     */
    private transient volatile Node tail;

    /** The number of apparent failures to unsplice cancelled nodes */
    private transient volatile boolean needSweep;

    private boolean casTail(Node cmp, Node val) {
        // assert cmp != null;
        // assert val != null;
        return TAIL.compareAndSet(this, cmp, val);
    }

    private boolean casHead(Node cmp, Node val) {
        return HEAD.compareAndSet(this, cmp, val);
    }

    /**
     * Tries to CAS pred.next (or head, if pred is null) from c to p.
     * Caller must ensure that we're not unlinking the trailing node.
     */
    private boolean tryCasSuccessor(Node pred, Node c, Node p) {
        // assert p != null;
        // assert c.isData != (c.item != null);
        // assert c != p;
        if (pred != null)
            return pred.casNext(c, p);
        if (casHead(c, p)) {
            c.selfLink();
            return true;
        }
        return false;
    }

    /**
     * Collapses dead (matched) nodes between pred and q.
     * 折叠pred和q之间的死（匹配）节点。
     * @param pred the last known live node, or null if none
     * @param c the first dead node
     * @param p the last dead node
     * @param q p.next: the next live node, or null if at end
     * @return pred if pred still alive and CAS succeeded; else p
     */
    private Node skipDeadNodes(Node pred, Node c, Node p, Node q) {
        // assert pred != c;
        // assert p != q;
        // assert c.isMatched();
        // assert p.isMatched();
        if (q == null) {
            // Never unlink trailing node.
            if (c == p) return pred;
            q = p;
        }
        return (tryCasSuccessor(pred, c, q)
                && (pred == null || !pred.isMatched()))
            ? pred : p;
    }

    /**
     * Collapses dead (matched) nodes from h (which was once head) to p.
     * Caller ensures all nodes from h up to and including p are dead.
     *
     * 将死（匹配）节点从h（曾经是头）折叠到p。调用者确保从h到p（包括p）的所有节点都是死的。
     */
    private void skipDeadNodesNearHead(Node h, Node p) {
        // assert h != null;
        // assert h != p;
        // assert p.isMatched();
        for (;;) {
            final Node q;
            if ((q = p.next) == null) break;
            else if (!q.isMatched()) { p = q; break; }
            else if (p == (p = q)) return;
        }
        if (casHead(h, p))
            h.selfLink();
    }

    /* Possible values for "how" argument in xfer method. */

    private static final int NOW   = 0; // for untimed poll, tryTransfer
    private static final int ASYNC = 1; // for offer, put, add
    private static final int SYNC  = 2; // for transfer, take
    private static final int TIMED = 3; // for timed poll, tryTransfer

    /**
     * Implements all queuing methods. See above for explanation.
     * 入队方法。请参阅上面的说明。
     * @param e the item or null for take
     * @param haveData true if this is a put, else a take
     * @param how NOW, ASYNC, SYNC, or TIMED
     * @param nanos timeout in nanosecs, used only if mode is TIMED
     * @return an item if matched, else e
     * @throws NullPointerException if haveData mode but e is null
     */
    @SuppressWarnings("unchecked")
    private E xfer(E e, boolean haveData, int how, long nanos) {
        if (haveData && (e == null))
            throw new NullPointerException();

        restart: for (Node s = null, t = null, h = null;;) {
            //当t不是tail，t有数据时，取t值，否则取head值
            for (Node p = (t != (t = tail) && t.isData == haveData) ? t
                     : (h = head);; ) {
                //
                final Node q; final Object item;
                if (p.isData != haveData //取item值
                    && haveData == ((item = p.item) == null)) {
                    if (h == null) h = head;
                    if (p.tryMatch(item, e)) {//匹配节点
                        if (h != p) skipDeadNodesNearHead(h, p);
                        return (E) item;
                    }
                }
                if ((q = p.next) == null) {//尾部的节点是null时，是稳定状态
                    if (how == NOW) return e; //
                    if (s == null) s = new Node(e); //实例化E的节点
                    if (!p.casNext(null, s)) continue; //设置tail不成功则继续下一次循环
                    if (p != t) casTail(t, s); //当p不是tail时则设置尾部节点为s
                    if (how == ASYNC) return e; //
                    return awaitMatch(s, p, e, (how == TIMED), nanos); //等待
                }
                //当q不是null时，即q是个中间节点，则把q，即把q.next赋值给p，如果是一个节点则从头开始
                if (p == (p = q)) continue restart; 
            }//功能操作
        } //restart-for
    }

    /**
     * Possibly blocks until node s is matched or caller gives up.
     * 可能会阻塞，直到节点s匹配或调用方放弃。
     * @param s the waiting node
     * @param pred the predecessor of s, or null if unknown (the null
     * case does not occur in any current calls but may in possible
     * future extensions)
     * @param e the comparison value for checking match
     * @param timed if true, wait only until timeout elapses
     * @param nanos timeout in nanosecs, used only if timed is true
     * @return matched item, or e if unmatched on interrupt or timeout
     */
    @SuppressWarnings("unchecked")
    private E awaitMatch(Node s, Node pred, E e, boolean timed, long nanos) {
        final boolean isData = s.isData;
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        final Thread w = Thread.currentThread();
        int stat = -1;                   // -1: may yield, +1: park, else 0
        Object item;
        while ((item = s.item) == e) {
            if (needSweep)               // help clean
                sweep();
            else if ((timed && nanos <= 0L) || w.isInterrupted()) {
                if (s.casItem(e, (e == null) ? s : null)) {
                    unsplice(pred, s);   // cancelled
                    return e;
                }
            }
            else if (stat <= 0) {
                if (pred != null && pred.next == s) {
                    if (stat < 0 &&
                        (pred.isData != isData || pred.isMatched())) {
                        stat = 0;        // yield once if first
                        Thread.yield();
                    }
                    else {
                        stat = 1;
                        s.waiter = w;    // enable unpark
                    }
                }                        // else signal in progress
            }
            else if ((item = s.item) != e)
                break;                   // recheck
            else if (!timed) {
                LockSupport.setCurrentBlocker(this);
                try {
                    ForkJoinPool.managedBlock(s);
                } catch (InterruptedException cannotHappen) { }
                LockSupport.setCurrentBlocker(null);
            }
            else {
                nanos = deadline - System.nanoTime();
                if (nanos > SPIN_FOR_TIMEOUT_THRESHOLD)
                    LockSupport.parkNanos(this, nanos);
            }
        }
        if (stat == 1)
            WAITER.set(s, null);
        if (!isData)
            ITEM.set(s, s);              // self-link to avoid garbage
        return (E) item;
    }

    /* -------------- Traversal methods -------------- */

    /**
     * Returns the first unmatched data node, or null if none.
     * Callers must recheck if the returned node is unmatched
     * before using.
     */
    final Node firstDataNode() {
        Node first = null;
        restartFromHead: for (;;) {
            Node h = head, p = h;
            while (p != null) {
                if (p.item != null) {
                    if (p.isData) {
                        first = p;
                        break;
                    }
                }
                else if (!p.isData)
                    break;
                final Node q;
                if ((q = p.next) == null)
                    break;
                if (p == (p = q))
                    continue restartFromHead;
            }
            if (p != h && casHead(h, p))
                h.selfLink();
            return first;
        }
    }

    /**
     * Traverses and counts unmatched nodes of the given mode.
     * Used by methods size and getWaitingConsumerCount.
     */
    private int countOfMode(boolean data) {
        restartFromHead: for (;;) {
            int count = 0;
            for (Node p = head; p != null;) {
                if (!p.isMatched()) {
                    if (p.isData != data)
                        return 0;
                    if (++count == Integer.MAX_VALUE)
                        break;  // @see Collection.size()
                }
                if (p == (p = p.next))
                    continue restartFromHead;
            }
            return count;
        }
    }



    /* -------------- Removal methods -------------- */

    /**
     * Unsplices (now or later) the given deleted/cancelled node with
     * the given predecessor.
     *
     * @param pred a node that was at one time known to be the
     * predecessor of s
     * @param s the node to be unspliced
     */
    final void unsplice(Node pred, Node s) {
        // assert pred != null;
        // assert pred != s;
        // assert s != null;
        // assert s.isMatched();
        // assert (SWEEP_THRESHOLD & (SWEEP_THRESHOLD - 1)) == 0;
        s.waiter = null; // disable signals
        /*
         * See above for rationale. Briefly: if pred still points to
         * s, try to unlink s.  If s cannot be unlinked, because it is
         * trailing node or pred might be unlinked, and neither pred
         * nor s are head or offlist, set needSweep;
         */
        if (pred != null && pred.next == s) {
            Node n = s.next;
            if (n == null ||
                (n != s && pred.casNext(s, n) && pred.isMatched())) {
                for (;;) {               // check if at, or could be, head
                    Node h = head;
                    if (h == pred || h == s)
                        return;          // at head or list empty
                    if (!h.isMatched())
                        break;
                    Node hn = h.next;
                    if (hn == null)
                        return;          // now empty
                    if (hn != h && casHead(h, hn))
                        h.selfLink();  // advance head
                }
                if (pred.next != pred && s.next != s)
                    needSweep = true;
            }
        }
    }

    /**
     * Unlinks matched (typically cancelled) nodes encountered in a
     * traversal from head.
     */
    private void sweep() {
        needSweep = false;
        for (Node p = head, s, n; p != null && (s = p.next) != null; ) {
            if (!s.isMatched())
                // Unmatched nodes are never self-linked
                p = s;
            else if ((n = s.next) == null) // trailing node is pinned
                break;
            else if (s == n)    // stale
                // No need to also check for p == s, since that implies s == n
                p = head;
            else
                p.casNext(s, n);
        }
    }

    /**
     * Creates an initially empty {@code LinkedTransferQueue}.
     */
    public LinkedTransferQueue() {
        head = tail = new Node();
    }

    /**
     * Creates a {@code LinkedTransferQueue}
     * initially containing the elements of the given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public LinkedTransferQueue(Collection<? extends E> c) {
        Node h = null, t = null;
        for (E e : c) {
            Node newNode = new Node(Objects.requireNonNull(e));
            if (h == null)
                h = t = newNode;
            else
                t.appendRelaxed(t = newNode);
        }
        if (h == null)
            h = t = new Node();
        head = h;
        tail = t;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block.
     *
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) {
        xfer(e, true, ASYNC, 0L);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @return {@code true} (as specified by
     *  {@link BlockingQueue#offer(Object,long,TimeUnit) BlockingQueue.offer})
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        xfer(e, true, ASYNC, 0L);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never return {@code false}.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        xfer(e, true, ASYNC, 0L);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        xfer(e, true, ASYNC, 0L);
        return true;
    }

    /**
     * Transfers the element to a waiting consumer immediately, if possible.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * otherwise returning {@code false} without enqueuing the element.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean tryTransfer(E e) {
        return xfer(e, true, NOW, 0L) == null;
    }

    /**
     * Transfers the element to a consumer, waiting if necessary to do so.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer.
     *
     * @throws NullPointerException if the specified element is null
     */
    public void transfer(E e) throws InterruptedException {
        if (xfer(e, true, SYNC, 0L) != null) {
            Thread.interrupted(); // failure possible only due to interrupt
            throw new InterruptedException();
        }
    }

    /**
     * Transfers the element to a consumer if it is possible to do so
     * before the timeout elapses.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer,
     * returning {@code false} if the specified wait time elapses
     * before the element can be transferred.
     *
     * @throws NullPointerException if the specified element is null
     */
    public boolean tryTransfer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (xfer(e, true, TIMED, unit.toNanos(timeout)) == null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    public E take() throws InterruptedException {
        E e = xfer(null, false, SYNC, 0L);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = xfer(null, false, TIMED, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    public E poll() {
        return xfer(null, false, NOW, 0L);
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        Objects.requireNonNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null; n++)
            c.add(e);
        return n;
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null; n++)
            c.add(e);
        return n;
    }

    public E peek() {
        restartFromHead: for (;;) {
            for (Node p = head; p != null;) {
                Object item = p.item;
                if (p.isData) {
                    if (item != null) {
                        @SuppressWarnings("unchecked") E e = (E) item;
                        return e;
                    }
                }
                else if (item == null)
                    break;
                if (p == (p = p.next))
                    continue restartFromHead;
            }
            return null;
        }
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        return firstDataNode() == null;
    }

    public boolean hasWaitingConsumer() {
        restartFromHead: for (;;) {
            for (Node p = head; p != null;) {
                Object item = p.item;
                if (p.isData) {
                    if (item != null)
                        break;
                }
                else if (item == null)
                    return true;
                if (p == (p = p.next))
                    continue restartFromHead;
            }
            return false;
        }
    }

    /**
     * Returns the number of elements in this queue.  If this queue
     * contains more than {@code Integer.MAX_VALUE} elements, returns
     * {@code Integer.MAX_VALUE}.
     *
     * <p>Beware that, unlike in most collections, this method is
     * <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of these queues, determining the current
     * number of elements requires an O(n) traversal.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return countOfMode(true);
    }

    public int getWaitingConsumerCount() {
        return countOfMode(false);
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        restartFromHead: for (;;) {
            for (Node p = head, pred = null; p != null; ) {
                Node q = p.next;
                final Object item;
                if ((item = p.item) != null) {
                    if (p.isData) {
                        if (o.equals(item) && p.tryMatch(item, null)) {
                            skipDeadNodes(pred, p, p, q);
                            return true;
                        }
                        pred = p; p = q; continue;
                    }
                }
                else if (!p.isData)
                    break;
                for (Node c = p;; q = p.next) {
                    if (q == null || !q.isMatched()) {
                        pred = skipDeadNodes(pred, c, p, q); p = q; break;
                    }
                    if (p == (p = q)) continue restartFromHead;
                }
            }
            return false;
        }
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        restartFromHead: for (;;) {
            for (Node p = head, pred = null; p != null; ) {
                Node q = p.next;
                final Object item;
                if ((item = p.item) != null) {
                    if (p.isData) {
                        if (o.equals(item))
                            return true;
                        pred = p; p = q; continue;
                    }
                }
                else if (!p.isData)
                    break;
                for (Node c = p;; q = p.next) {
                    if (q == null || !q.isMatched()) {
                        pred = skipDeadNodes(pred, c, p, q); p = q; break;
                    }
                    if (p == (p = q)) continue restartFromHead;
                }
            }
            return false;
        }
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because a
     * {@code LinkedTransferQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE} (as specified by
     *         {@link BlockingQueue#remainingCapacity()})
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return bulkRemove(filter);
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    public void clear() {
        bulkRemove(e -> true);
    }

    /**
     * Tolerate this many consecutive dead nodes before CAS-collapsing.
     * Amortized cost of clear() is (1 + 1/MAX_HOPS) CASes per element.
     */
    private static final int MAX_HOPS = 8;

    /** Implementation of bulk remove methods. */
    @SuppressWarnings("unchecked")
    private boolean bulkRemove(Predicate<? super E> filter) {
        boolean removed = false;
        restartFromHead: for (;;) {
            int hops = MAX_HOPS;
            // c will be CASed to collapse intervening dead nodes between
            // pred (or head if null) and p.
            for (Node p = head, c = p, pred = null, q; p != null; p = q) {
                q = p.next;
                final Object item; boolean pAlive;
                if (pAlive = ((item = p.item) != null && p.isData)) {
                    if (filter.test((E) item)) {
                        if (p.tryMatch(item, null))
                            removed = true;
                        pAlive = false;
                    }
                }
                else if (!p.isData && item == null)
                    break;
                if (pAlive || q == null || --hops == 0) {
                    // p might already be self-linked here, but if so:
                    // - CASing head will surely fail
                    // - CASing pred's next will be useless but harmless.
                    if ((c != p && !tryCasSuccessor(pred, c, c = p))
                        || pAlive) {
                        // if CAS failed or alive, abandon old pred
                        hops = MAX_HOPS;
                        pred = p;
                        c = q;
                    }
                } else if (p == q)
                    continue restartFromHead;
            }
            return removed;
        }
    }

    /**
     * Runs action on each element found during a traversal starting at p.
     * If p is null, the action is not run.
     */
    @SuppressWarnings("unchecked")
    void forEachFrom(Consumer<? super E> action, Node p) {
        for (Node pred = null; p != null; ) {
            Node q = p.next;
            final Object item;
            if ((item = p.item) != null) {
                if (p.isData) {
                    action.accept((E) item);
                    pred = p; p = q; continue;
                }
            }
            else if (!p.isData)
                break;
            for (Node c = p;; q = p.next) {
                if (q == null || !q.isMatched()) {
                    pred = skipDeadNodes(pred, c, p, q); p = q; break;
                }
                if (p == (p = q)) { pred = null; p = head; break; }
            }
        }
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        forEachFrom(action, head);
    }

    // VarHandle mechanics
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;
    static final VarHandle ITEM;
    static final VarHandle NEXT;
    static final VarHandle WAITER;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(LinkedTransferQueue.class, "head",
                                   Node.class);
            TAIL = l.findVarHandle(LinkedTransferQueue.class, "tail",
                                   Node.class);
            ITEM = l.findVarHandle(Node.class, "item", Object.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
            WAITER = l.findVarHandle(Node.class, "waiter", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;
    }
}
