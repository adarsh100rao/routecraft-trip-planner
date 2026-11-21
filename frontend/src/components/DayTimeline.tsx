import { Clock, Lock, Unlock, Trash2, RefreshCw, Utensils, Compass, Navigation } from 'lucide-react';
import type { DayPlanResponse } from '../types/trip';

interface Props {
  dayPlan: DayPlanResponse;
  currency: string;
  hoveredStopId: string | null;
  isReoptimizing: boolean;
  onHoverStop: (stopId: string | null) => void;
  onToggleLock: (stopId: string, locked: boolean) => void;
  onDeleteStop: (stopId: string, poiId: string) => void;
  onReoptimize: () => void;
}

export function DayTimeline({
  dayPlan,
  currency,
  hoveredStopId,
  isReoptimizing,
  onHoverStop,
  onToggleLock,
  onDeleteStop,
  onReoptimize,
}: Props) {
  const symbol = currency === 'INR' ? '₹' : currency === 'EUR' ? '€' : '$';

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between rounded-xl border border-slate-800 bg-slate-900/80 px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold text-white">
            Day {dayPlan.dayNumber} · <span className="text-slate-400">{dayPlan.date}</span>
          </h3>
          <p className="text-xs text-slate-400">
            {dayPlan.stops.length} stops · {dayPlan.totalTravelMinutes} min transit · Est. {symbol}
            {dayPlan.totalEstimatedCost.toLocaleString()}
          </p>
        </div>
        <button
          onClick={onReoptimize}
          disabled={isReoptimizing}
          className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-500/40 bg-indigo-500/15 px-3 py-1.5 text-xs font-medium text-indigo-300 transition hover:bg-indigo-500/25 disabled:opacity-50"
          title="Keep locked stops fixed and re-optimize the rest of this day"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${isReoptimizing ? 'animate-spin' : ''}`} />
          Re-optimize Day
        </button>
      </div>

      {dayPlan.stops.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-800 p-8 text-center text-xs text-slate-500">
          No stops scheduled for this day. Click "Re-optimize Day" to fill it.
        </div>
      ) : (
        <div className="space-y-2.5">
          {dayPlan.stops.map((stop, idx) => {
            const isFood = stop.slotType === 'LUNCH' || stop.slotType === 'DINNER' || stop.slotType === 'CAFE';
            const isHovered = hoveredStopId === stop.stopId;
            return (
              <div key={stop.stopId}>
                {idx > 0 && stop.travelMinsFromPrev > 0 && (
                  <div className="mb-2 ml-5 flex items-center gap-1.5 text-[11px] text-slate-500">
                    <Navigation className="h-3 w-3 text-slate-600" />
                    ~{stop.travelMinsFromPrev} min travel to next stop
                  </div>
                )}

                <div
                  onMouseEnter={() => onHoverStop(stop.stopId)}
                  onMouseLeave={() => onHoverStop(null)}
                  className={`rounded-xl border p-3.5 transition ${
                    isHovered
                      ? 'border-indigo-500 bg-slate-900'
                      : 'border-slate-800 bg-slate-900/60 hover:border-slate-700'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-start gap-3">
                      <div
                        className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ${
                          isFood ? 'bg-amber-500' : 'bg-indigo-600'
                        }`}
                      >
                        {idx + 1}
                      </div>
                      <div>
                        <div className="flex flex-wrap items-center gap-2">
                          <h4 className="text-sm font-semibold text-white">{stop.name}</h4>
                          <span
                            className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-medium ${
                              isFood
                                ? 'bg-amber-500/15 text-amber-300'
                                : 'bg-indigo-500/15 text-indigo-300'
                            }`}
                          >
                            {isFood ? <Utensils className="h-2.5 w-2.5" /> : <Compass className="h-2.5 w-2.5" />}
                            {stop.slotType === 'ACTIVITY' ? stop.category : stop.slotType}
                          </span>
                        </div>

                        <div className="mt-1 flex flex-wrap items-center gap-3 text-xs text-slate-400">
                          <span className="inline-flex items-center gap-1">
                            <Clock className="h-3 w-3 text-slate-500" />
                            {stop.startTime.slice(0, 5)} – {stop.endTime.slice(0, 5)}
                          </span>
                          <span>
                            {symbol}
                            {stop.estimatedCost.toLocaleString()}
                          </span>
                          <span>★ {stop.rating.toFixed(1)}</span>
                          {stop.tags?.grounding === 'OSM_VERIFIED' && (
                            <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-[10px] font-medium text-emerald-300">
                              OSM Verified
                            </span>
                          )}
                          {stop.hoursEstimated && (
                            <span className="text-[10px] text-slate-500">(est. hours)</span>
                          )}
                        </div>
                        {stop.tags?.localTip && (
                          <p className="mt-1.5 text-xs leading-relaxed text-slate-300/90">
                            {stop.tags.localTip}
                          </p>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => onToggleLock(stop.stopId, !stop.locked)}
                        className={`rounded-lg p-1.5 transition ${
                          stop.locked
                            ? 'bg-emerald-500/20 text-emerald-300'
                            : 'text-slate-500 hover:bg-slate-800 hover:text-slate-300'
                        }`}
                        title={stop.locked ? 'Unlock stop' : 'Lock stop (keep fixed on re-optimize)'}
                      >
                        {stop.locked ? <Lock className="h-3.5 w-3.5" /> : <Unlock className="h-3.5 w-3.5" />}
                      </button>
                      <button
                        onClick={() => onDeleteStop(stop.stopId, stop.poiId)}
                        className="rounded-lg p-1.5 text-slate-500 transition hover:bg-rose-500/20 hover:text-rose-400"
                        title="Remove stop"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
