import { useEffect, useState } from "react";
import {
  ApplicationRow,
  MatchRow,
  Summary,
  fetchApplications,
  fetchMatches,
  fetchSummary,
} from "./api";

type State = { summary: Summary | null; matches: MatchRow[]; applications: ApplicationRow[] };

export function App() {
  const [state, setState] = useState<State | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Load all three read-only views together.
    Promise.all([fetchSummary(), fetchMatches(), fetchApplications()])
      .then(([summary, matches, applications]) => setState({ summary, matches, applications }))
      .catch((e: Error) => setError(e.message));
  }, []);

  if (error) return <main><p className="error">Could not load the dashboard: {error}. Is the api running with the local-dashboard profile?</p></main>;
  if (!state) return <main><p>Loading…</p></main>;
  if (!state.summary) return <main><p>No user yet. Import a profile seed first.</p></main>;

  const s = state.summary;
  const stats: [string, number][] = [
    ["Postings", s.postings],
    ["Eligible matches", s.eligibleMatches],
    ["Hot/warm with CV", s.hotOrWarmWithCv],
    ["Applications", s.applications],
    ["Replies", s.replies],
    ["Follow-ups due", s.followUpsDue],
  ];

  return (
    <main>
      <h1>JobRadar <small>{s.email}</small></h1>
      <section className="stats">
        {stats.map(([label, value]) => (
          <div key={label} className="stat"><b>{value}</b><span>{label}</span></div>
        ))}
      </section>

      <h2>Matches</h2>
      {state.matches.length === 0 ? <p>No eligible matches yet.</p> : (
        <table>
          <thead><tr><th>Title</th><th>Company</th><th>Location</th><th>Band</th><th>Fit</th><th>CV</th><th>Apply</th></tr></thead>
          <tbody>
            {state.matches.map((m) => (
              <tr key={m.postingId} title={m.reasons ?? ""}>
                <td>{m.title}</td>
                <td>{m.company ?? "—"}</td>
                <td>{m.location ?? "—"}</td>
                <td>{m.band}</td>
                <td>{m.fit ?? "—"}</td>
                <td>{m.cvReady ? "ready" : "—"}</td>
                <td>{m.applyTarget && /^https?:\/\//.test(m.applyTarget)
                  ? <a href={m.applyTarget} target="_blank" rel="noreferrer noopener">open</a>
                  : (m.applyTarget ?? "—")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Applications</h2>
      {state.applications.length === 0 ? <p>No applications yet.</p> : (
        <table>
          <thead><tr>{Object.keys(state.applications[0]).map((k) => <th key={k}>{k}</th>)}</tr></thead>
          <tbody>
            {state.applications.map((a, i) => (
              <tr key={i}>{Object.values(a).map((v, j) => <td key={j}>{v == null ? "—" : String(v)}</td>)}</tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
