//
/**
    /**
this = {ForkJoinPool@765} "java.util.concurrent.ForkJoinPool@3af49f1c[
Running, parallelism = 8, size = 8, active = 5, running = 7,
steals = 17, tasks = 0, submissions = 0]"
 keepAlive = 60000
 stealCount = 0
 scanRover = 0
 threadIds = 8
 bounds = 2146959353
 mode = 8
 queues = {ForkJoinPool$WorkQueue[16]@767} 
  1 = {ForkJoinPool$WorkQueue@1355} 
   phase = 1
   stackPred = -239350328 //
   config = 1
   base = 0
   array = {ForkJoinTask[256]@1377} 
   owner = {ForkJoinWorkerThread@1178} "Thread[ForkJoinPool-1-worker-8,5,main]"
   top = 0
   source = 0
   nsteals = 0
  2 = {ForkJoinPool$WorkQueue@806} 
   phase = -1
   stackPred = 0 // 
   config = 1013904242
   base = 1
   array = {ForkJoinTask[256]@824} 
   owner = null
   top = 1
   source = 0
   nsteals = 0
  3 = {ForkJoinPool$WorkQueue@1281} 
   phase = 3
   stackPred = -1879881855 //
   config = 131075
   base = 0
   array = {ForkJoinTask[256]@1427} 
   owner = {ForkJoinWorkerThread@1254} "Thread[ForkJoinPool-1-worker-7,5,main]"
   top = 0
   source = 0
   nsteals = 2
  5 = {ForkJoinPool$WorkQueue@841} 
   phase = 5
   stackPred = 1013904242 //
   config = 131077
   base = 7
   array = {ForkJoinTask[256]@878} 
   owner = {ForkJoinWorkerThread@842} "Thread[ForkJoinPool-1-worker-1,5,main]"
   top = 7
   source = 131079
   nsteals = 0
  7 = {ForkJoinPool$WorkQueue@974} 
   phase = 7
   stackPred = -626627285 //19
   config = 131079
   base = 4
   array = {ForkJoinTask[256]@999} 
   owner = {ForkJoinWorkerThread@975} "Thread[ForkJoinPool-1-worker-2,5,main]"
   top = 4
   source = 131077
   nsteals = 0
  9 = {ForkJoinPool$WorkQueue@1084} 
   phase = 9
   stackPred = 2027808484 //20
   config = 131081
   base = 5
   array = {ForkJoinTask[256]@1104} 
   owner = {ForkJoinWorkerThread@1085} "Thread[ForkJoinPool-1-worker-3,5,main]"
   top = 5
   source = 0
   nsteals = 1
  11 = {ForkJoinPool$WorkQueue@1286} 
   phase = 11
   stackPred = 387276957 //21
   config = 131083
   base = 4
   array = {ForkJoinTask[256]@1432} 
   owner = {ForkJoinWorkerThread@1165} "Thread[ForkJoinPool-1-worker-4,5,main]"
   top = 4
   source = 0
   nsteals = 1
  13 = {ForkJoinPool$WorkQueue@1218} 
   phase = -2147418099
   stackPred = 131087 //22
   config = 131085
   base = 7
   array = {ForkJoinTask[256]@1228} 
   owner = {ForkJoinWorkerThread@1170} "Thread[ForkJoinPool-1-worker-5,5,main]"
   top = 7
   source = 0
   nsteals = 3
  15 = {ForkJoinPool$WorkQueue@1287} 
   phase = -2147352561
   stackPred = 0 //23
   config = 131087
   base = 0
   array = {ForkJoinTask[256]@1435} 
   owner = {ForkJoinWorkerThread@1171} "Thread[ForkJoinPool-1-worker-6,5,main]"
   top = 0
   source = 0
   nsteals = 10
   
 registrationLock = {ReentrantLock@766} "
 java.util.concurrent.locks.ReentrantLock@5b2133b1[
 Locked by thread ForkJoinPool-1-worker-8]"
 termination = null
 workerNamePrefix = "ForkJoinPool-1-worker-"
 factory = {ForkJoinPool$DefaultForkJoinWorkerThreadFactory@762} 
 ueh = null
 saturate = null
 ctl = -844424930066419
w = {ForkJoinPool$WorkQueue@1355} 
 phase = 1
 stackPred = -239350328
 config = 1
 base = 0
 array = {ForkJoinTask[256]@1377} 
 owner = {ForkJoinWorkerThread@1178} "Thread[ForkJoinPool-1-worker-8,5,main]"
 top = 0
 source = 0
 nsteals = 0
lock = {ReentrantLock@766} "java.util.concurrent.locks.
ReentrantLock@5b2133b1[Locked by thread ForkJoinPool-1-worker-8]"
seed = -239350328
modebits = 0
id = 1
queues = {ForkJoinPool$WorkQueue[16]@767} 
 1 = {ForkJoinPool$WorkQueue@1355} 
 2 = {ForkJoinPool$WorkQueue@806} 
 3 = {ForkJoinPool$WorkQueue@1281} 
 5 = {ForkJoinPool$WorkQueue@841} 
 7 = {ForkJoinPool$WorkQueue@974} 
 9 = {ForkJoinPool$WorkQueue@1084} 
 11 = {ForkJoinPool$WorkQueue@1286} 
 13 = {ForkJoinPool$WorkQueue@1218} 
 15 = {ForkJoinPool$WorkQueue@1287}
 **/

package com.abc.test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class ForkJoinPoolTest {

    public static void main(String[] args){
        ForkJoinPool pool=new ForkJoinPool();

        Calculate calculate=new Calculate(10,10000000);
        long sum=pool.invoke(calculate);
        System.out.println("snum:\t" + sum);
    }

    static class Calculate extends RecursiveTask<Long>{
        private final long start;
        private final long end;

        static final long THRESHOLD=10000;

        Calculate(long start,long end){
            this.start=start;
            this.end=end;
        }
        @Override
        protected Long compute() {
            if(this.end-this.start<=THRESHOLD){
                long sum=0;
                for(long i=start;i<end;i++){
                    sum +=i;
                }
                return sum;
            }else {
                long middle=(start+end)/2;
                Calculate left=new Calculate(start,middle);
                left.fork();
                Calculate right=new Calculate(middle+1,end);
                right.fork();
                return left.join()+right.join();
            }

        }
    }

}