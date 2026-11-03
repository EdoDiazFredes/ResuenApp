// Genera las imágenes del instalador con el tema Noche de la app:
//  - sprites/<idioma>.png: hoja de imágenes del Setup.exe (fondo de la ventana, botones con su
//    texto en cada idioma, botones de minimizar/cerrar y barra de progreso). thm.xml recorta cada
//    pieza con SourceX/SourceY, así que las coordenadas de aquí y las de thm.xml deben coincidir.
//  - ../macos/dmg-background.png y @2x: fondo de la ventana de la .dmg.
//
// Las imágenes ya generadas están en el repositorio; solo hay que volver a correr esto si se
// cambia el diseño o un texto de los botones (labels.json):
//   node desktopApp/installer/theme/generate.mjs
// Usa Playwright (Chromium) para dibujar con las mismas fuentes de la app.
import { createRequire } from 'node:module';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const fonts = resolve(here, '../../src/main/resources/font');
// Fuentes e ícono van incrustados (data:) porque la página no puede leer archivos locales.
const dataUrl = (file, type) => `data:${type};base64,${readFileSync(file).toString('base64')}`;
const font = (name) => dataUrl(join(fonts, `${name}.ttf`), 'font/ttf');
const icon = dataUrl(resolve(here, '../../icons/resuenapp.svg'), 'image/svg+xml');
const labels = JSON.parse(readFileSync(join(here, 'labels.json'), 'utf8'));

// Colores del tema Noche (desktopApp/.../ui/Theme.kt).
const C = {
  night: '#0A0D14', footer: '#0E121B', surface: '#121722', card: '#1A202C', border: '#1F283B',
  hover: '#2A354D', teal: '#22C7A9', cyan: '#14C4CC', amber: '#FFB800', text: '#E2E8F0', muted: '#94A3B8', faint: '#64748B',
};

// Medidas compartidas con thm.xml.
const W = 540, H = 340, FOOTER = 68;        // ventana y franja inferior
const BW = 112, BH = 32;                            // botones de acción (3 estados apilados)
const SW = 44, SH = 30;                             // cerrar (WixStdBA 3.11 no atiende el de minimizar)
const BUTTONS = [                                   // orden en la hoja: x = i * BW, y = H
  ['install', 'primary'], ['repair', 'primary'], ['launch', 'primary'], ['restart', 'primary'],
  ['close', 'secondary'], ['cancel', 'secondary'], ['uninstall', 'secondary'],
];
const SY = H + BH * 3;                              // fila de cerrar y progreso
const SPRITE_W = BUTTONS.length * BW, SPRITE_H = SY + SH * 3;

const fontFaces = `
  @font-face { font-family: Bricolage; font-weight: 600; src: url(${font('bricolage_grotesque_semibold')}); }
  @font-face { font-family: Inter; font-weight: 500; src: url(${font('inter_medium')}); }
  @font-face { font-family: Inter; font-weight: 600; src: url(${font('inter_semibold')}); }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: ${C.night}; }
  .abs { position: absolute; }
`;

