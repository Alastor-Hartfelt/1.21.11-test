package net.lax1dude.eaglercraft.v1_21.netty;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;

/**
 * Client-side EaglercraftX handshake, run at the HEAD of the pipeline over {@link EaglerWebSocketChannel}.
 * Mirrors the repo's 1.8 HandshakerHandler/Instance/V5 byte-for-byte, but advertises the 1.21.11 MC protocol
 * and uses netty ByteBufs. On channelActive it sends 0x01 CLIENT_VERSION and *swallows* the event so MC's
 * Connection (further down the pipeline) doesn't send its own intention packet mid-handshake. After the
 * server's 0x09 FINISH it hands off to Minecraft's configuration protocol.
 *
 * Packet ids: 0x01 CLIENT_VERSION, 0x02 SERVER_VERSION, 0x03 VERSION_MISMATCH, 0x04 CLIENT_REQUEST_LOGIN,
 * 0x05 SERVER_ALLOW_LOGIN, 0x06 SERVER_DENY_LOGIN, 0x07 CLIENT_PROFILE_DATA, 0x08 CLIENT_FINISH_LOGIN,
 * 0x09 SERVER_FINISH_LOGIN, 0xFF SERVER_ERROR. Each WebSocket frame carries one packet.
 */
public final class EaglerHandshakeHandler extends ChannelInboundHandlerAdapter {

	private enum State { NEW, SENT_VERSION, SENT_LOGIN, SENT_FINISH, DONE }

	private State state = State.NEW;
	private boolean nicknameSelection = true;
	private GameProfile acceptedProfile;
	private final String username;

