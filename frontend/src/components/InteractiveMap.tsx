import { useEffect, useMemo, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import type { StyleSpecification } from 'maplibre-gl';
import { ExternalLink, Layers, Maximize2, Mountain, X, Clock, Star } from 'lucide-react';
import type { DayPlanResponse, StopResponse } from '../types/trip';
import { decodePolyline } from '../utils/polyline';

type MapTheme = 'streets' | 'terrain' | 'vector3d' | 'satellite';

const DAY_COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ec4899', '#06b6d4', '#8b5cf6', '#ef4444'];

function rasterStyle(tileUrl: string, attribution: string, overlayUrl?: string): StyleSpecification {
  const sources: StyleSpecification['sources'] = {
    base: {
      type: 'raster',
      tiles: [tileUrl],
      tileSize: 256,
      attribution,
    },
  };
  const layers: StyleSpecification['layers'] = [
    { id: 'base-layer', type: 'raster', source: 'base' },
  ];
  if (overlayUrl) {
    sources.overlay = {
      type: 'raster',
      tiles: [overlayUrl],
      tileSize: 256,
    };
    layers.push({ id: 'overlay-layer', type: 'raster', source: 'overlay' });
  }
  return { version: 8, sources, layers };
}

function getMapStyle(theme: MapTheme): string | StyleSpecification {
  switch (theme) {
    case 'streets':
      return rasterStyle(
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',
        'Tiles &copy; Esri &mdash; Source: Esri, HERE, Garmin, OpenStreetMap'
      );
    case 'terrain':
      return rasterStyle(
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}',
        'Tiles &copy; Esri &mdash; World Topographic Map'
      );
    case 'vector3d':
      return 'https://tiles.openfreemap.org/styles/liberty';
    case 'satellite':
      return rasterStyle(
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        'Tiles &copy; Esri &mdash; World Imagery',
        'https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}'
      );
  }
}

interface Props {
  center: [number, number]; // [lat, lng]
  currency: string;
  activeDayPlan: DayPlanResponse;
  hoveredStopId: string | null;
  onSelectStop: (stopId: string | null) => void;
}

