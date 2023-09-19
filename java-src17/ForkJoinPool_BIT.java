/**
 * ForkJoinPool Bit Operation
 *
FIFO:	65536	H>:	10000	S-B->:17
                                                            1 0000 0000 0000 0000
SRC:	131072	H>:	20000	S-B->:18
                                                           10 0000 0000 0000 0000
INNOCUOUS:	262144	H>:	40000	S-B->:19
                                                          100 0000 0000 0000 0000
QUIET:	524288	H>:	80000	S-B->:20
                                                         1000 0000 0000 0000 0000
SHUTDOWN:	16777216	H>:	1000000	S-B->:25
                                                  1 0000 0000 0000 0000 0000 0000
TERMINATED:	33554432	H>:	2000000	S-B->:26
                                                 10 0000 0000 0000 0000 0000 0000
STOP:	-2147483648	H>:	80000000	S-B->:32
                                          1000 0000 0000 0000 0000 0000 0000 0000
UNCOMPENSATE:	65536	H>:	10000	S-B->:17
                                                            1 0000 0000 0000 0000

MAX_CAP:	32767	H>:	7fff	S-B->:15
                                                               111 1111 1111 1111
SS_SEQ:	65536	H>:	10000	S-B->:17
                                                            1 0000 0000 0000 0000
SMASK:	65535	H>:	ffff	S-B->:16
                                                              1111 1111 1111 1111
                                                              
UNSIGNALLED:	-2147483648	H>:	ffffffff80000000	S-B->:64
   1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
~UNSIGNALLED:	2147483647	H>:	7fffffff	S-B->:31
                                            111 1111 1111 1111 1111 1111 1111 1111
                                            
TC_MASK:	281470681743360	H>:	ffff00000000	S-B->:48
                      1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
RC_MASK:	-281474976710656	H>:	ffff000000000000	S-B->:64
  1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
  
~RC_MASK:	281474976710655	H>:	ffffffffffff	S-B->:48
                      1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
                      
UC_MASK:	-4294967296	H>:	ffffffff00000000	S-B->:64
  1111 1111 1111 1111 1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
SP_MASK:	4294967295	H>:	ffffffff	S-B->:32
                                          1111 1111 1111 1111 1111 1111 1111 1111
                                          
RC_UNIT:	281474976710656	H>:	1000000000000	S-B->:49
                    1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
TC_UNIT:	4294967296	H>:	100000000	S-B->:33
                                        1 0000 0000 0000 0000 0000 0000 0000 0000
ADD_WORKER:	140737488355328	H>:	800000000000	S-B->:48
                      1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
                      
    16   15  14   13   12   11   10   9    8    7     6    5    4    3    2   1                       
--------------------------------------------------------------------------------

p:	16	H>:	10	S-B->:5
                                                                           1 0000
-p:	-16	H>:	fffffffffffffff0	S-B->:64
  1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 0000
-core:	-64	H>:	ffffffffffffffc0	S-B->:64
  1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1100 0000
-corep(tc):	-16	H>:	ffc000000000	S-B->:48
                      1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
-p(rc):	-64	H>:	fff0000000000000	S-B->:64
  1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

size:	32	H>:	20	S-B->:6
                                                                          10 0000
seed:	13	H>:	d	S-B->:4
                                                                             1101
phase.id:	27	H>:	1b	S-B->:5
	(seed << 1) | 1
                                                                           1 1011
phase+:	65563	H>:	1001b	S-B->:17
	(seed + SS_SEQ) & ~UNSIGNALLED
                                                            1 0000 0000 0001 1011
phase-:	-2147418085	H>:	8001001b	S-B->:32
	phase | UNSIGNALLED
                                          1000 0000 0000 0001 0000 0000 0001 1011
ctl:	-4222399528566784	H>:	fff0ffc000000000	S-B->:64
  1111 1111 1111 0000 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
                    1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
ctl - RC_UNIT:	-4503874505277440	H>:	ffefffc000000000	S-B->:64
	ctl - RC_UNIT
  1111 1111 1110 1111 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
  1111 1111 1110 1110 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
  
phase & SP_MASK:	2147549211	H>:	8001001b	S-B->:	32
	(phase & SP_MASK)
                                          1000 0000 0000 0001 0000 0000 0001 1011
ctl-:	-4503872357728229	H>:	ffefffc08001001b	S-B->:64
	 ((ctl - RC_UNIT) & UC_MASK) | (phase & SP_MASK)
  1111 1111 1110 1111 1111 1111 1100 0000 1000 0000 0000 0001 0000 0000 0001 1011
ac:	-17	H>:	ffffffef	S-B->:32
	ctl >> RC_SHIFT
                                          1111 1111 1111 1111 1111 1111 1110 1111
nc:	-4785347334438885	H>:	ffeeffc08001001b	S-B->:64
	 nc = ((RC_MASK & (ctl - RC_UNIT)) | (~RC_MASK & ctl)); 
 1111 1111 1110 1110 1111 1111 1100 0000 1000 0000 0000 0001 0000 0000 0001 1011
p & SMASK:	16	H>:	10	S-B->:5
                                                                          1 0000


////////////////////////////////////////////////////////////////////////////////
-1:	-1	H>:	ffffffffffffffff	S-B->:64
  1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
-2:	-2	H>:	fffffffffffffffe	S-B->:64
  1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1110

   16   15  14   13   12   11   10   9    8    7     6    5    4    3    2   1                       
--------------------------------------------------------------------------------                    
maxLong:	9223372036854775807	Hex->:	7fffffffffffffff
Bit-Size->:	63			Hex-Size->:16
 111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111
 
minLong:	-9223372036854775808	Hex->:	8000000000000000
Bit-Size->:	64			Hex-Size->:16
1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

maxInteger:	2147483647	Hex->:	7fffffff
Bit-Size->:	31			Hex-Size->:8
                                         111 1111 1111 1111 1111 1111 1111 1111
mimInteger:	-9223372036854775808	Hex->:	80000000
Bit-Size->:	32			Hex-Size->:8
                                        1000 0000 0000 0000 0000 0000 0000 0000

maxShort:	32767	Hex->:	7fff
Bit-Size->:	15			Hex-Size->:4
                                                             111 1111 1111 1111
mimShort:	-32768	Hex->:	ffff8000
Bit-Size->:	32			Hex-Size->:8
                                         1111 1111 1111 1111 1000 0000 0000 0000
**/
                   

 
  public class FoolJoinPool_Bit{   

    /**
     *     * 核心信息：
     * 1.int stackPred;             线程哈希值，探针哈希值原值
     *                              // pool stack (ctl) predecessor link
     *
     * 读
     * signalWork                       f*8        f*8+0*8       1<<48
     *      long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
     *
     * runWorker
     *     int r = w.stackPred, src = 0;  // use seed from registerWorker
     *
     * tryCompensate                        取低32位      取高32位
     *      long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
     *
     * 写：
     * 
     * registerWorker
     *       w.stackPred = seed;          线程哈希值，探针哈希值原值
     *                                    // stash for runWorker
     *
     * awaitWork
     *
     *   long prevCtl = ctl, c;         // enqueue ctl:主池标志位
     *   do {
     *       w.stackPred = (int)prevCtl; 高32                低32
     *       c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
     *   } while (prevCtl !=
     *              (prevCtl = compareAndExchangeCtl(prevCtl, c)));
     *   
     *
     * 2. int config;        // index, mode, ORed with SRC after init
     *                           主池的三大阶段：注册、运行、退出
     *
     *  读
     *   registerWorker
     *     int modebits = (mode & FIFO) | w.config;
     *     
     *   deregisterWorker
     *      cfg = w.config;
     *      
     *   awaitWork              0xffffL
     *       else if (((int)c & SMASK) == (w.config & SMASK) &&
     *        compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) | //高32
     *                                 (prevCtl & SP_MASK)))) { //低32
     
     *           w.config |= QUIET;   // sentinel for deregisterWorker
     *
     *  helpJoin                                 0xffffL
     *      int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
     *  helpComplete
     *      int r = w.config;
     *
     * 写
     *  registerWorker
     *       w.phase = w.config = id | modebits; // now publishable
     *  runWorker
     *       w.config |= SRC;
     *       
     *  awaitWork
     *      w.config |= QUIET;     // sentinel for deregisterWorker
     *
     *  WorkQueue(ForkJoinWorkerThread owner, boolean isInnocuous)
     *      this.config = (isInnocuous) ? INNOCUOUS : 0;
     *      
     *  WorkQueue(int config)
     *      this.config = config;
     *      
     *
     * 3.volatile int phase;        // versioned, negative if inactive
     *
     *  读
     *      awaitWork                     1 << 16      1<<31
     *             int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *             if (w.phase >= 0)
     *                                            UNS: 1 f*7 1 0*3 0*7
     *                                           ~UNS:         f*3 f*7
     *  写   
     *      registerWorker
     *           w.phase = w.config = id | modebits; // now publishable
     *
     *      signalWork
     *          (sp = (int)c & ~UNSIGNALLED)
     *          v.phase = sp;
     *
     *      awaitWork
     *          int phase = (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *          w.phase = phase | UNSIGNALLED;       // advance phase
     *
     *          w.phase = phase;     // self-signal
     *      
     *      tryCompensate
     *          sp        = (int)c & ~UNSIGNALLED;
     *          v.phase = sp;
     *          
     *
     *      WorkQueue(int config) 共享队列中phase为-1
     *          phase = -1;
     *
     *
     * 4. volatile int source;       // source queue id, lock, or sentinel
     *                               队列中（基于线程哈希值，探针哈希值）的位置
     *  读
     *      awaitWork
     *          q.source != 0)) {
     *      canStop
     *          q.source != 0
     *      helpJoin
     *          int wsrc = w.source, wid = w.config & SMASK, r = wid + 2;
     *          int sq = q.source & SMASK, cap, b; //SMASK:0xffffL
     *           ((sx = (x.source & SMASK)) == wid || 
     *           (y.source & SMASK) == wid)));
     *           else if ((q.source & SMASK) != sq ||
     *
     *
     *  写
     *      scan
     *          int nextIndex = (cap - 1) & nextBase, src = j | SRC;
     *          if ((w.source = src) != prevSrc && next != null)
     *
     *      helpJoin
     *            if ((q = qs[j = r & m]) != null)
     *            int nextBase = b + 1, src = j | SRC, sx;
     *            
     *            w.source = src; //主队列任务中的位置（基于探针哈希算完之后）
     *            t.doExec();
     *            w.source = wsrc;
     *
     *     WorkQueue        在WorkQueue内，表示锁，即共享队列时，获取锁的标识
     *                      获取锁之后，线程进入临界区
     *      lockedPush
     *      growArray
     *      topLevelExec
     *      
     *      externalTryUnpush
     *      tryRemove
     *      helpComplete
     *          source = 0;
     *
     *  锁方法
     *  tryLock
     *      submissionQueue 外部生成共享队列，
     *                      此时线程拥有者变量为null，config为探针哈希ID
     *      WorkQueue
     *          externalTryUnpush
     *          tryRemove
     *          helpComplete
     *          
     *  状态信息位操作
     * UC_MASK:	-4294967296 ~SP_MASK f*8+0*8 32
     * 1111111111111111111111111111111100000000000000000000000000000000
     * 
     * SP_MASK:	4294967295 0xffffffffL（f*8)
     *                                  11111111111111111111111111111111
     *
     * SS_SEQ:	65536	SS_SEQ      = 1 << 16;       // version count
     *                                             1 0000 0000 0000 0000
     *
     * SMASK:	65535	SMASK = 0xffff;      // short bits == max index
     *                                                1111 1111 1111 1111
     *
     * UNSIGNALLED:	-2147483648 1 << 31
     *  1111111111111111111111111111111110000000000000000000000000000000
     * ~UNSIGNALLED:	2147483647
     *                                   1111111111111111111111111111111
     *      stackPred
     *          
     *          (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
     *          
     *      config
     *          w.config & SMASK
     *
     *      phase
     *         (w.phase + SS_SEQ) & ~UNSIGNALLED;
     *         
     *      source
     *          q.source & SMASK
     *          
     *  状态信息写入顺序 w:写 r：读  非w任务操作: q
     *  
     *                       |stackPred|config |phase |source  | base
     *                       --------------------------------- |-------  
     *      new ForkJoinPool |         |   w  |       |        |
     *      -------------------------------------------------- |------
     *      new WorkQueue    |         |   w  |       |        |
     *      -------------------------------------------------- |------
     *      submissionQueue  |         |      |       |        |
     *      -------------------------------------------------- |------
     *       signalWork      |    v.r  |      |   w   |        |
     *      ---------------------------------------------------|------
     *      registerWorker   |    w    |  w/r |       |        |
     *      ---------------------------------------------------|------
     *      runWorker        |    r    |   w  |       | |
     *      ---------------------------------------------------|------
     *      scan             |         |      |       |   w    | q.r
     *      ---------------------------------------------------|------
     *      awaitWork        |    w    |  w/r |   r   | r q.r  | q.r
     *      ---------------------------------------------------|------
     *      helpJoin         |         |   r  |       | w/r q.r| q.r
     *      ---------------------------------------------------|------
     *      tryCompensate    |    r    |      |   w   |        | 
     *      ---------------------------------------------------|------
     *      helpComplete     |         |   r  |       |        |q.r
     *      ---------------------------------------------------|-----
     *
     *      invoke -> submissionQueue-> new WorkQueue -> signalWork
     *      fork ->              push                  -> signalWork
     *      registerWorker-> runWorker       -> scan   -> signalWork
     *                                                 -> awaitWork
     *      ForkJoinTask invoke/join/get     -> awaitDone            
     *                                                 -> helpJoin
     *                                                 -> helpComplete
     *      signalWork
     *          v.phase = sp;
     *
     *      base：
     *          setBaseOpaque
     *              helpComplete(ForkJoinTask<?>, WorkQueue, boolean)
     *              pollScan(boolean)
     *              
     *          scan
     *              b = q.base
     *              
     *          helpJoin
     *              q.base != b
     *              
     *          ForkJoinPool.WorkQueue
     *              tryPoll()
     *              nextLocalTask(int)
     *              helpAsyncBlocker(ManagedBlocker)
     *              
     *     // signalWork
     *     //long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
     *
     *     // awaitWork
     *    //c = ((prevCtl - RC_UNIT) & UC_MASK) | (phase & SP_MASK);
     *
     *    //tryCompensate
     *    //long nc = ((long)v.stackPred & SP_MASK) | (UC_MASK & c);
     *
     *    // long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
     *    // long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
     *
     *    awaitWork
     *       compareAndSetCtl(c, ((UC_MASK & (c - TC_UNIT)) |
     *         //11111 1111 1111 1111 111 1111 1111 1111
     *         (prevCtl & SP_MASK)))) {
     *
     *   // RC/TC 计数处1
     *    if (c == (c = compareAndExchangeCtl(
     *       c, ((RC_MASK & (c + RC_UNIT)) | // RC增加
     *       (TC_MASK & (c + TC_UNIT)))))) { // TC增加      
     */
   
   
    // Bounds
    static final int SWIDTH       = 16;          // width of short 宽度
    static final int SMASK        = 0xffff;      // short bits == max index
    static final int MAX_CAP      = 0x7fff;      // max #workers - 1

    // Masks and units for WorkQueue.phase and ctl sp subfield
    static final int UNSIGNALLED  = 1 << 31;     // must be negative
    //
    static final int SS_SEQ       = 1 << 16;     // version count

    // Mode bits and sentinels, some also used in WorkQueue fields
    static final int FIFO         = 1 << 16;     // fifo queue or access mode
    static final int SRC          = 1 << 17;     // set for valid queue ids
    static final int INNOCUOUS    = 1 << 18;     // set for Innocuous workers
    static final int QUIET        = 1 << 19;     // quiescing phase or source
                                                 // 静止阶段或源
    static final int SHUTDOWN     = 1 << 24;
    static final int TERMINATED   = 1 << 25;
    static final int STOP         = 1 << 31;     // must be negative
    static final int UNCOMPENSATE = 1 << 16;     // tryCompensate return
    
    //Lower and upper word masks
    private static final long SP_MASK    = 0xffffffffL;
    private static final long UC_MASK    = ~SP_MASK;

    // Release counts 释放计数
    private static final int  RC_SHIFT   = 48;
    private static final long RC_UNIT    = 0x0001L << RC_SHIFT;
    private static final long RC_MASK    = 0xffffL << RC_SHIFT;

    // Total counts 总计数
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    // sign
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15);
    
    
}
