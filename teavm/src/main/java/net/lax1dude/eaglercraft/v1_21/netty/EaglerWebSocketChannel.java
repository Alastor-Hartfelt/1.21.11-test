package net.lax1dude.eaglercraft.v1_21.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.PreferHeapByteBufAllocator;
import io.netty.channel.SingleThreadEventLoop;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.teavm.jso.JSBody;

/**
 * netty {@link AbstractChannel} whose transport is a browser WebSocket ({@link EaglerWebSocketTransport}).
 * MC's normal client pipeline (varint framing, codecs, {@code Connection}) runs on top unchanged. Async WS
 * callbacks are marshalled onto this channel's event loop so netty only ever sees events on its loop thread
 * (the pattern proven by the integrated-server LocalChannel + DefaultEventLoop in this TeaVM build).
 *
 * The WebSocket opens before the Eagler handshake starts. The connect future completes once
 * the handshake has released the Minecraft pipeline.
 */
public final class EaglerWebSocketChannel extends AbstractChannel {

	private static final ChannelMetadata METADATA = new ChannelMetadata(false);

	private enum State { OPEN, CONNECTED, CLOSED }

	private final ChannelConfig config;
	private final String url;
	private volatile State state = State.OPEN;
	private ChannelPromise connectPromise;
	private EaglerWebSocketTransport transport;
	private volatile SocketAddress remoteAddress;
	private volatile SocketAddress localAddress;
	private boolean readPending;

	public EaglerWebSocketChannel(String url) {
		super(null);
		this.url = url;
		System.out.println("[EaglerHS] channel ctor: AbstractChannel ready");
		config = new DefaultChannelConfig(this);
		System.out.println("[EaglerHS] channel ctor: DefaultChannelConfig ready");
		config.setAllocator(new PreferHeapByteBufAllocator(config.getAllocator()));
		System.out.println("[EaglerHS] channel ctor: allocator ready");
		// Eagler handshake runs first, at the head of the pipeline. MC's client handlers (added later by
		// Connection.connect's initializer) sit after it. The handshake swallows channelActive until 0x09.
		String username = "Player";
		try {
			username = Minecraft.getInstance().getUser().getName();
		} catch (Throwable ignored) {
		}
		pipeline().addLast("eagler_handshake", new EaglerHandshakeHandler(username));
		System.out.println("[EaglerHS] channel ctor: handshake handler ready");
	}

	@Override
	public ChannelMetadata metadata() {
		return METADATA;
	}

	@Override
	public ChannelConfig config() {
		return config;
	}

	@Override
	public boolean isOpen() {
		return state != State.CLOSED;
	}

	@Override
	public boolean isActive() {
		return state == State.CONNECTED;
	}

	@Override
	protected boolean isCompatible(EventLoop loop) {
		return loop instanceof SingleThreadEventLoop;
	}

	@Override
	protected AbstractUnsafe newUnsafe() {
		return new WebSocketUnsafe();
	}

	@Override
	protected SocketAddress localAddress0() {
		return localAddress;
	}

	@Override
	protected SocketAddress remoteAddress0() {
		return remoteAddress;
	}

	@Override
	protected void doRegister() {
		System.out.println("[EaglerHS] doRegister (on event loop)");
	}

	@Override
	protected void doBind(SocketAddress addr) {
		this.localAddress = addr;
	}

	@Override
	protected void doDisconnect() {
		doClose();
	}

	@Override
	protected void doClose() {
		if (state == State.CLOSED) {
			return;
		}
		state = State.CLOSED;
		if (connectPromise != null) {
			connectPromise.tryFailure(new IllegalStateException("WebSocket closed before the Eagler login completed"));
		}
		if (transport != null) {
			transport.close();
		}
	}

	@Override
	protected void doBeginRead() {
		readPending = true;
		drainInbound();
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		if (state != State.CONNECTED) {
			return;
		}
		for (;;) {
			Object msg = in.current();
			if (msg == null) {
				break;
			}
			if (msg instanceof ByteBuf) {
				ByteBuf buf = (ByteBuf) msg;
				int len = buf.readableBytes();
				byte[] bytes = new byte[len];
				buf.getBytes(buf.readerIndex(), bytes);
				transport.send(bytes);
			}
			in.remove();
		}
	}

	// Drain buffered WS frames into the pipeline while a read is pending. Runs on the event loop.
	private void drainInbound() {
		if (transport == null) {
			return;
		}
		boolean read = false;
		byte[] frame;
		while (readPending && (frame = transport.poll()) != null) {
			read = true;
			pipeline().fireChannelRead(Unpooled.wrappedBuffer(frame));
		}
		if (read) {
			pipeline().fireChannelReadComplete();
		}
	}

