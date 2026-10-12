#!/usr/bin/env node
// NOTE: Android has no /usr/bin/env, so this file cannot be executed directly.
// Stage it as usr/bin/dshdev.js and launch it through the usr/bin/dshdev shell wrapper.
/**
 * dshdev -- device control client for the DSH Android app.
 *
 * The app owns a loopback bridge (DeviceServer) that can capture the screen and
 * inject input events, because those need signature-level permissions the Node
 * runtime cannot hold. This client is the runtime-side half: it finds the bridge,
 * talks to it with zero dependencies, and decodes captured PNGs in pure JS so no
 * native image module is involved (the host Node is bionic and cannot load the
 * glibc-only sharp build).
 *
 * usage:
 *   dshdev status
 *   dshdev shot [out.png]        capture to a file (default under TMPDIR)
 *   dshdev see [cols]            capture and print an ASCII view + colour summary
 *   dshdev text [--png=FILE]     print the text on screen (needs the OCR runtime)
 *   dshdev tap X Y
 *   dshdev swipe X1 Y1 X2 Y2 [MS]
 *   dshdev key NAME|CODE
 *   dshdev type "STRING"
 *   dshdev start pkg/.Activity
 *   dshdev top
 */
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const zlib = require('zlib');

const FILES_DIR = process.env.DSH_ANDROID_FILES
  || '/data/user/0/com.dsh.android/files';
const PORT_CACHE = path.join(process.env.TMPDIR || '/tmp', '.dshdev-port');

/** The bridge token, created by DeviceServer on first run (mode 0600). */
function deviceToken() {
  const candidates = [];
  if (process.env.DSH_HOME) candidates.push(path.join(process.env.DSH_HOME, '.device-token'));
  candidates.push(path.join(FILES_DIR, '.device-token'));
  for (const file of candidates) {
    try {
      const value = fs.readFileSync(file, 'utf8').trim();
      if (value) return value;
    } catch (e) { /* try the next location */ }
  }
  return null;
}
const TOKEN = deviceToken();

// ---------------------------------------------------------------- bridge

function readPortHint() {
  // The app logs the bridge line; the log is readable from inside the runtime.
  for (const candidate of [path.join(FILES_DIR, 'dsh.log')]) {
    try {
      const text = fs.readFileSync(candidate, 'utf8');
      const matches = text.match(/device bridge: http:\/\/127\.0\.0\.1:(\d+)\//g);
      if (matches && matches.length) {
        const last = matches[matches.length - 1].match(/:(\d+)\//);
        if (last) return Number(last[1]);
      }
    } catch (e) { /* not there yet */ }
  }
  return null;
}

function cachedPort() {
  try {
    const value = Number(fs.readFileSync(PORT_CACHE, 'utf8').trim());
    return Number.isInteger(value) && value > 0 ? value : null;
  } catch (e) {
    return null;
  }
}

function rememberPort(port) {
  try { fs.writeFileSync(PORT_CACHE, String(port)); } catch (e) { /* best effort */ }
}

function request(port, urlPath, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: urlPath, method: 'GET', timeout: timeoutMs,
        headers: TOKEN ? { 'X-Device-Token': TOKEN } : {} },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({
          status: res.statusCode,
          type: res.headers['content-type'] || '',
          body: Buffer.concat(chunks),
        }));
      },
    );
    req.on('timeout', () => { req.destroy(new Error('bridge request timed out')); });
    req.on('error', reject);
    req.end();
  });
}

/** Probe the documented port range; remember whichever answers. */
async function connect() {
  const tried = [];
  const hints = [cachedPort(), readPortHint()].filter(Boolean);
  const range = [];
  // 3090 才是 DeviceServer.PORTS 的起点；旧的三段保留，兼容历史构建。
  for (const base of [3090, 3195, 3205, 3290]) for (let p = base; p < base + 7; p++) range.push(p);
  const order = [...new Set([...hints, ...range])];
  for (const port of order) {
    try {
      const res = await request(port, '/device/status', 4000);
      if (res.status === 200 && res.body.includes('dsh device bridge')) {
        rememberPort(port);
        return port;
      }
      tried.push(`${port}:${res.status}`);
    } catch (e) {
      tried.push(`${port}:${e.code || e.message}`);
    }
  }
  throw new Error(
    'no device bridge found (is the DSH app running with root granted?)\n'
    + '  tried ' + tried.join(' '),
  );
}

function fail(message) {
  process.stderr.write('dshdev: ' + message + '\n');
  process.exit(1);
}

// ------------------------------------------------------------ png decode

/** Minimal PNG decoder: 8-bit greyscale/RGB/palette/grey+alpha/RGBA, no interlace. */
function decodePNG(buf) {
  let pos = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
  let palette = null, transparency = null;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'PLTE') palette = data;
    else if (type === 'tRNS') transparency = data;
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (interlace) throw new Error('interlaced PNG is not supported');
  if (bitDepth !== 8) throw new Error('unsupported bit depth ' + bitDepth);
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error('unsupported colour type ' + colorType);

  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(height * stride);
  let rp = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[rp++];
    const row = raw.subarray(rp, rp + stride); rp += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0;
      const b = prev ? prev[i] : 0;
      const c = (prev && i >= channels) ? prev[i - channels] : 0;
      let v = row[i];
      switch (filter) {
        case 1: v += a; break;
        case 2: v += b; break;
        case 3: v += (a + b) >> 1; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
          break;
        }
        default: break;
      }
      cur[i] = v & 0xff;
    }
  }

  const rgba = Buffer.alloc(width * height * 4);
  for (let i = 0, n = width * height; i < n; i++) {
    let r, g, b, a = 255;
    if (colorType === 6) { r = out[i * 4]; g = out[i * 4 + 1]; b = out[i * 4 + 2]; a = out[i * 4 + 3]; }
    else if (colorType === 2) { r = out[i * 3]; g = out[i * 3 + 1]; b = out[i * 3 + 2]; }
    else if (colorType === 0) { r = g = b = out[i]; }
    else if (colorType === 4) { r = g = b = out[i * 2]; a = out[i * 2 + 1]; }
    else {
      const idx = out[i];
      r = palette[idx * 3]; g = palette[idx * 3 + 1]; b = palette[idx * 3 + 2];
      if (transparency && idx < transparency.length) a = transparency[idx];
    }
    rgba[i * 4] = r; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = b; rgba[i * 4 + 3] = a;
  }
  return { width, height, rgba };
}

