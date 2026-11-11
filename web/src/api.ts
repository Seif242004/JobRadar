// Client for the dashboard endpoints. Reads are GET; the only writes are the search preferences,
// the confirmation of a CV summary line, and a request to rebuild one CV.
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
  levels: string[];
  thin: boolean;
}

export interface ApplicationRow {
  [key: string]: unknown;
}

export interface Preferences {
  roles: string[];
  cities: string[];
  remote: boolean;
  jobTypes: string[];
  entryLevelOnly: boolean;
  maxYearsExperience: number;
  maxAgeDays: number;
  pushMinFit: number;
  digestMinFit: number;
  titleKeywords: string[];
  excludeKeywords: string[];
  onboarded: boolean;
}

export interface AtsCheck {
  name: string;
  ok: boolean;
  detail: string;
}

export interface AtsReport {
  score: number;
  matched: string[];
  missing: string[];
  checks: AtsCheck[];
  fixes: string[];
}

export interface CvInfo {
  id: number;
  atsScore: number;
  summary: string | null;
  atsReport: AtsReport | null;
  pdfUrl: string;
}

export interface PostingDetail {
  postingId: number;
  title: string;
  company: string | null;
  location: string | null;
  description: string | null;
  band: string | null;
  fit: number | null;
  reasons: string | null;
  applyChannel: string | null;
  applyTarget: string | null;
  cv: CvInfo | null;
  baseScores: BaseScore[];
  thin: boolean;
  enrichStatus: string | null;
}

export interface BaseCv { id: number; label: string; filename: string }
export interface BaseScore { cvId: number; label: string; ats: number; keywords: number; missing: string[] }

export interface SummaryRow {
  id: number;
  label: string;
  text: string;
  confirmed: boolean;
  focus: string[];
}

export class Unauthorized extends Error {}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/dashboard/${path}`, { credentials: "same-origin", ...init });
  if (response.status === 401) throw new Unauthorized("unauthorized");
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      message = (await response.json()).error ?? message;
    } catch {
      /* body was not JSON */
    }
    throw new Error(message);
  }
  // Some endpoints return an empty body (no user yet, or 204).
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const fetchSummary = () => call<Summary | null>("summary");
export const fetchMatches = () => call<MatchRow[]>("matches?limit=100");
export const fetchApplications = () => call<ApplicationRow[]>("applications?limit=100");
export const fetchPreferences = () => call<Preferences | null>("preferences");
export const savePreferences = (p: Preferences) => call<Preferences>("preferences", json("PUT", p));
export const fetchPosting = (id: number) => call<PostingDetail>(`postings/${id}`);
export const rebuildCv = (id: number) => call<{ status: string }>(`postings/${id}/cv/rebuild`, { method: "POST" });
export const fetchBaseCvs = () => call<BaseCv[]>("base-cvs");
export const deleteBaseCv = (id: number) => call<null>(`base-cvs/${id}`, { method: "DELETE" });
export const uploadBaseCv = (label: string, file: File) => {
  const form = new FormData();
  form.append("label", label);
  form.append("file", file);
  return call<BaseCv>("base-cvs", { method: "POST", body: form });
};
export const fetchSummaries = () => call<SummaryRow[]>("summaries");
export const confirmSummary = (id: number, confirmed: boolean) =>
  call<null>(`summaries/${id}/confirm`, json("POST", { confirmed }));

/** Sends the access token once; the api answers with a cookie so later requests need nothing. */
export const signIn = (token: string) => call<Summary | null>(`summary?token=${encodeURIComponent(token)}`);
