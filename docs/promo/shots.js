// Crops of real projector screenshots (docs/promo/shots/, 1920x1080, taken 2026-11-09).
// shotCrop('shots/home.png', x, y, w, h, outW, {radius, hideWifi}) -> HTML for that region,
// scaled to outW px wide. Coordinates are in the screenshot's own pixels.
(function () {
  // The top bar showed the Wi-Fi network's real name: it's erased from the PNGs in shots/ and
  // redrawn here as [arcs] Home. Like the app, the block is anchored right: it ends where the
  // real name ended (x 1424, 47 px before Close all apps). Measured on shots/home.png: arcs x 1084-1128,
  // y 101-135; 18 px gap; name in Unbounded Light, caps y 104-128.
  const WIFI = { x: 1070, y: 90, w: 370, h: 58, end: 1424, icon: [1084, 100, 45, 37], gap: 18, size: 34 };
  window.shotCrop = function (src, x, y, w, h, outW, opt) {
    opt = opt || {};
    const s = outW / w;
    let cover = '';
    if (opt.hideWifi) {
      const [ix, iy, iw, ih] = WIFI.icon;
      cover = `<div style="position:absolute;left:${(WIFI.x - x) * s}px;top:${(WIFI.y - y) * s}px;
        width:${WIFI.w * s}px;height:${WIFI.h * s}px;background:#141516;display:flex;align-items:center;
        justify-content:flex-end;padding-right:${(WIFI.x + WIFI.w - WIFI.end) * s}px;box-sizing:border-box">
        <div style="width:${iw * s}px;height:${ih * s}px;margin-right:${WIFI.gap * s}px;flex:none;
          background:url(${src}) ${-ix * s}px ${-iy * s}px / ${1920 * s}px ${1080 * s}px no-repeat"></div>
        <span style="font:300 ${WIFI.size * s}px/1 var(--display);color:#b8bcc2;position:relative;top:${-3.5 * s}px">Home</span>
      </div>`;
    }
    return `<div style="position:relative;overflow:hidden;width:${outW}px;height:${h * s}px;
      border-radius:${(opt.radius || 0) * s}px;flex:none">
      <img src="${src}" style="position:absolute;left:${-x * s}px;top:${-y * s}px;width:${1920 * s}px;height:${1080 * s}px">
      ${cover}</div>`;
  };
})();
