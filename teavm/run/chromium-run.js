// Headless-chromium harness: serves index.html + the generated classes.js over a
// local static server, launches chromium with a REAL WebGL2 context (swiftshader),
// runs main([]), streams the page console to stdout, and screenshots the canvas.
//
// Usage: node chromium-run.js [classes.js path] [seconds-to-run]
//   defaults: ../build/generated/teavm/js/classes.js, 25s
const http = require('http');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');

const RUN_DIR = __dirname;
const CLASSES = path.resolve(process.argv[2] || path.join(RUN_DIR, '../build/generated/teavm/js/classes.js'));
const RUN_SECONDS = Number(process.argv[3] || 25);
const SHOT = path.resolve(process.env.SCREENSHOT || path.join(RUN_DIR, 'title-screen.png'));
const SMOKE = process.env.SMOKE === '1';

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.png': 'image/png', '.json': 'application/json', '.zip': 'application/zip' };

function serveFile(res, file, type) {
	fs.stat(file, (err, st) => {
		if (err) { res.writeHead(404); res.end('not found: ' + file); return; }
		res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size });
		fs.createReadStream(file).pipe(res);
	});
}

const server = http.createServer((req, res) => {
	let url = req.url.split('?')[0];
	if (url === '/') url = '/index.html';
	if (url === '/classes.js') return serveFile(res, CLASSES, 'text/javascript');
	if (url === '/classes.js.map') return serveFile(res, CLASSES + '.map', 'application/json');
	if (url === '/index.html' || url === '/vanilla-assets.zip') {
		return serveFile(res, path.join(RUN_DIR, url), MIME[path.extname(url)]);
	}
	res.writeHead(404);
	res.end('not found');
});

(async () => {
	await new Promise(r => server.listen(0, '127.0.0.1', r));
	const port = server.address().port;
	const base = `http://127.0.0.1:${port}/`;
	console.log('[harness] serving', RUN_DIR, 'classes.js=', CLASSES, 'at', base);

	let browser;
	try {
		browser = await puppeteer.launch({
			headless: true,
			args: [
				'--no-sandbox',
				'--enable-unsafe-swiftshader',   // allow WebGL over swiftshader in headless
				'--ignore-gpu-blocklist',
				'--use-gl=angle',
				'--use-angle=swiftshader',
				'--enable-webgl',
				'--disable-dev-shm-usage',
			],
		});
		const page = await browser.newPage();
		await page.setViewport({ width: 1280, height: 720 });

		// Multiplayer P1: set the transport self-test / connect URL BEFORE the module boots.
		if (process.env.WS_TEST) {
			await page.evaluateOnNewDocument(u => { window.__EAGLER_WS_TEST = u; window.__EAGLER_WS_URL = u; }, process.env.WS_TEST);
		}
		if (process.env.WS_TEST2) {
			await page.evaluateOnNewDocument(u => { window.__EAGLER_WS_TEST2 = u; window.__EAGLER_WS_URL = u; }, process.env.WS_TEST2);
		}
		if (process.env.WS_URL) {
			await page.evaluateOnNewDocument(u => { window.__EAGLER_WS_URL = u; }, process.env.WS_URL);
		}

		page.on('console', msg => {
			console.log('[page]', msg.text());
			if (msg.text().includes('EAGLER-SUB-BAD')) {
				const frames = msg.stackTrace() || [];
				for (const f of frames.slice(0, 12)) console.log('   [frame]', (f.url || '').replace(/^http[^/]*\/\/[^/]*\//, ''), f.lineNumber, f.columnNumber);
			}
		});
		page.on('pageerror', err => console.log('[pageerror]', err.message));
		page.on('requestfailed', r => console.log('[reqfail]', r.url(), r.failure() && r.failure().errorText));

		// Confirm a real WebGL2 context is available before loading the 144MB module.
		await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 120000 });

		if (SMOKE) {
			await page.waitForFunction(() => {
				const status = document.getElementById('bootStatus');
				return status.hidden || status.dataset.failed;
			}, { timeout: RUN_SECONDS * 1000 });
			const failure = await page.$eval('#bootStatus', status => status.dataset.failed ? status.textContent : '');
			if (failure) throw new Error(failure);
			console.log('[harness] Minecraft presented its first frame');
		} else {
			console.log(`[harness] loaded; running for ${RUN_SECONDS}s...`);
			await new Promise(r => setTimeout(r, RUN_SECONDS * 1000));
		}

		// Optional input driving for verification: CLICKS="x,y;x,y" clicks each point (canvas px,
		// which == viewport px since the canvas fills the viewport), 1.5s apart, before the shot.
		if (process.env.CLICKS) {
			// diagnostic: what element is under the click point, and does the canvas see DOM events?
			await page.evaluate(() => {
				const el = document.elementFromPoint(636, 670);
				console.log('PROBE elementFromPoint(636,670)=', el && el.id, el && el.tagName);
				const c = document.getElementById('gameCanvas');
				['mousemove', 'mousedown', 'mouseup'].forEach(t =>
					c.addEventListener(t, e => console.log('PROBE canvas', t, 'at', e.offsetX | 0, e.offsetY | 0), { capture: true }));
			});
			for (const pt of process.env.CLICKS.split(';')) {
				const [x, y] = pt.split(',').map(Number);
				if (process.env.HOVER) {
					console.log('[harness] hover', x, y);
					await page.mouse.move(x, y);
				} else {
					console.log('[harness] click', x, y);
					await page.mouse.click(x, y);
				}
				await new Promise(r => setTimeout(r, 3000));
			}
		}

		// Optional extra settle (e.g. wait for world gen to finish) before the screenshot.
		if (process.env.WORLDWAIT) {
			const s = Number(process.env.WORLDWAIT);
			console.log('[harness] worldwait', s, 's');
			await new Promise(r => setTimeout(r, s * 1000));
		}

		try {
			const canvas = await page.$('#gameCanvas');
			if (canvas) { await canvas.screenshot({ path: SHOT }); console.log('[harness] screenshot ->', SHOT); }
			else { await page.screenshot({ path: SHOT }); console.log('[harness] full-page screenshot ->', SHOT); }
		} catch (e) { console.log('[harness] screenshot failed', e.message); }

	} finally {
		try {
			if (browser) await browser.close();
		} finally {
			server.close();
		}
	}
})().catch(error => {
	console.error('[harness]', error);
	process.exitCode = 1;
});