// ------------------------------------------------------------- renderers

const RAMP = ' .:-=+*#%@';

function asciiView(img, cols) {
  const { width, height, rgba } = img;
  const rows = Math.max(1, Math.round(cols * height / width / 2.1));
  const colors = new Map();
  const lines = [];
  for (let cy = 0; cy < rows; cy++) {
    let line = '';
    for (let cx = 0; cx < cols; cx++) {
      const x0 = Math.floor(cx * width / cols), x1 = Math.max(x0 + 1, Math.floor((cx + 1) * width / cols));
      const y0 = Math.floor(cy * height / rows), y1 = Math.max(y0 + 1, Math.floor((cy + 1) * height / rows));
      let sr = 0, sg = 0, sb = 0, n = 0;
      for (let y = y0; y < y1; y += 2) {
        for (let x = x0; x < x1; x += 2) {
          const p = (y * width + x) * 4;
          sr += rgba[p]; sg += rgba[p + 1]; sb += rgba[p + 2]; n++;
        }
      }
      sr /= n; sg /= n; sb /= n;
      const lum = (0.2126 * sr + 0.7152 * sg + 0.0722 * sb) / 255;
      line += RAMP[Math.min(RAMP.length - 1, Math.round((1 - lum) * (RAMP.length - 1)))];
      const key = `${sr >> 5 << 5},${sg >> 5 << 5},${sb >> 5 << 5}`;
      colors.set(key, (colors.get(key) || 0) + 1);
    }
    lines.push(line);
  }
  const top = [...colors.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)
    .map(([k, v]) => {
      const [r, g, b] = k.split(',').map(Number);
      return `  rgb(${r},${g},${b})  ${v} cells`;
    });
  return [
    `# ${width}x${height}`,
    ...lines,
    '',
    '# dominant colours:',
    ...top,
  ].join('\n');
}

// ------------------------------------------------------------------- main

async function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0] || 'status';
  const pngFlag = argv.find((a) => a.startsWith('--png='));
  const pngFile = pngFlag ? pngFlag.slice(6) : null;

  const port = await connect();

  const capture = async () => {
    const res = await request(port, '/device/shot', 60000);
    if (res.status !== 200) fail('capture failed: ' + res.body.toString('utf8').trim());
    return res.body;
  };

  switch (cmd) {
    case 'status': {
      const res = await request(port, '/device/status', 8000);
      process.stdout.write(`bridge port ${port}\n` + res.body.toString('utf8'));
      return;
    }
    case 'shot': {
      const png = await capture();
      const out = argv[1] || path.join(process.env.TMPDIR || '/tmp', 'screen.png');
      fs.writeFileSync(out, png);
      process.stdout.write(`${out} (${png.length} bytes)\n`);
      return;
    }
    case 'see': {
      const png = pngFile ? fs.readFileSync(pngFile) : await capture();
      process.stdout.write(asciiView(decodePNG(png), Number(argv[1]) || 60) + '\n');
      return;
    }
    case 'text': {
      if (pngFile) fail('--png is not supported for OCR yet; capture is done in-app');
      const res = await request(port, '/device/ocr', 150000);
      if (res.status === 501) {
        process.stdout.write(res.body.toString('utf8').trim() + '\n');
        process.exit(3);
      }
      if (res.status !== 200) fail('ocr failed: ' + res.body.toString('utf8').trim());
      process.stdout.write(res.body.toString('utf8'));
      return;
    }
    case 'tap': {
      if (argv.length < 3) fail('usage: dshdev tap X Y');
      const res = await request(port, `/device/tap?x=${argv[1]}&y=${argv[2]}`, 30000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    case 'swipe': {
      if (argv.length < 5) fail('usage: dshdev swipe X1 Y1 X2 Y2 [MS]');
      const ms = argv[5] || '300';
      const res = await request(port,
        `/device/swipe?x1=${argv[1]}&y1=${argv[2]}&x2=${argv[3]}&y2=${argv[4]}&ms=${ms}`, 30000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    case 'key': {
      if (argv.length < 2) fail('usage: dshdev key NAME|CODE');
      const res = await request(port, `/device/key?name=${encodeURIComponent(argv[1])}`, 30000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    case 'type': {
      const value = argv.slice(1).join(' ');
      if (!value) fail('usage: dshdev type "STRING"');
      const res = await request(port, `/device/text?value=${encodeURIComponent(value)}`, 30000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    case 'start': {
      if (argv.length < 2) fail('usage: dshdev start pkg/.Activity');
      const res = await request(port, `/device/start?component=${encodeURIComponent(argv[1])}`, 40000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    case 'top': {
      const res = await request(port, '/device/top', 30000);
      process.stdout.write(res.body.toString('utf8'));
      if (res.status !== 200) process.exit(1);
      return;
    }
    default:
      fail(`unknown command '${cmd}'`);
  }
}

main().catch((e) => fail(e.message));
