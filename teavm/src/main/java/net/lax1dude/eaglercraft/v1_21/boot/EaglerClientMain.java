package net.lax1dude.eaglercraft.v1_21.boot;

import org.teavm.jso.JSBody;

/**
 * Stage C entry point: boot the full Minecraft client through its normal
 * {@code net.minecraft.client.main.Main} path with synthetic launch args. This
 * pulls the entire render/window/input stack into the reachable set so the
 * TeaVM linker exercises the org.lwjgl WebGL/GLFW shims.
 *
 * The desktop launcher (runClient) passes equivalent args; here they are fixed
 * for the offline browser build.
 */
public class EaglerClientMain {

	public static void main(String[] args) {
		// netty DefaultChannelId computes a MACHINE_ID from the NIC MAC at clinit; that lookup can't run in
		// the browser (NetworkInterface stub) and leaves MACHINE_ID null -> AbstractChannel.newId() NPEs on
		// every channel create (WebSocket + Local). Provide fixed machine/process ids so netty skips the MAC
		// lookup. Must be set before any netty channel is constructed.
		System.setProperty("io.netty.processId", "1");
		System.setProperty("io.netty.machineId", "0a:1b:2c:3d:4e:5f");

		// P1 multiplayer transport self-test: if window.__EAGLER_WS_TEST is set, open a raw WebSocket to it
		// and log frames instead of booting the game. Lets us verify the wss transport against a live
		// EaglerXServer in isolation.
		if (net.lax1dude.eaglercraft.v1_21.netty.EaglerWebSocketTransport.runSelfTestIfRequested()) {
			return;
		}
		// P2: full netty channel + Eagler handshake self-test (window.__EAGLER_WS_TEST2).
		String username = browserUsername();
		if (net.lax1dude.eaglercraft.v1_21.netty.EaglerWebSocketChannel.runHandshakeSelfTestIfRequested(username)) {
			return;
		}

		// slf4j must cache the real System.err before Bootstrap.wrapStreams()
		// redirects it (see EaglerBootMain / milestone 1).
		System.setProperty("org.slf4j.simpleLogger.cacheOutputStream", "true");

		// Unzip the vanilla 1.21.11 resources (fetched by the page harness to
		// window.__EAGLER_ZIP) into the TeaVM VFS so ClientPackSource can read the
		// vanilla pack. Must run before Main.main builds the resource manager.
		EaglerAssets.install();

		String[] launchArgs = {
			"--version", "1.21.11-port",
			"--gameDir", "/game",
			"--assetsDir", "/game/assets",
			"--assetIndex", "29",
			"--username", username,
			"--accessToken", "0",
			"--userType", "legacy",
			"--uuid", "00000000000000000000000000000000",
			// Browser build has no Mojang network/auth: mark offline so Realms availability
			// short-circuits (no HTTP) instead of throwing URL.openConnection -> title-screen crash.
			"--offlineDeveloperMode",
		};

		net.minecraft.client.main.Main.main(launchArgs);
	}

	@JSBody(params = {}, script = "var name = typeof window === 'undefined' ? '' : new URLSearchParams(window.location.search).get('username');"
		+ "return /^[A-Za-z0-9_]{1,16}$/.test(name || '') ? name : 'Player';")
	private static native String browserUsername();
}
