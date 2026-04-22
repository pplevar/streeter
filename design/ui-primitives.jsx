// Shared Material 3 Expressive primitives for Streeter screens.

const StatusBar = ({ t, dark }) => (
  <div style={{
    height: 28, display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '0 20px', color: t.onSurface, fontSize: 14, fontWeight: 500,
    fontFamily: 'Roboto, system-ui', flexShrink: 0, position: 'relative', zIndex: 2,
  }}>
    <span style={{ letterSpacing: 0.2 }}>9:41</span>
    <div style={{ position: 'absolute', left: '50%', top: 6, transform: 'translateX(-50%)',
      width: 18, height: 18, borderRadius: 100, background: '#0a0a0a' }}/>
    <div style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
      <svg width="14" height="10" viewBox="0 0 14 10" fill={t.onSurface}><path d="M7 10L0 3a10 10 0 0114 0L7 10z"/></svg>
      <svg width="12" height="10" viewBox="0 0 12 10" fill={t.onSurface}><path d="M11 10V0L1 10h10z"/></svg>
      <svg width="22" height="11" viewBox="0 0 22 11" fill="none">
        <rect x="0.5" y="0.5" width="19" height="10" rx="2" stroke={t.onSurface}/>
        <rect x="2" y="2" width="15" height="7" rx="1" fill={t.onSurface}/>
        <rect x="20" y="3" width="1.5" height="5" rx="0.5" fill={t.onSurface}/>
      </svg>
    </div>
  </div>
);

const NavBar = ({ t }) => (
  <div style={{ height: 20, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, background: t.surface }}>
    <div style={{ width: 108, height: 4, borderRadius: 2, background: t.onSurface, opacity: 0.9 }}/>
  </div>
);

const Screen = ({ t, dark, children, bg }) => (
  <div style={{
    width: '100%', height: '100%', background: bg || t.surface,
    color: t.onSurface, display: 'flex', flexDirection: 'column',
    fontFamily: '"Google Sans Text", Roboto, system-ui, sans-serif',
    overflow: 'hidden', position: 'relative',
  }}>
    <StatusBar t={t} dark={dark}/>
    {children}
    <NavBar t={t}/>
  </div>
);

const IconButton = ({ children, onClick, size = 40, style }) => (
  <button onClick={onClick} style={{
    width: size, height: size, borderRadius: size/2, border: 'none', background: 'transparent',
    display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
    color: 'inherit', ...style,
  }}>{children}</button>
);

const AppBar = ({ t, title, leading, trailing, large = false, bg }) => (
  <div style={{ background: bg || 'transparent', padding: '4px 4px 0', flexShrink: 0 }}>
    <div style={{ height: 64, display: 'flex', alignItems: 'center', gap: 4, padding: '0 4px' }}>
      {leading || <div style={{ width: 48 }}/>}
      {!large && <span style={{ flex: 1, fontSize: 22, fontWeight: 500, color: t.onSurface, letterSpacing: -0.2 }}>{title}</span>}
      {large && <div style={{ flex: 1 }}/>}
      <div style={{ display: 'flex' }}>{trailing}</div>
    </div>
    {large && (
      <div style={{ padding: '8px 24px 20px', fontSize: 32, fontWeight: 500, color: t.onSurface, letterSpacing: -0.5 }}>
        {title}
      </div>
    )}
  </div>
);

// M3 Expressive button — rounder, bolder
const Button = ({ children, variant = 'filled', size = 'md', onClick, style, disabled, icon, t }) => {
  const h = size === 'lg' ? 64 : size === 'sm' ? 40 : 52;
  const fs = size === 'lg' ? 18 : size === 'sm' ? 14 : 16;
  const bg = disabled ? t.surfaceContainerHigh
    : variant === 'filled' ? t.primary
    : variant === 'tonal' ? t.secondaryContainer
    : variant === 'destructive' ? t.error
    : 'transparent';
  const fg = disabled ? t.onSurfaceVariant
    : variant === 'filled' ? t.onPrimary
    : variant === 'tonal' ? t.onSecondaryContainer
    : variant === 'destructive' ? t.onError
    : variant === 'outlined' ? t.primary
    : t.primary;
  const border = variant === 'outlined' ? `1.5px solid ${t.outline}` : 'none';
  return (
    <button onClick={onClick} disabled={disabled} style={{
      height: h, padding: '0 28px', borderRadius: h/2,
      background: bg, color: fg, border,
      fontSize: fs, fontWeight: 600, letterSpacing: 0.1,
      fontFamily: 'inherit', cursor: disabled ? 'default' : 'pointer',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      boxShadow: variant === 'filled' || variant === 'destructive' ? '0 1px 2px rgba(0,0,0,.08)' : 'none',
      ...style,
    }}>{icon}{children}</button>
  );
};

const Chip = ({ children, variant = 'assist', t, style, icon, selected }) => {
  const bg = selected ? t.secondaryContainer
    : variant === 'tonal' ? t.secondaryContainer
    : variant === 'tertiary' ? t.tertiaryContainer
    : variant === 'error' ? t.errorContainer
    : 'transparent';
  const fg = selected ? t.onSecondaryContainer
    : variant === 'tonal' ? t.onSecondaryContainer
    : variant === 'tertiary' ? t.onTertiaryContainer
    : variant === 'error' ? t.onErrorContainer
    : t.onSurfaceVariant;
  const border = variant === 'assist' && !selected ? `1px solid ${t.outline}` : 'none';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      height: 32, padding: '0 12px', borderRadius: 8,
      background: bg, color: fg, border,
      fontSize: 12, fontWeight: 600, letterSpacing: 0.3,
      textTransform: 'uppercase', ...style,
    }}>{icon}{children}</span>
  );
};

const Card = ({ children, t, style, variant = 'elevated' }) => (
  <div style={{
    background: variant === 'filled' ? t.surfaceContainerHigh : t.surfaceContainerLow,
    borderRadius: 28, padding: 20,
    boxShadow: variant === 'elevated' ? '0 1px 3px rgba(0,0,0,.06), 0 1px 2px rgba(0,0,0,.04)' : 'none',
    ...style,
  }}>{children}</div>
);

window.StatusBar = StatusBar;
window.NavBar = NavBar;
window.Screen = Screen;
window.IconButton = IconButton;
window.AppBar = AppBar;
window.Button = Button;
window.Chip = Chip;
window.Card = Card;
