package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * An unbounded {@link TransferQueue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out) with respect
 * to any given producer.  The <em>head</em> of the queue is that
 * element that has been on the queue the longest time for some
 * producer.  The <em>tail</em> of the queue is that element that has
 * been on the queue the shortest time for some producer.
 * 一个基于链接节点的无界｛TransferQueue｝。
 * 该队列对任何给定生产者的元素FIFO（先进先出）进行排序。
 * 队列的头Head是某个生产者在队列中停留时间最长的元素。
 * 队列的尾部Tail是某个生产者在队列上停留时间最短的元素。
 * 
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 * Additionally, the bulk operations {@code addAll},
 * {@code removeAll}, {@code retainAll}, {@code containsAll},
 * {@code equals}, and {@code toArray} are <em>not</em> guaranteed
 * to be performed atomically. For example, an iterator operating
 * concurrently with an {@code addAll} operation might view only some
 * of the added elements.
 *
 * 注意，与大多数集合不同，{@code-size}方法是NOT一个常量时间运算。
 * 由于这些队列的异步性质，确定当前元素数量需要遍历元素，
 * 因此如果在遍历过程中修改此集合，则可能会报告不准确的结果。
 * 此外，批量操作{@code addAll}、{@code removeAll}、
 * {@code retainAll}、｛@code containsAll}、｛@code equals｝
 * 和{@code toArray｝不能保证以原子方式执行。例如，与{@code addAll}
 * 操作同时操作的迭代器可能只查看一些添加的元素。
 * 
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 */
public class LinkedTransferQueue<E> extends AbstractQueue<E>
    implements TransferQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * *** Overview of Dual Queues with Slack ***
     *
     * Dual Queues, introduced by Scherer and Scott
     * (http://www.cs.rice.edu/~wns1/papers/2004-DISC-DDS.pdf) are
     * (linked) queues in which nodes may represent either data or
     * requests.  When a thread tries to enqueue a data node, but
     * encounters a request node, it instead "matches" and removes it;
     * and vice versa for enqueuing requests. Blocking Dual Queues
     * arrange that threads enqueuing unmatched requests block until
     * other threads provide the match. Dual Synchronous Queues (see
     * Scherer, Lea, & Scott
     * http://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf)
     * additionally arrange that threads enqueuing unmatched data also
     * block.  Dual Transfer Queues support all of these modes, as
     * dictated by callers.
     *
     * （链接的）队列，其中节点可以表示数据或请求。
     * 当线程试图将数据节点排入队列，但遇到请求节点时，它会“匹配”并删除它；
     * 反之亦然。阻塞双队列安排将不匹配的请求排入队列的线程阻塞，直到其他线程提供匹配为止。
     * 双同步队列（参见Scherer、Lea和Scott
     * http://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf)
     * 另外安排将不匹配的数据排入队列的线程也阻塞。
     * 根据调用方的指示，双传输队列支持所有这些模式。
     * 
     * A FIFO dual queue may be implemented using a variation of the
     * Michael & Scott (M&S) lock-free queue algorithm
     * (http://www.cs.rochester.edu/u/scott/papers/1996_PODC_queues.pdf).
     * It maintains two pointer fields, "head", pointing to a
     * (matched) node that in turn points to the first actual
     * (unmatched) queue node (or null if empty); and "tail" that
     * points to the last node on the queue (or again null if
     * empty). For example, here is a possible queue with four data
     * elements:
     *
     * FIFO双队列可以使用Michael&Scott（M&S）无锁队列算法的变体来实现
     * (http://www.cs.rochester.edu/u/scott/papers/1996_PODC_queues.pdf)。
     * 它维护两个指针字段“head”，指向一个（匹配的）节点，该节点
     * 又指向第一个实际（不匹配的）队列节点（如果为空，则为null）；
     * 和指向队列上最后一个节点的“tail”（如果为空，则为null）。
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
     * contention-reducing variants such as elimination arrays (see
     * Moir et al http://portal.acm.org/citation.cfm?id=1074013) and
     * optimistic back pointers (see Ladan-Mozes & Shavit
     * http://people.csail.mit.edu/edya/publications/OptimisticFIFOQueue-journal.pdf).
     * However, the nature of dual queues enables a simpler tactic for
     * improving M&S-style implementations when dual-ness is needed.
     *
     * 众所周知，当维护（通过CAS）这些头和尾指针时，M&S队列算法容易受到可扩展性和开销限制。
     * 这导致了诸如消除阵列之类的减少争用的变体的发展（参见Moir等人
     * http://portal.acm.org/citation.cfm?id=1074013)
     * 和乐观的后点（见Ladan Mozes&Shavit
     * http://people.csail.mit.edu/edya/publications/OptimisticFIFOQueue-journal.pdf)。
     * 然而，当需要双重性时，双队列的性质使改进M&S风格实现的策略变得更简单。
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
     * 但我们在这里实现为：
     * 对于数据模式节点，匹配需要在匹配时将“项”字段从非null数据值CASing为null，
     * 反之亦然，请求节点从null CASing为数据值。
     * （请注意，这种类型队列的线性化属性很容易验证——元素通过链接可用，通过匹配不可用。）
     * 与普通M&S队列相比，双队列的这种属性需要每个enq/deq对额外一个成功的原子操作。
     * 但它也支持队列维护机制的低成本变体。
     * （这个想法的变体甚至适用于支持删除内部元素的非双重队列，
     * 如j.u.c.ConcurrentLinkedQueue。）
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
     * trailing node on appends. (Plus some special-casing when
     * initially empty).  While this would be a terrible idea in
     * itself, it does have the benefit of not requiring ANY atomic
     * updates on head/tail fields.
     *
     * 一旦匹配了一个节点，它的匹配状态就再也不会改变。
     * 因此，我们可以安排它们的链表包含零个或多个匹配节点的前缀，
     * 后跟零个或更多个不匹配节点的后缀。
     * （请注意，我们允许前缀和后缀都为零长度，这反过来意味着我们不使用伪标头。）
     * 如果我们不关心时间或空间效率，
     * 我们可以通过从指针遍历到初始节点来正确执行入队和出队操作；
     * 在匹配时对第一个不匹配节点的项进行CAS处理，在追加时对尾随节点的下一个字段进行CAS处理。
     * （加上最初排空时的一些特殊套管）。
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
     * 与减少队列指针更新的开销和争用之间提供了一种折衷。例如，队列的可能快照是：
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
     * 这个“松弛”的最佳值（“头部”的值和第一个不匹配节点之间的目标最大距离，“尾部”也是如此）
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
     * 有时只更新头指针或尾指针；以便保持目标松弛。
     * “有时”的概念可以通过几种方式加以实施。
     * 最简单的方法是在每个遍历步骤中使用递增的每个操作计数器，
     * 并尝试（通过CAS）在计数超过阈值时更新关联的队列指针。
     * 另一个需要更多开销的方法是使用随机数生成器在每个遍历步骤中以给定的概率进行更新。
     * 
     * In any strategy along these lines, because CASes updating
     * fields may fail, the actual slack may exceed targeted
     * slack. However, they may be retried at any time to maintain
     * targets.  Even when using very small slack values, this
     * approach works well for dual queues because it allows all
     * operations up to the point of matching or appending an item
     * (hence potentially allowing progress by another thread) to be
     * read-only, thus not introducing any further contention. As
     * described below, we implement this by performing slack
     * maintenance retries only after these points.
     *
     * 在沿着这些路线的任何策略中，由于CAS更新字段可能失败，实际松弛可能超过目标松弛。
     * 但是，可以随时重试它们以维护目标。
     * 即使使用非常小的松弛值，这种方法也适用于双队列，
     * 因为它允许直到匹配或附加项目（因此可能允许另一个线程进行）的所有操作都是只读的，
     * 因此不会引入任何进一步的争用。
     * 如下所述，我们通过仅在这些点之后执行松弛维护重试来实现这一点。
     * 
     * As an accompaniment to such techniques, traversal overhead can
     * be further reduced without increasing contention of head
     * pointer updates: Threads may sometimes shortcut the "next" link
     * path from the current "head" node to be closer to the currently
     * known first unmatched node, and similarly for tail. Again, this
     * may be triggered with using thresholds or randomization.
     *
     * 伴随着这种技术，遍历开销可以进一步减少，而不会增加头指针更新的争用：
     * 线程有时可能会从当前的“头”节点缩短“下一个”链接路径，
     * 使其更接近当前已知的第一个不匹配节点，尾部也是如此。
     * 同样，这可以通过使用阈值或随机化来触发。
     * 
     * These ideas must be further extended to avoid unbounded amounts
     * of costly-to-reclaim garbage caused by the sequential "next"
     * links of nodes starting at old forgotten head nodes: As first
     * described in detail by Boehm
     * (http://portal.acm.org/citation.cfm?doid=503272.503282) if a GC
     * delays noticing that any arbitrarily old node has become
     * garbage, all newer dead nodes will also be unreclaimed.
     * (Similar issues arise in non-GC environments.)
     *
     * 这些想法必须进一步扩展，以避免从旧的被遗忘的头节点开始的节点的
     * 顺序“下一个”链接所导致的无限制的回收垃圾的成本：
     * 正如Boehm首先详细描述的那样(
     * http://portal.acm.org/citation.cfm?doid=503272.503282)
     * 如果GC延迟注意到任何任意旧的节点都变成了垃圾，那么所有新的死节点也将不被回收。
     * （在非GC环境中也会出现类似的问题。）
     * 
     * To cope with
     * this in our implementation, upon CASing to advance the head
     * pointer, we set the "next" link of the previous head to point
     * only to itself; thus limiting the length of connected dead lists.
     * (We also take similar care to wipe out possibly garbage
     * retaining values held in other Node fields.)  However, doing so
     * adds some further complexity to traversal: If any "next"
     * pointer links to itself, it indicates that the current thread
     * has lagged behind a head-update, and so the traversal must
     * continue from the "head".
     *
     * 为了在我们的实现中解决这个问题，在CASing推进头指针时，
     * 我们将前一个头的“下一个”链接设置为仅指向它自己；
     * 从而限制了连接死列表的长度。
     * （我们也会采取类似的措施来清除其他Node字段中可能存在的垃圾保留值。）
     * 然而，这样做会增加遍历的复杂性：如果任何“下一个”指针链接到它自己，
     * 则表明当前线程落后于头部更新，因此遍历必须从“头部”开始继续。
     * 
     * Traversals trying to find the
     * current tail starting from "tail" may also encounter
     * self-links, in which case they also continue at "head".
     *
     * 试图从“tail”开始查找当前尾部的遍历也可能遇到自链接，
     * 在这种情况下，它们也会在“head”处继续。
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
     * 我们使用基于阈值的方法进行更新，
     * 松弛阈值为2——也就是说，当当前指针距离第一个/最后一个节点两步或更多时，
     * 我们更新头/尾。松弛值是硬连接的：大于1的路径自然是通过检查遍历指针的相等性来实现的，
     * 除非列表只有一个元素，在这种情况下，我们将松弛阈值保持在1。
     * 避免在方法调用之间跟踪显式计数会稍微简化本已混乱的实现。
     * 如果有低质量、低成本的线程，使用随机化可能会更好，
     * 但即使ThreadLocalRandom对于这些目的来说也太重了。
     * 
     * With such a small slack threshold value, it is not worthwhile
     * to augment this with path short-circuiting (i.e., unsplicing
     * interior nodes) except in the case of cancellation/removal (see
     * below).
     *
     * 在这样一个小的松弛阈值的情况下，除了在取消/移除的情况下（见下文），
     * 不值得用路径短路（即，解开内部节点）来增加这一点。
     * 
     * We allow both the head and tail fields to be null before any
     * nodes are enqueued; initializing upon first append.  This
     * simplifies some other logic, as well as providing more
     * efficient explicit control paths instead of letting JVMs insert
     * implicit NullPointerExceptions when they are null.  While not
     * currently fully implemented, we also leave open the possibility
     * of re-nulling these fields when empty (which is complicated to
     * arrange, for little benefit.)
     *
     * 在任何节点入队之前，我们允许头字段和尾字段都为空；在第一次附加时初始化。
     * 这简化了一些其他逻辑，并提供了更有效的显式控制路径，
     * 而不是让JVM在为null时插入隐式NullPointerException。虽然目前还没有完全实现，
     * 但我们也保留了在空字段时重新取消这些字段的可能性（这很复杂，没有什么好处）
     * 
     * All enqueue/dequeue operations are handled by the single method
     * "xfer" with parameters indicating whether to act as some form
     * of offer, put, poll, take, or transfer (each possibly with
     * timeout). The relative complexity of using one monolithic
     * method outweighs the code bulk and maintenance problems of
     * using separate methods for each case.
     *
     * 所有入队/出队操作都由单一方法“xfer”处理，参数指示是否作为某种形式的
     * offer、put、poll、take或transfer（每种可能都有超时）。
     * 使用单一方法的相对复杂性超过了在每种情况下使用单独方法的代码量和维护问题。
     * 
     * Operation consists of up to three phases. The first is
     * implemented within method xfer, the second in tryAppend, and
     * the third in method awaitMatch.
     *
     * 操作最多由三个阶段组成。第一个在xfer方法中实现，
     * 第二个在tryAppend中实现，而第三个在awaitMatch方法中实现。
     * 
     * 1. Try to match an existing node
     *    尝试匹配现有节点
     *
     *    Starting at head, skip already-matched nodes until finding
     *    an unmatched node of opposite mode, if one exists, in which
     *    case matching it and returning, also if necessary updating
     *    head to one past the matched node (or the node itself if the
     *    list has no other unmatched nodes). If the CAS misses, then
     *    a loop retries advancing head by two steps until either
     *    success or the slack is at most two. By requiring that each
     *    attempt advances head by two (if applicable), we ensure that
     *    the slack does not grow without bound. Traversals also check
     *    if the initial head is now off-list, in which case they
     *    start at the new head.
     *
     *    从head开始，跳过已经匹配的节点，直到找到相反模式的不匹配节点（如果存在），
     *    在这种情况下，匹配它并返回，如果必要，也可以从头到尾更新匹配节点
     *    （或者如果列表中没有其他不匹配节点，则更新节点本身）。
     *    如果CAS未命中，则一个循环会重试将头部向前推进两步，
     *    直到成功或松弛最多为两步。通过要求每次尝试将头部向前推进两个（如果适用），
     *    我们确保松弛不会在没有约束的情况下增长。
     *    遍历还检查初始标头是否现在已从列表中删除，在这种情况下，它们从新标头开始。
     *    
     *    If no candidates are found and the call was untimed
     *    poll/offer, (argument "how" is NOW) return.
     *
     *    如果没有找到候选人，并且电话是无计时的poll/offer，
     *    （参数: how:“NOW”）返回。
     *
     * 2. Try to append a new node (method tryAppend)
     *    尝试附加新节点（方法tryAppend）
     *
     *    Starting at current tail pointer, find the actual last node
     *    and try to append a new node (or if head was null, establish
     *    the first node). Nodes can be appended only if their
     *    predecessors are either already matched or are of the same
     *    mode. If we detect otherwise, then a new node with opposite
     *    mode must have been appended during traversal, so we must
     *    restart at phase 1. The traversal and update steps are
     *    otherwise similar to phase 1: Retrying upon CAS misses and
     *    checking for staleness.  In particular, if a self-link is
     *    encountered, then we can safely jump to a node on the list
     *    by continuing the traversal at current head.
     *
     *    从当前尾部指针开始，找到实际的最后一个节点，
     *    并尝试附加一个新节点（或者，如果head为null，则建立第一个节点）。
     *    只有当节点的前置节点已经匹配或处于相同模式时，才能附加节点。
     *    如果我们检测到其他情况，那么在遍历过程中一定附加了一个具有相反模式的新节点，
     *    所以我们必须在第1阶段重新启动。遍历和更新步骤在其他方面类似于
     *    阶段1：对CAS未命中进行重试并检查是否陈旧。
     *    特别是，如果遇到自链接，那么我们可以通过在当前头继续遍历来安全地跳到列表上的节点。
     *    
     *    On successful append, if the call was ASYNC, return.
     *    在成功追加时，如果调用是ASYNC，则返回。
     *
     * 3. Await match or cancellation (method awaitMatch)
     *    等待匹配或取消（方法awaitMatch）
     *
     *    Wait for another thread to match node; instead cancelling if
     *    the current thread was interrupted or the wait timed out. On
     *    multiprocessors, we use front-of-queue spinning: If a node
     *    appears to be the first unmatched node in the queue, it
     *    spins a bit before blocking. In either case, before blocking
     *    it tries to unsplice any nodes between the current "head"
     *    and the first unmatched node.
     *
     *    等待另一个线程匹配节点；如果当前线程被中断或等待超时，则取消。
     *    在多处理器上，我们使用队列前旋转：如果一个节点是队列中第一个不匹配的节点，
     *    它会在阻塞之前旋转一点。在任何一种情况下，
     *    在阻塞之前，它都会尝试解开当前“头”和第一个不匹配节点之间的任何节点。
     *    
     *    Front-of-queue spinning vastly improves performance of
     *    heavily contended queues. And so long as it is relatively
     *    brief and "quiet", spinning does not much impact performance
     *    of less-contended queues.
     *
     *    队列前端旋转极大地提高了争用严重的队列的性能。只要它相对简短且“静态”，
     *    旋转就不会对争用较少的队列的性能产生太大影响。
     *    
     *    During spins threads check their
     *    interrupt status and generate a thread-local random number
     *    to decide to occasionally perform a Thread.yield. While
     *    yield has underdefined specs, we assume that it might help,
     *    and will not hurt, in limiting impact of spinning on busy
     *    systems.  We also use smaller (1/2) spins for nodes that are
     *    not known to be front but whose predecessors have not
     *    blocked -- these "chained" spins avoid artifacts of
     *    front-of-queue rules which otherwise lead to alternating
     *    nodes spinning vs blocking. Further, front threads that
     *    represent phase changes (from data to request node or vice
     *    versa) compared to their predecessors receive additional
     *    chained spins, reflecting longer paths typically required to
     *    unblock threads during phase changes.
     *
     *     在旋转过程中，线程会检查其中断状态，并生成线程本地随机数，
     *     以决定偶尔执行thread.fyield。
     *     虽然yield低于定义的规格，但我们认为它可能有助于限制旋转对繁忙系统的影响，
     *     而不会造成损害。
     *     我们还对不知道在前面但其前身未被阻止的节点使用较小的（1/2）
     *     旋转——这些“链式”旋转避免了队列前面规则的伪影，否则会导致节点旋转与阻止交替。
     *     此外，与前代线程相比，表示相位变化（从数据到请求节点或从请求节点到请求节点）
     *     的前线程接收到额外的链式旋转，反映出在相位变化期间解锁线程通常需要更长的路径。
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
     * already have been removed or is now offlist).
     *
     * 除了通过上述自链接最大限度地减少垃圾保留外，我们还取消了已删除的内部节点的链接。
     * 这些情况可能是由于超时或等待中断，或者调用remove（x）或Iterator.remove而导致的。
     * 通常，给定一个节点曾是要删除的某个节点s的前代节点，如果它仍然指向s，
     * 我们可以通过对其前代节点的下一个字段进行CASing来取消复制s
     * （否则s必须已经被删除或现在处于脱机状态）。
     * 
     * But there are two
     * situations in which we cannot guarantee to make node s
     * unreachable in this way: (1) If s is the trailing node of list
     * (i.e., with null next), then it is pinned as the target node
     * for appends, so can only be removed later after other nodes are
     * appended. (2) We cannot necessarily unlink s given a
     * predecessor node that is matched (including the case of being
     * cancelled): the predecessor may already be unspliced, in which
     * case some previous reachable node may still point to s.
     * (For further explanation see Herlihy & Shavit "The Art of
     * Multiprocessor Programming" chapter 9).  Although, in both
     * cases, we can rule out the need for further action if either s
     * or its predecessor are (or can be made to be) at, or fall off
     * from, the head of list.
     *
     * 但有两种情况我们不能保证通过这种方式使节点s不可达：
     * （1）如果s是列表的尾部节点（即下一个为null），则它被固定为附加的目标节点，
     * 因此只有在附加了其他节点之后才能删除。
     * （2） 给定一个匹配的前置节点（包括被取消的情况），我们不一定要取消s的链接：
     * 前置节点可能已经被取消了链接，在这种情况下，一些先前可到达的节点可能仍然指向s。
     * （有关进一步的解释，请参见Herlihy&Shavit“多处理器编程的艺术”第9章）。
     *
     * 尽管在这两种情况下，如果s或其前任处于（或可以成为）排行榜首位，
     * 或从排行榜首位跌落，我们可以排除采取进一步行动的必要性。
     * 
     * Without taking these into account, it would be possible for an
     * unbounded number of supposedly removed nodes to remain
     * reachable.  Situations leading to such buildup are uncommon but
     * can occur in practice; for example when a series of short timed
     * calls to poll repeatedly time out but never otherwise fall off
     * the list because of an untimed call to take at the front of the
     * queue.
     *
     * 如果不考虑这些，无限数量的假定已删除的节点将有可能保持可访问性。
     * 导致这种堆积的情况并不常见，但在实践中可能会发生；
     * 例如，当一系列用于轮询的短时间调用重复超时，
     * 但从未因为队列前面的未计时调用而从列表中掉下来时。
     *
     * When these cases arise, rather than always retraversing the
     * entire list to find an actual predecessor to unlink (which
     * won't help for case (1) anyway), we record a conservative
     * estimate of possible unsplice failures (in "sweepVotes").
     * We trigger a full sweep when the estimate exceeds a threshold
     * ("SWEEP_THRESHOLD") indicating the maximum number of estimated
     * removal failures to tolerate before sweeping through, unlinking
     * cancelled nodes that were not unlinked upon initial removal.
     * We perform sweeps by the thread hitting threshold (rather than
     * background threads or by spreading work to other threads)
     * because in the main contexts in which removal occurs, the
     * caller is already timed-out, cancelled, or performing a
     * potentially O(n) operation (e.g. remove(x)), none of which are
     * time-critical enough to warrant the overhead that alternatives
     * would impose on other threads.
     *
     * 当出现这些情况时，我们不是总是重新转换整个列表以找到要取消链接的实际前任
     * （无论如何，这对情况（1）都没有帮助），
     * 而是记录了对可能的未复制故障的保守估计（在“sweepVotes”中）。
     * 当估计值超过阈值（“sweep_threshold”）时，我们触发全扫描，
     * 该阈值指示在扫描之前可容忍的估计移除失败的最大数量，
     * 从而取消初始移除时未取消链接的已取消节点的链接。
     * 我们通过线程达到阈值来执行扫描（而不是后台线程或通过将工作扩展到其他线程），
     * 因为在发生删除的主要上下文中，调用方已经超时、
     * 被取消或执行潜在的O（n）操作（例如，删除（x）），
     * 这些操作都不足以保证替代方案会给其他线程带来的开销。
     * 
     * Because the sweepVotes estimate is conservative, and because
     * nodes become unlinked "naturally" as they fall off the head of
     * the queue, and because we allow votes to accumulate even while
     * sweeps are in progress, there are typically significantly fewer
     * such nodes than estimated.  Choice of a threshold value
     * balances the likelihood of wasted effort and contention, versus
     * providing a worst-case bound on retention of interior nodes in
     * quiescent queues. The value defined below was chosen
     * empirically to balance these under various timeout scenarios.
     *
     * 由于sweepVotes的估计是保守的，而且节点从队列的顶端掉下来时会“自然”地断开连接，
     * 而且即使在扫描进行时，我们也允许选票累积，因此这样的节点通常比估计的要少得多。
     * 阈值的选择平衡了浪费精力和争用的可能性，
     * 而不是提供静态队列中内部节点保留的最坏情况限制。
     * 根据经验选择以下定义的值，以在各种超时情况下平衡这些值。
     * 
     * Note that we cannot self-link unlinked interior nodes during
     * sweeps. However, the associated garbage chains terminate when
     * some successor ultimately falls off the head of the list and is
     * self-linked.
     *
     * 请注意，我们不能在扫描过程中自链接未链接的内部节点。
     * 然而，当某个后续的垃圾链最终从列表的头上掉下来并自链接时，相关的垃圾链就会终止。
     */

    /** True if on multiprocessor */
    private static final boolean MP =
        Runtime.getRuntime().availableProcessors() > 1;

    /**
     * The number of times to spin (with randomly interspersed calls
     * to Thread.yield) on multiprocessor before blocking when a node
     * is apparently the first waiter in the queue.  See above for
     * explanation. Must be a power of two. The value is empirically
     * derived -- it works pretty well across a variety of processors,
     * numbers of CPUs, and OSes.
     *
     * 当节点显然是队列中的第一个服务员时，在阻塞之前，在多处理器上旋转
     * （随机穿插调用Thread.fyield）的次数。
     * 请参阅上面的说明。
     * 一定是二次幂。这个值是根据经验得出的——它在各种处理器、CPU数量和操作系统中都能很好地工作。
     */
    private static final int FRONT_SPINS   = 1 << 7;

    /**
     * The number of times to spin before blocking when a node is
     * preceded by another node that is apparently spinning.  Also
     * serves as an increment to FRONT_SPINS on phase changes, and as
     * base average frequency for yielding during spins. Must be a
     * power of two.
     *
     * 当一个节点前面有另一个明显在旋转的节点时，在阻塞之前旋转的次数。
     * 在相位变化时也用作FRONT_SPINS的增量，
     * 并在旋转过程中用作屈服的基本平均频率。一定是二次幂。
     */
    private static final int CHAINED_SPINS = FRONT_SPINS >>> 1;

    /**
     * The maximum number of estimated removal failures (sweepVotes)
     * to tolerate before sweeping through the queue unlinking
     * cancelled nodes that were not unlinked upon initial
     * removal. See above for explanation. The value must be at least
     * two to avoid useless sweeps when removing trailing nodes.
     *
     * 在清除队列之前，估计可容忍的最大删除失败次数（sweepVotes）
     * 取消了初始删除时未取消链接的已取消链接的节点。
     * 请参阅上面的说明。该值必须至少为2，以避免在删除尾部节点时进行无用的扫描。
     */
    static final int SWEEP_THRESHOLD = 32;

    /**
     * Queue nodes. Uses Object, not E, for items to allow forgetting
     * them after use.  Relies heavily on Unsafe mechanics to minimize
     * unnecessary ordering constraints: Writes that are intrinsically
     * ordered wrt other accesses or CASes use simple relaxed forms.
     *
     * 队列节点。使用Object而不是E作为项目，以允许在使用后忘记它们。
     * 在很大程度上依赖于不安全机制来最大限度地减少不必要的排序约束：
     * 与其他访问或CAS本质上有序的写入使用简单的宽松形式。
     */
    static final class Node {
        // 如果这是一个请求节点，则为false
        final boolean isData;   // false if this is a request node
        // 如果isData，则初始值为非null；CASed匹配
        volatile Object item;   // initially non-null if isData; CASed to match
        volatile Node next;
        // 等待前为null
        volatile Thread waiter; // null until waiting

        // CAS methods for fields
        final boolean casNext(Node cmp, Node val) {
            return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
        }

        final boolean casItem(Object cmp, Object val) {
            // assert cmp == null || cmp.getClass() != Node.class;
            return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
        }

        /**
         * Constructs a new node.  Uses relaxed write because item can
         * only be seen after publication via casNext.
         * 
         * 构造一个新节点。使用轻松写入，因为只有在通过casNext发布后才能看到数据item。
         */
        Node(Object item, boolean isData) {
            UNSAFE.putObject(this, itemOffset, item); // relaxed write
            this.isData = isData;
        }

        /**
         * Links node to itself to avoid garbage retention.  Called
         * only after CASing head field, so uses relaxed write.
         * 
         * 将节点链接到自身以避免垃圾保留。仅在CASing头字段之后调用，因此使用轻松写入。
         */
        final void forgetNext() {
            UNSAFE.putObject(this, nextOffset, this);
        }

        /**
         * Sets item to self and waiter to null, to avoid garbage
         * retention after matching or cancelling. Uses relaxed writes
         * because order is already constrained in the only calling
         * contexts: item is forgotten only after volatile/atomic
         * mechanics that extract items.  Similarly, clearing waiter
         * follows either CAS or return from park (if ever parked;
         * else we don't care).
         *
         * 将item设置为self，将waiter设置为null，以避免匹配或取消后的垃圾保留。
         * 使用宽松的写入，因为顺序在唯一的调用上下文中已经受到约束：
         * 只有在提取项的volatile/原子机制之后，项item才会被废弃。
         * 类似地，清理waiter要么跟随CAS，
         * 要么从阻塞park返回（如果曾经阻塞parked；否则我们不在乎）。
         */
        final void forgetContents() {
            UNSAFE.putObject(this, itemOffset, this);
            UNSAFE.putObject(this, waiterOffset, null);
        }

        /**
         * Returns true if this node has been matched, including the
         * case of artificial matches due to cancellation.
         *
         * 如果此节点已匹配，包括由于取消而导致的手工为匹配，则返回true。
         */
        final boolean isMatched() {
            Object x = item;
            return (x == this) || ((x == null) == isData);
        }

        /**
         * Returns true if this is an unmatched request node.
         * 如果这是一个不匹配的请求节点，则返回true。
         */
        final boolean isUnmatchedRequest() {
            return !isData && item == null;
        }

        /**
         * Returns true if a node with the given mode cannot be
         * appended to this node because this node is unmatched and
         * has opposite data mode.
         * 如果具有给定模式的节点无法附加到此节点，
         * 节点不匹配并且具有相反的数据模式，则返回true。
         */
        final boolean cannotPrecede(boolean haveData) {
            boolean d = isData;
            Object x;
            return d != haveData && (x = item) != this && (x != null) == d;
        }

        /**
         * Tries to artificially match a data node -- used by remove.
         * 
         * 尝试人为地匹配数据节点--由remove使用。
         */
        final boolean tryMatchData() {
            // assert isData;
            Object x = item;
            if (x != null && x != this && casItem(x, null)) {
                LockSupport.unpark(waiter);
                return true;
            }
            return false;
        }

        private static final long serialVersionUID = -3375979862319811754L;

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long itemOffset;
        private static final long nextOffset;
        private static final long waiterOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = Node.class;
                itemOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("item"));
                nextOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("next"));
                waiterOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiter"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * head of the queue; null until first enqueue
     * 队列的head；在第一次入队之前为null
    */
    transient volatile Node head;

    /**
     * tail of the queue; null until first append
     * 队列的尾部；在第一次追加之前为null
    */
    private transient volatile Node tail;

    /**
     * The number of apparent failures to unsplice removed nodes
     * 无法解开已删除节点的明显故障数
    */
    private transient volatile int sweepVotes;

    // CAS methods for fields
    private boolean casTail(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, tailOffset, cmp, val);
    }

    private boolean casHead(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, headOffset, cmp, val);
    }

    private boolean casSweepVotes(int cmp, int val) {
        return UNSAFE.compareAndSwapInt(this, sweepVotesOffset, cmp, val);
    }

    /*
     * Possible values for "how" argument in xfer method.
     * xfer方法中“how”参数的可能值。
     */
    private static final int NOW   = 0; // for untimed poll, tryTransfer
    private static final int ASYNC = 1; // for offer, put, add
    private static final int SYNC  = 2; // for transfer, take
    private static final int TIMED = 3; // for timed poll, tryTransfer

    @SuppressWarnings("unchecked")
    static <E> E cast(Object item) {
        // assert item == null || item.getClass() != Node.class;
        return (E) item;
    }

    /**
     * Returns spin/yield value for a node with given predecessor and
     * data mode. See above for explanation.
     * 返回具有给定前置任务和数据模式的节点的旋转spin/让步yield。
     */
    private static int spinsFor(Node pred, boolean haveData) {
        if (MP && pred != null) {
            if (pred.isData != haveData)      // phase change
                return FRONT_SPINS + CHAINED_SPINS;
            if (pred.isMatched())             // probably at front
                return FRONT_SPINS;
            if (pred.waiter == null)          // pred apparently spinning
                return CHAINED_SPINS;
        }
        return 0;
    }
    
    /**
     * Implements all queuing methods. See above for explanation.
     * 实现所有排队方法。请参阅上面的说明。
     * @param e the item or null for take null:take
     * @param haveData true if this is a put, else a take 
     * @param how NOW, ASYNC, SYNC, or TIMED
     * @param nanos timeout in nanosecs, used only if mode is TIMED
     */
    private E xfer(E e, boolean haveData, int how, long nanos) {
        if (haveData && (e == null)){
            throw new NullPointerException();
        }
        //要附加的节点（如果需要）
        Node s = null;               // the node to append, if needed
        retry:
        for (;;) { // restart on append race 加入节点时争用时重新启动

            for (Node h = head, p = h; p != null;) { // find & match first node
                boolean isData = p.isData;
                Object item = p.item;
                if (item != p && (item != null) == isData) { // unmatched
                    if (isData == haveData){   // can't match
                        break;
                    }
                    if (p.casItem(item, e)) { // match
                        for (Node q = p; q != h;) { //p更新之后，与h不相等
                            Node n = q.next;  // update by 2 unless singleton
                            //是头部节点 
                            if (head == h && casHead(h, n == null ? q : n)) {
                                // h的next设置为自己，
                                // cas更新下，使用plian、Oqueue模式可以保证内存访问安全
                                h.forgetNext();
                                break;
                            }                 // advance and retry
                            if ((h = head)   == null ||
                                (q = h.next) == null || !q.isMatched())
                                break;        // unless slack < 2 除非松弛度<2
                        }
                        // 解除阻塞
                        LockSupport.unpark(p.waiter);
                        // 返回值
                        return LinkedTransferQueue.<E>cast(item);
                    }
                } // 数据节点
                // 取下一个节点
                Node n = p.next;
                //如果p 下线 offlist，则使用head
                p = (p != n) ? n : (h = head); // Use head if p offlist
            }
            
            //取出数据结束 for-over
            if (how != NOW) {                 // No matches available
                if (s == null){
                    s = new Node(e, haveData);
                }
                //队列中加入节点
                Node pred = tryAppend(s, haveData);
                if (pred == null){
                    // 争用失败 反向模式
                    continue retry;           // lost race vs opposite mode
                }
                if (how != ASYNC){
                    return awaitMatch(s, pred, e, (how == TIMED), nanos);
                }
            }
            return e; // not waiting
        }
    }

    /**
     * Tries to append node s as tail.
     * 尝试将节点s附加为尾部。
     * @param s the node to append
     * @param haveData true if appending in data mode
     * @return null on failure due to losing race with append in
     * different mode, else s's predecessor, or s itself if no
     * predecessor
     */
    private Node tryAppend(Node s, boolean haveData) {
        // 将p移动到最后一个节点并附加
        for (Node t = tail, p = t;;) {        // move p to last node and append
            //读取下一个next & 尾部tail
            Node n, u;                        // temps for reads of next & tail
            if (p == null && (p = head) == null) {
                if (casHead(null, s)){
                    return s;                 // initialize
                }
            }
            else if (p.cannotPrecede(haveData)){ //竞争失败或者反模式
                return null;          // lost race vs opposite mode
            }
            //不是最后一个节点；继续遍历
            else if ((n = p.next) != null){    // not last; keep traversing
                p = p != t && t != (u = tail) ? (t = u) : // stale tail 旧的尾
                    (p != n) ? n : null;      // restart if off list
                                              // 如果不在列表中，则重新启动
            }else if (!p.casNext(null, s)){
                // cas 失败
                p = p.next;                   // re-read on CAS failure
            }
            else {
                // 如果现在松弛>=2，则更新
                if (p != t) {   // update if slack now >= 2
                    while ((tail != t || !casTail(t, s)) &&
                           (t = tail)   != null &&
                           // 前进和重试
                           (s = t.next) != null && // advance and retry
                           (s = s.next) != null && s != t);
                }
                return p;
            }
        }
    }

    /**
     * Spins/yields/blocks until node s is matched or caller gives up.
     *
     * @param s the waiting node
     * @param pred the predecessor of s, or s itself if it has no
     * predecessor, or null if unknown (the null case does not occur
     * in any current calls but may in possible future extensions)
     * @param e the comparison value for checking match
     * @param timed if true, wait only until timeout elapses
     * @param nanos timeout in nanosecs, used only if timed is true
     * @return matched item, or e if unmatched on interrupt or timeout
     */
    private E awaitMatch(Node s, Node pred, E e, boolean timed, long nanos) {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        Thread w = Thread.currentThread();
        int spins = -1; // initialized after first item and cancel checks
        ThreadLocalRandom randomYields = null; // bound if needed

        for (;;) {
            Object item = s.item;
            if (item != e) {                  // matched
                // assert item != s;
                s.forgetContents();           // avoid garbage
                return LinkedTransferQueue.<E>cast(item);
            }
            // 删除中断节点
            if ((w.isInterrupted() || (timed && nanos <= 0)) &&
                    s.casItem(e, s)) {        // cancel
                //
                unsplice(pred, s);
                return e;
            }

            //
            if (spins < 0) {                  // establish spins at/near front
                if ((spins = spinsFor(pred, s.isData)) > 0){
                    randomYields = ThreadLocalRandom.current();
                }
            }
            else if (spins > 0) {             // spin
                --spins;
                if (randomYields.nextInt(CHAINED_SPINS) == 0){
                    Thread.yield();           // occasionally yield
                }
            }
            else if (s.waiter == null) {
                s.waiter = w;                 // request unpark then recheck
            }
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos > 0L){
                    LockSupport.parkNanos(this, nanos);
                }
            }
            else {
                LockSupport.park(this);
            }
        }
    }


    /* -------------- Removal methods -------------- */

    /**
     * Unsplices (now or later) the given deleted/cancelled node with
     * the given predecessor.
     *
     * 将给定的已删除/已取消的节点与给定的前置节点一起解开（现在或以后）。
     *
     * @param pred a node that was at one time known to be the
     * predecessor of s, or null or s itself if s is/was at head
     * @param s the node to be unspliced
     */
    final void unsplice(Node pred, Node s) {
        s.forgetContents(); // forget unneeded fields
        /*
         * See above for rationale. Briefly: if pred still points to
         * s, try to unlink s.  If s cannot be unlinked, because it is
         * trailing node or pred might be unlinked, and neither pred
         * nor s are head or offlist, add to sweepVotes, and if enough
         * votes have accumulated, sweep.
         *
         * 基本原理见上文。简单地说：如果pred仍然指向s，请尝试取消链接s。
         * 如果s无法取消链接，因为它是尾随节点，或者pred可能被取消链接，
         * 并且pred和s都不是head或offlist，
         * 请添加到sweepVotes，如果累积了足够的票数，则进行扫选。
         */
        if (pred != null && pred != s && pred.next == s) {
            Node n = s.next;
            if (n == null ||
                (n != s && pred.casNext(s, n) && pred.isMatched())) {
                for (;;) {               // check if at, or could be, head
                    Node h = head;
                    if (h == pred || h == s || h == null)
                        return;          // at head or list empty
                    if (!h.isMatched())
                        break;
                    Node hn = h.next;
                    if (hn == null)
                        return;          // now empty
                    if (hn != h && casHead(h, hn))
                        h.forgetNext();  // advance head
                }
                if (pred.next != pred && s.next != s) { // recheck if offlist
                    for (;;) {           // sweep now if enough votes
                        int v = sweepVotes;
                        if (v < SWEEP_THRESHOLD) {
                            if (casSweepVotes(v, v + 1))
                                break;
                        }
                        else if (casSweepVotes(v, 0)) {
                            sweep();
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Unlinks matched (typically cancelled) nodes encountered in a
     * traversal from head.
     */
    private void sweep() {
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

     /* -------------- Traversal methods -------------- */

    /**
     * Returns the successor of p, or the head node if p.next has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     */
    final Node succ(Node p) {
        Node next = p.next;
        return (p == next) ? head : next;
    }

    /**
     * Returns the first unmatched node of the given mode, or null if
     * none.  Used by methods isEmpty, hasWaitingConsumer.
     */
    private Node firstOfMode(boolean isData) {
        for (Node p = head; p != null; p = succ(p)) {
            if (!p.isMatched())
                return (p.isData == isData) ? p : null;
        }
        return null;
    }

    /**
     * Version of firstOfMode used by Spliterator. Callers must
     * recheck if the returned node's item field is null or
     * self-linked before using.
     */
    final Node firstDataNode() {
        for (Node p = head; p != null;) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p)
                    return p;
            }
            else if (item == null)
                break;
            if (p == (p = p.next))
                p = head;
        }
        return null;
    }

    /**
     * Returns the item in the first unmatched node with isData; or
     * null if none.  Used by peek.
     */
    private E firstDataItem() {
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p)
                    return LinkedTransferQueue.<E>cast(item);
            }
            else if (item == null)
                return null;
        }
        return null;
    }

    /**
     * Traverses and counts unmatched nodes of the given mode.
     * Used by methods size and getWaitingConsumerCount.
     */
    private int countOfMode(boolean data) {
        int count = 0;
        for (Node p = head; p != null; ) {
            if (!p.isMatched()) {
                if (p.isData != data)
                    return 0;
                if (++count == Integer.MAX_VALUE) // saturated
                    break;
            }
            Node n = p.next;
            if (n != p)
                p = n;
            else {
                count = 0;
                p = head;
            }
        }
        return count;
    }
    
    /**
     * Main implementation of remove(Object)
     */
    private boolean findAndRemove(Object e) {
        if (e != null) {
            for (Node pred = null, p = head; p != null; ) {
                Object item = p.item;
                if (p.isData) {
                    if (item != null && item != p && e.equals(item) &&
                        p.tryMatchData()) {
                        unsplice(pred, p);
                        return true;
                    }
                }
                else if (item == null)
                    break;
                pred = p;
                if ((p = p.next) == pred) { // stale
                    pred = null;
                    p = head;
                }
            }
        }
        return false;
    }

    /**
     * Creates an initially empty {@code LinkedTransferQueue}.
     */
    public LinkedTransferQueue() {
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
        this();
        addAll(c);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block.
     *
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) {
        xfer(e, true, ASYNC, 0);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @return {@code true} (as specified by
     *  {@link java.util.concurrent.BlockingQueue#offer(Object,long,TimeUnit)
     *  BlockingQueue.offer})
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        xfer(e, true, ASYNC, 0);
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
        xfer(e, true, ASYNC, 0);
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
        xfer(e, true, ASYNC, 0);
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
        return xfer(e, true, NOW, 0) == null;
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
        if (xfer(e, true, SYNC, 0) != null) {
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
        E e = xfer(null, false, SYNC, 0);
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
        return xfer(null, false, NOW, 0);
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    public E peek() {
        return firstDataItem();
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        for (Node p = head; p != null; p = succ(p)) {
            if (!p.isMatched())
                return !p.isData;
        }
        return true;
    }

    public boolean hasWaitingConsumer() {
        return firstOfMode(false) != null;
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
        return findAndRemove(o);
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
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p && o.equals(item))
                    return true;
            }
            else if (item == null)
                break;
        }
        return false;
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because a
     * {@code LinkedTransferQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE} (as specified by
     *         {@link java.util.concurrent.BlockingQueue#remainingCapacity()
     *         BlockingQueue.remainingCapacity})
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    // Unsafe mechanics

    private static final sun.misc.Unsafe UNSAFE;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long sweepVotesOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = LinkedTransferQueue.class;
            headOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("head"));
            tailOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("tail"));
            sweepVotesOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("sweepVotes"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
