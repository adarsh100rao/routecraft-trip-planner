import { useEffect, useState } from 'react';
import { ArrowLeft, Compass, Loader2 } from 'lucide-react';
import { useTripStream } from './hooks/useTripStream';
import { TripInputForm } from './components/TripInputForm';
import { GenerationStepper } from './components/GenerationStepper';
import { BudgetBreakdownBar } from './components/BudgetBreakdownBar';
import { DayTimeline } from './components/DayTimeline';
import { InteractiveMap } from './components/InteractiveMap';
import { deleteStop, fetchTrip, reoptimizeDay, toggleStopLock } from './api/tripApi';

function updateUrlParams(tripId: string | null, day?: number) {
  const url = new URL(window.location.href);
  if (tripId) {
    url.searchParams.set('tripId', tripId);
    url.searchParams.set('day', String(day ?? 1));
  } else {
    url.searchParams.delete('tripId');
    url.searchParams.delete('day');
  }
  window.history.replaceState(null, '', url.toString());
}

export function App() {
  const { isGenerating, stages, progress, error, trip, setTrip, generate, reset } = useTripStream();
  const [activeDay, setActiveDay] = useState<number>(() => {
    const dayParam = Number(new URLSearchParams(window.location.search).get('day'));
    return Number.isInteger(dayParam) && dayParam >= 1 ? dayParam : 1;
  });
  const [isHydrating, setIsHydrating] = useState<boolean>(() =>
    Boolean(new URLSearchParams(window.location.search).get('tripId'))
  );
  const [hoveredStopId, setHoveredStopId] = useState<string | null>(null);
  const [excludedByDay, setExcludedByDay] = useState<Record<number, string[]>>({});
  const [isReoptimizing, setIsReoptimizing] = useState(false);

  // Restore saved trip from PostgreSQL on page refresh if ?tripId=... is present
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const savedTripId = params.get('tripId');
    if (!savedTripId) return;

    fetchTrip(savedTripId)
      .then((loaded) => {
        setTrip(loaded);
        const dayParam = Number(params.get('day'));
        if (Number.isInteger(dayParam) && dayParam >= 1 && dayParam <= loaded.days) {
          setActiveDay(dayParam);
        } else {
          setActiveDay(1);
        }
      })
      .catch(() => {
        updateUrlParams(null);
      })
      .finally(() => setIsHydrating(false));
  }, [setTrip]);

  // Keep URL synced with active trip & day so browser refresh preserves exact page state
  useEffect(() => {
    if (trip) {
      updateUrlParams(trip.tripId, activeDay);
    }
  }, [trip, activeDay]);

  const activeDayPlan = trip?.dayPlans.find((d) => d.dayNumber === activeDay) ?? trip?.dayPlans[0];

  const handleToggleLock = async (stopId: string, locked: boolean) => {
    if (!trip) return;
    await toggleStopLock(trip.tripId, stopId, locked);
    const updated = await fetchTrip(trip.tripId);
    setTrip(updated);
  };

  const handleDeleteStop = async (stopId: string, poiId: string) => {
    if (!trip) return;
    setExcludedByDay((prev) => ({
      ...prev,
      [activeDay]: [...(prev[activeDay] ?? []), poiId],
    }));
    await deleteStop(trip.tripId, stopId);
    const updated = await fetchTrip(trip.tripId);
    setTrip(updated);
  };

  const handleReoptimizeDay = async () => {
    if (!trip || !activeDayPlan) return;
    setIsReoptimizing(true);
    try {
      const lockedIds = activeDayPlan.stops.filter((s) => s.locked).map((s) => s.stopId);
      const excluded = excludedByDay[activeDay] ?? [];
      await reoptimizeDay(trip.tripId, activeDayPlan.dayNumber, excluded, lockedIds);
      const updated = await fetchTrip(trip.tripId);
      setTrip(updated);
    } finally {
      setIsReoptimizing(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800/80 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3.5">
          <div className="flex items-center gap-2.5">
            <Compass className="h-6 w-6 text-indigo-400" />
            <span className="text-base font-bold tracking-tight text-white">RouteCraft</span>
            <span className="rounded-full bg-indigo-500/15 px-2 py-0.5 text-[10px] font-medium text-indigo-300">
              PostGIS + OSRM Engine
            </span>
          </div>

          {trip && (
            <button
              onClick={() => {
                setActiveDay(1);
                setExcludedByDay({});
                updateUrlParams(null);
                reset();
              }}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-300 hover:bg-slate-900"
            >
              <ArrowLeft className="h-3.5 w-3.5" /> New Trip
            </button>
          )}
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-8">
        {error && (
          <div className="mx-auto mb-6 max-w-2xl rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-sm text-rose-300">
            {error}
          </div>
        )}

        {isHydrating && (
          <div className="flex flex-col items-center justify-center py-24 text-sm text-slate-400">
            <Loader2 className="mb-3 h-6 w-6 animate-spin text-indigo-400" />
            Restoring your saved itinerary...
          </div>
        )}

        {!isHydrating && !trip && !isGenerating && (
          <TripInputForm
            onSubmit={(req) => {
              setActiveDay(1);
              generate(req);
            }}
            disabled={isGenerating}
          />
        )}

        {isGenerating && <GenerationStepper stages={stages} progress={progress} />}

        {trip && activeDayPlan && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-end justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold text-white">{trip.destination}</h1>
                <p className="text-xs text-slate-400">
                  {trip.days} Days · {trip.peopleCount}{' '}
                  {trip.peopleCount === 1 ? 'Traveler' : 'Travelers'} · {trip.pace} Pace
                </p>
              </div>

              <div className="flex gap-1.5">
                {trip.dayPlans.map((d) => (
                  <button
                    key={d.dayNumber}
                    onClick={() => setActiveDay(d.dayNumber)}
                    className={`rounded-lg px-3.5 py-1.5 text-xs font-semibold transition ${
                      activeDayPlan.dayNumber === d.dayNumber
                        ? 'bg-indigo-600 text-white shadow'
                        : 'border border-slate-800 bg-slate-900 text-slate-400 hover:text-white'
                    }`}
                  >
                    Day {d.dayNumber}
                  </button>
                ))}
              </div>
            </div>

            <BudgetBreakdownBar trip={trip} />

            <div className="grid grid-cols-1 gap-5 lg:grid-cols-12">
              <div className="lg:col-span-5">
                <DayTimeline
                  dayPlan={activeDayPlan}
                  currency={trip.currency}
                  hoveredStopId={hoveredStopId}
                  isReoptimizing={isReoptimizing}
                  onHoverStop={setHoveredStopId}
                  onToggleLock={handleToggleLock}
                  onDeleteStop={handleDeleteStop}
                  onReoptimize={handleReoptimizeDay}
                />
              </div>

              <div className="lg:col-span-7 lg:sticky lg:top-6 lg:h-[calc(100vh-14rem)]">
                <InteractiveMap
                  center={[trip.centerLat, trip.centerLng]}
                  currency={trip.currency}
                  activeDayPlan={activeDayPlan}
                  hoveredStopId={hoveredStopId}
                  onSelectStop={setHoveredStopId}
                />
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;
