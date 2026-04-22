// Streeter screens — Home, Recording, History, Walk Detail
// Uses: Screen, AppBar, Button, Chip, Card, IconButton, Icons, MapBg

// ─── HOME ──────────────────────────────────────────────────────────
function HomeScreen({ t, dark, active = false }) {
  return (
    <Screen t={t} dark={dark}>
      <AppBar t={t} title=""
        trailing={<IconButton><Icons.Settings size={24} color={t.onSurfaceVariant}/></IconButton>}/>

      {/* Hero */}
      <div style={{ padding: '0 28px 12px', flexShrink: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 700, letterSpacing: 1.5, color: t.primary, textTransform: 'uppercase', marginBottom: 12 }}>
          Streeter
        </div>
        <div style={{ fontSize: 40, fontWeight: 500, lineHeight: 1.05, letterSpacing: -1.2, color: t.onSurface, textWrap: 'balance' }}>
          Walk every street in your city.
        </div>
      </div>

      {/* Progress card */}
      <div style={{ padding: '16px 24px 0', flexShrink: 0 }}>
        <div style={{
          background: t.primaryContainer, color: t.onPrimaryContainer,
          borderRadius: 28, padding: '20px 22px',
          position: 'relative', overflow: 'hidden',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div>
              <div style={{ fontSize: 13, fontWeight: 600, opacity: 0.75, letterSpacing: 0.4 }}>YOUR CITY</div>
              <div style={{ fontSize: 20, fontWeight: 600, marginTop: 2 }}>Kreuzberg</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <div style={{ fontSize: 44, fontWeight: 500, lineHeight: 1, letterSpacing: -1.5, fontVariantNumeric: 'tabular-nums' }}>
                18<span style={{ fontSize: 22, opacity: 0.7 }}>%</span>
              </div>
              <div style={{ fontSize: 12, fontWeight: 600, opacity: 0.7, marginTop: 2 }}>covered</div>
            </div>
          </div>
          <div style={{ marginTop: 18, height: 8, borderRadius: 4, background: 'rgba(0,0,0,0.12)', overflow: 'hidden' }}>
            <div style={{ width: '18%', height: '100%', background: t.primary, borderRadius: 4 }}/>
          </div>
          <div style={{ marginTop: 12, display: 'flex', gap: 18, fontSize: 13, fontWeight: 500, opacity: 0.85 }}>
            <span><b style={{ fontWeight: 700 }}>147</b> streets</span>
            <span><b style={{ fontWeight: 700 }}>42.8</b> km walked</span>
            <span><b style={{ fontWeight: 700 }}>23</b> walks</span>
          </div>
        </div>
      </div>

      <div style={{ flex: 1 }}/>

      {/* Actions */}
      <div style={{ padding: '0 24px 24px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {active ? (
          <Button t={t} variant="destructive" size="lg" style={{ width: '100%' }}
            icon={<Icons.Play size={22} fill/>}>Resume Walk</Button>
        ) : (
          <Button t={t} variant="filled" size="lg" style={{ width: '100%' }}
            icon={<Icons.Play size={22} fill/>}>Start Walk</Button>
        )}
        <div style={{ display: 'flex', gap: 12 }}>
          <Button t={t} variant="outlined" size="md" style={{ flex: 1 }}
            icon={<Icons.History size={20}/>}>History</Button>
          <Button t={t} variant="outlined" size="md" style={{ flex: 1 }}
            icon={<Icons.Plus size={20}/>}>Manual</Button>
        </div>
      </div>
    </Screen>
  );
}

// ─── RECORDING ─────────────────────────────────────────────────────
function RecordingScreen({ t, dark }) {
  return (
    <Screen t={t} dark={dark} bg={t.mapLand}>
      {/* Map (absolute, under status bar) */}
      <div style={{ position: 'absolute', inset: 0 }}>
        <MapBg t={t} seed={0} route="half" showDot dark={dark}/>
      </div>
      {/* Top translucent back */}
      <div style={{ position: 'absolute', top: 40, left: 16, zIndex: 3 }}>
        <div style={{
          width: 44, height: 44, borderRadius: 22, background: t.surfaceContainer,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 2px 8px rgba(0,0,0,.15)',
        }}>
          <Icons.Back size={22} color={t.onSurface}/>
        </div>
      </div>
      {/* Live metric chip (top) */}
      <div style={{ position: 'absolute', top: 40, right: 16, zIndex: 3 }}>
        <div style={{
          background: t.surfaceContainer, padding: '8px 14px', borderRadius: 18,
          display: 'flex', alignItems: 'center', gap: 8, boxShadow: '0 2px 8px rgba(0,0,0,.15)',
        }}>
          <span style={{ width: 8, height: 8, borderRadius: 4, background: '#E53935',
            boxShadow: '0 0 0 3px rgba(229,57,53,.2)' }}/>
          <span style={{ fontSize: 13, fontWeight: 700, color: t.onSurface, letterSpacing: 0.2 }}>REC</span>
          <span style={{ fontSize: 13, fontWeight: 600, color: t.onSurfaceVariant }}>·</span>
          <span style={{ fontSize: 13, fontWeight: 600, color: t.onSurfaceVariant, fontVariantNumeric: 'tabular-nums' }}>00:12:34</span>
        </div>
      </div>

      {/* Bottom sheet card */}
      <div style={{ position: 'absolute', left: 16, right: 16, bottom: 36, zIndex: 3 }}>
        <div style={{
          background: t.surfaceContainerLowest, borderRadius: 32, padding: 22,
          boxShadow: '0 6px 24px rgba(0,0,0,.18)',
        }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 16 }}>
            <MetricBlock t={t} value="1.2" unit="km" label="Distance"/>
            <div style={{ width: 1, background: t.outlineVariant, margin: '8px 0' }}/>
            <MetricBlock t={t} value="12:34" unit="" label="Duration"/>
            <div style={{ width: 1, background: t.outlineVariant, margin: '8px 0' }}/>
            <MetricBlock t={t} value="42" unit="pts" label="GPS"/>
          </div>
          <Button t={t} variant="destructive" size="lg" style={{ width: '100%' }}
            icon={<Icons.Stop size={20} fill/>}>Stop Walk</Button>
        </div>
      </div>
    </Screen>
  );
}

function MetricBlock({ t, value, unit, label }) {
  return (
    <div style={{ flex: 1, padding: '4px 2px' }}>
      <div style={{ fontSize: 24, fontWeight: 500, color: t.onSurface, letterSpacing: -0.6, lineHeight: 1.1 }}>
        {value}<span style={{ fontSize: 14, color: t.onSurfaceVariant, marginLeft: 2, fontWeight: 600 }}>{unit}</span>
      </div>
      <div style={{ fontSize: 11, fontWeight: 600, color: t.onSurfaceVariant, letterSpacing: 0.5, textTransform: 'uppercase', marginTop: 2 }}>{label}</div>
    </div>
  );
}

// ─── HISTORY ───────────────────────────────────────────────────────
function HistoryScreen({ t, dark }) {
  const walks = [
    { title: 'Mon, Apr 21', dist: '2.4 km', dur: '42 min', seed: 0, manual: false, processing: false, streets: 8 },
    { title: 'Sun, Apr 20', dist: '5.1 km', dur: '1h 23m', seed: 1, manual: false, processing: true, streets: 0 },
    { title: 'Evening loop', dist: '3.2 km', dur: '58 min', seed: 2, manual: true, processing: false, streets: 11 },
    { title: 'Fri, Apr 18', dist: '850 m', dur: '14 min', seed: 0, manual: false, processing: false, streets: 3 },
  ];
  return (
    <Screen t={t} dark={dark}>
      <AppBar t={t} title="History"
        leading={<IconButton><Icons.Back size={24} color={t.onSurfaceVariant}/></IconButton>}
        trailing={<>
          <IconButton><Icons.Sort size={22} color={t.onSurfaceVariant}/></IconButton>
        </>}/>
      {/* Summary strip */}
      <div style={{ padding: '0 24px 16px', display: 'flex', gap: 10, flexShrink: 0 }}>
        <StatPill t={t} label="This week" value="4 walks" tone="primary"/>
        <StatPill t={t} label="Distance" value="11.6 km"/>
        <StatPill t={t} label="Streets" value="22"/>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '4px 16px 24px' }}>
        {walks.map((w, i) => (
          <div key={i} style={{
            background: t.surfaceContainerLow, borderRadius: 24, padding: 16, marginBottom: 10,
            display: 'flex', gap: 14, alignItems: 'stretch',
          }}>
            {/* Mini map */}
            <div style={{ width: 72, height: 72, borderRadius: 18, overflow: 'hidden', flexShrink: 0, background: t.mapLand }}>
              <MapBg t={t} seed={w.seed} route={w.processing ? 'none' : 'full'} dark={dark}/>
            </div>
            {/* Body */}
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
                <div style={{ fontSize: 16, fontWeight: 600, color: t.onSurface, letterSpacing: -0.2 }}>{w.title}</div>
                <div style={{ display: 'flex', gap: 4 }}>
                  {w.manual && <Chip t={t} variant="tonal" style={{ height: 22, fontSize: 10, padding: '0 8px' }}>Manual</Chip>}
                  {w.processing && <Chip t={t} variant="tertiary" style={{ height: 22, fontSize: 10, padding: '0 8px' }}>Processing</Chip>}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 16, fontSize: 13, color: t.onSurfaceVariant, alignItems: 'center' }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontWeight: 500 }}>
                  <Icons.Route size={14}/> {w.dist}
                </span>
                <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontWeight: 500 }}>
                  <Icons.Clock size={14}/> {w.dur}
                </span>
                {!w.processing && (
                  <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontWeight: 500, color: t.primary }}>
                    <Icons.Pin size={14}/> {w.streets}
                  </span>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </Screen>
  );
}

