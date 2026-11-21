import type { TripResponse } from '../types/trip';

interface Props {
  trip: TripResponse;
}

export function BudgetBreakdownBar({ trip }: Props) {
  const foodPct = Math.min(100, (trip.foodCost / trip.totalBudget) * 100);
  const actPct = Math.min(100 - foodPct, (trip.activityCost / trip.totalBudget) * 100);
  const remaining = Math.max(0, trip.totalBudget - trip.estimatedTotalCost);
  const symbol = trip.currency === 'INR' ? '₹' : trip.currency === 'EUR' ? '€' : '$';

  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/80 p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs">
        <span className="font-semibold text-slate-200">
          Budget Usage: {symbol}
          {trip.estimatedTotalCost.toLocaleString()} / {symbol}
          {trip.totalBudget.toLocaleString()}
        </span>
        <span className="text-emerald-400">
          {symbol}
          {remaining.toLocaleString()} buffer remaining
        </span>
      </div>

      <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-slate-800">
        <div
          className="bg-amber-400 transition-all"
          style={{ width: `${foodPct}%` }}
          title={`Food: ${symbol}${trip.foodCost.toLocaleString()}`}
        />
        <div
          className="bg-indigo-500 transition-all"
          style={{ width: `${actPct}%` }}
          title={`Activities: ${symbol}${trip.activityCost.toLocaleString()}`}
        />
      </div>

      <div className="mt-2 flex flex-wrap gap-4 text-[11px] text-slate-400">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-amber-400" />
          Food & Dining: {symbol}
          {trip.foodCost.toLocaleString()}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-indigo-500" />
          Places & Activities: {symbol}
          {trip.activityCost.toLocaleString()}
        </span>
        <span className="ml-auto font-mono text-[10px] text-slate-500">
          Routing: {trip.routingMode} · {trip.cacheHit ? 'Spatial Cache HIT' : 'Cold Fetch'}
          {trip.generationTimeMs > 0 ? ` · ${trip.generationTimeMs}ms` : ''}
        </span>
      </div>
    </div>
  );
}