	public EaglerHandshakeHandler(String username) {
		this.username = username != null && !username.isEmpty() ? username : "Player";
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		System.out.println("[EaglerHS] handlerAdded");
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		System.out.println("[EaglerHS] channelRegistered");
		ctx.fireChannelRegistered();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		System.out.println("[EaglerHS] channelActive -> sending 0x01 CLIENT_VERSION");
		sendClientVersion(ctx);
		state = State.SENT_VERSION;
		// Wait for the Eagler server to finish login before activating Minecraft's pipeline.
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof ByteBuf) || state == State.DONE) {
			ctx.fireChannelRead(msg);
			return;
		}
		ByteBuf in = (ByteBuf) msg;
		try {
			int pkt = in.readUnsignedByte();
			switch (state) {
				case SENT_VERSION:
					if (pkt == 0x02) {
						handleServerVersion(ctx, in);
					} else if (pkt == 0x03) {
						fail(ctx, "version mismatch (server does not support this client/MC version)");
					} else if (pkt == 0xFF) {
						handleServerError(ctx, in, true);
					} else {
						fail(ctx, "unexpected handshake packet 0x" + Integer.toHexString(pkt) + " in SENT_VERSION");
					}
					break;
				case SENT_LOGIN:
					if (pkt == 0x05) {
						handleAllowLogin(ctx, in);
					} else if (pkt == 0x06) {
						handleDenyLogin(ctx, in);
					} else if (pkt == 0xFF) {
						handleServerError(ctx, in, false);
					} else {
						fail(ctx, "unexpected handshake packet 0x" + Integer.toHexString(pkt) + " in SENT_LOGIN");
					}
					break;
				case SENT_FINISH:
					if (pkt == 0x09) {
						state = State.DONE;
						System.out.println("[EaglerHS] HANDSHAKE COMPLETE (0x09). Starting Minecraft configuration.");
						// EaglerXServer has already sent the vanilla handshake, hello, and
						// login acknowledgement to its backend. Hold its configuration frames
						// until ConnectScreen installs the configuration decoder.
						ctx.channel().attr(Connection.EAGLER_PROFILE).set(acceptedProfile);
						if (ctx.pipeline().context("splitter") != null) {
							ctx.channel().config().setAutoRead(false);
							ctx.pipeline().remove("splitter");
							ctx.pipeline().remove("prepender");
							// The proxy already handled the vanilla intention packet.
							ctx.pipeline().replace("encoder", "outbound_config", new UnconfiguredPipelineHandler.Outbound());
						}
						ctx.pipeline().remove(this);
						ctx.fireChannelActive();
						((EaglerWebSocketChannel) ctx.channel()).finishHandshake();
					} else if (pkt == 0x0A) {
						fail(ctx, "This Eagler server requested a redirect");
					} else if (pkt == 0xFF) {
						handleServerError(ctx, in, false);
					} else {
						fail(ctx, "unexpected handshake packet 0x" + Integer.toHexString(pkt) + " in SENT_FINISH");
					}
					break;
				default:
					break;
			}
		} catch (Exception e) {
			fail(ctx, "handshake decode error: " + e);
		} finally {
			in.release();
		}
	}

	// ---- outbound ------------------------------------------------------------------------------

	private void sendClientVersion(ChannelHandlerContext ctx) {
		int mcProto = SharedConstants.getProtocolVersion();
		String brand = "eagler-1.21.11-port";
		String vers = "u1";
		ByteBuf b = ctx.alloc().buffer();
		b.writeByte(0x01);           // PROTOCOL_CLIENT_VERSION
		b.writeByte(2);              // legacy protocol version
		b.writeShort(3);             // eagler protocol count
		b.writeShort(3);
		b.writeShort(4);
		b.writeShort(5);
		b.writeShort(1);             // game protocol count
		b.writeShort(mcProto);       // 1.21.11 MC protocol (was 47 for 1.8)
		b.writeByte(brand.length());
		writeAscii(b, brand);
		b.writeByte(vers.length());
		writeAscii(b, vers);
		b.writeBoolean(false);       // auth (no password)
		b.writeByte(username.length());
		writeAscii(b, username);
		System.out.println("[EaglerHS] -> 0x01 CLIENT_VERSION mcProto=" + mcProto + " user=" + username);
		ctx.writeAndFlush(b);
	}

	private void handleServerVersion(ChannelHandlerContext ctx, ByteBuf in) {
		int eaglerProto = in.readUnsignedShort();
		int gameVers = in.readUnsignedShort();
		String brand = readAscii(in, in.readUnsignedByte());
		String version = readAscii(in, in.readUnsignedByte());
		int authType = in.readUnsignedByte();
		int saltLen = in.readUnsignedShort();
		in.skipBytes(saltLen);
		if (eaglerProto >= 5) {
			nicknameSelection = in.readBoolean();
		}
		System.out.println("[EaglerHS] <- 0x02 SERVER_VERSION eaglerProto=" + eaglerProto + " gameVers=" + gameVers
				+ " brand=" + brand + " ver=" + version + " auth=" + authType + " nickSel=" + nicknameSelection);
		int mcProto = SharedConstants.getProtocolVersion();
		if (gameVers != mcProto) {
			fail(ctx, "server MC protocol " + gameVers + " != client " + mcProto + " (server is a different MC version)");
			return;
		}
		sendClientRequestLogin(ctx);
		state = State.SENT_LOGIN;
	}

	private void sendClientRequestLogin(ChannelHandlerContext ctx) {
		ByteBuf b = ctx.alloc().buffer();
		b.writeByte(0x04);                 // PROTOCOL_CLIENT_REQUEST_LOGIN (V5 layout)
		if (nicknameSelection) {
			b.writeByte(username.length());
			writeAscii(b, username);
		} else {
			b.writeByte(0);
		}
		String requestedServer = "default";
		b.writeByte(requestedServer.length());
		writeAscii(b, requestedServer);
		b.writeByte(0);                    // password len (none)
		b.writeBoolean(false);             // enableCookies
		b.writeByte(0);                    // cookie len
		writeVarInt(b, 0);                 // standardCaps = 0 (minimal; no cap versions follow)
		b.writeByte(0);                    // extended caps count = 0
		System.out.println("[EaglerHS] -> 0x04 REQUEST_LOGIN user=" + username);
		ctx.writeAndFlush(b);
	}

	private void handleAllowLogin(ChannelHandlerContext ctx, ByteBuf in) {
		String user = readAscii(in, in.readUnsignedByte());
		long msb = in.readLong();
		long lsb = in.readLong();
		acceptedProfile = new GameProfile(new UUID(msb, lsb), user);
		System.out.println("[EaglerHS] <- 0x05 ALLOW_LOGIN user=" + user + " uuid=" + msb + ":" + lsb);
		// V5 also carries caps; we don't need them to proceed. Send minimal profile data + finish.
		sendProfileData(ctx);
		sendFinishLogin(ctx);
		state = State.SENT_FINISH;
	}

	private void sendProfileData(ChannelHandlerContext ctx) {
		// One 0x07 with a single entry: brand_uuid_v1 (16 bytes). Minimal; skin/cape default server-side.
		byte[] brandUuid = new byte[16]; // zeros -> default brand uuid; server tolerates
		String key = "brand_uuid_v1";
		ByteBuf b = ctx.alloc().buffer();
		b.writeByte(0x07);            // PROTOCOL_CLIENT_PROFILE_DATA
		b.writeByte(1);              // packet count
		b.writeByte(key.length());
		writeAscii(b, key);
		b.writeShort(brandUuid.length);
		b.writeBytes(brandUuid);
		System.out.println("[EaglerHS] -> 0x07 PROFILE_DATA (brand_uuid_v1)");
		ctx.writeAndFlush(b);
	}

	private void sendFinishLogin(ChannelHandlerContext ctx) {
		ByteBuf b = ctx.alloc().buffer();
		b.writeByte(0x08);           // PROTOCOL_CLIENT_FINISH_LOGIN
		System.out.println("[EaglerHS] -> 0x08 FINISH_LOGIN");
		ctx.writeAndFlush(b);
	}

	private void handleDenyLogin(ChannelHandlerContext ctx, ByteBuf in) {
		String msg = readAscii(in, in.readableBytes());
		fail(ctx, "server denied login: " + msg);
	}

	private void handleServerError(ChannelHandlerContext ctx, ByteBuf in, boolean afterVersion) {
		int code = in.readUnsignedByte();
		int len = afterVersion ? in.readUnsignedByte() : in.readUnsignedShort();
		String msg = in.readCharSequence(Math.min(len, in.readableBytes()), StandardCharsets.UTF_8).toString();
		fail(ctx, "server error code=" + code + " msg=" + msg);
	}

	private void fail(ChannelHandlerContext ctx, String why) {
		System.out.println("[EaglerHS] FAIL: " + why);
		state = State.DONE;
		((EaglerWebSocketChannel) ctx.channel()).failHandshake(why);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static void writeAscii(ByteBuf b, String s) {
		for (int i = 0; i < s.length(); i++) {
			b.writeByte(s.charAt(i));
		}
	}

	private static String readAscii(ByteBuf b, int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append((char) (b.readUnsignedByte()));
		}
		return sb.toString();
	}

	private static void writeVarInt(ByteBuf b, int value) {
		while ((value & ~0x7F) != 0) {
			b.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		b.writeByte(value);
	}
}