function StatPill({ t, label, value, tone }) {
  const bg = tone === 'primary' ? t.primaryContainer : t.surfaceContainerLow;
  const fg = tone === 'primary' ? t.onPrimaryContainer : t.onSurface;
  return (
    <div style={{
      flex: 1, padding: '10px 12px', borderRadius: 16, background: bg, color: fg,
    }}>
      <div style={{ fontSize: 10, fontWeight: 700, opacity: 0.7, letterSpacing: 0.5, textTransform: 'uppercase' }}>{label}</div>
      <div style={{ fontSize: 16, fontWeight: 600, marginTop: 2, letterSpacing: -0.2 }}>{value}</div>
    </div>
  );
}

// ─── WALK DETAIL ───────────────────────────────────────────────────
function WalkDetailScreen({ t, dark, processing = false }) {
  const streets = [
    { name: 'Oranienstraße', pct: 100 },
    { name: 'Wiener Straße', pct: 100 },
    { name: 'Görlitzer Park Path', pct: 100 },
    { name: 'Skalitzer Straße', pct: 82 },
    { name: 'Manteuffelstraße', pct: 67 },
    { name: 'Lausitzer Platz', pct: 54 },
    { name: 'Reichenberger Straße', pct: 34 },
    { name: 'Ohlauer Straße', pct: 18 },
  ];
  const tier = (p) => p === 100 ? 'full' : p >= 50 ? 'partial' : 'low';
  const fullCount = streets.filter(s => s.pct === 100).length;
  const partialCount = streets.filter(s => s.pct >= 50 && s.pct < 100).length;
  const lowCount = streets.filter(s => s.pct < 50).length;

  return (
    <Screen t={t} dark={dark}>
      <AppBar t={t} title=""
        leading={<IconButton><Icons.Back size={24} color={t.onSurfaceVariant}/></IconButton>}
        trailing={<>
          {!processing && <IconButton><Icons.Edit size={22} color={t.onSurfaceVariant}/></IconButton>}
          <IconButton><Icons.Delete size={22} color={t.onSurfaceVariant}/></IconButton>
        </>}/>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 20px 24px' }}>
        {/* Hero header */}
        <div style={{ padding: '0 4px 16px' }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: t.primary, letterSpacing: 0.3, marginBottom: 4 }}>
            Mon · Apr 21 · 9:42
          </div>
          <div style={{ fontSize: 32, fontWeight: 500, color: t.onSurface, letterSpacing: -0.8, lineHeight: 1.1 }}>
            Morning walk
          </div>
        </div>

        {/* Map preview */}
        <div style={{ height: 160, borderRadius: 28, overflow: 'hidden', background: t.mapLand, position: 'relative' }}>
          <MapBg t={t} seed={0} route="full" showStart showEnd dark={dark}/>
          {processing && (
            <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,.1)',
              backdropFilter: 'blur(2px)' }}/>
          )}
        </div>

        {/* Metric row */}
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <BigStat t={t} value="2.4" unit="km" label="Distance"/>
          <BigStat t={t} value="42" unit="min" label="Duration"/>
          <BigStat t={t} value={String(fullCount + partialCount + lowCount)} unit="" label="Streets"/>
        </div>

        {/* Processing banner OR recalc */}
        {processing ? (
          <div style={{
            marginTop: 16, background: t.tertiaryContainer, color: t.onTertiaryContainer,
            borderRadius: 24, padding: '18px 20px',
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                <div style={{ width: 20, height: 20, borderRadius: 10,
                  border: `2.5px solid ${t.onTertiaryContainer}`, borderTopColor: 'transparent',
                  animation: 'spin 1s linear infinite' }}/>
                <span style={{ fontSize: 15, fontWeight: 600 }}>Matching route…</span>
              </div>
              <span style={{ fontSize: 20, fontWeight: 500, letterSpacing: -0.4 }}>37%</span>
            </div>
            <div style={{ marginTop: 12, height: 6, borderRadius: 3, background: 'rgba(0,0,0,.12)', overflow: 'hidden' }}>
              <div style={{ width: '37%', height: '100%', background: t.onTertiaryContainer, borderRadius: 3 }}/>
            </div>
            <div style={{ fontSize: 12, opacity: 0.75, marginTop: 8, fontWeight: 500 }}>Snapping GPS points to road network</div>
          </div>
        ) : (
          <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end' }}>
            <Button t={t} variant="outlined" size="sm">Recalculate Route</Button>
          </div>
        )}

        {/* Streets covered */}
        {!processing && (
          <div style={{ marginTop: 24 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', padding: '0 4px', marginBottom: 12 }}>
              <div style={{ fontSize: 22, fontWeight: 500, color: t.onSurface, letterSpacing: -0.4 }}>Streets covered</div>
              <div style={{ fontSize: 13, fontWeight: 600, color: t.onSurfaceVariant }}>{streets.length} total</div>
            </div>
            {/* Tier legend */}
            <div style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
              <TierPill t={t} color="#2E7D32" bg={dark ? '#1F3A23' : '#D4EDD6'} label="Full" count={fullCount}/>
              <TierPill t={t} color="#B26A00" bg={dark ? '#3A2A14' : '#FFE8C4'} label="Partial" count={partialCount}/>
              <TierPill t={t} color="#B71C1C" bg={dark ? '#3A1414' : '#F9D6D6'} label="Low" count={lowCount}/>
            </div>
            {/* List */}
            <div style={{ background: t.surfaceContainerLow, borderRadius: 24, padding: 6 }}>
              {streets.map((s, i) => (
                <StreetRow key={i} t={t} street={s} tier={tier(s.pct)} dark={dark}/>
              ))}
            </div>
          </div>
        )}
      </div>
    </Screen>
  );
}

function BigStat({ t, value, unit, label }) {
  return (
    <div style={{ flex: 1, background: t.surfaceContainerLow, borderRadius: 20, padding: '14px 14px' }}>
      <div style={{ fontSize: 28, fontWeight: 500, color: t.onSurface, letterSpacing: -0.8, lineHeight: 1 }}>
        {value}{unit && <span style={{ fontSize: 13, color: t.onSurfaceVariant, marginLeft: 3, fontWeight: 600 }}>{unit}</span>}
      </div>
      <div style={{ fontSize: 11, fontWeight: 700, color: t.onSurfaceVariant, letterSpacing: 0.5, textTransform: 'uppercase', marginTop: 4 }}>
        {label}
      </div>
    </div>
  );
}

function TierPill({ t, color, bg, label, count }) {
  return (
    <div style={{
      flex: 1, padding: '10px 12px', borderRadius: 14, background: bg,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: color }}/>
        <span style={{ fontSize: 11, fontWeight: 700, color, letterSpacing: 0.4, textTransform: 'uppercase' }}>{label}</span>
      </div>
      <div style={{ fontSize: 20, fontWeight: 600, color, marginTop: 2, letterSpacing: -0.4 }}>{count}</div>
    </div>
  );
}

function StreetRow({ t, street, tier, dark }) {
  const colors = {
    full: { c: dark ? '#8ED99A' : '#2E7D32', bg: dark ? '#1F3A23' : '#D4EDD6' },
    partial: { c: dark ? '#F5C06F' : '#B26A00', bg: dark ? '#3A2A14' : '#FFE8C4' },
    low: { c: dark ? '#EF9A9A' : '#B71C1C', bg: dark ? '#3A1414' : '#F9D6D6' },
  };
  const col = colors[tier];
  return (
    <div style={{
      padding: '12px 14px', borderRadius: 18, display: 'flex', alignItems: 'center', gap: 12,
    }}>
      <div style={{
        width: 36, height: 36, borderRadius: 18, background: col.bg, color: col.c,
        display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
        fontSize: 11, fontWeight: 700, letterSpacing: -0.2,
      }}>
        {street.pct}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 500, color: t.onSurface, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {street.name}
        </div>
        <div style={{ height: 5, borderRadius: 3, background: t.surfaceContainerHigh, overflow: 'hidden', marginTop: 6 }}>
          <div style={{ width: `${street.pct}%`, height: '100%', background: col.c, borderRadius: 3 }}/>
        </div>
      </div>
    </div>
  );
}

// ─── SETTINGS ──────────────────────────────────────────────────────
function SettingsScreen({ t, dark, refreshing = false }) {
  return (
    <Screen t={t} dark={dark}>
      <AppBar t={t} title="Settings"
        leading={<IconButton><Icons.Back size={24} color={t.onSurfaceVariant}/></IconButton>}/>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 20px 24px' }}>
        {/* GPS Recording */}
        <SectionHeader t={t} label="GPS Recording"/>
        <div style={{ background: t.surfaceContainerLow, borderRadius: 24, padding: 20, marginBottom: 16 }}>
          <TickSlider t={t} label="GPS sample interval" value="20 s" pct={0.27} min="5 s" max="60 s"/>
          <div style={{ height: 20 }}/>
          <TickSlider t={t} label="Max speed filter" value="50 km/h" pct={0.375} min="20" max="100 km/h"/>
        </div>

        {/* Map Data */}
        <SectionHeader t={t} label="Map Data"/>
        <div style={{ background: t.surfaceContainerLow, borderRadius: 24, padding: '18px 20px', marginBottom: 16,
          display: 'flex', gap: 14, alignItems: 'center' }}>
          <div style={{
            width: 44, height: 44, borderRadius: 14, background: t.secondaryContainer, color: t.onSecondaryContainer,
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}>
            <Icons.Map size={22}/>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 600, color: t.onSurface }}>Refresh map data</div>
            <div style={{ fontSize: 13, color: t.onSurfaceVariant, marginTop: 2, lineHeight: 1.3 }}>
              Re-index OSM street data from bundled assets
            </div>
          </div>
          {refreshing ? (
            <div style={{ width: 22, height: 22, borderRadius: 11,
              border: `2.5px solid ${t.primary}`, borderTopColor: 'transparent',
              animation: 'spin 1s linear infinite', marginRight: 8 }}/>
          ) : (
            <Button t={t} variant="tonal" size="sm" style={{ padding: '0 18px', height: 36, fontSize: 13 }}>Refresh</Button>
          )}
        </div>

        {/* Data */}
        <SectionHeader t={t} label="Data"/>
        <div style={{ background: t.errorContainer, color: t.onErrorContainer, borderRadius: 24, padding: '18px 20px',
          display: 'flex', gap: 14, alignItems: 'center' }}>
          <div style={{
            width: 44, height: 44, borderRadius: 14, background: 'rgba(0,0,0,.08)', color: t.error,
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}>
            <Icons.Delete size={22}/>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 600 }}>Clear all data</div>
            <div style={{ fontSize: 13, opacity: 0.8, marginTop: 2, lineHeight: 1.3 }}>
              Permanently delete all walks and coverage
            </div>
          </div>
          <button style={{
            height: 36, padding: '0 18px', borderRadius: 18, border: 'none',
            background: t.error, color: t.onError, fontFamily: 'inherit',
            fontSize: 13, fontWeight: 600, cursor: 'pointer',
          }}>Clear</button>
        </div>

        {/* Footer — privacy reminder */}
        <div style={{ marginTop: 28, padding: '16px 20px', borderRadius: 20,
          background: t.primaryContainer, color: t.onPrimaryContainer,
          display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <Icons.Lock size={20} color={t.onPrimaryContainer} style={{ flexShrink: 0, marginTop: 2 }}/>
          <div>
            <div style={{ fontSize: 14, fontWeight: 600 }}>On-device only</div>
            <div style={{ fontSize: 12, opacity: 0.8, marginTop: 2, lineHeight: 1.4 }}>
              GPS, route matching, and coverage run locally. Nothing is sent to any server.
            </div>
          </div>
        </div>

        <div style={{ textAlign: 'center', fontSize: 11, fontWeight: 600, color: t.onSurfaceVariant,
          marginTop: 20, letterSpacing: 0.3, opacity: 0.7 }}>Streeter · v1.2.0</div>
      </div>
    </Screen>
  );
}

function SectionHeader({ t, label }) {
  return (
    <div style={{ fontSize: 13, fontWeight: 700, letterSpacing: 0.8, textTransform: 'uppercase',
      color: t.primary, padding: '18px 6px 10px' }}>{label}</div>
  );
}

function TickSlider({ t, label, value, pct, min, max }) {
  const ticks = 22;
  const filledTicks = Math.round(pct * ticks);
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 }}>
        <span style={{ fontSize: 15, fontWeight: 500, color: t.onSurface }}>{label}</span>
        <span style={{ fontSize: 15, fontWeight: 600, color: t.primary, fontVariantNumeric: 'tabular-nums' }}>{value}</span>
      </div>
      {/* M3 Expressive tick-mark slider — notched track */}
      <div style={{ position: 'relative', height: 24, display: 'flex', alignItems: 'center', gap: 2 }}>
        {/* Filled portion */}
        <div style={{ display: 'flex', gap: 2, alignItems: 'center', flex: pct, height: 16 }}>
          {Array.from({ length: filledTicks }).map((_, i) => (
            <div key={i} style={{
              flex: 1, height: 16, borderRadius: 3, background: t.primary,
              opacity: i === filledTicks - 1 ? 0 : 1,
            }}/>
          ))}
        </div>
        {/* Thumb */}
        <div style={{
          width: 4, height: 24, borderRadius: 2, background: t.primary, flexShrink: 0,
          boxShadow: '0 1px 3px rgba(0,0,0,.15)',
        }}/>
        {/* Empty portion */}
        <div style={{ display: 'flex', gap: 2, alignItems: 'center', flex: 1 - pct, height: 4 }}>
          {Array.from({ length: ticks - filledTicks }).map((_, i) => (
            <div key={i} style={{
              width: 3, height: 3, borderRadius: 2, background: t.primaryContainer,
              flexShrink: 0, marginRight: 'auto', marginLeft: 'auto',
            }}/>
          ))}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6 }}>
        <span style={{ fontSize: 11, color: t.onSurfaceVariant, fontWeight: 500 }}>{min}</span>
        <span style={{ fontSize: 11, color: t.onSurfaceVariant, fontWeight: 500 }}>{max}</span>
      </div>
    </div>
  );
}

