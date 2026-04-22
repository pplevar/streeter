// Stylized SVG mini-map with optional walked route + current position.
// Used across Recording, History cards, and Walk Detail.
function MapBg({ t, seed = 1, route = 'full', showDot = false, showStart = false, showEnd = false, zoom = 1, dark }) {
  // Deterministic street network — hand-tuned to look like a plausible city block grid.
  // We render several "types" of roads: major boulevards, minor streets, a diagonal, and parks.
  const W = 360, H = 640;
  // Predefined networks per seed
  const nets = [
    {
      // Urban grid
      parks: [ { x: 220, y: 380, w: 110, h: 90 }, { x: 40, y: 120, w: 70, h: 90 } ],
      water: null,
      majors: [
        'M-20 120 L380 160',
        'M-20 340 L380 360',
        'M80 -20 L110 680',
        'M250 -20 L270 680',
      ],
      minors: [
        'M-20 60 L380 80', 'M-20 200 L380 220', 'M-20 260 L380 280',
        'M-20 420 L380 440', 'M-20 500 L380 510', 'M-20 580 L380 590',
        'M30 -20 L40 680', 'M160 -20 L180 680', 'M200 -20 L220 680',
        'M320 -20 L340 680',
      ],
      // Walked route — a plausible loop
      routeFull:  'M80 520 L80 360 L180 360 L180 220 L270 220 L270 160 L340 160',
      routeHalf:  'M80 520 L80 420 L80 360 L180 360',
    },
    {
      // Suburban with park
      parks: [ { x: 160, y: 200, w: 180, h: 140 } ],
      water: 'M-20 600 Q120 560 220 600 T380 580 L380 680 L-20 680 Z',
      majors: [
        'M-20 100 Q140 110 200 180',
        'M-20 420 L380 440',
        'M60 -20 L80 680',
        'M300 -20 L320 680',
      ],
      minors: [
        'M-20 60 L380 70', 'M-20 160 L160 200',
        'M-20 280 L380 300', 'M-20 360 L380 380',
        'M-20 500 L380 520',
        'M20 -20 L30 680', 'M120 -20 L140 200',
        'M220 -20 L230 200', 'M340 -20 L360 680',
      ],
      routeFull: 'M60 440 L60 300 L140 300 L140 200 L340 200 L340 440',
      routeHalf: 'M60 440 L60 360 L60 300 L140 300',
    },
    {
      // Diagonal old town
      parks: [ { x: 60, y: 420, w: 90, h: 110 } ],
      water: null,
      majors: [
        'M-20 80 L380 320',
        'M-20 300 L380 540',
        'M100 -20 L40 680',
        'M300 -20 L240 680',
      ],
      minors: [
        'M-20 180 L380 420', 'M-20 420 L380 660',
        'M200 -20 L140 680', 'M60 -20 L20 680',
        'M360 -20 L300 680',
      ],
      routeFull: 'M80 520 L120 400 L220 340 L280 220 L340 200',
      routeHalf: 'M80 520 L120 400 L180 370',
    },
  ];
  const n = nets[seed % nets.length];
  const routeD = route === 'full' ? n.routeFull : route === 'half' ? n.routeHalf : null;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid slice"
      style={{ width: '100%', height: '100%', display: 'block', background: t.mapLand }}>
      <defs>
        <pattern id={`hatch-${seed}`} width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="8" height="8" fill={t.mapPark}/>
          <line x1="0" y1="0" x2="0" y2="8" stroke={dark ? '#283a2d' : '#C8D9C4'} strokeWidth="1"/>
        </pattern>
      </defs>
      {n.water && <path d={n.water} fill={t.mapWater}/>}
      {n.parks.map((p, i) => (
        <rect key={i} x={p.x} y={p.y} width={p.w} height={p.h} rx="6" fill={t.mapPark}/>
      ))}
      {/* Minor roads */}
      {n.minors.map((d, i) => (
        <path key={i} d={d} stroke={t.mapRoadMinor} strokeWidth="6" fill="none" strokeLinecap="round"/>
      ))}
      {/* Major roads */}
      {n.majors.map((d, i) => (
        <path key={i} d={d} stroke={t.mapRoad} strokeWidth="14" fill="none" strokeLinecap="round"/>
      ))}
      {n.majors.map((d, i) => (
        <path key={`c${i}`} d={d} stroke={dark ? '#3a4641' : '#E7E9E2'} strokeWidth="14" fill="none" strokeLinecap="round" opacity="0.25"/>
      ))}
      {/* Walked route — white halo + blue line */}
      {routeD && (
        <>
          <path d={routeD} stroke={dark ? '#0a1015' : '#ffffff'} strokeWidth="9" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
          <path d={routeD} stroke={t.routeBlue} strokeWidth="5" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
        </>
      )}
      {showStart && routeD && (
        <StartEndMarker x={parseRouteStart(routeD).x} y={parseRouteStart(routeD).y} color={t.pinStart}/>
      )}
      {showEnd && routeD && (
        <StartEndMarker x={parseRouteEnd(routeD).x} y={parseRouteEnd(routeD).y} color={t.pinEnd}/>
      )}
      {showDot && routeD && (
        <LocationDot x={parseRouteEnd(routeD).x} y={parseRouteEnd(routeD).y}/>
      )}
    </svg>
  );
}

function parseRouteStart(d) {
  const m = d.match(/M\s*(-?\d+\.?\d*)\s+(-?\d+\.?\d*)/);
  return { x: +m[1], y: +m[2] };
}
function parseRouteEnd(d) {
  const pts = d.match(/(-?\d+\.?\d*)\s+(-?\d+\.?\d*)/g);
  const last = pts[pts.length - 1].split(/\s+/);
  return { x: +last[0], y: +last[1] };
}

function StartEndMarker({ x, y, color }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <circle r="8" fill="#fff"/>
      <circle r="6" fill={color}/>
    </g>
  );
}

function LocationDot({ x, y }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <circle r="18" fill="#3B82F6" opacity="0.15">
        <animate attributeName="r" values="10;22;10" dur="2s" repeatCount="indefinite"/>
        <animate attributeName="opacity" values="0.3;0;0.3" dur="2s" repeatCount="indefinite"/>
      </circle>
      <circle r="7" fill="#fff"/>
      <circle r="5" fill="#3B82F6"/>
    </g>
  );
}

window.MapBg = MapBg;
