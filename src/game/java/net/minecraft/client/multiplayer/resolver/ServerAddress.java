package net.minecraft.client.multiplayer.resolver;

import com.google.common.net.HostAndPort;
import com.mojang.logging.LogUtils;
import java.net.URI;
import java.net.IDN;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ServerAddress {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final HostAndPort hostAndPort;
    @Nullable
    private final String webSocketUrl;
    private static final ServerAddress INVALID = new ServerAddress(HostAndPort.fromParts("server.invalid", 25565), null);

    public ServerAddress(String p_171861_, int p_171862_) {
        this(HostAndPort.fromParts(p_171861_, p_171862_), null);
    }

    private ServerAddress(HostAndPort p_171859_) {
        this(p_171859_, null);
    }

    private ServerAddress(HostAndPort p_171859_, @Nullable String webSocketUrl) {
        this.hostAndPort = p_171859_;
        this.webSocketUrl = webSocketUrl;
    }

    public String getHost() {
        try {
            return IDN.toASCII(this.hostAndPort.getHost());
        } catch (IllegalArgumentException illegalargumentexception) {
            return "";
        }
    }

    public int getPort() {
        return this.hostAndPort.getPort();
    }

    @Nullable
    public String getWebSocketUrl() {
        return this.webSocketUrl;
    }

    public static ServerAddress parseString(@Nullable String p_171865_) {
        if (p_171865_ == null) {
            return INVALID;
        } else {
            try {
                if (p_171865_.startsWith("ws://") || p_171865_.startsWith("wss://")) {
                    URI uri = URI.create(p_171865_);
                    String host = uri.getHost();
                    if (host == null || host.isEmpty() || uri.getUserInfo() != null || uri.getFragment() != null) {
                        return INVALID;
                    }
                    int port = uri.getPort();
                    if (port < 0) {
                        port = uri.getScheme().equals("wss") ? 443 : 80;
                    }
                    return new ServerAddress(HostAndPort.fromParts(host, port), p_171865_);
                }
                HostAndPort hostandport = HostAndPort.fromString(p_171865_).withDefaultPort(25565);
                return hostandport.getHost().isEmpty() ? INVALID : new ServerAddress(hostandport);
            } catch (IllegalArgumentException illegalargumentexception) {
                LOGGER.info("Failed to parse URL {}", p_171865_, illegalargumentexception);
                return INVALID;
            }
        }
    }

    public static boolean isValidAddress(String p_171868_) {
        try {
            if (p_171868_.startsWith("ws://") || p_171868_.startsWith("wss://")) {
                URI uri = URI.create(p_171868_);
                return uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null && uri.getFragment() == null;
            }
            HostAndPort hostandport = HostAndPort.fromString(p_171868_);
            String s = hostandport.getHost();
            if (!s.isEmpty()) {
                IDN.toASCII(s);
                return true;
            }
        } catch (IllegalArgumentException illegalargumentexception) {
        }

        return false;
    }

    static int parsePort(String p_171870_) {
        try {
            return Integer.parseInt(p_171870_.trim());
        } catch (Exception exception) {
            return 25565;
        }
    }

    @Override
    public String toString() {
        return this.hostAndPort.toString();
    }

    @Override
    public boolean equals(Object p_171872_) {
        if (this == p_171872_) {
            return true;
        } else {
            return p_171872_ instanceof ServerAddress ? this.hostAndPort.equals(((ServerAddress)p_171872_).hostAndPort) : false;
        }
    }

    @Override
    public int hashCode() {
        return this.hostAndPort.hashCode();
    }
}
