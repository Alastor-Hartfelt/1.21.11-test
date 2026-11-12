package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Single-threaded CompletableFuture for the browser. Completion runs pending
 * callbacks immediately on the completing "thread"; blocking on an incomplete
 * future is impossible and throws instead of deadlocking silently.
 */
public class TCompletableFuture<T> implements TCompletionStage<T>, TFuture<T> {
	private static final Executor DIRECT = Runnable::run;

	private boolean done;
	private boolean cancelled;
	private T value;
	private Throwable exception;
	private List<Runnable> actions;

	public TCompletableFuture() {
	}

	public static <U> TCompletableFuture<U> completedFuture(U value) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		f.complete(value);
		return f;
	}

	public static <U> TCompletableFuture<U> failedFuture(Throwable ex) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		f.completeExceptionally(ex);
		return f;
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier) {
		return supplyAsync(supplier, DIRECT);
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				f.complete(supplier.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable) {
		return runAsync(runnable, DIRECT);
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
		TCompletableFuture<Void> f = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				runnable.run();
				f.complete(null);
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	public static TCompletableFuture<Void> allOf(TCompletableFuture<?>... futures) {
		TCompletableFuture<Void> result = new TCompletableFuture<>();
		if (futures.length == 0) {
			result.complete(null);
			return result;
		}
		int[] remaining = { futures.length };
		for (TCompletableFuture<?> f : futures) {
			f.whenDone(() -> {
				if (f.exception != null) {
					result.completeExceptionally(f.exception);
				} else if (--remaining[0] == 0) {
					result.complete(null);
				}
			});
		}
		return result;
	}

	public static TCompletableFuture<Object> anyOf(TCompletableFuture<?>... futures) {
		TCompletableFuture<Object> result = new TCompletableFuture<>();
		for (TCompletableFuture<?> f : futures) {
			f.whenDone(() -> {
				if (f.exception != null) {
					result.completeExceptionally(f.exception);
				} else {
					result.complete(f.value);
				}
			});
		}
		return result;
	}

	private void whenDone(Runnable action) {
		if (done) {
			action.run();
		} else {
			if (actions == null) {
				actions = new ArrayList<>();
			}
			actions.add(action);
		}
	}

	private void whenDone(Executor executor, Runnable action) {
		if (executor == DIRECT) {
			whenDone(action);
		} else {
			whenDone(() -> executor.execute(action));
		}
	}

	public boolean complete(T value) {
		if (done) {
			return false;
		}
		this.value = value;
		finish();
		return true;
	}

	public boolean completeExceptionally(Throwable ex) {
		if (done) {
			return false;
		}
		this.exception = ex;
		finish();
		return true;
	}

	public boolean cancel(boolean mayInterruptIfRunning) {
		if (done) {
			return false;
		}
		cancelled = true;
		exception = new CancellationException();
		finish();
		return true;
	}

	private void finish() {
		done = true;
		if (actions != null) {
			List<Runnable> pending = actions;
			actions = null;
			for (Runnable r : pending) {
				r.run();
			}
		}
	}

	public boolean isDone() {
		return done;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public boolean isCompletedExceptionally() {
		return done && exception != null;
	}

	public int getNumberOfDependents() {
		return actions != null ? actions.size() : 0;
	}

	public T join() {
		if (!done) {
			throw new IllegalStateException("Blocking join() on an incomplete future in a single-threaded environment");
		}
		if (exception != null) {
			if (exception instanceof CancellationException cancellation) {
				throw cancellation;
			}
			throw wrapCompletion(exception);
		}
		return value;
	}

	private static RuntimeException wrapCompletion(Throwable t) {
		if (t instanceof TCompletionException completion) {
			return completion;
		}
		return new TCompletionException(t);
	}

	public T get() throws InterruptedException, ExecutionException {
		if (!done) {
			throw new IllegalStateException("Blocking get() on an incomplete future in a single-threaded environment");
		}
		if (exception != null) {
			if (exception instanceof CancellationException cancellation) {
				throw cancellation;
			}
			throw new ExecutionException(exception);
		}
		return value;
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException {
		Objects.requireNonNull(unit);
		if (!done && timeout <= 0) {
			throw new TTimeoutException();
		}
		return get();
	}

	public T getNow(T valueIfAbsent) {
		if (!done) {
			return valueIfAbsent;
		}
		if (exception != null) {
			if (exception instanceof CancellationException cancellation) {
				throw cancellation;
			}
			throw wrapCompletion(exception);
		}
		return value;
	}

	public TCompletableFuture<T> copy() {
		TCompletableFuture<T> f = new TCompletableFuture<>();
		whenDone(() -> f.completeFrom(this));
		return f;
	}

	@Override
	public TCompletableFuture<T> toCompletableFuture() {
		return this;
	}

	private void completeFrom(TCompletableFuture<? extends T> other) {
		if (other.exception != null) {
			completeExceptionally(other.exception);
		} else {
			complete(other.value);
		}
	}

	// ---- stage operations ----

	private <U> TCompletableFuture<U> uni(Executor executor, BiConsumer<TCompletableFuture<U>, TCompletableFuture<T>> op) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		whenDone(executor, () -> {
			try {
				op.accept(f, this);
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	@Override
	public <U> TCompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				f.completeExceptionally(src.exception);
			} else {
				f.complete(fn.apply(src.value));
			}
		});
	}

	@Override
	public TCompletableFuture<Void> thenAccept(Consumer<? super T> action) {
		return thenAcceptAsync(action, DIRECT);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
		return thenAcceptAsync(action, DIRECT);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				f.completeExceptionally(src.exception);
			} else {
				action.accept(src.value);
				f.complete(null);
			}
		});
	}

	@Override
	public TCompletableFuture<Void> thenRun(Runnable action) {
		return thenRunAsync(action, DIRECT);
	}

	@Override
	public TCompletableFuture<Void> thenRunAsync(Runnable action) {
		return thenRunAsync(action, DIRECT);
	}

	public TCompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				f.completeExceptionally(src.exception);
			} else {
				action.run();
				f.complete(null);
			}
		});
	}

	@Override
	public <U> TCompletableFuture<U> thenCompose(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return thenComposeAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return thenComposeAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				f.completeExceptionally(src.exception);
			} else {
				TCompletableFuture<U> next = fn.apply(src.value).toCompletableFuture();
				next.whenDone(() -> f.completeFrom(next));
			}
		});
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombine(TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
		return thenCombineAsync(other, fn, DIRECT);
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombineAsync(TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
		TCompletableFuture<? extends U> otherFuture = other.toCompletableFuture();
		return uni(executor, (f, src) -> otherFuture.whenDone(() -> {
			try {
				if (src.exception != null) {
					f.completeExceptionally(src.exception);
				} else if (otherFuture.exception != null) {
					f.completeExceptionally(otherFuture.exception);
				} else {
					f.complete(fn.apply(src.value, otherFuture.value));
				}
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		}));
	}

	@Override
	public <U> TCompletableFuture<U> applyToEither(TCompletionStage<? extends T> other, Function<? super T, U> fn) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		Runnable relay = () -> {
			if (f.done) {
				return;
			}
			try {
				if (this.done) {
					if (this.exception != null) {
						f.completeExceptionally(this.exception);
					} else {
						f.complete(fn.apply(this.value));
					}
				}
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		};
		whenDone(relay);
		TCompletableFuture<? extends T> otherFuture = other.toCompletableFuture();
		otherFuture.whenDone(() -> {
			if (f.done) {
				return;
			}
			try {
				if (otherFuture.exception != null) {
					f.completeExceptionally(otherFuture.exception);
				} else {
					f.complete(fn.apply(otherFuture.value));
				}
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	@Override
	public TCompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
		return whenCompleteAsync(action, DIRECT);
	}

	@Override
	public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
		return uni(executor, (f, src) -> {
			try {
				action.accept(src.value, src.exception);
			} catch (Throwable t) {
				if (src.exception == null) {
					f.completeExceptionally(t);
					return;
				}
			}
			f.completeFrom(src);
		});
	}

	@Override
	public <U> TCompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, DIRECT);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
		return uni(executor, (f, src) -> f.complete(fn.apply(src.value, src.exception)));
	}

	@Override
	public TCompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
		return exceptionallyAsync(fn, DIRECT);
	}

	@Override
	public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				f.complete(fn.apply(src.exception));
			} else {
				f.complete(src.value);
			}
		});
	}

	public TCompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends TCompletionStage<T>> fn) {
		return exceptionallyComposeAsync(fn, DIRECT);
	}

	public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn, Executor executor) {
		return uni(executor, (f, src) -> {
			if (src.exception != null) {
				TCompletableFuture<T> next = fn.apply(src.exception).toCompletableFuture();
				next.whenDone(() -> f.completeFrom(next));
			} else {
				f.complete(src.value);
			}
		});
	}
}