// ─── MANUAL CREATE ─────────────────────────────────────────────────
// Multi-point route builder: drop a point at map center, routing engine
// computes the segment, repeat, then Finish.
function ManualCreateScreen({ t, dark, step = 'placing-next' }) {
  // step: 'placing-first' | 'placing-next' | 'routing' | 'finishing'
  const isRouting = step === 'routing';
  const isFinishing = step === 'finishing';
  const pinActive = step === 'placing-first' || step === 'placing-next';
  const hasPoints = step !== 'placing-first';
  const hasSegments = step === 'placing-next' || step === 'routing' || step === 'finishing';

  const instruction = {
    'placing-first': 'Move map to start point, then tap Add Point',
    'placing-next':  'Move map to next point, then tap Add Point',
    'routing':       'Computing route segment…',
    'finishing':     'Saving walk…',
  }[step];

  // Which segments to render on the mini-map
  const routeVariant = step === 'placing-first' ? 'none'
    : step === 'placing-next' || step === 'finishing' ? 'full'
    : 'half';

  return (
    <Screen t={t} dark={dark} bg={t.mapLand}>
      {/* Map */}
      <div style={{ position: 'absolute', inset: 0 }}>
        <MapBg t={t} seed={1} route={routeVariant} dark={dark}/>
      </div>

      {/* TopAppBar */}
      <div style={{ position: 'relative', zIndex: 3, flexShrink: 0,
        background: `linear-gradient(180deg, ${t.surface} 55%, ${t.surface}00)`, padding: '4px 4px 24px' }}>
        <AppBar t={t} title="Create Manual Walk"
          leading={<IconButton><Icons.Back size={24} color={isFinishing ? t.outline : t.onSurfaceVariant}/></IconButton>}/>
      </div>

      {/* Instruction banner */}
      {instruction && (
        <div style={{ position: 'absolute', top: 104, left: 0, right: 0, zIndex: 3, display: 'flex', justifyContent: 'center', pointerEvents: 'none' }}>
          <div style={{
            background: t.surfaceContainerLowest, color: t.onSurface,
            padding: '10px 18px', borderRadius: 20, boxShadow: '0 4px 16px rgba(0,0,0,.15)',
            fontSize: 13, fontWeight: 600, letterSpacing: 0.1,
            display: 'flex', alignItems: 'center', gap: 8,
          }}>
            {(isRouting || isFinishing) && (
              <div style={{ width: 14, height: 14, borderRadius: 7,
                border: `2px solid ${t.primary}`, borderTopColor: 'transparent',
                animation: 'spin 1s linear infinite' }}/>
            )}
            {instruction}
          </div>
        </div>
      )}

      {/* Points counter pill (top-right) */}
      {hasPoints && !isFinishing && (
        <div style={{ position: 'absolute', top: 160, right: 16, zIndex: 3 }}>
          <div style={{
            background: t.surfaceContainerLowest, padding: '6px 12px', borderRadius: 14,
            display: 'flex', alignItems: 'center', gap: 6, boxShadow: '0 2px 8px rgba(0,0,0,.12)',
          }}>
            <Icons.Pin size={14} color={t.primary}/>
            <span style={{ fontSize: 12, fontWeight: 700, color: t.onSurface }}>3 points</span>
            <span style={{ fontSize: 11, color: t.onSurfaceVariant, fontWeight: 600 }}>· 1.8 km</span>
          </div>
        </div>
      )}

      {/* Centered pin overlay — green target reticle */}
      {pinActive && (
        <div style={{ position: 'absolute', inset: 0, zIndex: 2, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
          <div style={{ position: 'relative', width: 60, height: 60, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {/* Pulsing outer ring */}
            <div style={{
              position: 'absolute', width: 48, height: 48, borderRadius: 24,
              background: 'rgba(76,175,80,0.18)', border: '2px solid #4CAF50',
            }}/>
            {/* Center dot */}
            <div style={{
              width: 12, height: 12, borderRadius: 6, background: '#4CAF50',
              boxShadow: '0 0 0 3px rgba(255,255,255,0.9), 0 2px 6px rgba(0,0,0,0.25)',
            }}/>
            {/* Crosshair lines */}
            <div style={{ position: 'absolute', left: -8, top: '50%', width: 12, height: 2, background: '#4CAF50', transform: 'translateY(-50%)', borderRadius: 1 }}/>
            <div style={{ position: 'absolute', right: -8, top: '50%', width: 12, height: 2, background: '#4CAF50', transform: 'translateY(-50%)', borderRadius: 1 }}/>
            <div style={{ position: 'absolute', top: -8, left: '50%', height: 12, width: 2, background: '#4CAF50', transform: 'translateX(-50%)', borderRadius: 1 }}/>
            <div style={{ position: 'absolute', bottom: -8, left: '50%', height: 12, width: 2, background: '#4CAF50', transform: 'translateX(-50%)', borderRadius: 1 }}/>
          </div>
        </div>
      )}

      {/* Finishing/routing full-screen overlay */}
      {isFinishing && (
        <div style={{ position: 'absolute', inset: 0, zIndex: 10, background: 'rgba(0,0,0,.35)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', backdropFilter: 'blur(2px)' }}>
          <div style={{
            background: t.surfaceContainerLowest, borderRadius: 28, padding: '24px 32px',
            display: 'flex', alignItems: 'center', gap: 14, boxShadow: '0 12px 32px rgba(0,0,0,.25)',
          }}>
            <div style={{ width: 24, height: 24, borderRadius: 12,
              border: `2.5px solid ${t.primary}`, borderTopColor: 'transparent',
              animation: 'spin 1s linear infinite' }}/>
            <span style={{ fontSize: 16, fontWeight: 600, color: t.onSurface }}>Saving walk…</span>
          </div>
        </div>
      )}

      <div style={{ flex: 1 }}/>

      {/* Bottom app bar — Undo / Add Point / Finish */}
      <div style={{ position: 'relative', zIndex: 3, padding: '0 16px 16px', flexShrink: 0 }}>
        <div style={{
          background: t.surfaceContainerLowest, borderRadius: 32, padding: 12,
          boxShadow: '0 6px 24px rgba(0,0,0,.15)',
          display: 'flex', gap: 8, alignItems: 'center',
        }}>
          <Button t={t} variant="outlined" size="md"
            disabled={!hasPoints || isRouting || isFinishing}
            style={{ flex: 1, padding: '0 14px' }}>Undo</Button>
          <Button t={t} variant="filled" size="md"
            disabled={!pinActive}
            icon={<Icons.Plus size={18}/>}
            style={{ flex: 1.6, padding: '0 14px' }}>Add Point</Button>
          <Button t={t} variant="tonal" size="md"
            disabled={!hasSegments || isRouting || isFinishing}
            icon={<Icons.Check size={18}/>}
            style={{ flex: 1.2, padding: '0 14px' }}>Finish</Button>
        </div>
      </div>
    </Screen>
  );
}

Object.assign(window, {
  HomeScreen, RecordingScreen, HistoryScreen, WalkDetailScreen, SettingsScreen, ManualCreateScreen,
});
