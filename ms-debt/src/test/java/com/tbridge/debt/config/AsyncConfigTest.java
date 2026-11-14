package com.tbridge.debt.config;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncConfigTest {
    @Test void invitation_overflow_uses_caller_without_dropping_or_spawning_threads() {
        var executor=new AsyncConfig().invitations();executor.initialize();
        var release=new CountDownLatch(1);
        try {
            for(int n=0;n<104;n++)executor.execute(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            AtomicReference<Thread> overflow=new AtomicReference<>();
            executor.execute(()->overflow.set(Thread.currentThread()));
            assertSame(Thread.currentThread(),overflow.get());
            assertEquals(4,executor.getPoolSize());assertEquals(100,executor.getQueueSize());
        }finally{release.countDown();executor.shutdown();}
    }
}
