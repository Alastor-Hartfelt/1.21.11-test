package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import net.lax1dude.eaglercraft.v1_21.platform.webgl.GLContext;
import net.lax1dude.eaglercraft.v1_21.platform.webgl.NativeHeap;
import org.lwjgl.PointerBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLContextAttributes;

/**
 * TeaVM shim for org.lwjgl.glfw.GLFW. Only the methods MC 1.21.11 references
 * (see /tmp/glfw-refs.txt) are implemented, with the exact descriptors so the
 * precompiled bytecode links. Window/monitor handles are opaque fixed fakes;
 * the one real job is wiring a WebGL2 canvas context into {@link GLContext}.
 *
 * The real GLFW's GLFW_* ints are compile-time constants, so javac inlines them
 * at MC's call sites — there are no field references to satisfy here, only methods.
 *
 * ponytail: every entry point is the laziest browser-correct stub. Stage D wires
 * DOM keyboard/mouse into the stored callbacks: handlers enqueue events and
 * glfwPollEvents drains them on the main thread, matching desktop GLFW timing.
 */
public final class GLFW {
	private GLFW() {
	}

	/** Fixed fake window/monitor/context handle. Values are never dereferenced. */
	private static final long HANDLE = 1L;

	/** Live canvas + context, set by {@link #glfwCreateWindow}; read by input wiring. */
	public static HTMLCanvasElement canvas;
	public static WebGL2RenderingContext gl;

	// Stored callback delegates (public for Stage D input dispatch).
	public static GLFWErrorCallbackI errorCallback;
	public static GLFWMonitorCallbackI monitorCallback;
	public static GLFWKeyCallbackI keyCallback;
	public static GLFWCharModsCallbackI charModsCallback;
	public static GLFWCursorPosCallbackI cursorPosCallback;
	public static GLFWCursorEnterCallbackI cursorEnterCallback;
	public static GLFWMouseButtonCallbackI mouseButtonCallback;
	public static GLFWScrollCallbackI scrollCallback;
	public static GLFWDropCallbackI dropCallback;
	public static GLFWFramebufferSizeCallbackI framebufferSizeCallback;
	public static GLFWWindowCloseCallbackI windowCloseCallback;
	public static GLFWWindowFocusCallbackI windowFocusCallback;
	public static GLFWWindowIconifyCallbackI windowIconifyCallback;
	public static GLFWWindowPosCallbackI windowPosCallback;
	public static GLFWWindowSizeCallbackI windowSizeCallback;

	// ---- input constants used by the wiring below (MC inlines its own) ----
	// (Real GLFW's GLFW_* ints are compile-time constants inlined at MC's call
	// sites; these locals just let this shim read the values it dispatches.)
	public static final int GLFW_RELEASE = 0;
	public static final int GLFW_PRESS = 1;
	public static final int GLFW_REPEAT = 2;
	public static final int GLFW_KEY_UNKNOWN = -1;
	public static final int GLFW_MOD_SHIFT = 0x0001;
	public static final int GLFW_MOD_CONTROL = 0x0002;
	public static final int GLFW_MOD_ALT = 0x0004;
	public static final int GLFW_MOD_SUPER = 0x0008;
	public static final int GLFW_CURSOR = 0x00033001;
	public static final int GLFW_CURSOR_NORMAL = 0x00034001;
	public static final int GLFW_CURSOR_DISABLED = 0x00034003;

	// ---- DOM input plumbing (Eaglercraft pattern: enqueue on the DOM handler,
	//      drain synchronously in glfwPollEvents so callbacks fire on the main
	//      thread exactly like desktop GLFW) --------------------------------
	private static final int EV_KEY = 0, EV_CHAR = 1, EV_CURSORPOS = 2, EV_MBUTTON = 3,
			EV_SCROLL = 4, EV_ENTER = 5, EV_FOCUS = 6;

	/** One queued DOM event; ints carry key/button/action/mods, doubles carry pos/scroll. */
	private static final class Ev {
		final int kind, a, b, c, d;
		final double x, y;

		Ev(int kind, int a, int b, int c, int d, double x, double y) {
			this.kind = kind;
			this.a = a;
			this.b = b;
			this.c = c;
			this.d = d;
			this.x = x;
			this.y = y;
		}
	}

	private static final ArrayDeque<Ev> eventQueue = new ArrayDeque<Ev>();
	/** Press state indexed by GLFW key code (GLFW_KEY_LAST == 348). */
	private static final boolean[] keyState = new boolean[349];
	/** Virtual absolute cursor position MC reads; advanced by movementX/Y when pointer-locked. */
	private static double cursorX, cursorY;
	private static boolean listenersAttached;
	private static boolean firstFramePresented;