export function InteractiveMap({
  center,
  currency,
  activeDayPlan,
  hoveredStopId,
  onSelectStop,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markersRef = useRef<Map<string, { marker: maplibregl.Marker; el: HTMLDivElement }>>(new Map());
  const appliedThemeRef = useRef<MapTheme>('streets');

  const [theme, setTheme] = useState<MapTheme>('streets');
  const [is3D, setIs3D] = useState(false);
  const [selectedStop, setSelectedStop] = useState<StopResponse | null>(null);

  const dayColor = DAY_COLORS[(activeDayPlan.dayNumber - 1) % DAY_COLORS.length];
  const symbol = currency === 'INR' ? '₹' : currency === 'EUR' ? '€' : '$';

  // Convert decoded [lat, lng] polyline into GeoJSON [lng, lat] coordinates for MapLibre
  const routeCoords = useMemo<[number, number][]>(() => {
    const decoded = decodePolyline(activeDayPlan.routePolyline);
    if (decoded.length >= 2) {
      return decoded.map(([lat, lng]) => [lng, lat]);
    }
    return activeDayPlan.stops.map((s) => [s.lng, s.lat]);
  }, [activeDayPlan]);

  // Keep latest route data in refs so `style.load` always renders the current day's polyline
  const latestRouteRef = useRef({ routeCoords, dayColor, stops: activeDayPlan.stops });
  latestRouteRef.current = { routeCoords, dayColor, stops: activeDayPlan.stops };

  const googleMapsDayUrl = useMemo(() => {
    const stops = activeDayPlan.stops;
    if (stops.length === 0) return '';
    if (stops.length === 1) {
      return `https://www.google.com/maps/search/?api=1&query=${stops[0].lat},${stops[0].lng}`;
    }
    const origin = `${stops[0].lat},${stops[0].lng}`;
    const dest = `${stops[stops.length - 1].lat},${stops[stops.length - 1].lng}`;
    const waypoints = stops
      .slice(1, -1)
      .map((s) => `${s.lat},${s.lng}`)
      .join('|');
    const wpParam = waypoints ? `&waypoints=${encodeURIComponent(waypoints)}` : '';
    return `https://www.google.com/maps/dir/?api=1&origin=${origin}&destination=${dest}${wpParam}&travelmode=driving`;
  }, [activeDayPlan.stops]);

  const drawRoute = (map: maplibregl.Map, fitView: boolean) => {
    const { routeCoords: coords, dayColor: color, stops } = latestRouteRef.current;

    if (coords.length >= 2) {
      const geojson: GeoJSON.Feature<GeoJSON.LineString> = {
        type: 'Feature',
        properties: {},
        geometry: {
          type: 'LineString',
          coordinates: coords,
        },
      };

      const existingSource = map.getSource('day-route') as maplibregl.GeoJSONSource | undefined;
      if (existingSource) {
        existingSource.setData(geojson);
        if (map.getLayer('day-route-line')) {
          map.setPaintProperty('day-route-line', 'line-color', color);
        }
      } else {
        map.addSource('day-route', { type: 'geojson', data: geojson });
        map.addLayer({
          id: 'day-route-casing',
          type: 'line',
          source: 'day-route',
          layout: { 'line-join': 'round', 'line-cap': 'round' },
          paint: {
            'line-color': '#ffffff',
            'line-width': 8,
            'line-opacity': 0.92,
          },
        });
        map.addLayer({
          id: 'day-route-line',
          type: 'line',
          source: 'day-route',
          layout: { 'line-join': 'round', 'line-cap': 'round' },
          paint: {
            'line-color': color,
            'line-width': 4.5,
            'line-opacity': 0.95,
          },
        });
      }
    }

    if (fitView && stops.length > 0) {
      const bounds = new maplibregl.LngLatBounds();
      stops.forEach((s) => bounds.extend([s.lng, s.lat]));
      map.fitBounds(bounds, {
        padding: { top: 75, bottom: 115, left: 65, right: 65 },
        maxZoom: 14.8,
        duration: 650,
      });
    }
  };

  // 1. Initialize MapLibre GL once
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: getMapStyle(appliedThemeRef.current),
      center: [center[1], center[0]],
      zoom: 12.5,
      pitch: 0,
    });

    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'top-right');
    mapRef.current = map;

    map.on('style.load', () => {
      drawRoute(map, true);
    });

    return () => {
      markersRef.current.forEach(({ marker }) => marker.remove());
      markersRef.current.clear();
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 2. Switch map style ONLY when theme actually changes after mount
  useEffect(() => {
    const map = mapRef.current;
    if (!map || appliedThemeRef.current === theme) return;
    appliedThemeRef.current = theme;
    map.setStyle(getMapStyle(theme));
  }, [theme]);

  // 3. Toggle 3D pitch
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    map.easeTo({ pitch: is3D ? 50 : 0, bearing: is3D ? -15 : 0, duration: 500 });
  }, [is3D]);

  // 4. Sync markers & route whenever active day changes
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    setSelectedStop(null);

    markersRef.current.forEach(({ marker }) => marker.remove());
    markersRef.current.clear();

    activeDayPlan.stops.forEach((stop, idx) => {
      const isFood =
        stop.slotType === 'LUNCH' || stop.slotType === 'DINNER' || stop.slotType === 'CAFE';
      const pinColor = isFood ? '#f59e0b' : dayColor;

      const wrapper = document.createElement('div');
      wrapper.className = 'group cursor-pointer';
      wrapper.style.zIndex = '10';

      const pill = document.createElement('div');
      pill.style.cssText = `
        display:flex;align-items:center;gap:5px;padding:4px 9px 4px 5px;
        border-radius:9999px;background:#0f172a;color:#ffffff;
        border:2px solid ${pinColor};
        box-shadow:0 6px 16px rgba(0,0,0,0.4);
        transition:transform 160ms ease, box-shadow 160ms ease;
        white-space:nowrap;font-size:11px;font-weight:600;
      `;

      const badge = document.createElement('span');
      badge.textContent = String(idx + 1);
      badge.style.cssText = `
        width:20px;height:20px;border-radius:9999px;background:${pinColor};
        color:#ffffff;display:flex;align-items:center;justify-content:center;
        font-size:11px;font-weight:700;flex-shrink:0;
      `;

      const label = document.createElement('span');
      label.textContent = stop.name.length > 20 ? stop.name.slice(0, 19) + '…' : stop.name;
      label.style.maxWidth = '135px';
      label.style.overflow = 'hidden';
      label.style.textOverflow = 'ellipsis';

      pill.appendChild(badge);
      pill.appendChild(label);
      wrapper.appendChild(pill);

      wrapper.addEventListener('click', (e) => {
        e.stopPropagation();
        setSelectedStop(stop);
        onSelectStop(stop.stopId);
        map.flyTo({ center: [stop.lng, stop.lat], zoom: Math.max(map.getZoom(), 14.2), duration: 550 });
      });

      const marker = new maplibregl.Marker({ element: wrapper, anchor: 'center' })
        .setLngLat([stop.lng, stop.lat])
        .addTo(map);

      markersRef.current.set(stop.stopId, { marker, el: pill });
    });

    if (map.isStyleLoaded()) {
      drawRoute(map, true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDayPlan, dayColor, routeCoords]);

  // 5. Highlight marker when hovered in DayTimeline
  useEffect(() => {
    markersRef.current.forEach(({ el, marker }, stopId) => {
      const active = stopId === hoveredStopId;
      el.style.transform = active ? 'scale(1.14)' : 'scale(1)';
      el.style.boxShadow = active
        ? '0 0 0 4px rgba(99, 102, 241, 0.45), 0 10px 24px rgba(0,0,0,0.55)'
        : '0 6px 16px rgba(0,0,0,0.4)';
      marker.getElement().style.zIndex = active ? '40' : '10';
    });
  }, [hoveredStopId]);

  const handleRecenter = () => {
    const map = mapRef.current;
    if (!map || activeDayPlan.stops.length === 0) return;
    const bounds = new maplibregl.LngLatBounds();
    activeDayPlan.stops.forEach((s) => bounds.extend([s.lng, s.lat]));
    map.fitBounds(bounds, {
      padding: { top: 75, bottom: 115, left: 65, right: 65 },
      maxZoom: 14.8,
      duration: 600,
    });
  };

  return (
    <div className="relative h-full min-h-[480px] w-full overflow-hidden rounded-2xl border border-slate-800 shadow-2xl">
      <div ref={containerRef} className="h-full w-full" />

      {/* Top-Left Floating Map Style Switcher */}
      <div className="absolute left-3 top-3 z-20 flex flex-wrap items-center gap-1.5 rounded-xl border border-slate-700/80 bg-slate-900/85 p-1 shadow-lg backdrop-blur-md">
        <span className="flex items-center gap-1 px-2 text-[11px] font-medium text-slate-400">
          <Layers className="h-3.5 w-3.5 text-indigo-400" />
        </span>
        {(
          [
            { id: 'streets', label: 'Streets' },
            { id: 'terrain', label: 'Terrain' },
            { id: 'vector3d', label: '3D City' },
            { id: 'satellite', label: 'Satellite' },
          ] as { id: MapTheme; label: string }[]
        ).map((t) => (
          <button
            key={t.id}
            onClick={() => {
              setTheme(t.id);
              if (t.id === 'vector3d') setIs3D(true);
            }}
            className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold transition ${
              theme === t.id
                ? 'bg-indigo-600 text-white shadow'
                : 'text-slate-300 hover:bg-slate-800 hover:text-white'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Bottom-Right Quick Actions */}
      <div className="absolute bottom-8 right-3 z-20 flex items-center gap-2">
        <button
          onClick={() => setIs3D((v) => !v)}
          className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold shadow-lg backdrop-blur-md transition ${
            is3D
              ? 'border-indigo-400 bg-indigo-600/90 text-white'
              : 'border-slate-700/80 bg-slate-900/85 text-slate-200 hover:bg-slate-800'
          }`}
          title="Toggle 3D perspective tilt"
        >
          <Mountain className="h-3.5 w-3.5" /> 3D
        </button>

        <button
          onClick={handleRecenter}
          className="inline-flex items-center gap-1.5 rounded-xl border border-slate-700/80 bg-slate-900/85 px-3 py-2 text-xs font-semibold text-slate-200 shadow-lg backdrop-blur-md transition hover:bg-slate-800"
          title="Fit all stops for this day"
        >
          <Maximize2 className="h-3.5 w-3.5" /> Fit Route
        </button>

        {googleMapsDayUrl && (
          <a
            href={googleMapsDayUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 rounded-xl bg-emerald-600 px-3.5 py-2 text-xs font-semibold text-white shadow-lg shadow-emerald-950/40 transition hover:bg-emerald-500"
          >
            <ExternalLink className="h-3.5 w-3.5" /> Open in Google Maps
          </a>
        )}
      </div>

      {/* Bottom-Left Selected Stop Glassmorphic Info Card */}
      {selectedStop && (
        <div className="absolute bottom-8 left-3 z-30 w-80 max-w-[calc(100%-1.5rem)] rounded-2xl border border-slate-700/90 bg-slate-900/95 p-4 shadow-2xl backdrop-blur-md">
          <div className="flex items-start justify-between gap-2">
            <div>
              <div className="text-[10px] font-semibold uppercase tracking-wider text-indigo-400">
                Stop #{selectedStop.orderIndex + 1} ·{' '}
                {selectedStop.slotType === 'ACTIVITY' ? selectedStop.category : selectedStop.slotType}
              </div>
              <h4 className="mt-0.5 text-sm font-bold text-white">{selectedStop.name}</h4>
            </div>
            <button
              onClick={() => {
                setSelectedStop(null);
                onSelectStop(null);
              }}
              className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-white"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-300">
            <span className="inline-flex items-center gap-1">
              <Clock className="h-3.5 w-3.5 text-indigo-400" />
              {selectedStop.startTime.slice(0, 5)} – {selectedStop.endTime.slice(0, 5)}
            </span>
            <span className="font-semibold text-emerald-400">
              {symbol}
              {selectedStop.estimatedCost.toLocaleString()}
            </span>
            <span className="inline-flex items-center gap-0.5 text-amber-300">
              <Star className="h-3 w-3 fill-amber-300" /> {selectedStop.rating.toFixed(1)}
            </span>
          </div>

          {selectedStop.tags?.localTip && (
            <p className="mt-2 text-xs leading-relaxed text-slate-300/90">
              {selectedStop.tags.localTip}
            </p>
          )}

          <a
            href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(
              selectedStop.name
            )}+${selectedStop.lat},${selectedStop.lng}`}
            target="_blank"
            rel="noreferrer"
            className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-indigo-400 hover:text-indigo-300"
          >
            <ExternalLink className="h-3.5 w-3.5" /> View Place & Reviews on Google Maps
          </a>
        </div>
      )}
    </div>
  );
}
