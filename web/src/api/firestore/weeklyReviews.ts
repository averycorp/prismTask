import {
  collection,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  orderBy,
  query,
  setDoc,
  type DocumentData,
  type Unsubscribe,
} from 'firebase/firestore';
import { firestore } from '@/lib/firebase';
import { format } from 'date-fns';

/**
 * Firestore mapper for persisted weekly reviews. Mirrors Android's
 * `WeeklyReviewEntity` (parity audit C.4a). Each doc is one week of
 * aggregated stats plus an optional AI-generated narrative payload.
 *
 * Doc id is the ISO `YYYY-MM-DD` of the review week's Monday (Pattern A
 * canonical-row scheme — see `WEB_CANONICAL_ROW_DEDUP_PARITY_AUDIT.md`),
 * so a re-run of the aggregation worker over the same week is naturally
 * idempotent. Android's `cloud_id` column populates from this id on pull.
 */

const COLLECTION = 'weekly_reviews';

export interface WeeklyReview {
  /** Doc id == ISO YYYY-MM-DD of the review week's Monday. */
  id: string;
  /** Monday-of-week date in ISO YYYY-MM-DD. Equal to `id`; duplicated for ergonomics. */
  weekStartDate: string;
  /** Epoch ms — Monday 00:00 local of the review week. */
  weekStartMs: number;
  /** JSON-serialized metrics. Schema matches Android `WeeklyReviewAggregator` output. */
  metricsJson: string;
  /** JSON-serialized AI insights (Premium). Null on Free or pre-AI weeks. */
  aiInsightsJson: string | null;
  /** Epoch ms — when this row first landed (or was last reaggregated). */
  createdAt: number;
  /** Epoch ms — last mutation. Used by LWW guard. */
  updatedAt: number;
}

function reviewsCol(uid: string) {
  return collection(firestore, 'users', uid, COLLECTION);
}

function reviewDoc(uid: string, weekStartDate: string) {
  return doc(firestore, 'users', uid, COLLECTION, weekStartDate);
}

function read(id: string, data: DocumentData | undefined): WeeklyReview | null {
  if (!data) return null;
  const weekStartDate =
    typeof data.week_start_date === 'string' ? data.week_start_date : id;
  return {
    id,
    weekStartDate,
    weekStartMs:
      typeof data.week_start_ms === 'number' ? data.week_start_ms : 0,
    metricsJson: typeof data.metrics_json === 'string' ? data.metrics_json : '{}',
    aiInsightsJson:
      typeof data.ai_insights_json === 'string' ? data.ai_insights_json : null,
    createdAt: typeof data.created_at === 'number' ? data.created_at : 0,
    updatedAt: typeof data.updated_at === 'number' ? data.updated_at : 0,
  };
}

export async function getWeeklyReview(
  uid: string,
  weekStartDate: string,
): Promise<WeeklyReview | null> {
  const snap = await getDoc(reviewDoc(uid, weekStartDate));
  return read(snap.id, snap.exists() ? snap.data() : undefined);
}

export async function getRecentWeeklyReviews(
  uid: string,
  limit = 12,
): Promise<WeeklyReview[]> {
  const q = query(reviewsCol(uid), orderBy('week_start_date', 'desc'));
  const snap = await getDocs(q);
  const out: WeeklyReview[] = [];
  snap.forEach((d) => {
    const r = read(d.id, d.data());
    if (r) out.push(r);
  });
  return out.slice(0, limit);
}

export interface UpsertWeeklyReviewInput {
  /** Either a `Date` for Monday-of-week, or a pre-computed YYYY-MM-DD. */
  weekStart: Date | string;
  metricsJson: string;
  aiInsightsJson?: string | null;
}

export async function upsertWeeklyReview(
  uid: string,
  input: UpsertWeeklyReviewInput,
): Promise<WeeklyReview> {
  const weekStartDate =
    typeof input.weekStart === 'string'
      ? input.weekStart
      : format(input.weekStart, 'yyyy-MM-dd');
  const weekStartMs =
    typeof input.weekStart === 'string'
      ? Date.parse(input.weekStart)
      : input.weekStart.getTime();
  const now = Date.now();
  const existing = await getDoc(reviewDoc(uid, weekStartDate));
  const createdAt = existing.exists()
    ? typeof existing.data().created_at === 'number'
      ? existing.data().created_at
      : now
    : now;
  await setDoc(
    reviewDoc(uid, weekStartDate),
    {
      week_start_date: weekStartDate,
      week_start_ms: weekStartMs,
      metrics_json: input.metricsJson,
      ai_insights_json: input.aiInsightsJson ?? null,
      created_at: createdAt,
      updated_at: now,
    },
    { merge: true },
  );
  return {
    id: weekStartDate,
    weekStartDate,
    weekStartMs,
    metricsJson: input.metricsJson,
    aiInsightsJson: input.aiInsightsJson ?? null,
    createdAt,
    updatedAt: now,
  };
}

/**
 * Real-time listener over the user's weekly reviews collection. Emits
 * the most recent `limit` reviews (default 12) each time the underlying
 * documents change. Use within `useFirestoreSync.ts` once a consumer
 * UI surface wires the corresponding store.
 */
export function subscribeToWeeklyReviews(
  uid: string,
  cb: (reviews: WeeklyReview[]) => void,
  limit = 12,
): Unsubscribe {
  const q = query(reviewsCol(uid), orderBy('week_start_date', 'desc'));
  return onSnapshot(q, (snap) => {
    const out: WeeklyReview[] = [];
    snap.forEach((d) => {
      const r = read(d.id, d.data());
      if (r) out.push(r);
    });
    cb(out.slice(0, limit));
  });
}
