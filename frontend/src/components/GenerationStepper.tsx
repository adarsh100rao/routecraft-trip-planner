import { CheckCircle2, Loader2, Zap } from 'lucide-react';
import type { SseProgressEvent } from '../types/trip';

interface Props {
  stages: SseProgressEvent[];
  progress: number;
}

export function GenerationStepper({ stages, progress }: Props) {
  return (
    <div className="mx-auto max-w-xl rounded-2xl border border-[#24304A] bg-[#131C2E]/90 p-6 shadow-xl backdrop-blur-md">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-semibold text-slate-100">
          <Loader2 className="h-4 w-4 animate-spin text-[#FF6B4A]" />
          Building Your Personalized Itinerary
        </div>
        <span className="font-mono text-xs text-[#FFB547]">{progress}%</span>
      </div>

      <div className="mb-5 h-2 w-full overflow-hidden rounded-full bg-[#0B1220]">
        <div
          className="h-full rounded-full bg-gradient-to-r from-[#FF6B4A] to-[#FFB547] transition-all duration-300"
          style={{ width: `${progress}%` }}
        />
      </div>

      <div className="space-y-2.5">
        {stages.map((s, idx) => {
          const isLatest = idx === stages.length - 1;
          const cacheHit = s.metadata?.cacheHit;
          return (
            <div
              key={`${s.stage}-${idx}`}
              className="flex items-start gap-2.5 rounded-lg border border-[#24304A]/60 bg-[#0B1220]/75 px-3 py-2 text-xs"
            >
              {isLatest ? (
                <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin text-[#FF6B4A]" />
              ) : (
                <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#FFB547]" />
              )}
              <div className="flex-1 text-slate-300">
                <span className="font-mono text-[10px] uppercase tracking-wider text-slate-500">
                  [{s.stage}]
                </span>{' '}
                {s.message}
              </div>
              {typeof cacheHit === 'boolean' && (
                <span
                  className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                    cacheHit
                      ? 'bg-[#FFB547]/20 text-[#FFB547]'
                      : 'bg-[#FF6B4A]/20 text-[#FF8566]'
                  }`}
                >
                  <Zap className="h-2.5 w-2.5" />
                  {cacheHit ? 'FAST' : 'LIVE'}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