	private final class WebSocketUnsafe extends AbstractUnsafe {
		@Override
		public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
			System.out.println("[EaglerHS] unsafe.connect enter");
			if (promise.isCancelled() || !ensureOpen(promise)) {
				return;
			}
			connectPromise = promise;
			try {
				remoteAddress = remote;
				transport = new EaglerWebSocketTransport(url);
				System.out.println("[EaglerHS] transport constructed");
				final EventLoop loop = eventLoop();
				int timeoutMillis = config.getConnectTimeoutMillis();
				var timeout = timeoutMillis > 0 ? loop.schedule(
					() -> failHandshake("Timed out waiting for the Eagler server"),
					timeoutMillis, TimeUnit.MILLISECONDS
				) : null;
				promise.addListener(result -> {
					if (timeout != null) timeout.cancel(false);
					if (result.isCancelled()) close(voidPromise());
				});
				transport.setOnOpen(() -> loop.execute(() -> {
					if (state == State.OPEN) {
						state = State.CONNECTED;
						pipeline().fireChannelActive();
					}
				}));
				transport.setOnReadable(() -> loop.execute(EaglerWebSocketChannel.this::drainInbound));
				transport.setOnClosed(() -> loop.execute(() -> {
					if (state != State.CLOSED) {
						close(voidPromise());
					}
				}));
				System.out.println("[EaglerHS] transport callbacks wired");
			} catch (Throwable t) {
				System.out.println("[EaglerHS] unsafe.connect CAUGHT " + t.getClass().getName() + ": " + t.getMessage());
				promise.tryFailure(t);
				close(voidPromise());
			}
		}
	}

	void finishHandshake() {
		connectPromise.trySuccess();
	}

	void failHandshake(String reason) {
		if (connectPromise.tryFailure(new IllegalStateException(reason))) {
			close();
		}
	}

	// P2 self-test: if window.__EAGLER_WS_TEST2 is set, bootstrap the netty channel (which adds the Eagler
	// handshake handler) and connect, so we exercise the full handshake against the live server without the
	// MC Multiplayer UI. channelFactory avoids TeaVM reflective channel instantiation (P3 handles .channel()).
	public static boolean runHandshakeSelfTestIfRequested() {
		String url = handshakeTestUrl();
		if (url == null || url.isEmpty()) {
			return false;
		}
		System.out.println("[EaglerHS] self-test bootstrapping handshake to " + url);
		// Use the SAME group the real multiplayer path uses (holder builds it with a daemon
		// ThreadFactoryBuilder factory). A bare `new DefaultEventLoopGroup(1)` uses netty's
		// DefaultThreadFactory/FastThreadLocalThread whose ctor throws UnsupportedOperationException under
		// TeaVM, failing initAndRegister before unsafe.connect ever runs. The LOCAL variant of this group
		// already drives singleplayer, so this factory is proven to start threads in the browser.
		io.netty.channel.EventLoopGroup group;
		try {
			System.out.println("[EaglerHS] test stage: create event loop group");
			group = net.minecraft.server.network.EventLoopGroupHolder.remote(false).eventLoopGroup();
			System.out.println("[EaglerHS] test stage: event loop group ready");
		} catch (Throwable t) {
			System.out.println("[EaglerHS] FAIL creating event loop group: " + t);
			return true;
		}
		// No DNS in the browser: netty's default resolver calls InetAddress.getByName which the TeaVM stub
		// rejects (UnsupportedOperationException) before our transport ever opens. NoopAddressResolverGroup
		// treats the address as already resolved so Bootstrap goes straight to channel.connect().
		try {
			System.out.println("[EaglerHS] test stage: configure bootstrap");
			Bootstrap bootstrap = new Bootstrap().group(group).channelFactory(() -> {
				try {
					return new EaglerWebSocketChannel(url);
				} catch (Throwable t) {
					System.out.println("[EaglerHS] FAIL constructing channel: " + t);
					throw t;
				}
			})
					.resolver(io.netty.resolver.NoopAddressResolverGroup.INSTANCE)
					.handler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel ch) {
			}
			});
			System.out.println("[EaglerHS] test stage: bootstrap connect");
			bootstrap.connect(InetSocketAddress.createUnresolved("eagler", 0)).addListener(f -> {
			System.out.println("[EaglerHS] connect() future: success=" + f.isSuccess()
					+ (f.cause() != null ? " cause=" + f.cause() : ""));
					if (f.cause() != null) {
				f.cause().printStackTrace();
			}
		});
		} catch (Throwable t) {
			System.out.println("[EaglerHS] FAIL configuring/connecting bootstrap: " + t);
		}
		return true;
	}

	@JSBody(params = {}, script = "return (typeof window !== 'undefined' && window.__EAGLER_WS_TEST2) ? window.__EAGLER_WS_TEST2 : null;")
	private static native String handshakeTestUrl();

}
