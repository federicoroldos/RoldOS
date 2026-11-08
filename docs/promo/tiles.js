// RoldOS system tiles for the promo images. Icon geometry mirrors TileArt.drawIcon
// (48-unit box), so the images match what the projector actually shows.
(function () {
  function gear() {
    const cx = 24, cy = 24, body = 14.4, tooth = 21.1, n = 16; let d = '';
    for (let i = 0; i < n; i++) {
      const a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n, r = i % 2 === 0 ? tooth : body;
      d += (i === 0 ? 'M' : 'L') + (cx + Math.cos(a0) * r).toFixed(1) + ' ' + (cy + Math.sin(a0) * r).toFixed(1)
        + 'L' + (cx + Math.cos(a1) * r).toFixed(1) + ' ' + (cy + Math.sin(a1) * r).toFixed(1);
    }
    return `<path class="ln" d="${d}Z"/><circle class="ln" cx="24" cy="24" r="5.8"/>`;
  }
  const ICONS = {
    cal: `<path class="ln" d="M14.4 13.4H33.6L38.4 34.6H9.6Z"/>
          <path class="ln" d="M1.9 10.5V1.9H10.5M37.5 1.9H46.1V10.5M1.9 37.5V46.1H10.5M37.5 46.1H46.1V37.5"/>`,
    set: gear(),
    hdmi: `<path class="ln" d="M2.9 13.4H45.1V25L38.4 34.6H9.6L2.9 25Z"/><rect class="so" x="11.5" y="20.2" width="25" height="5.8" rx="1.4"/>`,
    wl: `<rect class="ln" x="2.9" y="14.4" width="31.7" height="23" rx="2.9"/>
         <path class="ln" d="M18.7 37.4V43.2M12 44.2H25.4M34.6 8.2A6.2 6.2 0 0 1 40.8 14.4M34.6 1.9A12.5 12.5 0 0 1 47.1 14.4"/>
         <circle class="so" cx="34.6" cy="14.4" r="1.9"/>`,
    wd: `<rect class="ln" x="16.3" y="4.8" width="29.8" height="21.1" rx="2.9"/>
         <rect class="ln" x="1.9" y="14.4" width="9.6" height="19.2" rx="2.4"/>
         <path class="ln" d="M6.7 33.6C6.7 45.6 31.2 45.6 31.2 25.9"/>`,
    home: `<path class="ln" d="M6.7 21.1V40.3H27.8V21.1M2.9 24L17.3 10.6L31.7 24M14.4 40.3V30.7H20.2V40.3"/>
           <path class="ln" d="M29.8 12.95A8.15 8.15 0 1 1 39.4 21M29.8 12.95L26.9 9.1M29.8 12.95L33.2 9.6"/>`,
  };
  const LABELS = { cal: 'Calibration', set: 'Settings', hdmi: 'HDMI', wl: 'Wireless Cast', wd: 'Wired Cast', home: 'Original launcher' };
  window.roldosTile = function (k, focus) {
    const svg = cls => `<svg viewBox="0 0 48 48" class="${cls}">${ICONS[k]}</svg>`;
    return `<div class="tile k-${k}${focus ? ' focus' : ''}">${svg('echo')}${svg('ico')}<span>${LABELS[k]}</span></div>`;
  };
  window.ROLDOS_ORDER = ['cal', 'set', 'hdmi', 'wl', 'wd', 'home'];
})();
