package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Queue for cooperative TeaVM threads. Monitor waits yield to the browser so Netty's
 * idle event loop can receive work from a WebSocket callback.
 */
public class TLinkedBlockingQueue<E> extends LinkedList<E> implements BlockingQueue<E> {
	public TLinkedBlockingQueue() {
	}

	public TLinkedBlockingQueue(int capacity) {
	}

	public TLinkedBlockingQueue(Collection<? extends E> c) {
		super(c);
	}

	public void put(E e) {
		add(e);
	}

	@Override
	public synchronized boolean add(E e) {
		boolean added = super.add(Objects.requireNonNull(e));
		notifyAll();
		return added;
	}

	@Override
	public boolean offer(E e) {
		return add(e);
	}

	@Override
	public synchronized boolean addAll(Collection<? extends E> elements) {
		boolean added = super.addAll(elements);
		if (added) {
			notifyAll();
		}
		return added;
	}

	public boolean offer(E e, long timeout, TimeUnit unit) {
		return offer(e);
	}

	public synchronized E take() throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		while (isEmpty()) {
			wait();
		}
		return poll();
	}

	public synchronized E poll(long timeout, TimeUnit unit) throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		long remainingNanos = unit.toNanos(timeout);
		long deadline = System.nanoTime() + remainingNanos;
		while (isEmpty() && remainingNanos > 0) {
			wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
			remainingNanos = deadline - System.nanoTime();
		}
		return poll();
	}

	public int remainingCapacity() {
		return Integer.MAX_VALUE;
	}

	public int drainTo(Collection<? super E> c) {
		return drainTo(c, Integer.MAX_VALUE);
	}

	public int drainTo(Collection<? super E> c, int maxElements) {
		int n = 0;
		E e;
		while (n < maxElements && (e = poll()) != null) {
			c.add(e);
			++n;
		}
		return n;
	}
}
