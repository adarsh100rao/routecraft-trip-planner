import { useState } from 'react';
import { Compass, MapPin, Users, Wallet, Calendar, Sparkles } from 'lucide-react';
import type { GenerateTripRequest, InterestTag, TripPace } from '../types/trip';

const ALL_INTERESTS: { tag: InterestTag; label: string }[] = [
  { tag: 'ADVENTURE', label: 'Adventure' },
  { tag: 'FOOD', label: 'Local Food' },
  { tag: 'CULTURE', label: 'Culture' },
  { tag: 'HISTORY', label: 'History' },
  { tag: 'NATURE', label: 'Nature' },
  { tag: 'NIGHTLIFE', label: 'Nightlife' },
  { tag: 'SHOPPING', label: 'Shopping' },
  { tag: 'RELAXATION', label: 'Relaxation' },
];

const PACES: { value: TripPace; label: string; desc: string }[] = [
  { value: 'RELAXED', label: 'Relaxed', desc: '10am–8pm · 4 stops/day' },
  { value: 'MODERATE', label: 'Moderate', desc: '9am–9pm · 6 stops/day' },
  { value: 'PACKED', label: 'Packed', desc: '8:30am–10pm · 8 stops/day' },
];

interface Props {
  onSubmit: (req: GenerateTripRequest) => void;
  disabled: boolean;
}

export function TripInputForm({ onSubmit, disabled }: Props) {
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() + 7);
    return d.toISOString().split('T')[0];
  });
  const [days, setDays] = useState(3);
  const [peopleCount, setPeopleCount] = useState(2);
  const [totalBudget, setTotalBudget] = useState(18000);
  const [currency, setCurrency] = useState('INR');
  const [pace, setPace] = useState<TripPace>('MODERATE');
  const [interests, setInterests] = useState<InterestTag[]>(['CULTURE', 'FOOD', 'ADVENTURE']);

  const toggleInterest = (tag: InterestTag) => {
    setInterests((prev) =>
      prev.includes(tag)
        ? prev.length > 1
          ? prev.filter((t) => t !== tag)
          : prev
        : [...prev, tag]
    );
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!destination.trim()) return;
    onSubmit({
      destination: destination.trim(),
      startDate,
      days,
      peopleCount,
      totalBudget,
      currency,
      pace,
      interests,
    });
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="mx-auto max-w-2xl rounded-2xl border border-slate-800 bg-slate-900/80 p-6 shadow-2xl backdrop-blur"
    >
      <div className="mb-6 flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-500/20 text-indigo-400">
          <Compass className="h-5 w-5" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-white">Plan Your Constraint-Optimized Trip</h2>
          <p className="text-xs text-slate-400">
            Real OpenStreetMap POIs · PostGIS geo-clustering · OSRM routing · Hard budget & opening-hour constraints
          </p>
        </div>
      </div>

      <div className="space-y-5">
        <div>
          <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-300">
            <MapPin className="h-4 w-4 text-indigo-400" /> Destination
          </label>
          <input
            type="text"
            required
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            placeholder="e.g., Jaipur, India or Goa, India or Tokyo, Japan"
            className="w-full rounded-xl border border-slate-700 bg-slate-950 px-4 py-2.5 text-sm text-white placeholder-slate-500 focus:border-indigo-500 focus:outline-none"
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-300">
              <Calendar className="h-4 w-4 text-indigo-400" /> Start Date
            </label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-white focus:border-indigo-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1.5 block text-sm font-medium text-slate-300">
              Duration ({days} {days === 1 ? 'Day' : 'Days'})
            </label>
            <input
              type="range"
              min={1}
              max={7}
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
              className="mt-2 w-full accent-indigo-500"
            />
          </div>

          <div>
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-300">
              <Users className="h-4 w-4 text-indigo-400" /> Travelers
            </label>
            <input
              type="number"
              min={1}
              max={20}
              value={peopleCount}
              onChange={(e) => setPeopleCount(Number(e.target.value))}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-white focus:border-indigo-500 focus:outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-300">
              <Wallet className="h-4 w-4 text-emerald-400" /> Total Group Budget
            </label>
            <input
              type="number"
              min={500}
              step={500}
              value={totalBudget}
              onChange={(e) => setTotalBudget(Number(e.target.value))}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-4 py-2 text-sm text-white focus:border-indigo-500 focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-sm font-medium text-slate-300">Currency</label>
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-white focus:border-indigo-500 focus:outline-none"
            >
              <option value="INR">INR (₹)</option>
              <option value="USD">USD ($)</option>
              <option value="EUR">EUR (€)</option>
            </select>
          </div>
        </div>

        <div>
          <label className="mb-2 block text-sm font-medium text-slate-300">Trip Pace</label>
          <div className="grid grid-cols-3 gap-2.5">
            {PACES.map((p) => (
              <button
                key={p.value}
                type="button"
                onClick={() => setPace(p.value)}
                className={`rounded-xl border px-3 py-2 text-left transition ${
                  pace === p.value
                    ? 'border-indigo-500 bg-indigo-500/15 text-white'
                    : 'border-slate-800 bg-slate-950/60 text-slate-400 hover:border-slate-700'
                }`}
              >
                <div className="text-xs font-semibold">{p.label}</div>
                <div className="text-[11px] opacity-75">{p.desc}</div>
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="mb-2 block text-sm font-medium text-slate-300">What do you enjoy?</label>
          <div className="flex flex-wrap gap-2">
            {ALL_INTERESTS.map(({ tag, label }) => {
              const active = interests.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => toggleInterest(tag)}
                  className={`rounded-full border px-3.5 py-1.5 text-xs font-medium transition ${
                    active
                      ? 'border-indigo-400 bg-indigo-500/20 text-indigo-200'
                      : 'border-slate-700 bg-slate-950 text-slate-400 hover:border-slate-600'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <button
          type="submit"
          disabled={disabled}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-indigo-600 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-indigo-600/25 transition hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Sparkles className="h-4 w-4" />
          {disabled ? 'Optimizing Itinerary...' : 'Generate Optimized Itinerary'}
        </button>
      </div>
    </form>
  );
}
