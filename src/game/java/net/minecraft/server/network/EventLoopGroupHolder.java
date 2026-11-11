package net.minecraft.server.network;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import org.jspecify.annotations.Nullable;

public abstract class EventLoopGroupHolder {
    private static final EventLoopGroupHolder NIO = new EventLoopGroupHolder("NIO", NioSocketChannel.class, NioServerSocketChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return NioIoHandler.newFactory();
        }
    };
    private static final EventLoopGroupHolder EPOLL = new EventLoopGroupHolder("Epoll", EpollSocketChannel.class, EpollServerSocketChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return EpollIoHandler.newFactory();
        }
    };
    private static final EventLoopGroupHolder KQUEUE = new EventLoopGroupHolder("Kqueue", KQueueSocketChannel.class, KQueueServerSocketChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return KQueueIoHandler.newFactory();
        }
    };
    private static final EventLoopGroupHolder LOCAL = new EventLoopGroupHolder("Local", LocalChannel.class, LocalServerChannel.class) {
        @Override
        protected IoHandlerFactory ioHandlerFactory() {
            return LocalIoHandler.newFactory();
        }
    };
    private final String type;
    private final Class<? extends Channel> channelCls;
    private final Class<? extends ServerChannel> serverChannelCls;
    private volatile @Nullable EventLoopGroup group;

    public static EventLoopGroupHolder remote(boolean p_459148_) {
        if (p_459148_) {
            if (KQueue.isAvailable()) {
                return KQUEUE;
            }

            if (Epoll.isAvailable()) {
                return EPOLL;
            }
        }

        return NIO;
    }

    public static EventLoopGroupHolder local() {
        return LOCAL;
    }

    EventLoopGroupHolder(String p_459051_, Class<? extends Channel> p_458933_, Class<? extends ServerChannel> p_459210_) {
        this.type = p_459051_;
        this.channelCls = p_458933_;
        this.serverChannelCls = p_459210_;
    }

    private ThreadFactory createThreadFactory() {
        return new ThreadFactoryBuilder().setNameFormat("Netty " + this.type + " IO #%d").setDaemon(true).build();
    }

    protected abstract IoHandlerFactory ioHandlerFactory();

    private EventLoopGroup createEventLoopGroup() {
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
        return bootstrap.channel(this.channelCls()).connect(address.getAddress(), address.getPort());
    }

    public Class<? extends ServerChannel> serverChannelCls() {
        return this.serverChannelCls;
    }
}