/** Fondo de la ventana del Setup.exe: encabezado con la marca, cuerpo liso y franja de botones. */
function windowBackground() {
  return `
  <div class="abs" style="left:0;top:0;width:${W}px;height:${H}px;background:${C.night};overflow:hidden;">
    <!-- Encabezado: brillo neón suave detrás del ícono, solo en esta franja (el texto va sobre fondo liso). -->
    <div class="abs" style="left:0;top:0;width:${W}px;height:80px;overflow:hidden;
      background:
        radial-gradient(240px 130px at 44px 40px, rgba(34,199,169,.15), transparent 70%);">
    </div>
    <img class="abs" src="${icon}" style="left:24px;top:20px;width:40px;height:40px;border-radius:9px;
      box-shadow:0 0 18px rgba(34,199,169,.25);">
    <div class="abs" style="left:76px;top:17px;font:600 21px Bricolage;color:#fff;letter-spacing:-.01em;">ResuenApp</div>
    <div class="abs" style="left:77px;top:46px;font:600 9.5px Inter;color:${C.teal};letter-spacing:.2em;">LOSSLESS PLAYER</div>
    <div class="abs" style="left:0;top:79px;width:${W}px;height:1px;background:${C.border};"></div>
    <div class="abs" style="left:24px;top:78px;width:120px;height:2px;border-radius:1px;
      background:linear-gradient(90deg,${C.teal},#9BD15A,${C.amber});box-shadow:0 0 8px rgba(34,199,169,.6);"></div>
    <!-- Franja inferior con los botones y la versión. -->
    <div class="abs" style="left:0;top:${H - FOOTER}px;width:${W}px;height:${FOOTER}px;background:${C.footer};border-top:1px solid ${C.border};"></div>
    <!-- Borde de 1 px: la ventana no tiene marco de Windows. -->
    <div class="abs" style="left:0;top:0;width:${W}px;height:${H}px;border:1px solid ${C.hover};"></div>
  </div>`;
}

/** Un botón en sus tres estados (normal, cursor encima, presionado), uno debajo del otro. */
function actionButton(x, text, kind) {
  const styles = kind === 'primary'
    ? [
      `background:${C.teal};color:${C.night};`,
      `background:#3FD8B8;color:${C.night};box-shadow:inset 0 0 0 1px rgba(255,255,255,.35);`,
      `background:#1BA88F;color:${C.night};`,
    ]
    : [
      `background:transparent;color:${C.text};box-shadow:inset 0 0 0 1px ${C.hover};`,
      `background:${C.card};color:#fff;box-shadow:inset 0 0 0 1px #3A4763;`,
      `background:${C.surface};color:${C.muted};box-shadow:inset 0 0 0 1px ${C.hover};`,
    ];
  return styles.map((s, i) => `
    <div class="abs" style="left:${x}px;top:${H + i * BH}px;width:${BW}px;height:${BH}px;background:${C.footer};">
      <div style="width:100%;height:100%;border-radius:8px;display:flex;align-items:center;justify-content:center;
        font:600 13px Inter;${s}">${text}</div>
    </div>`).join('');
}

/** Botón de cerrar, con el fondo del encabezado detrás. */
function captionButton(x, glyph, hoverBg, hoverFg) {
  const states = [['transparent', C.muted], [hoverBg, hoverFg], [hoverBg, hoverFg]];
  return states.map(([bg, fg], i) => `
    <div class="abs" style="left:${x}px;top:${SY + i * SH}px;width:${SW}px;height:${SH}px;background:${C.night};">
      <div style="width:100%;height:100%;background:${bg};${i === 2 ? 'filter:brightness(.85);' : ''}display:flex;align-items:center;justify-content:center;">
        <svg width="10" height="10" viewBox="0 0 10 10" stroke="${fg}" stroke-width="1.1" fill="none">${glyph}</svg>
      </div>
    </div>`).join('');
}

