package net.minecraft.server.network;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.NoopAddressResolverGroup;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import net.lax1dude.eaglercraft.v1_21.netty.EaglerWebSocketChannel;
import org.jspecify.annotations.Nullable;

// TeaVM browser shadow of the vanilla EventLoopGroupHolder. The vanilla class keeps
// static EPOLL/KQUEUE holder fields whose initializers construct the epoll/kqueue
// channels and IoHandlers - that clinit drags io.netty.channel.{epoll,kqueue,unix}
// (75 JNI native methods) into the reachable set, none of which can link in a browser.
// Those native transports are never usable here anyway, so this shadow drops the
// EPOLL/KQUEUE fields and their imports; remote() always returns NIO. Everything else
// is identical so every method descriptor still resolves. Our teavm/src/main output
// wins the TeaVM task classpath (see build.gradle.kts).
public abstract class EventLoopGroupHolder {
    private static final EventLoopGroupHolder NIO = new EventLoopGroupHolder("NIO", NioSocketChannel.class, NioServerSocketChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return NioIoHandler.newFactory();
        }
    };
    private static final EventLoopGroupHolder LOCAL = new EventLoopGroupHolder("Local", LocalChannel.class, LocalServerChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return LocalIoHandler.newFactory();
        }
    };
    // Browser multiplayer: MC can't open TCP. Route remote() to a WebSocket-backed netty channel
    // (EaglerWebSocketChannel) on a classic DefaultEventLoopGroup (SingleThreadEventLoop -> compatible with
    // the channel; the IoHandler model is only for the epoll/nio/local transports). No server channel here.
    private static final EventLoopGroupHolder WEBSOCKET = new EventLoopGroupHolder("WebSocket", EaglerWebSocketChannel.class, LocalServerChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return null; // unused; createEventLoopGroup is overridden
        }

        @Override
        protected EventLoopGroup createEventLoopGroup() {
            return new DefaultEventLoopGroup(1, this.createThreadFactory());
        }
    };
    private final String type;
    private final Class<? extends Channel> channelCls;
    private final Class<? extends ServerChannel> serverChannelCls;
    private volatile @Nullable EventLoopGroup group;

    public static EventLoopGroupHolder remote(boolean p_459148_) {
        return WEBSOCKET;
    }

    public static EventLoopGroupHolder local() {
        return LOCAL;
    }

    EventLoopGroupHolder(String p_459051_, Class<? extends Channel> p_458933_, Class<? extends ServerChannel> p_459210_) {
        this.type = p_459051_;
        this.channelCls = p_458933_;
        this.serverChannelCls = p_459210_;
    }

    protected ThreadFactory createThreadFactory() {
        return new ThreadFactoryBuilder().setNameFormat("Netty " + this.type + " IO #%d").setDaemon(true).build();
    }

    protected abstract IoHandlerFactory ioHandlerFactory();

    protected EventLoopGroup createEventLoopGroup() {
        return new MultiThreadIoEventLoopGroup(this.createThreadFactory(), this.ioHandlerFactory());
    }

    public EventLoopGroup eventLoopGroup() {
        EventLoopGroup eventloopgroup = this.group;
        if (eventloopgroup == null) {
            synchronized (this) {
                eventloopgroup = this.group;
                if (eventloopgroup == null) {
                    eventloopgroup = this.createEventLoopGroup();
                    this.group = eventloopgroup;
                }
            }
        }

        return eventloopgroup;
    }

    public Class<? extends Channel> channelCls() {
        return this.channelCls;
    }

    public ChannelFuture connect(Bootstrap bootstrap, InetSocketAddress address, @Nullable String webSocketUrl) {
        if (this == WEBSOCKET) {
            if (webSocketUrl == null) {
                throw new IllegalArgumentException("Enter a full ws:// or wss:// Eagler server address");
            }
            return bootstrap.channelFactory(() -> new EaglerWebSocketChannel(webSocketUrl))
                .resolver(NoopAddressResolverGroup.INSTANCE).connect(address);
        }
        return bootstrap.channel(this.channelCls()).connect(address);
    }

    public Class<? extends ServerChannel> serverChannelCls() {
        return this.serverChannelCls;
    }
}
