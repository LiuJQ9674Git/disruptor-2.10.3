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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An unbounded thread-safe {@linkplain Queue queue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out).
 * The <em>head</em> of the queue is that element that has been on the
 * queue the longest time.
 * The <em>tail</em> of the queue is that element that has been on the
 * queue the shortest time. New elements
 * are inserted at the tail of the queue, and the queue retrieval
 * operations obtain elements at the head of the queue.
 * A {@code ConcurrentLinkedQueue} is an appropriate choice when
 * many threads will share access to a common collection.
 * Like most other concurrent collection implementations, this class
 * does not permit the use of {@code null} elements.
 *
 *<p>
 *基于链节点的无边界线程安全队列。该队列对元素FIFO（先进先出）进行排序。
 *队列的头是在队列中停留时间最长的元素。队列的尾部是在队列中停留时间最短的元素。
 *新元素被插入到队列的尾部，队列检索操作获得队列头部的元素。当许多线程共享对公共集合的访问权时，
 *ConcurrentLinkedQueue是一个合适的选择。与大多数其他并发集合实现一样，此类不允许使用null元素。
 *
 * <p>This implementation employs an efficient <em>non-blocking</em>
 * algorithm based on one described in
 * <a href="http://www.cs.rochester.edu/~scott/papers/1996_PODC_queues.pdf">
 * Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue
 * Algorithms</a> by Maged M. Michael and Michael L. Scott.
 *
 * <p>Iterators are <i>weakly consistent</i>, returning elements
 * reflecting the state of the queue at some point at or since the
 * creation of the iterator.  They do <em>not</em> throw {@link
 * java.util.ConcurrentModificationException}, and may proceed concurrently
 * with other operations.  Elements contained in the queue since the creation
 * of the iterator will be returned exactly once.
 *
 *<p>
 *迭代器是弱一致的，返回的元素反映了迭代器创建时或创建之后某个时刻的队列状态。
 *它们不会抛出java.util.ConcurrentModificationException，并且可以与其他操作并行进行。
 *自迭代器创建以来，队列中包含的元素将只返回一次。
 *
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 *<p>
 *注意，与大多数集合不同，size方法不是一个常量时间运算。
 *由于这些队列的异步性质，确定当前元素数量需要遍历元素，因此如果在遍历过程中修改此集合，
 *则可能会报告不准确的结果。
 *
 * <p>Bulk operations that add, remove, or examine multiple elements,
 * such as {@link #addAll}, {@link #removeIf} or {@link #forEach},
 * are <em>not</em> guaranteed to be performed atomically.
 * For example, a {@code forEach} traversal concurrent with an {@code
 * addAll} operation might observe only some of the added elements.
 *
 *<p>
 *添加、删除或检查多个元素的大容量操作（如addAll、removeIf或forEach）不能保证以原子方式执行。
 *例如，与addAll操作并发的forEach遍历可能只观察到一些添加的元素。
 *
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Queue} and {@link Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent
 * collections, actions in a thread prior to placing an object into a
 * {@code ConcurrentLinkedQueue}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code ConcurrentLinkedQueue} in another thread.
 *
 *<p>
 *内存一致性影响：与其他并发集合一样，在一个线程中将对象放入ConcurrentLinkedQueue的动作，
 *happen-before
 *在另一个线程中从ConcurrentLinkdQueue访问或删除该元素之后的操作。
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/java.base/java/util/package-summary.html#CollectionsFramework">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this queue
 */