function sprite(lang) {
  const l = labels[lang];
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}</style></head>
  <body style="width:${SPRITE_W}px;height:${SPRITE_H}px;position:relative;overflow:hidden;">
    ${windowBackground()}
    ${BUTTONS.map(([key, kind], i) => actionButton(i * BW, l[key], kind)).join('')}
    ${captionButton(0, '<path d="M0.5 0.5l9 9M9.5 0.5l-9 9"/>', '#C42B1C', '#fff')}
    <!-- Barra de progreso: columna 0 = bordes, 1 = parte llena, 2 = parte vacía. -->
    <div class="abs" style="left:${SW}px;top:${SY}px;width:1px;height:6px;background:${C.border};"></div>
    <div class="abs" style="left:${SW + 1}px;top:${SY}px;width:1px;height:6px;background:linear-gradient(#3FD8B8,${C.teal});"></div>
    <div class="abs" style="left:${SW + 2}px;top:${SY}px;width:1px;height:6px;background:${C.border};"></div>
  </body></html>`;
}

/** Fondo de la .dmg (660 x 400 puntos). Finder escribe los nombres de los íconos en negro con
 *  el modo claro de macOS y en blanco con el oscuro: van sobre una placa de tono medio que se lee
 *  bien con los dos. */
function dmgBackground(lang) {
  const t = {
    es: { title: 'Instalar ResuenApp', sub: 'Arrastra el ícono a la carpeta Aplicaciones', foot: 'RESUENAPP · REPRODUCTOR DE MÚSICA SIN PÉRDIDA' },
  }[lang];
  const plate = (cx) => `<div class="abs" style="left:${cx - 70}px;top:271px;width:140px;height:24px;border-radius:12px;
    background:#6B7890;box-shadow:inset 0 0 0 1px rgba(255,255,255,.10);"></div>`;
  const halo = (cx) => `<div class="abs" style="left:${cx - 90}px;top:115px;width:180px;height:180px;border-radius:50%;
    background:radial-gradient(closest-side, rgba(34,199,169,.10), transparent);"></div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}
    @font-face { font-family: Inter; font-weight: 400; src: url(${font('inter_regular')}); }
  </style></head>
  <body style="width:660px;height:400px;position:relative;overflow:hidden;
    background:
      radial-gradient(420px 260px at 120px 0px, rgba(34,199,169,.14), transparent 70%),
      radial-gradient(380px 240px at 600px 420px, rgba(255,184,0,.08), transparent 70%),
      ${C.night};">
    ${[165, 330, 495].map((x) => `<div class="abs" style="left:${x}px;top:0;width:1px;height:400px;background:linear-gradient(rgba(255,255,255,.04),rgba(255,255,255,.01));"></div>`).join('')}
    <div class="abs" style="left:0;top:30px;width:660px;text-align:center;font:600 22px Bricolage;color:#fff;">${t.title}</div>
    <div class="abs" style="left:0;top:64px;width:660px;text-align:center;font:400 13px Inter;color:${C.muted};">${t.sub}</div>
    <div class="abs" style="left:250px;top:86px;width:160px;height:2px;border-radius:1px;
      background:linear-gradient(90deg,${C.teal},#9BD15A,${C.amber});box-shadow:0 0 8px rgba(34,199,169,.6);"></div>
    ${halo(170)}${halo(490)}
    <svg class="abs" style="left:250px;top:180px;filter:drop-shadow(0 0 6px rgba(255,184,0,.55));" width="160" height="50" viewBox="0 0 160 50">
      <defs><linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="${C.teal}"/><stop offset=".5" stop-color="#9BD15A"/><stop offset="1" stop-color="${C.amber}"/></linearGradient></defs>
      <path d="M12 25 H140 M124 11 L141 25 L124 39" stroke="url(#g)" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
    </svg>
    ${plate(170)}${plate(490)}
    <div class="abs" style="left:0;top:364px;width:660px;text-align:center;font:500 10px Inter;letter-spacing:.14em;color:${C.faint};">${t.foot}</div>
  </body></html>`;
}

const browser = await chromium.launch();
async function render(html, w, h, out, scale = 1) {
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  await page.setContent(html, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: out, omitBackground: false });
  await page.close();
  console.log(out);
}

mkdirSync(join(here, 'sprites'), { recursive: true });
for (const lang of Object.keys(labels)) {
  await render(sprite(lang), SPRITE_W, SPRITE_H, join(here, 'sprites', `${lang}.png`));
}
const mac = resolve(here, '../macos');
await render(dmgBackground('es'), 660, 400, join(mac, 'dmg-background.png'), 1);
await render(dmgBackground('es'), 660, 400, join(mac, 'dmg-background@2x.png'), 2);
await browser.close();
