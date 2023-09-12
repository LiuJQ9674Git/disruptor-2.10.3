/**
BIT Overview
                                                            +++++++++++++++++++
FIFO:	65536	            Bit->: 1 << 16
                                                          1 0000 0000 0000 0000
SRC:	131072	Bit->: 1 << 17
                                                         10 0000 0000 0000 0000
INNOCUOUS:	262144	    Bit->: 1 << 18
                                                        100 0000 0000 0000 0000
QUIET:	524288	Bit->: 1 << 19
                                                       1000 0000 0000 0000 0000
SHUTDOWN:	16777216	Bit->:1 << 24
                                                1 0000 0000 0000 0000 0000 0000
TERMINATED:	33554432	Bit->:1 << 25
                                               10 0000 0000 0000 0000 0000 0000
STOP:	-2147483648	Bit->:1 << 31
1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000

                                        ^^^^^^^^^^^^^^^^^^^
UNCOMPENSATE:	65536	Bit->:1 << 31
                                                          1 0000 0000 0000 0000
SMASK:	65535   Bit->:0xffff
                                                            1111 1111 1111 1111
MAX_CAP:	32767	Bit->:0x7fff
                                                             111 1111 1111 1111
SS_SEQ:	65536	Bit->:1 << 16
                                                          1 0000 0000 0000 0000
=================== ******************* ^^^^^^^^^^^^^^^^^^^ -------------------
ctl:	-4222399528566784	
1111 1111 1111 0000 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000

UNSIGNALLED:	-2147483648	Bit->:1 << 31
1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
~UNSIGNALLED:	2147483647	
                                         111 1111 1111 1111 1111 1111 1111 1111
=================== ******************* ^^^^^^^^^^^^^^^^^^^ -------------------

TC_MASK:	281470681743360  0xffffL<<32
                    1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
RC_MASK:	-281474976710656 0xff ffL>>48
1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

~RC_MASK:	281474976710655
                    1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111

SP_MASK 4294967295 0xffffffffL int最大数
                                        1111 1111 1111 1111 1111 1111 1111 1111
UC_MASK:	-4294967296 ~SP_MASK      int最小数
1111 1111 1111 1111 1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000

RC_UNIT:	281474976710656 Bit->1<<48
                  1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
TC_UNIT:	4294967296      Bit->1<<32
                                      1 0000 0000 0000 0000 0000 0000 0000 0000

ADD_WORKER:	140737488355328 1<<47
                    1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
                    
=================== ******************* ^^^^^^^^^^^^^^^^^^^ -------------------                    
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
     *  状态信息写入顺序 w:写 r：读 
     *                       | stackPred | config | phase | source
     *                       ------------------------------------------  
     *      new ForkJoinPool |           |    w  |        |
     *      -----------------------------------------------------------
     *      new WorkQueue    |           |    w  |     w  |
     *      -----------------------------------------------------------
     *      submissionQueue  |           |       |        |
     *      -----------------------------------------------------------
     *      registerWorker   |      w    |   w/r |       |
     *      -----------------------------------------------------------
     *      runWorker        |      r    |   w    |       |
     *      -----------------------------------------------------------
     *      signalWork       |      r    |        |   w   |
     *      -----------------------------------------------------------
     *      scan             |           |        |       |   w
     *      -----------------------------------------------------------
     *      awaitWork        |      w    |    w/r |   r   |   r
     *      -----------------------------------------------------------
     *      helpJoin         |           |     r  |           |   w/r
     *      -----------------------------------------------------------
     *      tryCompensate    |      r    |        |      w    |
     *      -----------------------------------------------------------
     *      helpComplete     |           |     r  |           |
     *      ----------------------------------------------------------
     *
     *      invoke -> submissionQueue-> new WorkQueue -> signalWork
     *      fork ->              push                  -> signalWork
     *      registerWorker-> runWorker       -> scan   -> signalWork
     *                                                 -> awaitWork
     *      ForkJoinTask invoke/join/get     -> awaitDone            
     *                                                 -> helpJoin
     *                                                 -> helpComplete
     *
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