	// ---- init / lifecycle -------------------------------------------------

	public static boolean glfwInit() {
		return true;
	}

	// Referenced directly by the patched InputConstants (raw mouse motion is
	// unavailable in the browser).
	public static final int GLFW_RAW_MOUSE_MOTION = 0x33005;

	public static boolean glfwRawMouseMotionSupported() {
		return false;
	}

	public static void glfwTerminate() {
	}

	public static void glfwDefaultWindowHints() {
	}

	public static void glfwWindowHint(int hint, int value) {
	}

	public static void glfwSwapInterval(int interval) {
	}

	public static void glfwPollEvents() {
		Ev ev;
		while ((ev = eventQueue.poll()) != null) {
			switch (ev.kind) {
				case EV_KEY:
					if (ev.a >= 0 && ev.a < keyState.length) {
						keyState[ev.a] = ev.c != GLFW_RELEASE;
					}
					if (keyCallback != null) {
						keyCallback.invoke(HANDLE, ev.a, ev.b, ev.c, ev.d);
					}
					break;
				case EV_CHAR:
					if (charModsCallback != null) {
						charModsCallback.invoke(HANDLE, ev.a, ev.d);
					}
					break;
				case EV_CURSORPOS:
					if (cursorPosCallback != null) {
						cursorPosCallback.invoke(HANDLE, ev.x, ev.y);
					}
					break;
				case EV_MBUTTON:
					if (mouseButtonCallback != null) {
						mouseButtonCallback.invoke(HANDLE, ev.a, ev.c, ev.d);
					}
					break;
				case EV_SCROLL:
					if (scrollCallback != null) {
						scrollCallback.invoke(HANDLE, ev.x, ev.y);
					}
					break;
				case EV_ENTER:
					if (cursorEnterCallback != null) {
						cursorEnterCallback.invoke(HANDLE, ev.a != 0);
					}
					break;
				case EV_FOCUS:
					if (windowFocusCallback != null) {
						windowFocusCallback.invoke(HANDLE, ev.a != 0);
					}
					break;
			}
		}
	}

	public static void glfwWaitEventsTimeout(double timeout) {
	}

	// ---- window creation --------------------------------------------------

	public static long glfwCreateWindow(int width, int height, CharSequence title, long monitor, long share) {
		HTMLDocument doc = HTMLDocument.current();
		HTMLElement el = doc.getElementById("gameCanvas");
		if (el == null) {
			el = doc.querySelector("canvas"); // ponytail: fall back to the first canvas
		}
		if (el == null) {
			throw new IllegalStateException("port-fable: no <canvas> (expected id 'gameCanvas') to bind the GL context");
		}
		HTMLCanvasElement c = (HTMLCanvasElement) el;
		c.setWidth(width);
		c.setHeight(height);

		WebGLContextAttributes attrs = WebGLContextAttributes.create();
		attrs.setAlpha(false);
		attrs.setDepth(true);
		attrs.setAntialias(false);
		attrs.setPremultipliedAlpha(false);

		WebGL2RenderingContext ctx = (WebGL2RenderingContext) c.getContext("webgl2", attrs);
		if (ctx == null) {
			throw new IllegalStateException("port-fable: WebGL2 is not available in this browser");
		}

		canvas = c;
		gl = ctx;
		GLContext.CURRENT = new GLContext(ctx);
		attachInputListeners(doc, c);
		return HANDLE;
	}

