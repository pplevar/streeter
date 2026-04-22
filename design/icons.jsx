// Minimal Material Symbols as inline SVG.
const Icon = ({ d, size = 24, color = 'currentColor', fill = false, strokeWidth = 1.8, style }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={style}
    fill={fill ? color : 'none'} stroke={fill ? 'none' : color}
    strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
    {d}
  </svg>
);

const Icons = {
  Settings: (p) => <Icon {...p} d={<><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"/></>} />,
  Back: (p) => <Icon {...p} d={<><path d="M19 12H5M12 19l-7-7 7-7"/></>} />,
  Edit: (p) => <Icon {...p} d={<><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.1 2.1 0 113 3L12 15l-4 1 1-4 9.5-9.5z"/></>} />,
  Delete: (p) => <Icon {...p} d={<><path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14z"/><path d="M10 11v6M14 11v6"/></>} />,
  Play: (p) => <Icon {...p} fill d={<polygon points="6 4 20 12 6 20 6 4"/>} />,
  Stop: (p) => <Icon {...p} fill d={<rect x="6" y="6" width="12" height="12" rx="1"/>} />,
  Footprints: (p) => <Icon {...p} d={<><path d="M4 16c-1 0-2-1-2-2 0-2 1-4 2-5 1 1 2 3 2 5 0 1-1 2-2 2zM7 22c-1 0-2-.8-2-2 0-.8.5-1.5 1-2 .4-.4 1-.5 2-.5s1.6.1 2 .5c.5.5 1 1.2 1 2 0 1.2-1 2-2 2H7zM16 10c-1 0-2-1-2-2 0-2 1-4 2-5 1 1 2 3 2 5 0 1-1 2-2 2zM19 16c-1 0-2-.8-2-2 0-.8.5-1.5 1-2 .4-.4 1-.5 2-.5s1.6.1 2 .5c.5.5 1 1.2 1 2 0 1.2-1 2-2 2h-2z"/></>} />,
  Route: (p) => <Icon {...p} d={<><circle cx="6" cy="19" r="3"/><path d="M9 19h7a3 3 0 003-3v-1a3 3 0 00-3-3H8a3 3 0 01-3-3V8a3 3 0 013-3h7"/><circle cx="18" cy="5" r="3"/></>} />,
  Sort: (p) => <Icon {...p} d={<><path d="M3 6h18M6 12h12M10 18h4"/></>} />,
  Clock: (p) => <Icon {...p} d={<><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>} />,
  Pin: (p) => <Icon {...p} d={<><path d="M12 22s7-7 7-12a7 7 0 10-14 0c0 5 7 12 7 12z"/><circle cx="12" cy="10" r="2.5"/></>} />,
  Check: (p) => <Icon {...p} d={<path d="M5 12l5 5L20 7"/>} />,
  Plus: (p) => <Icon {...p} d={<><path d="M12 5v14M5 12h14"/></>} />,
  History: (p) => <Icon {...p} d={<><path d="M3 12a9 9 0 109-9 9 9 0 00-7 3.5L3 8"/><path d="M3 3v5h5"/><path d="M12 8v4l3 2"/></>} />,
  Lock: (p) => <Icon {...p} d={<><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 018 0v4"/></>} />,
  MoreVert: (p) => <Icon {...p} fill d={<><circle cx="12" cy="5" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="12" cy="19" r="1.5"/></>} />,
  Sun: (p) => <Icon {...p} d={<><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></>} />,
  Moon: (p) => <Icon {...p} d={<path d="M21 13A9 9 0 1111 3a7 7 0 0010 10z"/>} />,
  Map: (p) => <Icon {...p} d={<><path d="M9 3l-6 3v15l6-3 6 3 6-3V3l-6 3-6-3z"/><path d="M9 3v15M15 6v15"/></>} />,
  Trophy: (p) => <Icon {...p} d={<><path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 01-10 0V4z"/><path d="M17 4h3v3a3 3 0 01-3 3M7 4H4v3a3 3 0 003 3"/></>} />,
};

window.Icon = Icon;
window.Icons = Icons;
