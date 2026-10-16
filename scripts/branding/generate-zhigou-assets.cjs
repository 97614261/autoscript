/* Deterministic derivatives of the user's bitmap-in-SVG. Never draws/replaces the logo. */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const ballSvg = require('./zhigou-ball-visuals.cjs');
const root = path.resolve(__dirname, '../..');
const brand = path.join(root, 'android/branding');
const source = path.join(brand, 'source/zhigou_logo.svg');
const args = process.argv.slice(2);
const option = name => args.includes(name) ? args[args.indexOf(name) + 1] : null;
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const manifestPath = path.join(brand, 'assets.json');
if (args.includes('--check')) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (sha(fs.readFileSync(source)) !== manifest.sourceSha256) throw new Error('Source SVG changed');
  for (const item of manifest.files) {
    const bytes = fs.readFileSync(path.join(root, item.path));
    if (bytes.length !== item.bytes || sha(bytes) !== item.sha256) throw new Error(`Changed asset: ${item.path}`);
  }
  console.log(`PASS: ${manifest.files.length} exact asset hashes; SVG source unchanged`);
  process.exit(0);
}
const sharp = require(option('--sharp') || 'sharp');
const files = [];
const record = async (relative, bytes) => {
  const dest = path.join(root, relative);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, bytes);
  files.push({ path: relative.replaceAll('\\', '/'), bytes: bytes.length, sha256: sha(bytes) });
};
const svg = text => Buffer.from(text);
const svgRoot = (size, body) => svg(`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">${body}</svg>`);
const uri = png => `data:image/png;base64,${png.toString('base64')}`;
const pngOptions = { compressionLevel: 9, palette: false };