	/**
	 * Wires browser DOM input into the event queue. Keys go on the document
	 * (the canvas needs focus for key events); mouse/wheel on the canvas; focus
	 * on the window. Handlers only enqueue + preventDefault — dispatch to MC's
	 * callbacks happens on the main thread in {@link #glfwPollEvents}.
	 */
	private static void attachInputListeners(HTMLDocument doc, HTMLCanvasElement c) {
		if (listenersAttached) {
			return;
		}
		listenersAttached = true;

		EventListener<Event> onKeyDown = e -> {
			String cd = code(e);
			int m = mods(e);
			int k = KeyCodes.glfw(cd);
			if (k != GLFW_KEY_UNKNOWN) {
				eventQueue.add(new Ev(EV_KEY, k, 0, repeat(e) ? GLFW_REPEAT : GLFW_PRESS, m, 0, 0));
				// ponytail: swallow default (page scroll, quick-find) for every mapped key
				// except the browser essentials refresh/fullscreen/devtools.
				if (!cd.equals("F5") && !cd.equals("F11") && !cd.equals("F12")) {
					e.preventDefault();
				}
			}
			int cp = charCode(e);
			if (cp >= 0) {
				eventQueue.add(new Ev(EV_CHAR, cp, 0, 0, m, 0, 0));
			}
		};
		EventListener<Event> onKeyUp = e -> {
			int k = KeyCodes.glfw(code(e));
			if (k != GLFW_KEY_UNKNOWN) {
				eventQueue.add(new Ev(EV_KEY, k, 0, GLFW_RELEASE, mods(e), 0, 0));
			}
		};
		EventListener<Event> onMove = e -> {
			// The canvas backing store (canvas.width/height, == MC's framebuffer/screen size) can differ
			// from its CSS display size (clientWidth/Height); DOM offset/movement are in CSS pixels, so
			// scale them into backing-store pixels or MC's mouse coords land in the wrong place.
			double sx = scaleX(c);
			double sy = scaleY(c);
			if (isPointerLocked()) {
				cursorX += movementX(e) * sx;
				cursorY += movementY(e) * sy;
			} else {
				cursorX = offsetX(e) * sx;
				cursorY = offsetY(e) * sy;
			}
			eventQueue.add(new Ev(EV_CURSORPOS, 0, 0, 0, 0, cursorX, cursorY));
		};
		EventListener<Event> onDown = e -> {
			eventQueue.add(new Ev(EV_MBUTTON, glfwButton(button(e)), 0, GLFW_PRESS, mods(e), 0, 0));
			e.preventDefault();
		};
		EventListener<Event> onUp = e -> {
			eventQueue.add(new Ev(EV_MBUTTON, glfwButton(button(e)), 0, GLFW_RELEASE, mods(e), 0, 0));
			e.preventDefault();
		};
		EventListener<Event> onWheel = e -> {
			// ponytail: normalize to GLFW's ±1-per-notch; raw deltaY is pixels/lines.
			eventQueue.add(new Ev(EV_SCROLL, 0, 0, 0, 0, -Math.signum(deltaX(e)), -Math.signum(deltaY(e))));
			e.preventDefault();
		};
		EventListener<Event> onCtx = e -> e.preventDefault();
		EventListener<Event> onMouseEnter = e -> eventQueue.add(new Ev(EV_ENTER, 1, 0, 0, 0, 0, 0));
		EventListener<Event> onMouseLeave = e -> eventQueue.add(new Ev(EV_ENTER, 0, 0, 0, 0, 0, 0));
		EventListener<Event> onFocus = e -> eventQueue.add(new Ev(EV_FOCUS, 1, 0, 0, 0, 0, 0));
		EventListener<Event> onBlur = e -> eventQueue.add(new Ev(EV_FOCUS, 0, 0, 0, 0, 0, 0));

		doc.addEventListener("keydown", onKeyDown);
		doc.addEventListener("keyup", onKeyUp);
		c.addEventListener("mousemove", onMove);
		c.addEventListener("mousedown", onDown);
		c.addEventListener("mouseup", onUp);
		c.addEventListener("wheel", onWheel);
		c.addEventListener("contextmenu", onCtx);
		c.addEventListener("mouseenter", onMouseEnter);
		c.addEventListener("mouseleave", onMouseLeave);
		Window.current().addEventListener("focus", onFocus);
		Window.current().addEventListener("blur", onBlur);
	}

	private static int mods(Event e) {
		int m = 0;
		if (shiftKey(e)) {
			m |= GLFW_MOD_SHIFT;
		}
		if (ctrlKey(e)) {
			m |= GLFW_MOD_CONTROL;
		}
		if (altKey(e)) {
			m |= GLFW_MOD_ALT;
		}
		if (metaKey(e)) {
			m |= GLFW_MOD_SUPER;
		}
		return m;
	}

	/** DOM mouse button (0=left,1=middle,2=right) -> GLFW (0=left,1=right,2=middle). */
	private static int glfwButton(int b) {
		return b == 1 ? 2 : b == 2 ? 1 : b;
	}

	// ---- @JSBody field readers (typed JSO interfaces vary; these always work) --
	@JSBody(params = "e", script = "return e.code;")
	private static native String code(Event e);

	@JSBody(params = "e", script = "return e.repeat;")
	private static native boolean repeat(Event e);

