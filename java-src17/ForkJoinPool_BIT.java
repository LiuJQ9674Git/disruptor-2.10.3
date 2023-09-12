   /**
     *FIFO:	65536	            Bit->: 1 << 16
     *                                                         1 0000 0000 0000 0000
     *SRC:	131072	Bit->: 1 << 17
     *                                                         10 0000 0000 0000 0000
     *INNOCUOUS:	262144	    Bit->: 1 << 18
     *                                                        100 0000 0000 0000 0000
     *QUIET:	524288	Bit->: 1 << 19
     *                                                       1000 0000 0000 0000 0000
     *SHUTDOWN:	16777216	Bit->:1 << 24
     *                                                1 0000 0000 0000 0000 0000 0000
     *TERMINATED:	33554432	Bit->:1 << 25
     *                                               10 0000 0000 0000 0000 0000 0000
     *STOP:	-2147483648	Bit->:1 << 31
     *1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
     *UNCOMPENSATE:	65536	Bit->:1 << 31
     *                                                          1 0000 0000 0000 0000
     * SMASK:	65535   Bit->:0xffff
     *                                                            1111 1111 1111 1111
     *MAX_CAP:	32767	Bit->:0x7fff
     *                                                             111 1111 1111 1111
     *SS_SEQ:	65536	Bit->:1 << 16
     *                                                          1 0000 0000 0000 0000
     *
     *UNSIGNALLED:	-2147483648	Bit->:1 << 31
     *1111 1111 1111 1111 1111 1111 1111 1111 1000 0000 0000 0000 0000 0000 0000 0000
     *~UNSIGNALLED:	2147483647	
     *                                         111 1111 1111 1111 1111 1111 1111 1111
     *------------------------------------------------------------------------------
     *ctl:	-4222399528566784	
     *1111 1111 1111 0000 1111 1111 1100 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *TC_MASK:	281470681743360  0xffffL<<32
     *                    1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
     *RC_MASK:	-281474976710656 0xff ffL>>48
     *1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *~RC_MASK:	281474976710655
     *                    1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111 1111

     *-------------------------------------------------------------------------------
     *SP_MASK:	4294967295 0xff ff ff ffL int最大数
     *                                        1111 1111 1111 1111 1111 1111 1111 1111
     *UC_MASK:	-4294967296 ~SP_MASK      int最小数
     *1111 1111 1111 1111 1111 1111 1111 1111 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *RC_UNIT:	281474976710656 Bit->1<<48
     *                   1 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
     *TC_UNIT:	4294967296      Bit->1<<32
     *                                       1 0000 0000 0000 0000 0000 0000 0000 0000
     *
     *ADD_WORKER:	140737488355328 1<<47
     *                     1000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000
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
    
    
    
    