async function main() {
  const provided = option('--source');
  if (provided) {
    fs.mkdirSync(path.dirname(source), { recursive: true });
    fs.copyFileSync(path.resolve(provided), source);
  }
  const original = fs.readFileSync(source);
  const embedded = original.toString('utf8').match(/(?:href|xlink:href)="data:image\/png;base64,([A-Za-z0-9+/=\r\n]+)"/);
  if (!embedded) throw new Error('Expected original embedded PNG, not a traced vector');
  const sourcePng = Buffer.from(embedded[1].replace(/\s/g, ''), 'base64');
  const { data, info } = await sharp(sourcePng).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.width !== info.height || info.width > 4096) throw new Error('Unexpected source geometry');
  let changed = 0;
  for (let i = 0; i < data.length; i += 4) {
    // Suppress only the desaturated translucent matte. Saturated purple/pink pixels,
    // their alpha, and every source pixel coordinate remain unchanged.
    const chroma = Math.max(data[i], data[i + 1], data[i + 2]) - Math.min(data[i], data[i + 1], data[i + 2]);
    if (chroma >= 45) continue;
    const t = Math.max(0, Math.min(1, (chroma - 12) / 33));
    const next = Math.round(data[i + 3] * t * t * (3 - 2 * t));
    if (next !== data[i + 3]) changed++;
    data[i + 3] = next;
  }
  const master = await sharp(data, { raw: info }).resize(1024, 1024, { kernel: 'lanczos3' }).png(pngOptions).toBuffer();
  await record('android/modules/brand-ui/src/main/res/drawable-nodpi/zhigou_brand_mark.png', master);
  await record('android/branding/exports/master/zhigou-1024-transparent.png', master);
  const raw = await sharp(master).raw().toBuffer();
  const mono = Buffer.from(raw);
  const pastel = Buffer.from(raw);
  const outline = Buffer.alloc(raw.length);
  const n = 1024;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    const i = (y * n + x) * 4;
    mono[i] = mono[i + 1] = mono[i + 2] = 255;
    pastel[i] = Math.round(raw[i] * .24 + 193.8);
    pastel[i + 1] = Math.round(raw[i + 1] * .24 + 185.44);
    pastel[i + 2] = Math.round(raw[i + 2] * .24 + 193.8);
    if (pastel[i + 3] !== raw[i + 3]) throw new Error('Floating variant changed source alpha');
    let low = 255, high = 0;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
      const ax = x + dx, ay = y + dy;
      const alpha = ax < 0 || ay < 0 || ax >= n || ay >= n ? 0 : raw[(ay * n + ax) * 4 + 3];
      low = Math.min(low, alpha); high = Math.max(high, alpha);
    }
    outline[i] = 128; outline[i + 1] = 48; outline[i + 2] = 255; outline[i + 3] = high - low;
  }
  const monoPng = await sharp(mono, { raw: { width: n, height: n, channels: 4 } }).png(pngOptions).toBuffer();
  const linePng = await sharp(outline, { raw: { width: n, height: n, channels: 4 } }).png(pngOptions).toBuffer();
  const pastelPng = await sharp(pastel, { raw: { width: n, height: n, channels: 4 } }).png(pngOptions).toBuffer();
  await record('android/branding/exports/floating/logo-pastel-1024.png', pastelPng);
  await record('android/modules/brand-ui/src/main/res/drawable-nodpi/zhigou_brand_monochrome.png', monoPng);
  await record('android/branding/exports/styles/outline.png', linePng);
  await record('android/branding/exports/styles/monochrome.png', await sharp(master).grayscale().png(pngOptions).toBuffer());

  const markUri = uri(master);
  function tile(size, variant, mark = markUri) {
    const dark = variant === 'dark';
    const circle = variant === 'circle';
    const fill = dark ? '#0a0d19' : variant === 'purple' ? '#7331ff' : variant === 'gradient' ? 'url(#bg)' : '#ffffff';
    return svgRoot(size, `<defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#f67bfa"/><stop offset=".5" stop-color="#7228ff"/><stop offset="1" stop-color="#2e55fe"/></linearGradient></defs>${circle ? `<circle cx="${size/2}" cy="${size/2}" r="${size/2}" fill="${fill}"/>` : `<rect width="${size}" height="${size}" rx="${size*.23}" fill="${fill}"/>`}<image x="${size*.08}" y="${size*.08}" width="${size*.84}" height="${size*.84}" xlink:href="${mark}"/>`);
  }
  const appSizes = [256,192,144,96,72,48,36,24,16];
  for (const size of appSizes) {
    await record(`android/branding/exports/app/light/${size}.png`, await sharp(tile(size, 'light')).png(pngOptions).toBuffer());
    await record(`android/branding/exports/transparent/${size}.png`, await sharp(master).resize(size,size).png(pngOptions).toBuffer());
  }
  for (const variant of ['light','dark','circle','rounded','purple','gradient']) {
    await record(`android/branding/exports/styles/${variant}-512.png`, await sharp(tile(512, variant)).png(pngOptions).toBuffer());
  }
  function frame(size, state, phase, surface = 'floating') {
    return svg(ballSvg(size, state, phase, surface, uri(pastelPng), uri(monoPng)));
  }
  for (const size of [256,128,64,48]) await record(`android/branding/exports/floating/${size}.png`, await sharp(frame(size,'idle',.4)).png(pngOptions).toBuffer());
  for (const state of ['idle','executing','running','success']) await record(`android/branding/exports/states/${state}.png`, await sharp(frame(256,state,.55)).png(pngOptions).toBuffer());
  for (const state of ['idle','running','success']) {
    const frames = [];
    for (let i = 0; i < 48; i++) frames.push(await sharp(frame(256,state,i/47,'dark')).ensureAlpha().raw().toBuffer());
    const gif = await sharp(Buffer.concat(frames), { raw: { width: 256, height: 256*48, channels: 4, pageHeight: 256 } }).gif({ loop: state === 'success' ? 1 : 0, delay: Array(48).fill(50), effort: 7 }).toBuffer();
    const metadata = await sharp(gif, { animated: true }).metadata();
    if (metadata.pages < 2 || metadata.pageHeight !== 256) throw new Error(`Animated GIF encoding failed: ${JSON.stringify({pages:metadata.pages,pageHeight:metadata.pageHeight,width:metadata.width,height:metadata.height})}`);
    if (!metadata.delay?.every(delay => delay >= 50) || metadata.delay.reduce((a,b)=>a+b,0) !== 2400) throw new Error('GIF frame timing differs');
    await record(`android/branding/exports/animation/${state}.gif`, gif);
  }

  // Same panel grid and copy as the supplied board. <use> reuses the exact master image.
  const board = [];
  board.push(`<defs><pattern id="check" width="16" height="16" patternUnits="userSpaceOnUse"><rect width="16" height="16" fill="#f8faff"/><path d="M0 0h8v8H0zM8 8h8v8H8z" fill="#e7eaf3"/></pattern><image id="mark" width="1024" height="1024" xlink:href="${markUri}"/></defs><rect width="1536" height="1024" fill="#f5faff"/>`);
  const text = (x,y,label,size=18,color='#111827',weight=400,anchor='start') => board.push(`<text x="${x}" y="${y}" font-family="Microsoft YaHei, Noto Sans CJK SC, sans-serif" font-size="${size}" font-weight="${weight}" fill="${color}" text-anchor="${anchor}">${label}</text>`);
  const panel = (x,y,w,h,dark=false,checker=false) => board.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="12" fill="${checker?'url(#check)':dark?'#0d1627':'#f9fcff'}" stroke="${dark?'#17213a':'#e5eef7'}"/>`);
  const title = (x,y,label,dark=false) => { board.push(`<path d="M${x} ${y-18}v20" stroke="#7123ff" stroke-width="4"/>`); text(x+14,y,label,22,dark?'#ffffff':'#0b0d17',700); };
  const pic = (x,y,w,png) => board.push(`<image x="${x}" y="${y}" width="${w}" height="${w}" xlink:href="${uri(png)}"/>`);
  const logo = (x,y,w) => board.push(`<use xlink:href="#mark" transform="translate(${x} ${y}) scale(${w/1024})"/>`);
  panel(7,0,440,515,false,true); title(18,36,'主图标（透明背景）'); text(33,65,'1024×1024 PNG（高清重建，完全一致）'); logo(12,79,425);
  panel(457,0,652,299); title(474,36,'应用图标（Android 自适应图标）');
  const labels=['浅色背景','深色背景','圆形图标','圆角矩形'];
  for (let i=0;i<4;i++){const x=475+i*160;pic(x,71,148,await sharp(tile(148,['light','dark','circle','rounded'][i])).png().toBuffer());text(x+74,257,labels[i],18,'#0d1735',400,'middle');text(x+74,280,'512×512',16,'#0d1735',400,'middle');}
  panel(1116,0,412,299); title(1122,36,'悬浮球图标（用于悬浮窗 / 快捷执行）');
  let fx=1127;for(const [i,w] of [170,108,64,48].entries()){pic(fx,232-w,w,await sharp(frame(w,'idle',.3)).png().toBuffer());text(fx+w/2,260,[256,128,64,48][i]+'×'+[256,128,64,48][i],16,'#0d1735',400,'middle');fx+=w+5;}
  panel(457,307,652,208); title(474,342,'不同尺寸（应用图标标准尺寸）');
  for(const [i,w] of [98,84,72,60,50,42,34,27,20].entries()){const center=[524,626,716,794,861,919,975,1028,1080][i];pic(center-w/2,461-w,w,await sharp(tile(w,'light')).png().toBuffer());text(center,494,appSizes[i]+'×'+appSizes[i],14,'#0d1735',400,'middle');}
  panel(1116,307,412,208); title(1128,342,'应用名称组合（桌面显示效果）'); pic(1142,363,128,await sharp(tile(128,'light')).png().toBuffer());text(1290,433,'智 构',52,'#0a0f1d',700);text(1294,478,'让 自 动 化 更 简 单',20,'#26335c');
  panel(15,523,794,228,true);title(28,557,'深浅色背景版本',true);
  for(const [i,v] of ['light','dark','purple','gradient','monochrome','outline'].entries()){const x=35+i*129;let bytes;if(v==='outline')bytes=await sharp(tile(112,'light',uri(linePng))).png().toBuffer();else if(v==='monochrome')bytes=await sharp(tile(112,'light')).grayscale().png().toBuffer();else bytes=await sharp(tile(112,v)).png().toBuffer();pic(x,578,112,bytes);text(x+56,726,['浅色背景','深色背景','纯色背景','渐变背景','单色版本','线性版本'][i],16,'#ffffff',400,'middle');}
  panel(818,523,705,228,true);text(836,557,'动态效果（逐帧展示）',23,'#ffffff',700);
  for(const [i,s] of ['idle','executing','running','success'].entries()){const x=845+i*170;pic(x,571,124,await sharp(frame(124,s,.55,'dark')).png().toBuffer());text(x+62,715,['待机状态','执行状态','运行中','任务完成'][i],18,'#fff',400,'middle');text(x+62,738,['柔和呼吸','光带流动','动态旋转光效','亮起反馈'][i],16,'#fff',400,'middle');if(i<3)text(x+144,644,'→',25,'#fff');}
  title(25,793,'动画资源（GIF 预览）');
  for(const [i,s] of ['idle','running','success'].entries()){const x=[21,510,1039][i];panel(x,805,[475,515,476][i],184,true);for(let j=0;j<4;j++)pic(x+18+j*(i===1?126:115),823,92,await sharp(frame(92,s,j/3,'dark')).png().toBuffer());text(x+(i===1?257:237),950,['待机动画（idle）','执行动画（running）','完成动画（success）'][i],21,'#fff',700,'middle');text(x+(i===1?257:237),976,['轻微呼吸，柔和光效','流光流动，速度感','亮起反馈，放大光环'][i],17,'#fff',400,'middle');}
  await record('android/branding/exports/zhigou-resource-board.png', await sharp(svg(`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1536" height="1024">${board.join('')}</svg>`)).png(pngOptions).toBuffer());
  fs.writeFileSync(manifestPath, JSON.stringify({ formatVersion:1, sourceSha256:sha(original), embeddedPngSha256:sha(sourcePng), sourceSize:[info.width,info.height], masterSize:[1024,1024], matteOnlyPixels:changed, logoGeometry:'unchanged source coordinates and aspect ratio; no tracing', appSizes, floatingSizes:[256,128,64,48], files },null,2)+'\n');
  console.log(`Generated ${files.length} resources from the exact supplied PNG; animation GIFs verified.`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
