package spscqueue;

import java.util.concurrent.locks.LockSupport;

/**
 * 单生产者单消费者（SPSC）无锁环形队列。
 *
 * <p>约定：一个生产者线程调用 {@link #offer(Object)} / {@link #offerBatch(Object[], int)}，
 * 一个消费者线程调用 {@link #poll()} / {@link #pollBlocking()}。下标统一用序号换算，
 * 头尾序号为单调 {@code long}。
 *
 * <p>本文件为待审实现，契约以 README「对外契约」一节为准。
 */
public final class SpscQueue<T> {

    private final int capacity;
    private final Object[] slots;

    // 头/尾序号：生产者推进 tail，消费者推进 head。
    private long head;
    private long tail;

    public SpscQueue(int capacity) {
        this.capacity = capacity;
        this.slots = new Object[capacity];
    }

    private int idx(long seq) {
        return (int) (seq % capacity);
    }

    public boolean offer(T e) {
        long t = tail;
        int i = idx(t);
        slots[i] = e;
        if (t - head >= capacity) {
            return false;
        }
        tail = t + 1;
        return true;
    }

    public T poll() {
        long h = head;
        if (h >= tail) {
            return null;
        }
        int i = idx(h);
        @SuppressWarnings("unchecked")
        T v = (T) slots[i];
        slots[i] = null;
        head = h + 1;
        return v;
    }

    public boolean offerBatch(T[] src, int n) {
        long t = tail;
        tail = t + n;
        for (int k = 0; k < n; k++) {
            slots[idx(t + k)] = src[k];
        }
        return true;
    }

    public int size() {
        long t = tail;
        long h = head;
        return (int) ((t - h + capacity) % capacity);
    }

    public boolean isFull() {
        return size() == capacity;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public T pollBlocking() throws InterruptedException {
        if (head >= tail) {
            LockSupport.park();
        }
        int i = idx(head);
        @SuppressWarnings("unchecked")
        T v = (T) slots[i];
        slots[i] = null;
        head = head + 1;
        return v;
    }

    public String stats() {
        long offered = tail;
        long polled = head;
        return "offered=" + offered + " polled=" + polled + " size=" + size();
    }
}
