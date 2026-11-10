// Read-only client for the local dashboard endpoints (GET only; the api offers no writes).
export interface Summary {
  email: string;
  postings: number;
  eligibleMatches: number;
  hotOrWarmWithCv: number;
  applications: number;
  replies: number;
  followUpsDue: number;
}

export interface MatchRow {
  postingId: number;
  title: string;
  company: string | null;
  location: string | null;
  band: string;
  fit: number | null;
  reasons: string | null;
  applyChannel: string | null;
  applyTarget: string | null;
  firstSeenAt: string;
  cvReady: boolean;
}

export interface ApplicationRow {
  [key: string]: unknown;
}

async function get<T>(path: string): Promise<T> {
  const response = await fetch(`/api/dashboard/${path}`);
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  // /summary returns an empty body when no user exists yet.
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
}

export const fetchSummary = () => get<Summary | null>("summary");
export const fetchMatches = () => get<MatchRow[]>("matches?limit=100");
export const fetchApplications = () => get<ApplicationRow[]>("applications?limit=100");