	@JSBody(params = "e", script = "return e.shiftKey;")
	private static native boolean shiftKey(Event e);

	@JSBody(params = "e", script = "return e.ctrlKey;")
	private static native boolean ctrlKey(Event e);

	@JSBody(params = "e", script = "return e.altKey;")
	private static native boolean altKey(Event e);

	@JSBody(params = "e", script = "return e.metaKey;")
	private static native boolean metaKey(Event e);

	@JSBody(params = "e", script = "return e.button | 0;")
	private static native int button(Event e);

	@JSBody(params = "e", script = "return e.movementX;")
	private static native double movementX(Event e);

	@JSBody(params = "e", script = "return e.movementY;")
	private static native double movementY(Event e);

	@JSBody(params = "e", script = "return e.offsetX;")
	private static native double offsetX(Event e);

	@JSBody(params = "e", script = "return e.offsetY;")
	private static native double offsetY(Event e);

	// CSS-pixel -> canvas-backing-pixel scale (1.0 when the canvas isn't CSS-stretched).
	@JSBody(params = "c", script = "return c.clientWidth > 0 ? c.width / c.clientWidth : 1;")
	private static native double scaleX(HTMLCanvasElement c);

	@JSBody(params = "c", script = "return c.clientHeight > 0 ? c.height / c.clientHeight : 1;")
	private static native double scaleY(HTMLCanvasElement c);

	@JSBody(params = "e", script = "return e.deltaX;")
	private static native double deltaX(Event e);

	@JSBody(params = "e", script = "return e.deltaY;")
	private static native double deltaY(Event e);

	// Printable codepoint for a keydown, or -1 for named/control keys and modified combos.
	@JSBody(params = "e", script = "var k = e.key;"
			+ "return (k && Array.from(k).length === 1 && !e.ctrlKey && !e.metaKey && k.codePointAt(0) >= 32)"
			+ " ? k.codePointAt(0) : -1;")
	private static native int charCode(Event e);

	@JSBody(params = "c", script = "if (c.requestPointerLock) c.requestPointerLock();")
	private static native void requestPointerLock(HTMLElement c);

	@JSBody(params = {}, script = "if (document.exitPointerLock) document.exitPointerLock();")
	private static native void exitPointerLock();

	@JSBody(params = {}, script = "return document.pointerLockElement != null;")
	private static native boolean isPointerLocked();

	public static void glfwDestroyWindow(long window) {
	}

	public static void glfwMakeContextCurrent(long window) {
	}

	public static long glfwGetCurrentContext() {
		return HANDLE;
	}

	public static void glfwSwapBuffers(long window) {
		// The browser composites the canvas. Let the launcher dismiss its loading message.
		if (!firstFramePresented) {
			firstFramePresented = true;
			announceFirstFrame();
		}
	}

	@JSBody(params = {}, script = "if (typeof window !== 'undefined') window.dispatchEvent(new Event('eagler-first-frame'));")
	private static native void announceFirstFrame();

	public static boolean glfwWindowShouldClose(long window) {
		return false;
	}

	public static void glfwSetWindowTitle(long window, CharSequence title) {
		// ponytail: page <title> is owned by the shell; ignore.
	}

