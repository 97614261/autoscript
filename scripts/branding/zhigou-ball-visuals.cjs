/* Resource/GIF previews of the native ball. The logo remains a single raster <image>. */
const point = (s, radius, angle) => {
  const radians = angle * Math.PI / 180;
  return [s / 2 + s * radius * Math.cos(radians), s / 2 + s * radius * Math.sin(radians)];
};
const arc = (s, radius, start, sweep) => {
  const a = point(s, radius, start), b = point(s, radius, start + sweep);
  return `M${a[0]},${a[1]}A${s*radius},${s*radius} 0 ${Math.abs(sweep)>180?1:0} ${sweep>=0?1:0} ${b[0]},${b[1]}`;
};
module.exports = function ball(s, state, phase, surface, logoUri, maskUri) {
  const p = Math.max(0, Math.min(1, phase));
  const pulse = .5 - .5 * Math.cos(p * 2 * Math.PI);
  const active = state === 'running' || state === 'executing';
  const haloOpacity = ((active ? 200 : 155) + 45 * pulse) / 255;
  const mainAngle = p * 360 + 22 * Math.sin(p * 2 * Math.PI) - 90;
  const counterAngle = -p * 360 + 14 * Math.sin(p * 4 * Math.PI) + 75;
  function comet(radius, angle, sweep, color, intensity) {
    let svg = '';
    for (let i = 0; i < 8; i++) {
      const t = (i + 1) / 8;
      svg += `<path d="${arc(s,radius,angle-sweep+sweep*i/8,sweep/8)}" fill="none" stroke="${color}" stroke-width="${s*(.004+.008*t)}" stroke-opacity="${t*t*235/255*intensity}" stroke-linecap="round"/>`;
    }
    const [x,y] = point(s,radius,angle);
    return svg + `<circle cx="${x}" cy="${y}" r="${s*.042}" fill="url(#head)" opacity="${intensity}"/><circle cx="${x}" cy="${y}" r="${s*.012}" fill="white" opacity="${intensity}"/>`;
  }
  const backdrop = surface === 'floating' ? '' : `<rect width="${s}" height="${s}" rx="${s*.1}" fill="#0d1424"/>`;
  const motion = active ? comet(.455,mainAngle,46+24*pulse,'#ffc5ff',1) +
    (state === 'running' ? comet(.436,counterAngle,-32,'#d9c9ff',.65) : '') : state === 'success' ?
      `<circle cx="${s/2}" cy="${s/2}" r="${s*(.39+.09*p)}" fill="none" stroke="white" stroke-opacity="${(1-p)*220/255}" stroke-width="${s*.011}"/>` : '';
  const sweep = active ? `<g mask="url(#logoMask)"><rect x="${s*(-.35+1.7*p)}" y="0" width="${s*.25}" height="${s}" fill="url(#shine)"/></g>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${s}" height="${s}" viewBox="0 0 ${s} ${s}">
    <defs>
      <radialGradient id="orb" gradientUnits="userSpaceOnUse" cx="${s*.38}" cy="${s*.34}" r="${s*.72}"><stop stop-color="#ce4bff"/><stop offset=".57" stop-color="#830dfa"/><stop offset="1" stop-color="#3911da"/></radialGradient>
      <radialGradient id="halo"><stop stop-color="#9b28ff" stop-opacity="0"/><stop offset=".66" stop-color="#9b28ff" stop-opacity="0"/><stop offset=".78" stop-color="#9b28ff" stop-opacity=".56"/><stop offset=".85" stop-color="#f68cff" stop-opacity=".8"/><stop offset="1" stop-color="#f68cff" stop-opacity="0"/></radialGradient>
      <radialGradient id="head"><stop stop-color="white"/><stop offset=".24" stop-color="#e794ff" stop-opacity=".65"/><stop offset="1" stop-color="#e794ff" stop-opacity="0"/></radialGradient>
      <linearGradient id="shine"><stop stop-color="white" stop-opacity="0"/><stop offset=".5" stop-color="white" stop-opacity=".5"/><stop offset="1" stop-color="white" stop-opacity="0"/></linearGradient>
      <mask id="logoMask"><image x="${s*.13}" y="${s*.13}" width="${s*.74}" height="${s*.74}" xlink:href="${maskUri}"/></mask>
    </defs>
    ${backdrop}
    <circle cx="${s/2}" cy="${s/2}" r="${s*.5}" fill="url(#halo)" opacity="${haloOpacity}"/>
    <circle cx="${s/2}" cy="${s/2}" r="${s*.418}" fill="url(#orb)"/>
    <circle cx="${s/2}" cy="${s/2}" r="${s*.42}" fill="none" stroke="#ffe1ff" stroke-opacity=".96" stroke-width="${s*.011}"/>
    ${motion}
    <image x="${s*.13}" y="${s*.13}" width="${s*.74}" height="${s*.74}" xlink:href="${logoUri}"/>
    ${sweep}
  </svg>`;
};