public class ConcurrentLinkedQueue<E> extends AbstractQueue<E>
        implements Queue<E>, java.io.Serializable {
    private static final long serialVersionUID = 196745693267521676L;

    /*
     * This is a modification of the Michael & Scott algorithm,
     * adapted for a garbage-collected environment, with support for
     * interior node deletion (to support e.g. remove(Object)).  For
     * explanation, read the paper.
     *
     * Note that like most non-blocking algorithms in this package,
     * this implementation relies on the fact that in garbage
     * collected systems, there is no possibility of ABA problems due
     * to recycled nodes, so there is no need to use "counted
     * pointers" or related techniques seen in versions used in
     * non-GC'ed settings.
     *
     *注意，像该包中的大多数非阻塞算法一样，该实现依赖于在垃圾中收集的系统，
     *不存在ABA问题的可能性到回收节点，因此无需使用“计数指针”或在中使用的版本中看
     *到的相关技术非GC’ed设置。
     * The fundamental invariants are:基本不变量是：
     * - There is exactly one (last) Node with a null next reference,
     *   which is CASed when enqueueing.  This last Node can be
     *   reached in O(1) time from tail, but tail is merely an
     *   optimization - it can always be reached in O(N) time from
     *   head as well.
     *
     *   恰好有一个（最后一个）节点具有空的下一个引用，其在排队时被CASed。
     *   最后一个节点可以在O（1）时间内从尾部到达，
     *   但尾部只是一个优化——它也总是可以在0（N）时间内从头到达。
     *   
     * - The elements contained in the queue are the non-null items in
     *   Nodes that are reachable from head.  CASing the item
     *   reference of a Node to null atomically removes it from the
     *   queue.  Reachability of all elements from head must remain
     *   true even in the case of concurrent modifications that cause
     *   head to advance.  A dequeued Node may remain in use
     *   indefinitely due to creation of an Iterator or simply a
     *   poll() that has lost its time slice.
     *
     *   队列中包含的元素是节点中可从头访问的非null项。CAS将Node的项引用原子化地从队列中删除。
     *   所有元素从头部的可达性必须保持不变，即使在导致头部前进的同时修改的情况下也是如此。
     *   由于创建了一个Iterator或只是一个丢失了时间片的poll()，
     *   出队节点可能会无限期地保持使用状态。
     *   
     * The above might appear to imply that all Nodes are GC-reachable
     * from a predecessor dequeued Node.
     *   上面的内容可能意味着所有节点都可以从前一个排队节点GC访问。
     *   
     * That would cause two problems:
     * - allow a rogue Iterator to cause unbounded memory retention
     *   允许迭代器导致无限内存保留
     *   
     * - cause cross-generational linking of old Nodes to new Nodes if
     *   a Node was tenured while live, which generational GCs have a
     *   hard time dealing with, causing repeated major collections.
     *
     *   如果一个节点在存活期间被终身使用，则会导致旧节点与新节点的跨代链接，
     *   而这一代GC很难处理，从而导致重复的主要收集。
     *   
     * However, only non-deleted Nodes need to be reachable from
     * dequeued Nodes, and reachability does not necessarily have to
     * be of the kind understood by the GC.  We use the trick of
     * linking a Node that has just been dequeued to itself.  Such a
     * self-link implicitly means to advance to head.
     *
     * 然而，只有未删除的节点才需要从退出队列的节点访问，并且可达性不一定必须是GC所理解的那种。
     * 我们使用的技巧是将刚刚出列的节点链接到它自己。这种自我链接隐含着向头部前进的意思。
     * 
     * Both head and tail are permitted to lag.  In fact, failing to
     * update them every time one could is a significant optimization
     * (fewer CASes). As with LinkedTransferQueue (see the internal
     * documentation for that class), we use a slack threshold of two;
     * that is, we update head/tail when the current pointer appears
     * to be two or more steps away from the first/last node.
     *
     * 头部和尾部都允许滞后。事实上，每次都不能更新它们是一个显著的优化（更少的CAS）。
     * 我们使用两个松弛阈值；也就是说，当当前指针看起来距离第一个/最后一个节点两步或两步以上时，
     * 我们更新head/tail。
     * 
     * Since head and tail are updated concurrently and independently,
     * it is possible for tail to lag behind head (why not)?
     * 既然头部和尾部是同时独立更新的，那么尾部有可能落后于头部？
     *
     * CASing a Node's item reference to null atomically removes the
     * element from the queue, leaving a "dead" node that should later
     * be unlinked (but unlinking is merely an optimization).
     * Interior element removal methods (other than Iterator.remove())
     * keep track of the predecessor node during traversal so that the
     * node can be CAS-unlinked.  Some traversal methods try to unlink
     * any deleted nodes encountered during traversal.  See comments
     * in bulkRemove.
     *
     * CASing将Node的item引用原子化地设置为null，来删除队列中的元素，
     * 留下一个稍后应该下链unlinking的“死”节点（但unlinking只是一种优化）。
     * 内部元素删除方法（而不是Iterator.remove（））在遍历期间跟踪前置任务（线程）节点，
     * 以便可以节点以CAS-unlinked断开该节点。
     * 一些遍历方法试图取消遍历过程中遇到的任何已删除节点的链接。
     * 
     * When constructing a Node (before enqueuing it) we avoid paying
     * for a volatile write to item.  This allows the cost of enqueue
     * to be "one-and-a-half" CASes.
     *
     * 在构造Node时（在将其排队之前），我们避免为易失性volatile的写入item字段。
     * 这使得排队的费用为“一个半”CASes。
     * 
     * Both head and tail may or may not point to a Node with a
     * non-null item.  If the queue is empty, all items must of course
     * be null.  Upon creation, both head and tail refer to a dummy
     * Node with null item.  Both head and tail are only updated using
     * CAS, so they never regress, although again this is merely an
     * optimization.
     *
     * 头和尾可以指向也可以不指向具有非空的数据item的节点。如果队列为空，
     * 那么所有item当然都必须为空。创建时，头和尾都引用了一个具有null的item的虚拟节点。
     * 头部和尾部都只使用CAS更新，所以它们永远不会回收，尽管这只是一个优化。
     */
    static final class Node<E> {
        volatile E item;
        volatile Node<E> next;

        /**
         * Constructs a node holding item.  Uses relaxed write because
         * item can only be seen after piggy-backing publication via CAS.
         * 构造一个节点保持项item。使用松弛relaxed写入（Release/Opeque），
         * 因为只有在通过CAS顺便使得piggy-backing发布后才能看到项目。
         * 之后通过CAS方式发布
         */
        Node(E item) {
            ITEM.set(this, item);
        }

        /** Constructs a dead dummy node. */
        Node() {}

        void appendRelaxed(Node<E> next) {
            // assert next != null;
            // assert this.next == null;
            NEXT.set(this, next);
        }

        //数据变化，原子设置
        boolean casItem(E cmp, E val) {
            // assert item == cmp || item == null;
            // assert cmp != null;
            // assert val == null;
            return ITEM.compareAndSet(this, cmp, val);
        }
    }

    /**
     * A node from which the first live (non-deleted) node (if any)
     * can be reached in O(1) time.
     * Invariants:
     * - all live nodes are reachable from head via succ()
     * - head != null
     * - (tmp = head).next != tmp || tmp != head
     * Non-invariants:
     * - head.item may or may not be null.
     * - it is permitted for tail to lag behind head, that is, for tail
     *   to not be reachable from head!
     */
    transient volatile Node<E> head;

    /**
     * A node from which the last node on list (that is, the unique
     * node with node.next == null) can be reached in O(1) time.
     * Invariants:
     * - the last node is always reachable from tail via succ()
     * - tail != null
     * Non-invariants:
     * - tail.item may or may not be null.
     * - it is permitted for tail to lag behind head, that is, for tail
     *   to not be reachable from head!
     * - tail.next may or may not be self-linked.
     */
    private transient volatile Node<E> tail;

    /**
     * Creates a {@code ConcurrentLinkedQueue} that is initially empty.
     */
    public ConcurrentLinkedQueue() {
        head = tail = new Node<E>();
    }


    // Have to override just to update the javadoc

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Tries to CAS head to p. If successful, repoint old head to itself
     * as sentinel for succ(), below.
     * 尝试将CAS头对p。如果成功，则将旧头重新指向自身作为succ（）的哨兵。
     */
    final void updateHead(Node<E> h, Node<E> p) {
        // assert h != null && p != null && (h == p || h.item == null);
        if (h != p && HEAD.compareAndSet(this, h, p))
            NEXT.setRelease(h, h);
    }

    /**
     * Returns the successor of p, or the head node if p.next has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     * 返回p的后继节点，或者如果p.next已链接到self，则返回head节点，
     * 只有当使用现在不在列表中的过时指针进行遍历时，为真。
     */
    final Node<E> succ(Node<E> p) {
        if (p == (p = p.next))
            p = head;
        return p;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never return {@code false}.
     */
    public boolean offer(E e) {
        final Node<E> newNode = new Node<E>(Objects.requireNonNull(e));
        //t:tail p:t
        for (Node<E> t = tail, p = t;;) {
            //q指向链表队尾的next，在稳定状态时，q应当为null
            //当把p添加到队列上，即执行Next原子设置时，
            //
            Node<E> q = p.next; //q是p的next指向的节点
            if (q == null) {//q为空，p是tail.next。在稳态时，q应当为null
                // p is last node
                // 把tail.next从null设置为新建节点
                if (NEXT.compareAndSet(p, null, newNode)) {//Node节点
                    // Successful CAS is the linearization point
                    // for e to become an element of this queue,
                    // and for newNode to become "live".
                    // 新节点加在了队列上，此时tail设置为新建节点
                    // p的next指向新节点时，p不等于t
                    // 在p没有变化时，存在p==t的情况
                    // 或者两者都指向newNode,也存在p==t的情况
                    // 此时尾部不是原来的t值
                    if (p != t) // hop two nodes at a time; failure is OK
                        TAIL.weakCompareAndSet(this, t, newNode);
                    return true; //只要把p的.next设置为新节点成功则返回
                }
                // Lost CAS race to another thread; re-read next
            }
            //NEXT.cas失败
            //Next把p的下个节点设置为newNode，而tail也设置了newNode
            //这种中间态存在p==q和p!=q,
            else if (p == q) 
                // We have fallen off list.  If tail is unchanged, it
                // will also be off-list, in which case we need to
                // jump to head, from which all live nodes are always
                // reachable.  Else the new tail is a better bet.
                // 下链，如果tail保持不变，它也将不在列表中，
                // 在这种情况下，我们需要跳到head，从head可以始终访问所有在线的节点。
                // 否则，新tail是更好的选择。
                // t改变了，即tail设置了新节点，此时t不是原来的tail
                // tail赋予t,当t和tail不是一个值时，则返回真
                p = (t != (t = tail)) ? t : head;
            else 
                //Next原子把p指向新节点时， p.next则不是原来tail的next
                // Check for tail updates after two hops.
                // p不是为t节点,二者在节点构造中已经分离，，t也不是
                //第二次循环进入
                p = (p != t && t != (t = tail)) ? t : q;
        }
    }

    public E peek() {
        restartFromHead: for (;;) {
            for (Node<E> h = head, p = h, q;; p = q) {
                final E item;
                if ((item = p.item) != null
                    || (q = p.next) == null) {
                    updateHead(h, p);
                    return item;
                }
                else if (p == q)
                    continue restartFromHead;
            }
        }
    }
    
    public E poll() {
        restartFromHead: for (;;) {
            for (Node<E> h = head, p = h, q;; p = q) {
                final E item;
                if ((item = p.item) != null && p.casItem(item, null)) {
                    // Successful CAS is the linearization point
                    // for item to be removed from this queue.
                    if (p != h) // hop two nodes at a time
                        updateHead(h, ((q = p.next) != null) ? q : p);
                    return item;
                }
                else if ((q = p.next) == null) {
                    updateHead(h, p);
                    return null;
                }
                else if (p == q)
                    continue restartFromHead;
            }
        }
    }

    /**
     * Returns the first live (non-deleted) node on list, or null if none.
     * This is yet another variant of poll/peek; here returning the
     * first node, not element.  We could make peek() a wrapper around
     * first(), but that would cost an extra volatile read of item,
     * and the need to add a retry loop to deal with the possibility
     * of losing a race to a concurrent poll().
     */
    Node<E> first() {
        restartFromHead: for (;;) {
            for (Node<E> h = head, p = h, q;; p = q) {
                boolean hasItem = (p.item != null);
                if (hasItem || (q = p.next) == null) {
                    updateHead(h, p);
                    return hasItem ? p : null;
                }
                else if (p == q)
                    continue restartFromHead;
            }
        }
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        return first() == null;
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
     * Additionally, if elements are added or removed during execution
     * of this method, the returned result may be inaccurate.  Thus,
     * this method is typically not very useful in concurrent
     * applications.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        restartFromHead: for (;;) {
            int count = 0;
            for (Node<E> p = first(); p != null;) {
                if (p.item != null)
                    if (++count == Integer.MAX_VALUE)
                        break;  // @see Collection.size()
                if (p == (p = p.next))
                    continue restartFromHead;
            }
            return count;
        }
    }

    /**
     * Tries to CAS pred.next (or head, if pred is null) from c to p.
     * Caller must ensure that we're not unlinking the trailing node.
     */
    private boolean tryCasSuccessor(Node<E> pred, Node<E> c, Node<E> p) {
        // assert p != null;
        // assert c.item == null;
        // assert c != p;
        if (pred != null)
            return NEXT.compareAndSet(pred, c, p);
        if (HEAD.compareAndSet(this, c, p)) {
            NEXT.setRelease(c, c);
            return true;
        }
        return false;
    }

    /**
     * Collapse dead nodes between pred and q.
     * @param pred the last known live node, or null if none
     * @param c the first dead node
     * @param p the last dead node
     * @param q p.next: the next live node, or null if at end
     * @return either old pred or p if pred dead or CAS failed
     */
    private Node<E> skipDeadNodes(Node<E> pred, Node<E> c, Node<E> p, Node<E> q) {
        // assert pred != c;
        // assert p != q;
        // assert c.item == null;
        // assert p.item == null;
        if (q == null) {
            // Never unlink trailing node.
            if (c == p) return pred;
            q = p;
        }
        return (tryCasSuccessor(pred, c, q)
                && (pred == null || ITEM.get(pred) != null))
            ? pred : p;
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
            for (Node<E> p = head, pred = null; p != null; ) {
                Node<E> q = p.next;
                final E item;
                if ((item = p.item) != null) {
                    if (o.equals(item))
                        return true;
                    pred = p; p = q; continue;
                }
                for (Node<E> c = p;; q = p.next) {
                    if (q == null || q.item != null) {
                        pred = skipDeadNodes(pred, c, p, q); p = q; break;
                    }
                    if (p == (p = q)) continue restartFromHead;
                }
            }
            return false;
        }
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
            for (Node<E> p = head, pred = null; p != null; ) {
                Node<E> q = p.next;
                final E item;
                if ((item = p.item) != null) {
                    if (o.equals(item) && p.casItem(item, null)) {
                        skipDeadNodes(pred, p, p, q);
                        return true;
                    }
                    pred = p; p = q; continue;
                }
                for (Node<E> c = p;; q = p.next) {
                    if (q == null || q.item != null) {
                        pred = skipDeadNodes(pred, c, p, q); p = q; break;
                    }
                    if (p == (p = q)) continue restartFromHead;
                }
            }
            return false;
        }
    }

    /**
     * Appends all of the elements in the specified collection to the end of
     * this queue, in the order that they are returned by the specified
     * collection's iterator.  Attempts to {@code addAll} of a queue to
     * itself result in {@code IllegalArgumentException}.
     *
     * @param c the elements to be inserted into this queue
     * @return {@code true} if this queue changed as a result of the call
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     * @throws IllegalArgumentException if the collection is this queue
     */
    public boolean addAll(Collection<? extends E> c) {
        if (c == this)
            // As historically specified in AbstractQueue#addAll
            throw new IllegalArgumentException();

        // Copy c into a private chain of Nodes
        Node<E> beginningOfTheEnd = null, last = null;
        for (E e : c) {
            Node<E> newNode = new Node<E>(Objects.requireNonNull(e));
            if (beginningOfTheEnd == null)
                beginningOfTheEnd = last = newNode;
            else
                last.appendRelaxed(last = newNode);
        }
        if (beginningOfTheEnd == null)
            return false;

        // Atomically append the chain at the tail of this collection
        for (Node<E> t = tail, p = t;;) {
            Node<E> q = p.next;
            if (q == null) {
                // p is last node
                if (NEXT.compareAndSet(p, null, beginningOfTheEnd)) {
                    // Successful CAS is the linearization point
                    // for all elements to be added to this queue.
                    if (!TAIL.weakCompareAndSet(this, t, last)) {
                        // Try a little harder to update tail,
                        // since we may be adding many elements.
                        t = tail;
                        if (last.next == null)
                            TAIL.weakCompareAndSet(this, t, last);
                    }
                    return true;
                }
                // Lost CAS race to another thread; re-read next
            }
            else if (p == q)
                // We have fallen off list.  If tail is unchanged, it
                // will also be off-list, in which case we need to
                // jump to head, from which all live nodes are always
                // reachable.  Else the new tail is a better bet.
                p = (t != (t = tail)) ? t : head;
            else
                // Check for tail updates after two hops.
                p = (p != t && t != (t = tail)) ? t : q;
        }
    }

    public String toString() {
        String[] a = null;
        restartFromHead: for (;;) {
            int charLength = 0;
            int size = 0;
            for (Node<E> p = first(); p != null;) {
                final E item;
                if ((item = p.item) != null) {
                    if (a == null)
                        a = new String[4];
                    else if (size == a.length)
                        a = Arrays.copyOf(a, 2 * size);
                    String s = item.toString();
                    a[size++] = s;
                    charLength += s.length();
                }
                if (p == (p = p.next))
                    continue restartFromHead;
            }

            if (size == 0)
                return "[]";

            return Helpers.toString(a, size, charLength);
        }
    }

    private Object[] toArrayInternal(Object[] a) {
        Object[] x = a;
        restartFromHead: for (;;) {
            int size = 0;
            for (Node<E> p = first(); p != null;) {
                final E item;
                if ((item = p.item) != null) {
                    if (x == null)
                        x = new Object[4];
                    else if (size == x.length)
                        x = Arrays.copyOf(x, 2 * (size + 4));
                    x[size++] = item;
                }
                if (p == (p = p.next))
                    continue restartFromHead;
            }
            if (x == null)
                return new Object[0];
            else if (a != null && size <= a.length) {
                if (a != x)
                    System.arraycopy(x, 0, a, 0, size);
                if (size < a.length)
                    a[size] = null;
                return a;
            }
            return (size == x.length) ? x : Arrays.copyOf(x, size);
        }
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        return toArrayInternal(null);
    }

    /**
     * Returns an array containing all of the elements in this queue, in
     * proper sequence; the runtime type of the returned array is that of
     * the specified array.  If the queue fits in the specified array, it
     * is returned therein.  Otherwise, a new array is allocated with the
     * runtime type of the specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     * <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        Objects.requireNonNull(a);
        return (T[]) toArrayInternal(a);
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {
        /**
         * Next node to return item for.
         */
        private Node<E> nextNode;

        /**
         * nextItem holds on to item fields because once we claim
         * that an element exists in hasNext(), we must return it in
         * the following next() call even if it was in the process of
         * being removed when hasNext() was called.
         */
        private E nextItem;

        /**
         * Node of the last returned item, to support remove.
         */
        private Node<E> lastRet;

        Itr() {
            restartFromHead: for (;;) {
                Node<E> h, p, q;
                for (p = h = head;; p = q) {
                    final E item;
                    if ((item = p.item) != null) {
                        nextNode = p;
                        nextItem = item;
                        break;
                    }
                    else if ((q = p.next) == null)
                        break;
                    else if (p == q)
                        continue restartFromHead;
                }
                updateHead(h, p);
                return;
            }
        }

        public boolean hasNext() {
            return nextItem != null;
        }

        public E next() {
            final Node<E> pred = nextNode;
            if (pred == null) throw new NoSuchElementException();
            // assert nextItem != null;
            lastRet = pred;
            E item = null;

            for (Node<E> p = succ(pred), q;; p = q) {
                if (p == null || (item = p.item) != null) {
                    nextNode = p;
                    E x = nextItem;
                    nextItem = item;
                    return x;
                }
                // unlink deleted nodes
                if ((q = succ(p)) != null)
                    NEXT.compareAndSet(pred, p, q);
            }
        }

        // Default implementation of forEachRemaining is "good enough".

        public void remove() {
            Node<E> l = lastRet;
            if (l == null) throw new IllegalStateException();
            // rely on a future traversal to relink.
            l.item = null;
            lastRet = null;
        }
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData All of the elements (each an {@code E}) in
     * the proper order, followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {

        // Write out any hidden stuff
        s.defaultWriteObject();

        // Write out all elements in the proper order.
        for (Node<E> p = first(); p != null; p = succ(p)) {
            final E item;
            if ((item = p.item) != null)
                s.writeObject(item);
        }

        // Use trailing null as sentinel
        s.writeObject(null);
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();

        // Read in elements until trailing null sentinel found
        Node<E> h = null, t = null;
        for (Object item; (item = s.readObject()) != null; ) {
            @SuppressWarnings("unchecked")
            Node<E> newNode = new Node<E>((E) item);
            if (h == null)
                h = t = newNode;
            else
                t.appendRelaxed(t = newNode);
        }
        if (h == null)
            h = t = new Node<E>();
        head = h;
        tail = t;
    }

    /** A customized variant of Spliterators.IteratorSpliterator */
    final class CLQSpliterator implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        Node<E> current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes

        public Spliterator<E> trySplit() {
            Node<E> p, q;
            if ((p = current()) == null || (q = p.next) == null)
                return null;
            int i = 0, n = batch = Math.min(batch + 1, MAX_BATCH);
            Object[] a = null;
            do {
                final E e;
                if ((e = p.item) != null) {
                    if (a == null)
                        a = new Object[n];
                    a[i++] = e;
                }
                if (p == (p = q))
                    p = first();
            } while (p != null && (q = p.next) != null && i < n);
            setCurrent(p);
            return (i == 0) ? null :
                Spliterators.spliterator(a, 0, i, (Spliterator.ORDERED |
                                                   Spliterator.NONNULL |
                                                   Spliterator.CONCURRENT));
        }

        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            final Node<E> p;
            if ((p = current()) != null) {
                current = null;
                exhausted = true;
                forEachFrom(action, p);
            }
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            Node<E> p;
            if ((p = current()) != null) {
                E e;
                do {
                    e = p.item;
                    if (p == (p = p.next))
                        p = first();
                } while (e == null && p != null);
                setCurrent(p);
                if (e != null) {
                    action.accept(e);
                    return true;
                }
            }
            return false;
        }

        private void setCurrent(Node<E> p) {
            if ((current = p) == null)
                exhausted = true;
        }

        private Node<E> current() {
            Node<E> p;
            if ((p = current) == null && !exhausted)
                setCurrent(p = first());
            return p;
        }

        public long estimateSize() { return Long.MAX_VALUE; }

        public int characteristics() {
            return (Spliterator.ORDERED |
                    Spliterator.NONNULL |
                    Spliterator.CONCURRENT);
        }
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
    private boolean bulkRemove(Predicate<? super E> filter) {
        boolean removed = false;
        restartFromHead: for (;;) {
            int hops = MAX_HOPS;
            // c will be CASed to collapse intervening dead nodes between
            // pred (or head if null) and p.
            for (Node<E> p = head, c = p, pred = null, q; p != null; p = q) {
                q = p.next;
                final E item; boolean pAlive;
                if (pAlive = ((item = p.item) != null)) {
                    if (filter.test(item)) {
                        if (p.casItem(item, null))
                            removed = true;
                        pAlive = false;
                    }
                }
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
    void forEachFrom(Consumer<? super E> action, Node<E> p) {
        for (Node<E> pred = null; p != null; ) {
            Node<E> q = p.next;
            final E item;
            if ((item = p.item) != null) {
                action.accept(item);
                pred = p; p = q; continue;
            }
            for (Node<E> c = p;; q = p.next) {
                if (q == null || q.item != null) {
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
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(ConcurrentLinkedQueue.class, "head",
                                   Node.class);
            TAIL = l.findVarHandle(ConcurrentLinkedQueue.class, "tail",
                                   Node.class);
            ITEM = l.findVarHandle(Node.class, "item", Object.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