	public static void glfwSetWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
	}

	public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) {
		// ponytail: no window chrome in the browser; icons are ignored.
	}

	public static void glfwSetWindowMonitor(long window, long monitor, int xpos, int ypos, int width, int height, int refreshRate) {
	}

	// ---- framebuffer / window geometry ------------------------------------

	public static void glfwGetFramebufferSize(long window, int[] width, int[] height) {
		width[0] = canvas != null ? canvas.getWidth() : 1;
		height[0] = canvas != null ? canvas.getHeight() : 1;
	}

	public static void glfwGetWindowPos(long window, int[] xpos, int[] ypos) {
		xpos[0] = 0;
		ypos[0] = 0;
	}

	// ---- monitors ---------------------------------------------------------

	public static long glfwGetPrimaryMonitor() {
		return HANDLE;
	}

	public static long glfwGetWindowMonitor(long window) {
		return 0L;
	}

	public static PointerBuffer glfwGetMonitors() {
		// ponytail: one virtual monitor (the canvas); back one 8-byte pointer slot.
		long addr = NativeHeap.calloc(1L, 8L);
		PointerBuffer pb = new PointerBuffer(addr, 1);
		pb.put(0, HANDLE);
		return pb;
	}

	public static void glfwGetMonitorPos(long monitor, int[] xpos, int[] ypos) {
		xpos[0] = 0;
		ypos[0] = 0;
	}

	public static int glfwGetPlatform() {
		return 0;
	}

	public static GLFWVidMode glfwGetVideoMode(long monitor) {
		int w = canvas != null ? canvas.getWidth() : 1920;
		int h = canvas != null ? canvas.getHeight() : 1080;
		return new GLFWVidMode(w, h, 8, 8, 8, 60);
	}

	public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
		return new GLFWVidMode.Buffer(glfwGetVideoMode(monitor));
	}

	// ---- time / input state ----------------------------------------------

	public static double glfwGetTime() {
		return System.nanoTime() / 1.0e9;
	}

	public static int glfwGetKey(long window, int key) {
		return (key >= 0 && key < keyState.length && keyState[key]) ? GLFW_PRESS : GLFW_RELEASE;
	}

	public static String glfwGetKeyName(int key, int scancode) {
		return null;
	}

	public static void glfwSetInputMode(long window, int mode, int value) {
		if (mode == GLFW_CURSOR && canvas != null) {
			if (value == GLFW_CURSOR_DISABLED) {
				requestPointerLock(canvas); // must ride a user gesture; MC calls this from a click
			} else {
				exitPointerLock(); // GLFW_CURSOR_NORMAL / GLFW_CURSOR_HIDDEN
			}
		}
		// ponytail: GLFW_RAW_MOUSE_MOTION / GLFW_STICKY_* are no-ops in the browser.
	}

	public static void glfwSetCursorPos(long window, double xpos, double ypos) {
		cursorX = xpos;
		cursorY = ypos;
	}

	public static void glfwSetCursor(long window, long cursor) {
	}

	public static long glfwCreateStandardCursor(int shape) {
		return 0L;
	}

	// ---- clipboard --------------------------------------------------------

	public static String glfwGetClipboardString(long window) {
		return "";
	}

	public static void glfwSetClipboardString(long window, ByteBuffer string) {
	}

	// ---- error handling ---------------------------------------------------

	public static int glfwGetError(PointerBuffer description) {
		return 0; // GLFW_NO_ERROR
	}

	public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cbfun) {
		errorCallback = cbfun;
		return cbfun == null ? null : GLFWErrorCallback.create(cbfun);
	}

	// ---- callback registration (store delegate, return concrete wrapper) --

	public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cbfun) {
		monitorCallback = cbfun;
		return cbfun == null ? null : GLFWMonitorCallback.create(cbfun);
	}

	public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cbfun) {
		keyCallback = cbfun;
		return cbfun == null ? null : GLFWKeyCallback.create(cbfun);
	}

	public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI cbfun) {
		charModsCallback = cbfun;
		return cbfun == null ? null : GLFWCharModsCallback.create(cbfun);
	}

	public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cbfun) {
		cursorPosCallback = cbfun;
		return cbfun == null ? null : GLFWCursorPosCallback.create(cbfun);
	}

	public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI cbfun) {
		cursorEnterCallback = cbfun;
		return cbfun == null ? null : GLFWCursorEnterCallback.create(cbfun);
	}

	public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cbfun) {
		mouseButtonCallback = cbfun;
		return cbfun == null ? null : GLFWMouseButtonCallback.create(cbfun);
	}

	public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI cbfun) {
		scrollCallback = cbfun;
		return cbfun == null ? null : GLFWScrollCallback.create(cbfun);
	}

	public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI cbfun) {
		dropCallback = cbfun;
		return cbfun == null ? null : GLFWDropCallback.create(cbfun);
	}

	public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cbfun) {
		framebufferSizeCallback = cbfun;
		return cbfun == null ? null : GLFWFramebufferSizeCallback.create(cbfun);
	}

	public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI cbfun) {
		windowCloseCallback = cbfun;
		return cbfun == null ? null : GLFWWindowCloseCallback.create(cbfun);
	}

	public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cbfun) {
		windowFocusCallback = cbfun;
		return cbfun == null ? null : GLFWWindowFocusCallback.create(cbfun);
	}

	public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cbfun) {
		windowIconifyCallback = cbfun;
		return cbfun == null ? null : GLFWWindowIconifyCallback.create(cbfun);
	}

	public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI cbfun) {
		windowPosCallback = cbfun;
		return cbfun == null ? null : GLFWWindowPosCallback.create(cbfun);
	}

	public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI cbfun) {
		windowSizeCallback = cbfun;
		return cbfun == null ? null : GLFWWindowSizeCallback.create(cbfun);
	}
}
