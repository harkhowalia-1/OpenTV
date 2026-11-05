/* OpenTV's live demo: OtvOS on a TV and Otv Remote on an iPhone, built in HTML so you can use them.
   Swipe or tap on the iPhone's touch surface (or use the arrow keys, Return and Esc once you've clicked the demo).
   The wallpapers are drawn live with WebGL: OtvOS's Colour Glow plus two scenes made for this page. */
(() => {
  'use strict';
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (sel, el = document) => el.querySelector(sel);
  const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };

  // ---- glyphs ---------------------------------------------------------------------------------------------
  const S = (d, vb = '0 0 24 24', extra = '') => `<svg viewBox="${vb}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" ${extra}>${d}</svg>`;
  const F = (d, vb = '0 0 24 24') => `<svg viewBox="${vb}" fill="currentColor">${d}</svg>`;
  const I = {
    toggles: F('<rect x="3" y="4.5" width="18" height="6.5" rx="3.25"/><rect x="3" y="13" width="18" height="6.5" rx="3.25" opacity=".95"/><circle cx="16.75" cy="7.75" r="2.2" fill="#2a2c30"/><circle cx="7.25" cy="16.25" r="2.2" fill="#2a2c30"/>'),
    power: S('<path d="M12 3v9"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>', '0 0 24 24', 'stroke-width="2.4"'),
    wifi: F('<path d="M12 18.5a1.9 1.9 0 1 1 0 .01z"/><path d="M5.6 12.6a9 9 0 0 1 12.8 0l-1.6 1.6a6.7 6.7 0 0 0-9.6 0z"/><path d="M2.4 9.4a13.6 13.6 0 0 1 19.2 0l-1.6 1.6a11.3 11.3 0 0 0-16 0z"/>'),
    bt: S('<path d="M7 7l10 10-5 4V3l5 4L7 17"/>', '0 0 24 24', 'stroke-width="2"'),
    mountains: F('<path d="M2 19l6.5-10 4 6 2.5-3.5L22 19z"/>'),
    timer: S('<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M10 2h4"/>'),
    vol: F('<path d="M4 9h3.5L12 5v14l-4.5-4H4z"/>') ,
    volHi: S('<path d="M4 9h3.5L12 5v14l-4.5-4H4z" fill="currentColor"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>'),
    remote: S('<rect x="8" y="2.5" width="8" height="19" rx="4"/><circle cx="12" cy="7.5" r="1.6"/><path d="M12 12.5v.01M12 15.5v.01M12 18v.01"/>'),
    airplay: S('<path d="M7 17H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/><path d="M12 15l5 6H7z" fill="currentColor"/>'),
    house: F('<path d="M12 3l9 8h-2.5v9h-5v-6h-3v6h-5v-9H3z"/>'),
    gear: F('<path d="M10.3 2h3.4l.5 2.6 1.9.8 2.2-1.5 2.4 2.4-1.5 2.2.8 1.9 2.6.5v3.4l-2.6.5-.8 1.9 1.5 2.2-2.4 2.4-2.2-1.5-1.9.8-.5 2.6h-3.4l-.5-2.6-1.9-.8-2.2 1.5-2.4-2.4 1.5-2.2-.8-1.9L2 13.7v-3.4l2.6-.5.8-1.9-1.5-2.2 2.4-2.4 2.2 1.5 1.9-.8z"/><circle cx="12" cy="12" r="3.3" fill="#26262a"/>'),
    pad: F('<path d="M7 7h10a5 5 0 0 1 4.9 6l-.9 4.2a2.4 2.4 0 0 1-4.3.9L15 16H9l-1.7 2.1a2.4 2.4 0 0 1-4.3-.9L2.1 13A5 5 0 0 1 7 7z"/><path d="M7 10v4M5 12h4" stroke="#26262a" stroke-width="1.6"/><circle cx="16" cy="11" r="1.1" fill="#26262a"/><circle cx="18" cy="13" r="1.1" fill="#26262a"/>'),
    closeapps: S('<rect x="7" y="7" width="13" height="13" rx="2.5"/><path d="M4 16V6a2 2 0 0 1 2-2h10M11 11l5 5M16 11l-5 5"/>'),
    plug: F('<path d="M8 3h8v6h1v5l-3 3v4h-4v-4l-3-3V9h1z"/>'),
    search: S('<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/>', '0 0 24 24', 'stroke-width="2.4"'),
    back: S('<path d="M15 4l-8 8 8 8"/>', '0 0 24 24', 'stroke-width="2.6"'),
    tv: S('<rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 21h8"/>', '0 0 24 24', 'stroke-width="2"'),
    playpause: F('<path d="M2 5v14l9-7z"/><rect x="13" y="5" width="3.4" height="14" rx="1"/><rect x="18.6" y="5" width="3.4" height="14" rx="1"/>'),
    mute: S('<path d="M4 9h3.5L12 5v14l-4.5-4H4z" fill="currentColor"/><path d="M3 3l18 18"/>', '0 0 24 24', 'stroke-width="2"'),
    keyboard: S('<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6 10h.01M9 10h.01M12 10h.01M15 10h.01M18 10h.01M6 13h.01M9 13h.01M12 13h.01M15 13h.01M18 13h.01M8 16h8"/>', '0 0 24 24', 'stroke-width="2"'),
    rew: F('<path d="M12 5v14L3 12zM21 5v14l-9-7z"/>'),
    fwd: F('<path d="M12 5v14l9-7zM3 5v14l9-7z"/>'),
    minus: S('<path d="M5 12h14"/>', '0 0 24 24', 'stroke-width="2.6"'),
    plus: S('<path d="M5 12h14M12 5v14"/>', '0 0 24 24', 'stroke-width="2.6"'),
    chevron: S('<path d="M6 9l6 6 6-6"/>', '0 0 24 24', 'stroke-width="3"'),
    hand: S('<path d="M9 11V5.5a1.5 1.5 0 0 1 3 0V11M12 10V4.5a1.5 1.5 0 0 1 3 0V11M15 10.5V6.5a1.5 1.5 0 0 1 3 0V14a7 7 0 0 1-7 7h-.5a6 6 0 0 1-4.7-2.3L3.5 15.6a1.6 1.6 0 0 1 2.4-2L9 15V8.5a1.5 1.5 0 0 0-3 0"/>', '0 0 24 24', 'stroke-width="1.5"'),
    cursor: S('<path d="M5 3l14 7-6 2-2 6z"/><path d="M12 2v.01M20 4v.01M3 12v.01"/>', '0 0 24 24', 'stroke-width="1.6"'),
    signal: F('<rect x="1" y="9" width="3" height="5" rx="1"/><rect x="5.5" y="6.5" width="3" height="7.5" rx="1"/><rect x="10" y="4" width="3" height="10" rx="1"/><rect x="14.5" y="1.5" width="3" height="12.5" rx="1"/>', '0 0 18 15'),
    wifiS: F('<path d="M9 12.6a1.6 1.6 0 1 1 0 .01z"/><path d="M4.2 8.2a6.8 6.8 0 0 1 9.6 0l-1.5 1.5a4.7 4.7 0 0 0-6.6 0z"/><path d="M1 5a11.3 11.3 0 0 1 16 0l-1.5 1.5a9.2 9.2 0 0 0-13 0z"/>', '0 0 18 14'),
    battery: '<svg viewBox="0 0 28 14"><rect x=".5" y=".5" width="24" height="13" rx="4" fill="none" stroke="currentColor" opacity=".4"/><rect x="2.5" y="2.5" width="20" height="9" rx="2.2" fill="currentColor"/><path d="M26 5v4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" opacity=".4"/></svg>',
  };
  // App tiles. Apps with a TV banner show it, exactly as OtvOS draws it (these are OtvOS's own vector banners:
  // launcher-source/app/src/main/res/drawable/banner_*.xml, otvlive and browser's banner.xml). Apps without one get
  // OtvOS's fallback tile: the app's icon over its name on #1C1C1E (item_app.xml).
  const BANNER = {
    iptv: `<svg viewBox="0 0 400 225" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="otvB1" x1="0" y1="0" x2="400" y2="112.5" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FF7AE6"/><stop offset="1" stop-color="#8A34FF"/></linearGradient></defs><path d="M0,0h400v225H0z" fill="url(#otvB1)"/><g transform="translate(0 -7.5)" fill="none" stroke="#fff" stroke-width="9" stroke-linecap="round"><path d="M155.6,82.7 A58,58 0 0,0 155.6,157.3" stroke-opacity=".75"/><path d="M244.4,82.7 A58,58 0 0,1 244.4,157.3" stroke-opacity=".75"/><path d="M137.2,67.3 A82,82 0 0,0 137.2,172.7" stroke-opacity=".38"/><path d="M262.8,67.3 A82,82 0 0,1 262.8,172.7" stroke-opacity=".38"/><path stroke="none" fill="#fff" fill-rule="evenodd" d="M158,120 A42,42 0 1,1 242,120 A42,42 0 1,1 158,120 Z M193.2,102.8 L218.3,117.2 Q223.1,120.0 218.3,122.8 L193.2,137.2 Q188.5,140.0 188.5,134.5 L188.5,105.5 Q188.5,100.0 193.2,102.8 Z"/></g></svg>`,
    browser: `<svg viewBox="0 0 400 225" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="otvB2" x1="0" y1="0" x2="400" y2="225" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#2FB4FF"/><stop offset="1" stop-color="#3D5CF5"/></linearGradient></defs><path d="M0,0h400v225H0z" fill="url(#otvB2)"/><g fill="none" stroke="#fff" stroke-width="9"><path d="M148,112.5 A52,52 0 1,1 252,112.5 A52,52 0 1,1 148,112.5 Z"/><path d="M200,60.5 A23.4,52 0 1,0 200,164.5 A23.4,52 0 1,0 200,60.5 Z" stroke-opacity=".85"/><path d="M148,112.5 H252" stroke-opacity=".85"/><path d="M155.3,86.5 Q200,80.3 244.7,86.5 M155.3,138.5 Q200,132.3 244.7,138.5" stroke-width="7.2" stroke-opacity=".6" stroke-linecap="round"/></g></svg>`,
    cloud: `<svg viewBox="0 0 320 180" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="otvB3" x1="0" y1="0" x2="320" y2="180" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1E9E1E"/><stop offset="1" stop-color="#0B5E0B"/></linearGradient></defs><path d="M0,0h320v180H0z" fill="url(#otvB3)"/><g transform="translate(103.9 33.9) scale(.219)"><path fill="#fff" d="M369.9,318.2c44.3,54.3 64.7,98.8 54.4,118.7 -7.9,15.1 -56.7,44.6 -92.6,55.9 -29.6,9.3 -68.4,13.3 -100.4,10.2 -38.2,-3.7 -76.9,-17.4 -110.1,-39C93.3,445.8 87,438.3 87,423.4c0,-29.9 32.9,-82.3 89.2,-142.1 32,-33.9 76.5,-73.7 81.4,-72.6 9.4,2.1 84.3,75.1 112.3,109.5zM188.6,143.8c-29.7,-26.9 -58.1,-53.9 -86.4,-63.4 -15.2,-5.1 -16.3,-4.8 -28.7,8.1 -29.2,30.4 -53.5,79.7 -60.3,122.4 -5.4,34.2 -6.1,43.8 -4.2,60.5 5.6,50.5 17.3,85.4 40.5,120.9 9.5,14.6 12.1,17.3 9.3,9.9 -4.2,-11 -0.3,-37.5 9.5,-64 14.3,-39 53.9,-112.9 120.3,-194.4zM500.2,207.3C483.3,127.3 432.7,77 425.6,77c-7.3,0 -24.2,6.5 -36,13.9 -23.3,14.5 -41,31.4 -64.3,52.8C367.7,197 427.5,283.1 448.2,346c6.8,20.7 9.7,41.1 7.4,52.3 -1.7,8.5 -1.7,8.5 1.4,4.6 6.1,-7.7 19.9,-31.3 25.4,-43.5 7.4,-16.2 15,-40.2 18.6,-58.7 4.3,-22.5 3.9,-70.8 -0.8,-93.4zM141.3,43C189,40.5 251,77.5 255.6,78.4c0.7,0.1 10.4,-4.2 21.6,-9.7 63.9,-31.1 94,-25.8 107.4,-25.2 -63.9,-39.3 -152.7,-50 -233.9,-11.7 -23.4,11.1 -24,11.9 -9.4,11.2z"/></g></svg>`,
    spotify: `<svg viewBox="0 0 320 180" preserveAspectRatio="xMidYMid slice"><path d="M0,0h320v180H0z" fill="#121212"/><g transform="translate(98.1 32.6) scale(.51)"><path fill="#1ED760" d="M125.5,3.3C65.1,0.9 14.3,47.9 11.9,108.3c-2.4,60.4 44.6,111.3 105,113.7 60.4,2.4 111.3,-44.6 113.7,-105C232.9,56.6 185.9,5.7 125.5,3.3ZM171.7,163.6c-1.4,2.4 -4,3.6 -6.6,3.2 -0.8,-0.1 -1.6,-0.4 -2.3,-0.8 -14.5,-8.2 -30.2,-13.6 -46.8,-15.9 -16.6,-2.3 -33.2,-1.5 -49.4,2.4 -3.5,0.9 -7,-1.3 -7.9,-4.8s1.3,-7 4.8,-7.9c17.8,-4.3 36.1,-5.2 54.3,-2.6 18.3,2.6 35.6,8.5 51.5,17.5 3.1,1.8 4.2,5.8 2.4,8.9h0ZM186.1,134.9c-2.2,4.1 -7.4,5.7 -11.5,3.4 -16.9,-9.1 -35.2,-15.2 -54.4,-17.9 -19.2,-2.7 -38.5,-2 -57.3,2.2 -1,0.2 -2,0.3 -3,0.1 -3.4,-0.5 -6.3,-3 -7.1,-6.6 -1,-4.6 1.9,-9.1 6.5,-10.1 20.8,-4.6 42.1,-5.4 63.3,-2.4 21.2,3 41.5,9.6 60.2,19.7 4.1,2.2 5.7,7.4 3.4,11.5h0ZM202,102.5c-2.1,4 -6.5,6.1 -10.7,5.5 -1.1,-0.2 -2.3,-0.5 -3.4,-1.1 -19.7,-10.2 -40.9,-17 -63.1,-20.1 -22.2,-3.1 -44.4,-2.4 -66.2,2 -5.7,1.2 -11.2,-2.5 -12.3,-8.2 -1.2,-5.7 2.5,-11.2 8.2,-12.3 24.1,-4.9 48.7,-5.6 73.2,-2.2 24.5,3.4 48,10.9 69.8,22.3 5.1,2.7 7.1,9 4.4,14.1h0Z"/></g></svg>`,
    store: `<svg viewBox="0 0 320 180" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="otvA1" x1="0" y1="0" x2="0" y2="180" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#2FC6FF"/><stop offset="1" stop-color="#1E62F5"/></linearGradient><linearGradient id="otvA2" x1="0" y1="54" x2="0" y2="149" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#D8ECFF"/></linearGradient></defs><path d="M0,0h320v180H0z" fill="url(#otvA1)"/><path d="M137,70v-8a23,23 0 0,1 46,0v8" fill="none" stroke="#fff" stroke-opacity=".9" stroke-width="9" stroke-linecap="round"/><path d="M116,68h88a6,6 0 0,1 6,6l-5,66a10,10 0 0,1 -10,9h-70a10,10 0 0,1 -10,-9l-5,-66a6,6 0 0,1 6,-6z" fill="url(#otvA2)"/><g fill="#2A7CFF"><path d="M144.5,85h8a5.5,5.5 0 0,1 5.5,5.5v8a5.5,5.5 0 0,1 -5.5,5.5h-8a5.5,5.5 0 0,1 -5.5,-5.5v-8a5.5,5.5 0 0,1 5.5,-5.5z"/><path d="M167.5,85h8a5.5,5.5 0 0,1 5.5,5.5v8a5.5,5.5 0 0,1 -5.5,5.5h-8a5.5,5.5 0 0,1 -5.5,-5.5v-8a5.5,5.5 0 0,1 5.5,-5.5z" fill-opacity=".55"/><path d="M144.5,108h8a5.5,5.5 0 0,1 5.5,5.5v8a5.5,5.5 0 0,1 -5.5,5.5h-8a5.5,5.5 0 0,1 -5.5,-5.5v-8a5.5,5.5 0 0,1 5.5,-5.5z" fill-opacity=".55"/></g><path d="M171.5,111.5v12M165.5,117.5h12" stroke="#2A7CFF" stroke-width="4.5" stroke-linecap="round"/></svg>`,
    settings: `<svg viewBox="0 0 320 180" preserveAspectRatio="xMidYMid slice"><defs><radialGradient id="otvS1" cx="128" cy="63" r="190" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FAFAFC"/><stop offset=".5" stop-color="#D3D3D8"/><stop offset="1" stop-color="#8F8F96"/></radialGradient><linearGradient id="otvS2" x1="0" y1="33" x2="0" y2="147" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#F6F6F8"/><stop offset="1" stop-color="#A2A2A8"/></linearGradient></defs><path d="M0,0h320v180H0z" fill="url(#otvS1)"/><path d="M92.00,90.00a68.00,68.00 0 1,1 136.00,0a68.00,68.00 0 1,1 -136.00,0Z" fill="#2C2C30"/><path d="M161.40,33.00L162.24,33.17L162.96,33.64L163.43,34.36L163.60,35.20L163.60,41.80L163.43,42.64L162.96,43.36L162.24,43.83L161.40,44.00L158.60,44.00L157.76,43.83L157.04,43.36L156.57,42.64L156.40,41.80L156.40,35.20L156.57,34.36L157.04,33.64L157.76,33.17L158.60,33.00ZM178.95,36.22L179.69,36.64L180.23,37.32L180.46,38.14L180.36,38.99L178.32,45.27L177.90,46.02L177.22,46.55L176.40,46.78L175.55,46.68L172.88,45.82L172.13,45.40L171.60,44.73L171.37,43.90L171.47,43.05L173.51,36.77L173.93,36.02L174.60,35.49L175.43,35.26L176.28,35.36ZM194.64,44.71L195.22,45.34L195.52,46.14L195.48,47.00L195.12,47.78L191.24,53.12L190.61,53.70L189.81,54.00L188.95,53.97L188.17,53.61L185.91,51.96L185.32,51.33L185.03,50.53L185.06,49.67L185.42,48.89L189.30,43.55L189.93,42.97L190.73,42.67L191.59,42.70L192.37,43.06ZM206.94,57.63L207.30,58.41L207.33,59.27L207.03,60.07L206.45,60.70L201.11,64.58L200.33,64.94L199.47,64.97L198.67,64.68L198.04,64.09L196.39,61.83L196.03,61.05L196.00,60.19L196.30,59.39L196.88,58.76L202.22,54.88L203.00,54.52L203.86,54.48L204.66,54.78L205.29,55.36ZM214.64,73.72L214.74,74.57L214.51,75.40L213.98,76.07L213.23,76.49L206.95,78.53L206.10,78.63L205.27,78.40L204.60,77.87L204.18,77.12L203.32,74.45L203.22,73.60L203.45,72.78L203.98,72.10L204.73,71.68L211.01,69.64L211.86,69.54L212.68,69.77L213.36,70.31L213.78,71.05ZM217.00,91.40L216.83,92.24L216.36,92.96L215.64,93.43L214.80,93.60L208.20,93.60L207.36,93.43L206.64,92.96L206.17,92.24L206.00,91.40L206.00,88.60L206.17,87.76L206.64,87.04L207.36,86.57L208.20,86.40L214.80,86.40L215.64,86.57L216.36,87.04L216.83,87.76L217.00,88.60ZM213.78,108.95L213.36,109.69L212.68,110.23L211.86,110.46L211.01,110.36L204.73,108.32L203.98,107.90L203.45,107.22L203.22,106.40L203.32,105.55L204.18,102.88L204.60,102.13L205.27,101.60L206.10,101.37L206.95,101.47L213.23,103.51L213.98,103.93L214.51,104.60L214.74,105.43L214.64,106.28ZM205.29,124.64L204.66,125.22L203.86,125.52L203.00,125.48L202.22,125.12L196.88,121.24L196.30,120.61L196.00,119.81L196.03,118.95L196.39,118.17L198.04,115.91L198.67,115.32L199.47,115.03L200.33,115.06L201.11,115.42L206.45,119.30L207.03,119.93L207.33,120.73L207.30,121.59L206.94,122.37ZM192.37,136.94L191.59,137.30L190.73,137.33L189.93,137.03L189.30,136.45L185.42,131.11L185.06,130.33L185.03,129.47L185.32,128.67L185.91,128.04L188.17,126.39L188.95,126.03L189.81,126.00L190.61,126.30L191.24,126.88L195.12,132.22L195.48,133.00L195.52,133.86L195.22,134.66L194.64,135.29ZM176.28,144.64L175.43,144.74L174.60,144.51L173.93,143.98L173.51,143.23L171.47,136.95L171.37,136.10L171.60,135.27L172.13,134.60L172.88,134.18L175.55,133.32L176.40,133.22L177.22,133.45L177.90,133.98L178.32,134.73L180.36,141.01L180.46,141.86L180.23,142.68L179.69,143.36L178.95,143.78ZM158.60,147.00L157.76,146.83L157.04,146.36L156.57,145.64L156.40,144.80L156.40,138.20L156.57,137.36L157.04,136.64L157.76,136.17L158.60,136.00L161.40,136.00L162.24,136.17L162.96,136.64L163.43,137.36L163.60,138.20L163.60,144.80L163.43,145.64L162.96,146.36L162.24,146.83L161.40,147.00ZM141.05,143.78L140.31,143.36L139.77,142.68L139.54,141.86L139.64,141.01L141.68,134.73L142.10,133.98L142.78,133.45L143.60,133.22L144.45,133.32L147.12,134.18L147.87,134.60L148.40,135.27L148.63,136.10L148.53,136.95L146.49,143.23L146.07,143.98L145.40,144.51L144.57,144.74L143.72,144.64ZM125.36,135.29L124.78,134.66L124.48,133.86L124.52,133.00L124.88,132.22L128.76,126.88L129.39,126.30L130.19,126.00L131.05,126.03L131.83,126.39L134.09,128.04L134.68,128.67L134.97,129.47L134.94,130.33L134.58,131.11L130.70,136.45L130.07,137.03L129.27,137.33L128.41,137.30L127.63,136.94ZM113.06,122.37L112.70,121.59L112.67,120.73L112.97,119.93L113.55,119.30L118.89,115.42L119.67,115.06L120.53,115.03L121.33,115.32L121.96,115.91L123.61,118.17L123.97,118.95L124.00,119.81L123.70,120.61L123.12,121.24L117.78,125.12L117.00,125.48L116.14,125.52L115.34,125.22L114.71,124.64ZM105.36,106.28L105.26,105.43L105.49,104.60L106.02,103.93L106.77,103.51L113.05,101.47L113.90,101.37L114.73,101.60L115.40,102.13L115.82,102.88L116.68,105.55L116.78,106.40L116.55,107.22L116.02,107.90L115.27,108.32L108.99,110.36L108.14,110.46L107.32,110.23L106.64,109.69L106.22,108.95ZM103.00,88.60L103.17,87.76L103.64,87.04L104.36,86.57L105.20,86.40L111.80,86.40L112.64,86.57L113.36,87.04L113.83,87.76L114.00,88.60L114.00,91.40L113.83,92.24L113.36,92.96L112.64,93.43L111.80,93.60L105.20,93.60L104.36,93.43L103.64,92.96L103.17,92.24L103.00,91.40ZM106.22,71.05L106.64,70.31L107.32,69.77L108.14,69.54L108.99,69.64L115.27,71.68L116.02,72.10L116.55,72.78L116.78,73.60L116.68,74.45L115.82,77.12L115.40,77.87L114.73,78.40L113.90,78.63L113.05,78.53L106.77,76.49L106.02,76.07L105.49,75.40L105.26,74.57L105.36,73.72ZM114.71,55.36L115.34,54.78L116.14,54.48L117.00,54.52L117.78,54.88L123.12,58.76L123.70,59.39L124.00,60.19L123.97,61.05L123.61,61.83L121.96,64.09L121.33,64.68L120.53,64.97L119.67,64.94L118.89,64.58L113.55,60.70L112.97,60.07L112.67,59.27L112.70,58.41L113.06,57.63ZM127.63,43.06L128.41,42.70L129.27,42.67L130.07,42.97L130.70,43.55L134.58,48.89L134.94,49.67L134.97,50.53L134.68,51.33L134.09,51.96L131.83,53.61L131.05,53.97L130.19,54.00L129.39,53.70L128.76,53.12L124.88,47.78L124.52,47.00L124.48,46.14L124.78,45.34L125.36,44.71ZM143.72,35.36L144.57,35.26L145.40,35.49L146.07,36.02L146.49,36.77L148.53,43.05L148.63,43.90L148.40,44.73L147.87,45.40L147.12,45.82L144.45,46.68L143.60,46.78L142.78,46.55L142.10,46.02L141.68,45.27L139.64,38.99L139.54,38.14L139.77,37.32L140.31,36.64L141.05,36.22ZM111.00,90.00a49.00,49.00 0 1,1 98.00,0a49.00,49.00 0 1,1 -98.00,0ZM120.00,90.00a40.00,40.00 0 1,0 80.00,0a40.00,40.00 0 1,0 -80.00,0ZM139.92,54.83L141.03,54.45L142.20,54.53L143.25,55.04L144.02,55.92L156.02,76.71L156.40,77.82L156.32,78.99L155.80,80.03L154.92,80.81L154.58,81.01L153.47,81.38L152.30,81.31L151.25,80.79L150.48,79.91L138.48,59.12L138.10,58.02L138.18,56.85L138.70,55.80L139.58,55.03ZM200.50,90.20L200.27,91.35L199.62,92.32L198.65,92.97L197.50,93.20L173.50,93.20L172.35,92.97L171.38,92.32L170.73,91.35L170.50,90.20L170.50,89.80L170.73,88.65L171.38,87.68L172.35,87.03L173.50,86.80L197.50,86.80L198.65,87.03L199.62,87.68L200.27,88.65L200.50,89.80ZM139.58,124.97L138.70,124.20L138.18,123.15L138.10,121.98L138.48,120.88L150.48,100.09L151.25,99.21L152.30,98.69L153.47,98.62L154.58,98.99L154.92,99.19L155.80,99.97L156.32,101.01L156.40,102.18L156.02,103.29L144.02,124.08L143.25,124.96L142.20,125.47L141.03,125.55L139.92,125.17ZM147.00,90.00a13.00,13.00 0 1,1 26.00,0a13.00,13.00 0 1,1 -26.00,0ZM153.50,90.00a6.50,6.50 0 1,0 13.00,0a6.50,6.50 0 1,0 -13.00,0Z" fill="url(#otvS2)"/></svg>`,
  };

  // The dock, as OtvOS ships it with OpenTV's apps; `tint` is the colour the Colour Glow takes from each tile.
  const APPS = [
    { id: 'iptv', name: 'IPTV', banner: true, tint: [0.85, 0.33, 1.0], app: 'images/v2/app-iptv.jpg' },
    { id: 'browser', name: 'Browser', banner: true, tint: [0.2, 0.55, 1.0], app: 'images/v2/app-browser.jpg' },
    { id: 'cloud', name: 'Cloud Gaming', banner: true, tint: [0.12, 0.62, 0.12], app: 'images/v2/app-cloud.jpg' },
    { id: 'spotify', name: 'Spotify', banner: true, tint: [0.12, 0.84, 0.38] },
    { id: 'store', name: 'App Store', banner: true, tint: [0.2, 0.5, 1.0] },
    { id: 'settings', name: 'Settings', banner: true, tint: [0.55, 0.56, 0.62], app: 'images/v2/app-wallsettings.jpg' },
  ];
  const WALLS = [
    { name: 'Colour Glow', thumb: 'radial-gradient(circle at 30% 70%, #e0457b 0%, #5a1838 38%, #0a0507 75%)' },
    { name: 'Underwater', thumb: 'linear-gradient(180deg, #3f8fa4 0%, #0e3a52 55%, #04121f 100%)' },
    { name: 'Aurora', thumb: 'radial-gradient(ellipse at 50% 55%, rgba(60,230,150,.75), rgba(130,80,230,.35) 45%, transparent 70%), linear-gradient(#020612, #0a1430)' },
  ];

  // ---- live wallpapers (WebGL) ----------------------------------------------------------------------------
  const FRAG = `
precision mediump float;
uniform vec2 r; uniform float t; uniform float modeA; uniform float modeB; uniform float mixAB; uniform vec3 tint;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p){ float v = 0.0, a = 0.5; for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; } return v; }
vec3 glow(vec2 uv, vec2 p){
  float ar = r.x / r.y;
  vec2 c1 = vec2(0.32 * ar + 0.07 * sin(t * 0.11), 0.30 + 0.06 * cos(t * 0.13));
  vec2 c2 = vec2(0.86 * ar + 0.08 * cos(t * 0.07), 0.80 + 0.05 * sin(t * 0.09));
  float g = exp(-pow(length(p - c1) * 1.55, 2.0)) * 0.95 + exp(-pow(length(p - c2) * 2.1, 2.0)) * 0.42;
  vec3 col = tint * g * 0.85;
  col += tint * 0.06 * fbm(p * 2.5 + t * 0.03);
  return col + vec3(0.012);
}
vec3 ocean(vec2 uv, vec2 p){
  vec3 col = mix(vec3(0.004, 0.03, 0.07), vec3(0.06, 0.33, 0.43), pow(uv.y, 1.7));
  vec2 src = vec2(0.7 * r.x / r.y, 1.7);
  vec2 d = p - src; float ang = atan(d.x, -d.y);
  float rays = (0.5 + 0.5 * sin(ang * 23.0 + t * 0.17)) * (0.5 + 0.5 * sin(ang * 41.0 - t * 0.11 + 2.0));
  rays = pow(rays, 1.6);
  col += vec3(0.36, 0.64, 0.68) * rays * pow(uv.y, 1.6) * 0.5;
  vec2 q = p * 7.0 + vec2(t * 0.07, t * 0.045);
  float c = abs(sin(q.x + 2.0 * sin(q.y * 0.7 + t * 0.3))) * abs(sin(q.y + 2.0 * sin(q.x * 0.8 - t * 0.25)));
  c = pow(1.0 - c, 16.0);
  col += vec3(0.45, 0.8, 0.85) * c * smoothstep(0.55, 1.0, uv.y) * 0.3;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    vec2 q = p * (5.0 + fi * 3.5) + vec2(sin(t * 0.1 + fi) * 0.4, -t * (0.05 + 0.03 * fi));
    vec2 id = floor(q); vec2 f = fract(q) - 0.5;
    float k = hash(id + fi * 13.1);
    if (k > 0.9) {
      vec2 o = vec2(hash(id + 3.7), hash(id + 9.1)) - 0.5;
      float d = length(f - o * 0.6);
      col += vec3(0.55, 0.78, 0.82) * smoothstep(0.11 - fi * 0.02, 0.0, d) * (0.10 + 0.08 * fi);
    }
  }
  return col;
}
vec3 aurora(vec2 uv, vec2 p){
  vec3 col = mix(vec3(0.004, 0.012, 0.04), vec3(0.02, 0.05, 0.13), uv.y);
  vec2 s = floor(p * 170.0); float st = hash(s);
  col += step(0.9965, st) * vec3(0.85) * (0.55 + 0.45 * sin(t * 1.7 + st * 60.0));
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float y = 0.52 + 0.11 * fi + 0.07 * sin(p.x * 1.6 + t * 0.09 + fi * 2.0) + 0.06 * (fbm(vec2(p.x * 1.8 + t * 0.04, fi * 3.0)) - 0.5);
    float band = exp(-pow((uv.y - y) * (6.5 + fi), 2.0));
    float above = smoothstep(y - 0.02, y + 0.22, uv.y) * smoothstep(y + 0.45, y + 0.05, uv.y);
    float streak = 0.55 + 0.45 * fbm(vec2(p.x * 14.0 + t * 0.25 + fi * 7.0, uv.y * 1.5));
    vec3 c = mix(vec3(0.12, 0.95, 0.58), vec3(0.58, 0.32, 0.98), fi / 2.0);
    col += c * (band * 0.75 + above * 0.18) * streak * 0.6;
  }
  float m = 0.16 + 0.07 * fbm(vec2(p.x * 1.4, 2.0)) + 0.03 * sin(p.x * 3.0);
  col = mix(col, vec3(0.004, 0.008, 0.016), smoothstep(m + 0.004, m - 0.004, uv.y));
  return col;
}
vec3 scene(float m, vec2 uv, vec2 p){
  if (m < 0.5) return glow(uv, p);
  if (m < 1.5) return ocean(uv, p);
  return aurora(uv, p);
}
void main(){
  vec2 uv = gl_FragCoord.xy / r;
  vec2 p = vec2(uv.x * r.x / r.y, uv.y);
  vec3 col = scene(modeA, uv, p);
  if (mixAB > 0.001) col = mix(col, scene(modeB, uv, p), mixAB);
  col *= 1.0 - 0.35 * pow(length(uv - 0.5) * 1.25, 2.4);
  col += (hash(gl_FragCoord.xy + t) - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}`;

  class Wall {
    constructor(canvas, mode) {
      this.canvas = canvas; this.mode = mode; this.from = mode; this.mixAB = 0; this.visible = false; this.t0 = performance.now() - 20000;
      this.tint = [1, 0.36, 0.6]; this.target = this.tint.slice();
      const gl = canvas.getContext('webgl', { antialias: false, alpha: false, powerPreference: 'low-power' });
      if (!gl) { this.fallback(); return; }
      const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return gl.getShaderParameter(s, gl.COMPILE_STATUS) ? s : null; };
      const vs = sh(gl.VERTEX_SHADER, 'attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }');
      const fs = sh(gl.FRAGMENT_SHADER, FRAG);
      if (!vs || !fs) { this.fallback(); return; }
      const pr = gl.createProgram(); gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr); gl.useProgram(pr);
      const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      const a = gl.getAttribLocation(pr, 'a'); gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a, 2, gl.FLOAT, false, 0, 0);
      this.gl = gl; this.u = {}; for (const n of ['r', 't', 'modeA', 'modeB', 'mixAB', 'tint']) this.u[n] = gl.getUniformLocation(pr, n);
      new ResizeObserver(() => this.resize()).observe(canvas);
      new IntersectionObserver(([e]) => { this.visible = e.isIntersecting; if (this.visible) this.loop(); }, { rootMargin: '100px' }).observe(canvas);
      this.resize();
    }
    fallback() { this.canvas.style.background = WALLS[this.mode].thumb; this.gl = null; }
    resize() {
      if (!this.gl) return;
      const w = Math.max(64, Math.round(this.canvas.clientWidth * Math.min(devicePixelRatio, 2) * 0.5));
      this.canvas.width = w; this.canvas.height = Math.round(w * 9 / 16);
      this.draw(performance.now());
    }
    set(mode) {
      if (mode === this.mode) return;
      if (!this.gl) { this.mode = mode; this.fallback(); return; }
      this.from = this.mode; this.mode = mode; this.mixStart = performance.now(); this.mixAB = 0;
      if (reduce) { this.from = mode; this.mixAB = 0; this.draw(performance.now()); }
    }
    setTint(c) { this.target = c.slice(); if (reduce) { this.tint = c.slice(); this.draw(performance.now()); } }
    draw(now) {
      const gl = this.gl; if (!gl) return;
      if (this.mixStart) { this.mixAB = Math.min(1, (now - this.mixStart) / 1200); if (this.mixAB >= 1) { this.from = this.mode; this.mixAB = 0; this.mixStart = 0; } }
      for (let i = 0; i < 3; i++) this.tint[i] += (this.target[i] - this.tint[i]) * 0.06;
      gl.viewport(0, 0, this.canvas.width, this.canvas.height);
      gl.uniform2f(this.u.r, this.canvas.width, this.canvas.height);
      gl.uniform1f(this.u.t, reduce ? 30 : (now - this.t0) / 1000);
      gl.uniform1f(this.u.modeA, this.mixAB ? this.from : this.mode);
      gl.uniform1f(this.u.modeB, this.mode);
      gl.uniform1f(this.u.mixAB, this.mixAB ? this.mixAB : 0);
      gl.uniform3fv(this.u.tint, this.tint);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    loop() {
      if (reduce || this.running) return;
      this.running = true;
      let last = 0;
      const step = (now) => {
        if (!this.visible) { this.running = false; return; }
        if (now - last > 33) { last = now; this.draw(now); }   // about 30 frames a second is plenty for slow scenes
        requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    }
  }

  // ---- the TV ---------------------------------------------------------------------------------------------
  const tile = (a) => `<div class="otv-tile" aria-label="${a.name}"><div class="otv-card">${BANNER[a.id]}<i class="otv-sheen"></i></div></div>`;
  const CC = [
    { id: 'power', cls: 'big', html: `${I.power}<span>Power Off</span>`, label: 'Power Off' },
    { id: 'wifi', cls: 'wide blue', html: `${I.wifi}<div><b>Wi-Fi</b><small>Home Wi-Fi</small></div>` },
    { id: 'bt', cls: 'wide blue', html: `${I.bt}<div><b>Bluetooth</b><small>On</small></div>` },
    { id: 'saver', cls: 'wide', html: `${I.mountains}<div><b>Screen Saver</b><small>Aerials</small></div>` },
    { id: 'sleep', cls: 'wide', html: `${I.timer}<div><b>Sleep Timer</b><small>Off</small></div>` },
    { id: 'vol', cls: 'full', html: `${I.volHi}<span class="otv-vol"><i></i></span>${I.vol}` },
    { id: 'remote', cls: 'sq', html: I.remote, label: 'Pair Remote' },
    { id: 'airplay', cls: 'sq', html: I.airplay, label: 'AirPlay' },
    { id: 'home', cls: 'sq', html: I.house, label: 'Apple Home' },
    { id: 'settings', cls: 'sq', html: I.gear, label: 'Settings' },
    { id: 'game', cls: 'sq', html: I.pad, label: 'Game Mode' },
    { id: 'close', cls: 'sq', html: I.closeapps, label: 'Close Background Apps' },
    { id: 'acc', cls: 'sq', html: I.plug, label: 'Game Controllers' },
    { id: 'search', cls: 'sq', html: I.search, label: 'Search' },
  ];

  class TV {
    constructor(host, opts = {}) {
      this.opts = opts;
      host.classList.add('otv');
      host.innerHTML = `<div class="otv-frame"><i class="otv-led"></i><div class="otv-bezel"><div class="otv-screen" role="img" aria-roledescription="interactive demo" aria-label="OtvOS home screen">
        <canvas class="otv-wall"></canvas>
        <div class="otv-home">
          <div class="otv-pill"><span class="otv-clock"></span><span class="otv-ccbtn">${I.toggles}</span><span class="otv-avatar">A</span></div>
          <div class="otv-title" aria-live="polite"></div>
          <div class="otv-rows">
            <div class="otv-dockbg"></div>
            <div class="otv-glass"></div>
            <div class="otv-dock"><div class="otv-row">${APPS.map(tile).join('')}</div></div>
          </div>
        </div>
        <div class="otv-cc">${CC.map(c => `<div class="${c.cls}" data-id="${c.id}">${c.html}</div>`).join('')}</div>
        <div class="otv-cclabel"></div>
        <div class="otv-app"><img alt=""></div>
        <div class="otv-saverclock"></div><div class="otv-saverplace">Northern lights, over the mountains</div>
        <div class="otv-search"><div class="bar">${I.search}<span class="q"></span><i></i></div><div class="hits"></div></div>
        <div class="otv-hud"></div>
        <div class="otv-off"><span>Off. Tap the TV, or ⏻ on the iPhone, to turn it on.</span></div>
      </div></div></div>`;
      this.el = host;
      this.screen = $('.otv-screen', host);
      this.wall = new Wall($('.otv-wall', host), opts.wall ?? 1);
      this.rows = [[...host.querySelectorAll('.otv-dock .otv-tile')]];
      this.pillItems = [$('.otv-ccbtn', host), $('.otv-avatar', host)];
      this.ccItems = [...host.querySelectorAll('.otv-cc > div')];
      this.title = $('.otv-title', host);
      this.scope = 'home'; this.row = 0; this.col = 0; this.ccIndex = 0; this.volume = 60; this.muted = false;
      this.state = { wifi: true, bt: true, sleep: 0 };
      const tick = () => { const s = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); $('.otv-clock', host).textContent = s.replace(/\s/g, ''); $('.otv-saverclock', host).textContent = s.replace(/\s?[AaPp]\.?[Mm]\.?/, ''); };
      tick(); setInterval(tick, 10000);
      // Clicking a tile, a pill button or a Control Center button works too.
      this.rows.forEach((r, ri) => r.forEach((t, ci) => t.addEventListener('click', () => { this.leaveCC(); this.row = ri; this.col = ci; this.paint(); this.select(); })));
      this.pillItems.forEach((p, i) => p.addEventListener('click', () => { this.row = -1; this.col = i; this.paint(); this.select(); }));
      this.ccItems.forEach((c, i) => c.addEventListener('click', () => { this.ccIndex = i; this.paint(); this.select(); }));
      $('.otv-off', host).addEventListener('click', () => this.power());
      new ResizeObserver(() => { this.glassOn = false; if (this.focused) this.glass(this.focused); }).observe(this.screen);
      this.paint();
    }
    get app() { return this.row === 0 ? APPS[this.col] : null; }
    paint() {
      this.ccItems.forEach(c => c.classList.remove('focus'));
      this.pillItems.forEach(p => p.classList.remove('focus'));
      const label = $('.otv-cclabel', this.el);
      if (this.scope === 'cc') {
        this.blurTiles(); this.glass(null);
        const it = this.ccItems[this.ccIndex]; it.classList.add('focus');
        const c = CC[this.ccIndex]; label.textContent = c.label || ''; label.classList.toggle('on', !!c.label);
        return;
      }
      label.classList.remove('on');
      if (this.row === -1) { this.blurTiles(); this.glass(null); this.pillItems[this.col].classList.add('focus'); this.prev = null; return; }
      const t = this.rows[this.row][this.col];
      if (this.focused !== t) {
        this.blurTiles();
        t.classList.add('focus'); this.focused = t;
        // OtvOS (AppAdapter.tiltFrom): the tile leans 9° towards where focus came from, then settles with an overshoot.
        const p = this.prev;
        if (p && p.row >= 0 && !reduce) {
          const dx = Math.sign(this.col - p.col), dy = Math.sign(this.row - p.row);
          if (dx || dy) t.firstElementChild.animate([{ transform: `rotateY(${dx * 9}deg) rotateX(${-dy * 9}deg)` }, { transform: 'none' }],
            { duration: 650, easing: 'cubic-bezier(.25,1.9,.5,1)' });
        }
      }
      this.prev = { row: this.row, col: this.col };
      this.glass(t);
      this.setTitle(this.app.name);
      this.wall.setTint(this.app.tint);
    }
    blurTiles() { if (this.focused) this.focused.classList.remove('focus'); this.focused = null; }
    /** OtvOS's top shelf title: slides out to the left, and the new one in from the right (MainActivity.showHero). */
    setTitle(text) {
      const el = this.title;
      if (text === this.titleText) return;
      const first = this.titleText == null || !this.titleText;
      this.titleText = text;
      clearTimeout(this.titleTimer);
      if (!text) { el.style.opacity = 0; return; }
      if (first || reduce) { el.textContent = text; el.style.transition = 'opacity .26s'; el.style.transform = 'none'; el.style.opacity = 1; return; }
      el.style.transition = 'opacity .12s, transform .12s'; el.style.opacity = 0; el.style.transform = 'translateX(-1.25cqw)';
      this.titleTimer = setTimeout(() => {
        el.textContent = text; el.style.transition = 'none'; el.style.transform = 'translateX(1.25cqw)';
        void el.offsetWidth;
        el.style.transition = 'opacity .2s, transform .2s'; el.style.opacity = 1; el.style.transform = 'none';
      }, 120);
    }
    /** The frosted glass behind the focused tile, springing from tile to tile (stiffness 260, damping 0.58, as in OtvOS). */
    glass(tile) {
      const g = $('.otv-glass', this.el), rows = $('.otv-rows', this.el);
      if (!tile) { g.style.opacity = 0; this.glassOn = false; return; }
      const rr = rows.getBoundingClientRect(), tr = tile.getBoundingClientRect();
      const k = rows.offsetWidth / rr.width || 1;   // the page may scale the TV
      const x = (tr.left + tr.width / 2 - rr.left) * k - g.offsetWidth / 2, y = (tr.top + tr.height / 2 - rr.top) * k - g.offsetHeight / 2;
      this.gTarget = { x, y };
      if (!this.glassOn || reduce) {
        this.gPos = { x, y, vx: 0, vy: 0 }; g.style.transform = `translate(${x}px, ${y}px)`;
        g.style.opacity = 1; this.glassOn = true; return;
      }
      if (this.springing) return;
      this.springing = true;
      let last = performance.now();
      const stiff = 260, damp = 2 * 0.58 * Math.sqrt(260);
      const step = (now) => {
        const dt = Math.min(0.032, (now - last) / 1000); last = now;
        const p = this.gPos, t = this.gTarget;
        p.vx += (-stiff * (p.x - t.x) - damp * p.vx) * dt; p.vy += (-stiff * (p.y - t.y) - damp * p.vy) * dt;
        p.x += p.vx * dt; p.y += p.vy * dt;
        g.style.transform = `translate(${p.x}px, ${p.y}px)`;
        if (Math.abs(p.x - t.x) + Math.abs(p.y - t.y) < 0.3 && Math.abs(p.vx) + Math.abs(p.vy) < 2) { this.springing = false; return; }
        requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    }
    flash(html, ms = 1500) {
      const hud = $('.otv-hud', this.el); hud.innerHTML = html; hud.classList.add('on');
      clearTimeout(this.hudTimer); this.hudTimer = setTimeout(() => hud.classList.remove('on'), ms);
    }
    move(dir) {
      if (this.el.classList.contains('off')) return;
      if (this.scope === 'saver') return this.endSaver();
      if (this.scope === 'app' || this.scope === 'search') return;
      if (this.scope === 'cc') {
        const item = CC[this.ccIndex];
        if (item.id === 'vol' && (dir === 'left' || dir === 'right')) return this.setVolume(this.volume + (dir === 'right' ? 10 : -10), true);
        return this.ccMove(dir);
      }
      if (dir === 'left') this.col = Math.max(0, this.col - 1);
      if (dir === 'right') this.col = Math.min((this.row === -1 ? this.pillItems : this.rows[this.row]).length - 1, this.col + 1);
      if (dir === 'up' && this.row === 0) { this.lastCol = this.col; this.row = -1; this.col = 0; }
      if (dir === 'down' && this.row === -1) { this.row = 0; this.col = this.lastCol ?? 0; }
      this.paint();
    }
    ccMove(dir) {
      // Spatial: the nearest button in that direction.
      const rect = (el) => el.getBoundingClientRect();
      const cur = rect(this.ccItems[this.ccIndex]); const cx = cur.left + cur.width / 2, cy = cur.top + cur.height / 2;
      let best = -1, score = Infinity;
      this.ccItems.forEach((el, i) => {
        if (i === this.ccIndex) return;
        const r = rect(el), x = r.left + r.width / 2, y = r.top + r.height / 2, dx = x - cx, dy = y - cy;
        const ok = dir === 'left' ? dx < -4 : dir === 'right' ? dx > 4 : dir === 'up' ? dy < -4 : dy > 4;
        if (!ok) return;
        const along = dir === 'left' || dir === 'right' ? Math.abs(dx) : Math.abs(dy), across = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
        const s = along + across * 2.2; if (s < score) { score = s; best = i; }
      });
      if (best >= 0) { this.ccIndex = best; this.paint(); }
    }
    select() {
      if (this.el.classList.contains('off')) return this.power();
      if (this.scope === 'saver') return this.endSaver();
      if (this.scope === 'cc') return this.ccAction(CC[this.ccIndex].id);
      if (this.scope !== 'home') return;
      if (this.row === -1) return this.col === 0 ? this.openCC() : this.flash(`<span class="otv-avatar" style="width:2.2cqw;height:2.2cqw;font-size:1.1cqw">A</span>Alex`);
      const a = this.app;
      if (a.app) return this.openApp(a.app);
      const t = this.rows[this.row][this.col]; t.classList.remove('bounce'); void t.offsetWidth; t.classList.add('bounce');
    }
    openApp(src) {
      const img = $('.otv-app img', this.el); img.src = src;
      this.scope = 'app'; this.el.classList.add('app-open');
      this.title.setAttribute('aria-label', 'Opened ' + (this.app?.name || 'app'));
    }
    back() {
      if (this.scope === 'app') { this.el.classList.remove('app-open'); this.scope = 'home'; this.paint(); }
      else if (this.scope === 'cc') this.leaveCC();
      else if (this.scope === 'saver') this.endSaver();
      else if (this.scope === 'search') this.closeSearch();
      else if (this.row !== 0) { this.row = 0; this.paint(); }
    }
    home() {
      if (this.el.classList.contains('off')) return this.power();
      this.el.classList.remove('app-open', 'cc-open', 'searching'); if (this.scope === 'saver') this.endSaver();
      this.scope = 'home'; this.row = 0; this.col = 0; this.paint();
    }
    openCC() { this.scope = 'cc'; this.ccIndex = 0; this.el.classList.add('cc-open'); this.paint(); }
    leaveCC() { if (this.scope !== 'cc') return; this.el.classList.remove('cc-open'); this.scope = 'home'; this.paint(); }
    toggleCC() { if (this.el.classList.contains('off')) return; if (this.scope === 'cc') this.leaveCC(); else { this.el.classList.remove('app-open', 'searching'); if (this.scope === 'saver') this.endSaver(); this.openCC(); } }
    ccAction(id) {
      const sub = (key, text) => { $(`.otv-cc [data-id="${key}"] small`, this.el).textContent = text; };
      if (id === 'power') { this.leaveCC(); return this.power(); }
      if (id === 'wifi') { this.state.wifi = !this.state.wifi; sub('wifi', this.state.wifi ? 'Home Wi-Fi' : 'Off'); }
      if (id === 'bt') { this.state.bt = !this.state.bt; sub('bt', this.state.bt ? 'On' : 'Off'); }
      if (id === 'sleep') { this.state.sleep = (this.state.sleep + 1) % 4; sub('sleep', ['Off', '15 min', '30 min', '1 hour'][this.state.sleep]); }
      if (id === 'saver') { this.leaveCC(); return this.startSaver(); }
      if (id === 'settings') { this.leaveCC(); return this.openApp('images/v2/app-wallsettings.jpg'); }
      if (id === 'search') { this.leaveCC(); return this.opts.onSearch ? this.opts.onSearch() : this.openSearch(); }
      if (id === 'close') this.flash(`${I.closeapps}Background apps closed`);
      if (id === 'game') this.flash(`${I.pad}Game Mode on`);
      if (id === 'airplay') this.flash(`${I.airplay}Ready for AirPlay`);
      if (id === 'home') this.flash(`${I.house}Living Room TV is in Apple Home`);
      if (id === 'remote') this.flash(`${I.remote}Pair a remote: code 4821`);
      if (id === 'acc') this.flash(`${I.plug}Looking for game controllers…`);
    }
    setVolume(v, quiet) {
      this.volume = Math.max(0, Math.min(100, v)); this.muted = false;
      $('.otv-vol i', this.el).style.width = this.volume + '%';
      if (!quiet || this.scope !== 'cc') this.flash(`${I.volHi}<span class="lvl"><i style="width:${this.volume}%"></i></span>`);
    }
    mute() { this.muted = !this.muted; this.flash(this.muted ? `${I.mute}Muted` : `${I.volHi}<span class="lvl"><i style="width:${this.volume}%"></i></span>`); }
    power() {
      const off = this.el.classList.toggle('off');
      if (!off) this.home();
    }
    startSaver() { this.saverFrom = this.wall.mode; this.scope = 'saver'; this.el.classList.add('saver'); this.wall.set(2); }
    endSaver() { this.el.classList.remove('saver'); this.scope = 'home'; this.wall.set(this.saverFrom ?? 1); this.paint(); }
    openSearch() { this.el.classList.remove('app-open', 'cc-open'); this.scope = 'search'; this.el.classList.add('searching'); this.search(''); }
    closeSearch() { this.el.classList.remove('searching'); this.scope = 'home'; this.paint(); }
    search(q) {
      $('.otv-search .q', this.el).textContent = q || '';
      const all = APPS;
      const hits = q ? all.filter(a => a.name.toLowerCase().includes(q.toLowerCase())) : [];
      $('.otv-search .hits', this.el).innerHTML = (q && !hits.length ? '<span>Search the web for “' + q.replace(/[<&]/g, '') + '”</span>' : hits.map(a => `<span>${a.name}</span>`).join(''));
    }
    setWall(i) { this.wall.set(i); if (this.scope === 'saver') this.saverFrom = i; }
  }

  // ---- the iPhone -------------------------------------------------------------------------------------------
  class Phone {
    constructor(host, tv) {
      this.tv = tv; this.wall = tv.wall.mode;
      host.classList.add('ophone');
      host.innerHTML = `<div class="ophone-frame"><div class="ophone-bezel"><div class="ophone-screen">
        <div class="ophone-island"></div>
        <div class="ophone-status"><span>9:41</span><span>${I.signal}${I.wifiS}${I.battery}</span></div>
        <div class="ophone-head"><span class="tv">${I.tv}</span><div><div class="name">Living Room TV${I.chevron}</div><div class="stat">Connected</div></div><span class="sp"></span>
          <button class="round" data-k="settings" aria-label="TV Settings">${I.gear.replace('#26262a', '#1c1c1e')}</button><button class="round power" data-k="power" aria-label="Sleep or wake the TV">${I.power}</button></div>
        <div class="ophone-pad" aria-label="Touch surface: swipe to move, tap to select"><span class="mode">${I.hand}</span><div class="hint">${I.hand}<span>Swipe to navigate, tap to select</span></div><span class="dot"></span></div>
        <div class="ophone-row"><button class="round" data-k="back" aria-label="Back">${I.back}</button><button class="round" data-k="home" aria-label="Home">${I.tv}</button><button class="round" data-k="cc" aria-label="Control Center">${I.toggles.replace(/#2a2c30/g, '#1c1c1e')}</button></div>
        <div class="ophone-row"><button class="round" data-k="play" aria-label="Play or pause">${I.playpause}</button><button class="round" data-k="mute" aria-label="Mute">${I.mute}</button><button class="round" data-k="type" aria-label="Keyboard">${I.keyboard}</button></div>
        <div class="ophone-bottom"><button class="round" data-k="rew" aria-label="Rewind">${I.rew}</button>
          <div class="ophone-vol"><button data-k="vdown" aria-label="Volume down">${I.minus}</button><span class="spk">${I.vol}</span><button data-k="vup" aria-label="Volume up">${I.plus}</button></div>
          <button class="round" data-k="fwd" aria-label="Fast forward">${I.fwd}</button></div>
        <div class="ophone-sheet" data-sheet="walls"><h4>Wallpaper<button data-k="close">Done</button></h4><div class="ophone-walls">${WALLS.map((w, i) => `<button data-wall="${i}"><i style="background:${w.thumb}"></i>${w.name}</button>`).join('')}</div><p>Changes on the TV at once.</p></div>
        <div class="ophone-sheet" data-sheet="type"><h4>Type on TV<button data-k="close">Done</button></h4><input type="text" placeholder="Search or type" aria-label="Type on TV" autocomplete="off" enterkeyhint="search"><p>Text appears in the box that's selected on your TV.</p></div>
        <div class="ophone-toast"></div>
        <div class="ophone-home"></div>
      </div></div></div>`;
      this.el = host;
      this.pad = $('.ophone-pad', host); this.dot = $('.dot', host);
      host.querySelectorAll('[data-k]').forEach(b => b.addEventListener('click', (e) => { e.stopPropagation(); this.key(b.dataset.k); }));
      host.querySelectorAll('[data-wall]').forEach(b => b.addEventListener('click', () => { this.setWall(+b.dataset.wall); }));
      const input = $('input', host);
      input.addEventListener('input', () => this.tv.search(input.value));
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); this.closeSheets(); } e.stopPropagation(); });
      $('.mode', host).addEventListener('click', (e) => { e.stopPropagation(); this.toast('Trackpad turns on by itself for web pages'); });
      tv.opts.onSearch = () => this.key('type');
      this.markWalls();
      this.touch();
    }
    markWalls() { this.el.querySelectorAll('[data-wall]').forEach(b => b.classList.toggle('on', +b.dataset.wall === this.wall)); }
    setWall(i) { this.wall = i; this.tv.setWall(i); this.markWalls(); }
    toast(text) { const t = $('.ophone-toast', this.el); t.textContent = text; t.classList.add('on'); clearTimeout(this.tt); this.tt = setTimeout(() => t.classList.remove('on'), 1800); }
    closeSheets() {
      const typing = $('[data-sheet="type"]', this.el).classList.contains('on');
      this.el.querySelectorAll('.ophone-sheet').forEach(s => s.classList.remove('on'));
      $('input', this.el).blur();
      if (typing) this.tv.closeSearch();
    }
    key(k) {
      const tv = this.tv;
      this.interacted?.();
      switch (k) {
        case 'settings': this.closeSheets(); $('[data-sheet="walls"]', this.el).classList.add('on'); break;
        case 'type': { this.closeSheets(); const s = $('[data-sheet="type"]', this.el); s.classList.add('on'); const i = $('input', s); i.value = ''; tv.openSearch(); setTimeout(() => i.focus({ preventScroll: true }), 350); break; }
        case 'close': this.closeSheets(); break;
        case 'power': tv.power(); break;
        case 'back': tv.back(); break;
        case 'home': tv.home(); break;
        case 'cc': tv.toggleCC(); break;
        case 'play': tv.flash(`${I.playpause}Play / Pause`, 1000); break;
        case 'mute': tv.mute(); break;
        case 'vup': tv.setVolume(tv.volume + 10); break;
        case 'vdown': tv.setVolume(tv.volume - 10); break;
        case 'rew': tv.flash(`${I.rew}Rewind`, 900); break;
        case 'fwd': tv.flash(`${I.fwd}Fast forward`, 900); break;
      }
    }
    touch() {
      const pad = this.pad, dot = this.dot;
      let start = null, last = null, moved = false;
      const pos = (e) => { const r = pad.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
      const show = (p) => { dot.style.left = p.x + 'px'; dot.style.top = p.y + 'px'; dot.classList.add('on'); };
      pad.addEventListener('pointerdown', (e) => {
        this.interacted?.(); pad.setPointerCapture(e.pointerId);
        start = last = pos(e); start.t = performance.now(); moved = false; show(start);
      });
      pad.addEventListener('pointermove', (e) => {
        if (!start) return;
        const p = pos(e); show(p);
        const dx = p.x - last.x, dy = p.y - last.y, th = pad.clientWidth * 0.16;
        if (Math.abs(dx) > th || Math.abs(dy) > th) {
          this.tv.move(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up'));
          last = p; moved = true;
        }
      });
      const end = (e) => {
        if (!start) return;
        const p = pos(e), d = Math.hypot(p.x - start.x, p.y - start.y);
        if (!moved && d < 12 && performance.now() - start.t < 450) this.tv.select();
        start = null; setTimeout(() => dot.classList.remove('on'), 120);
      };
      pad.addEventListener('pointerup', end);
      pad.addEventListener('pointercancel', () => { start = null; dot.classList.remove('on'); });
    }
    /** For the tour: a finger gliding across the touch surface. */
    ghost(dir) {
      const pad = this.pad, dot = this.dot, w = pad.clientWidth, hgt = pad.clientHeight;
      const from = { x: w / 2 - (dir === 'right' ? w * 0.18 : dir === 'left' ? -w * 0.18 : 0), y: hgt / 2 };
      dot.style.transition = 'none'; dot.style.left = from.x + 'px'; dot.style.top = from.y + 'px'; dot.classList.add('on');
      requestAnimationFrame(() => {
        dot.style.transition = 'left .45s ease, top .45s ease, opacity .25s';
        dot.style.left = (from.x + (dir === 'right' ? w * 0.36 : dir === 'left' ? -w * 0.36 : 0)) + 'px';
        if (dir === 'tap') dot.style.transform = 'scale(.8)';
      });
      setTimeout(() => { dot.classList.remove('on'); dot.style.transition = ''; dot.style.transform = ''; }, 520);
    }
  }

  // ---- mount -------------------------------------------------------------------------------------------------
  document.querySelectorAll('[data-otv]').forEach((host) => {
    const tv = new TV(host, { wall: +(host.dataset.wall || 1) });
    const scene = host.closest('.demo');
    const phoneHost = scene && $('[data-ophone]', scene);
    const phone = phoneHost ? new Phone(phoneHost, tv) : null;
    let touched = false, armed = false, visible = false, timer = null, step = 0;
    // A slow tour until someone takes over: along the apps, into Control Center and back.
    const tour = ['right', 'right', 'right', 'right', 'right', 'left', 'left', 'left', 'left', 'left'];
    const tourCC = phone ? ['right', 'right', 'right', 'cc', 'down', 'cc', 'left', 'left', 'left'] : tour;
    const run = () => {
      if (touched || !visible || reduce) return;
      const steps = (step >= tour.length) ? tourCC : tour; const k = steps[step % steps.length]; step = (step + 1) % (tour.length + tourCC.length);
      if (k === 'cc') { tv.toggleCC(); phone && phone.ghost('tap'); }
      else { tv.move(k); phone && phone.ghost(k); }
    };
    const interacted = () => { if (!touched) { touched = true; clearInterval(timer); if (tv.scope === 'cc') tv.leaveCC(); } armed = true; };
    if (phone) phone.interacted = interacted;
    new IntersectionObserver(([e]) => {
      visible = e.isIntersecting; if (!visible) armed = false;
      if (scene && visible) scene.classList.add('shown');
      clearInterval(timer); if (visible && !touched && !reduce) timer = setInterval(run, 2200);
    }, { threshold: 0.35 }).observe(host);
    if (!phone) return;
    host.addEventListener('pointerdown', interacted);
    document.addEventListener('pointerdown', (e) => { if (!scene.contains(e.target)) armed = false; });
    // Arrow keys, Return and Esc drive the TV once you've clicked the demo (so the page still scrolls otherwise).
    document.addEventListener('keydown', (e) => {
      if (!armed || !visible || e.target.closest('input, textarea')) return;
      const map = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
      if (map[e.key]) { e.preventDefault(); tv.move(map[e.key]); }
      else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); tv.select(); }
      else if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); tv.back(); }
      else if (e.key.toLowerCase() === 'h') tv.home();
      else if (e.key.toLowerCase() === 'c') tv.toggleCC();
    });
  });
})();
