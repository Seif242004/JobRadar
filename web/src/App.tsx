import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  ApplicationRow,
  MatchRow,
  Preferences,
  PostingDetail,
  Summary,
  SummaryRow,
  Unauthorized,
  confirmSummary,
  fetchApplications,
  fetchMatches,
  fetchPosting,
  fetchPreferences,
  fetchSummaries,
  fetchSummary,
  rebuildCv,
  fetchBaseCvs,
  deleteBaseCv,
  uploadBaseCv,
  type BaseCv,
  savePreferences,
  signIn,
  addPosting,
  pasteDescription,
} from "./api";
import { DEFAULT_EXCLUDES, JOB_TYPES, ROLE_PRESETS, presetIdsFromRoles } from "./presets";

type Tab = "matches" | "add" | "applications" | "settings";

/** "2h ago", "3d ago": freshness is the main thing a job seeker scans for. */
function ago(iso: string): string {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (minutes < 60) return `${minutes}m ago`;
  if (minutes < 60 * 48) return `${Math.round(minutes / 60)}h ago`;
  return `${Math.round(minutes / 1440)}d ago`;
}

const splitList = (text: string) =>
  text
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter(Boolean);

export function App() {
  const [auth, setAuth] = useState<"checking" | "needed" | "ok">("checking");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [matches, setMatches] = useState<MatchRow[]>([]);
  const [applications, setApplications] = useState<ApplicationRow[]>([]);
  const [tab, setTab] = useState<Tab>("matches");
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const [s, p, m, a] = await Promise.all([fetchSummary(), fetchPreferences(), fetchMatches(), fetchApplications()]);
      setSummary(s);
      setPrefs(p);
      setMatches(m);
      setApplications(a);
      setAuth("ok");
      setError(null);
    } catch (e) {
      if (e instanceof Unauthorized) setAuth("needed");
      else setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // The "JobRadar is online" push opens /?token=...: sign in with it once, then hide it from the address bar.
    const token = new URLSearchParams(window.location.search).get("token");
    const start = token
      ? signIn(token).catch(() => undefined).finally(() => window.history.replaceState(null, "", window.location.pathname))
      : Promise.resolve();
    start.then(load);
    const timer = setInterval(load, 5 * 60 * 1000); // fresh postings arrive all day
    return () => clearInterval(timer);
  }, [load]);

  if (auth === "checking" && !error) return <main className="center"><p className="muted">Loading…</p></main>;
  if (auth === "needed") return <SignIn onDone={load} />;
  if (error) return <main className="center"><p className="error">Could not load: {error}</p><button onClick={load}>Try again</button></main>;
  if (!summary) return <main className="center"><p>No profile yet. Import your profile seed first.</p></main>;
  if (!prefs?.onboarded) return <Onboarding initial={prefs} onDone={load} />;

  return (
    <div className="app">
      <header className="top">
        <h1>JobRadar</h1>
        <span className="muted">{summary.eligibleMatches} matches · {summary.applications} applied</span>
      </header>
      <main>
        {tab === "matches" && <Matches rows={matches} onOpen={setOpenId} />}
        {tab === "add" && <AddPosting onAdded={(id) => { load(); setTab("matches"); setOpenId(id); }} />}
        {tab === "applications" && <Applications rows={applications} />}
        {tab === "settings" && <Settings prefs={prefs} onSaved={load} />}
      </main>
      <nav className="tabs" aria-label="Sections">
        {(["matches", "add", "applications", "settings"] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? "on" : ""} aria-current={tab === t} onClick={() => setTab(t)}>
            {t === "matches" ? "Matches" : t === "add" ? "Add" : t === "applications" ? "Applied" : "Settings"}
          </button>
        ))}
      </nav>
      {openId !== null && <PostingSheet id={openId} onClose={() => setOpenId(null)} />}
    </div>
  );
}

// ------------------------------------------------------------------------------ sign-in

function SignIn({ onDone }: { onDone: () => void }) {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <main className="center">
      <form
        className="card narrow"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await signIn(token.trim());
            onDone();
          } catch {
            setError("That access key did not work.");
          }
        }}
      >
        <h1>JobRadar</h1>
        <label htmlFor="token">Access key</label>
        <input id="token" type="password" autoComplete="current-password" value={token} onChange={(e) => setToken(e.target.value)} />
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" type="submit" disabled={token.trim().length < 8}>Open dashboard</button>
      </form>
    </main>
  );
}

// ------------------------------------------------------------------------------ matches

function Matches({ rows, onOpen }: { rows: MatchRow[]; onOpen: (id: number) => void }) {
  const [filter, setFilter] = useState<string>("all");
  const [sort, setSort] = useState<"new" | "fit">("new");
  // "all", "hot", "cv", or one of the posting levels (student, non-graduate, fresh-grad, junior).
  const shown = [...rows].sort((a, b) =>
    sort === "fit" ? (b.fit ?? -1) - (a.fit ?? -1) : Date.parse(b.firstSeenAt) - Date.parse(a.firstSeenAt),
  ).filter((m) =>
    filter === "all" ? true : filter === "hot" ? m.band === "hot" : filter === "cv" ? m.cvReady : m.levels.includes(filter));
  return (
    <section>
      <div className="chips" role="group" aria-label="Filter">
        {([["all", "All"], ["hot", "Hot"], ["cv", "CV ready"], ["student", "Student"], ["non-graduate", "Non-graduate"], ["fresh-grad", "Fresh grad"], ["junior", "Junior"]] as const).map(([id, label]) => (
          <button key={id} className={`chip ${filter === id ? "on" : ""}`} aria-pressed={filter === id} onClick={() => setFilter(id)}>
            {label}
          </button>
        ))}
      </div>
      <div className="chips" role="group" aria-label="Sort">
        {([["new", "Newest first"], ["fit", "Best fit first"]] as const).map(([id, label]) => (
          <button key={id} className={`chip ${sort === id ? "on" : ""}`} aria-pressed={sort === id} onClick={() => setSort(id)}>
            {label}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="empty">
          {rows.length === 0
            ? "No matches yet. New postings are checked every 15 minutes; widen your preferences in Settings if this stays empty."
            : "Nothing in this filter."}
        </p>
      ) : (
        <ul className="list">
          {shown.map((m) => (
            <li key={m.postingId}>
              <button className="card job" onClick={() => onOpen(m.postingId)}>
                <span className="row">
                  <b dir="auto" className="title">{m.title}</b>
                  {m.fit !== null && <span className={`fit ${m.fit >= 80 ? "hi" : m.fit >= 60 ? "mid" : ""}`}>{Math.round(m.fit)}</span>}
                </span>
                <span dir="auto" className="muted">{m.company ?? "Unknown company"} · {m.location ?? "Location not listed"}</span>
                <span className="row small">
                  <span className={`tag ${m.band}`}>{m.band}</span>
                  {m.cvReady && <span className="tag cv">CV ready</span>}
                  {m.levels.map((l) => <span key={l} className="tag">{l}</span>)}
                  {m.thin && <span className="tag" title="Only a short description is stored, so the score is low-confidence">short text</span>}
                  <span className="muted">{ago(m.firstSeenAt)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------ add / paste
/** Add a posting seen in an app or on a phone: paste its link, its text, or both. */
function AddPosting({ onAdded }: { onAdded: (id: number) => void }) {
  const [url, setUrl] = useState("");
  const [title, setTitle] = useState("");
  const [company, setCompany] = useState("");
  const [location, setLocation] = useState("");
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { id } = await addPosting({ url, title, company, location, text });
      onAdded(id);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="add" onSubmit={submit}>
      <h2>Add a posting</h2>
      <p className="muted small">Seen one in the Indeed or Bayt app? Paste its link, or copy the whole post text. It is scored against both your CVs within a minute.</p>
      <label>Link <input type="url" inputMode="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://" /></label>
      <label>Posting text <textarea rows={8} value={text} onChange={(e) => setText(e.target.value)} /></label>
      <label>Title (optional) <input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
      <label>Company (optional) <input value={company} onChange={(e) => setCompany(e.target.value)} /></label>
      <label>Location (optional) <input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Cairo, Egypt" /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="primary" disabled={busy || (!url.trim() && text.trim().length < 40)}>{busy ? "Adding…" : "Add and score"}</button>
    </form>
  );
}

/** One-tap fix for a low-confidence posting: paste the real text and it is scored again. */
function PasteDescription({ id, onDone }: { id: number; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return <button onClick={() => setOpen(true)}>Paste full description</button>;
  return (
    <div className="paste">
      <label>Full posting text <textarea rows={8} value={text} onChange={(e) => setText(e.target.value)} /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button
        className="primary"
        disabled={busy || text.trim().length < 40}
        onClick={async () => {
          setBusy(true);
          try {
            await pasteDescription(id, text);
            setOpen(false);
            onDone();
          } catch (err) {
            setError((err as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        {busy ? "Saving…" : "Save and score again"}
      </button>
    </div>
  );
}

function PostingSheet({ id, onClose }: { id: number; onClose: () => void }) {
  const [posting, setPosting] = useState<PostingDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    setPosting(null);
    fetchPosting(id).then(setPosting).catch((e: Error) => setError(e.message));
  }, [id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const apply = posting?.applyTarget && /^https?:\/\//.test(posting.applyTarget) ? posting.applyTarget : null;
  const report = posting?.cv?.atsReport ?? null;

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" role="dialog" aria-modal="true" aria-label="Posting" onClick={(e) => e.stopPropagation()}>
        <button className="close" onClick={onClose} aria-label="Close">×</button>
        {error && <p className="error">{error}</p>}
        {!posting && !error && <p className="muted">Loading…</p>}
        {posting && (
          <>
            <h2 dir="auto">{posting.title}</h2>
            <p dir="auto" className="muted">{posting.company ?? "Unknown company"} · {posting.location ?? "Location not listed"}</p>
            {posting.reasons && <p className="why">{posting.reasons}</p>}
            {posting.thin && (
              <p className="muted small" role="note">
                Low-confidence score: only a short description is stored
                {posting.enrichStatus === "blocked" ? " (the full page is on a site that does not allow automated reading)" : ""}. Open the posting, copy the text, and paste it here.
              </p>
            )}
            {posting.thin && <PasteDescription id={id} onDone={() => setNote("Saved. The score updates in about a minute.")} />}

            <div className="actions">
              {posting.cv && <a className="button primary" href={posting.cv.pdfUrl} target="_blank" rel="noreferrer">Open tailored CV</a>}
              {apply && <a className="button" href={apply} target="_blank" rel="noreferrer noopener">Open posting</a>}
              {apply && posting.applyChannel === "google_form" && <span className="muted">Applies through a Google Form</span>}
              {!apply && posting.applyTarget && <span className="muted">Apply by e-mail: {posting.applyTarget}</span>}
              {posting.sourceUrl && <a className="button" href={posting.sourceUrl} target="_blank" rel="noreferrer noopener">Open original posting</a>}
            </div>
            <p className="muted small">You submit applications yourself; JobRadar never sends one without your approval.</p>

            {posting.baseScores.length > 0 && (
              <div className="ats">
                <h3>Your CVs against this posting</h3>
                <ul className="list">
                  {posting.baseScores.map((b, i) => (
                    <li key={b.cvId} className="card">
                      <span className="row">
                        <b>{b.label}{i === 0 && posting.baseScores.length > 1 ? " (best)" : ""}</b>
                        <span className={`fit ${b.ats >= 70 ? "hi" : b.ats >= 50 ? "mid" : ""}`}>{Math.round(b.ats)}% ATS</span>
                      </span>
                      <a href={`/api/dashboard/base-cvs/${b.cvId}/file`} target="_blank" rel="noreferrer">Open this CV</a>
                      {b.missing.length > 0 && <span className="muted small">Missing keywords: {b.missing.join(", ")}</span>}
                    </li>
                  ))}
                </ul>
                <p className="muted small">Your own CVs are used as they are. Tailoring below is optional.</p>
              </div>
            )}

            {posting.cv ? (
              <div className="ats">
                <h3>ATS check <span className={`fit ${posting.cv.atsScore >= 70 ? "hi" : posting.cv.atsScore >= 50 ? "mid" : ""}`}>{Math.round(posting.cv.atsScore)}%</span></h3>
                {posting.cv.summary && <blockquote dir="auto">{posting.cv.summary}</blockquote>}
                {report ? (
                  <>
                    <ul className="checks">
                      {report.checks.map((c) => (
                        <li key={c.name} className={c.ok ? "ok" : "bad"}>
                          <span aria-hidden="true">{c.ok ? "✓" : "✗"}</span> <b>{c.name}</b> <span className="muted"> {c.detail}</span>
                        </li>
                      ))}
                    </ul>
                    {report.missing.length > 0 && (
                      <p className="small"><b>Posting keywords not on this CV:</b> {report.missing.slice(0, 12).join(", ")}</p>
                    )}
                    {report.fixes.length > 0 && (
                      <ul className="fixes">{report.fixes.map((f, i) => <li key={i}>{f}</li>)}</ul>
                    )}
                  </>
                ) : (
                  <p className="muted small">This CV was made before the ATS check existed. Rebuild it to get a report.</p>
                )}
                <button
                  onClick={async () => {
                    try {
                      await rebuildCv(posting.postingId);
                      setNote("Rebuilding. Check again in a minute.");
                    } catch (e) {
                      setNote((e as Error).message);
                    }
                  }}
                >
                  Rebuild CV
                </button>
                {note && <p className="muted small" role="status">{note}</p>}
              </div>
            ) : (
              <p className="muted">No tailored CV yet. One is made for postings that fit well.</p>
            )}

            {posting.description && <details><summary>Job description</summary><p dir="auto" className="desc">{posting.description.slice(0, 4000)}</p></details>}
          </>
        )}
      </section>
    </div>
  );
}

// ------------------------------------------------------------------------------ applications

function Applications({ rows }: { rows: ApplicationRow[] }) {
  if (rows.length === 0) return <p className="empty">No applications yet. When you apply, it shows up here with its follow-up date.</p>;
  return (
    <ul className="list">
      {rows.map((a, i) => (
        <li key={i} className="card">
          <b dir="auto">{String(a.title ?? a.postingTitle ?? `Application ${i + 1}`)}</b>
          <span className="muted">{String(a.company ?? a.companyName ?? "")}</span>
          <span className="tag">{String(a.status ?? "")}</span>
        </li>
      ))}
    </ul>
  );
}

// ------------------------------------------------------------------------------ preferences

function PreferenceForm({ initial, submitLabel, onSubmit }: { initial: Preferences | null; submitLabel: string; onSubmit: (p: Preferences) => Promise<void> }) {
  const start = initial ?? {
    roles: [], cities: ["Cairo"], remote: true, jobTypes: ["internship", "full-time"], entryLevelOnly: true,
    maxYearsExperience: 2, maxAgeDays: 7, pushMinFit: 75, digestMinFit: 40, titleKeywords: [], excludeKeywords: DEFAULT_EXCLUDES, onboarded: false,
  };
  const [presets, setPresets] = useState<string[]>(presetIdsFromRoles(start.roles));
  const [extraKeywords, setExtraKeywords] = useState(() => {
    const fromPresets = new Set(ROLE_PRESETS.filter((p) => presetIdsFromRoles(start.roles).includes(p.id)).flatMap((p) => p.keywords));
    return start.titleKeywords.filter((k) => !fromPresets.has(k)).join(", ");
  });
  const [cities, setCities] = useState(start.cities.join(", "));
  const [remote, setRemote] = useState(start.remote);
  const [jobTypes, setJobTypes] = useState(start.jobTypes);
  const [entry, setEntry] = useState(start.entryLevelOnly);
  const [years, setYears] = useState(start.maxYearsExperience);
  const [ageDays, setAgeDays] = useState(start.maxAgeDays ?? 7);
  const [pushFit, setPushFit] = useState(start.pushMinFit ?? 75);
  const [digestFit, setDigestFit] = useState(start.digestMinFit ?? 40);
  const [excludes, setExcludes] = useState(start.excludeKeywords.join(", "));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const toggle = <T,>(list: T[], item: T) => (list.includes(item) ? list.filter((x) => x !== item) : [...list, item]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const chosen = ROLE_PRESETS.filter((p) => presets.includes(p.id));
    const keywords = [...new Set([...chosen.flatMap((p) => p.keywords), ...splitList(extraKeywords)])];
    setBusy(true);
    setMessage(null);
    try {
      await onSubmit({
        roles: chosen.map((p) => p.label),
        cities: splitList(cities),
        remote,
        jobTypes,
        entryLevelOnly: entry,
        maxYearsExperience: years,
        maxAgeDays: ageDays,
        pushMinFit: pushFit,
        digestMinFit: digestFit,
        titleKeywords: keywords,
        excludeKeywords: splitList(excludes),
        onboarded: true,
      });
      setMessage("Saved. Matches are being re-checked against your new filters.");
    } catch (err) {
      setMessage((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form" onSubmit={submit}>
      <fieldset>
        <legend>What roles are you looking for?</legend>
        <div className="chips">
          {ROLE_PRESETS.map((p) => (
            <button type="button" key={p.id} className={`chip ${presets.includes(p.id) ? "on" : ""}`} aria-pressed={presets.includes(p.id)} onClick={() => setPresets(toggle(presets, p.id))}>
              {p.label}
            </button>
          ))}
        </div>
        <label htmlFor="extra">Other title words (comma separated)</label>
        <input id="extra" value={extraKeywords} onChange={(e) => setExtraKeywords(e.target.value)} placeholder="e.g. embedded, robotics" />
      </fieldset>

      <fieldset>
        <legend>Experience level</legend>
        <label className="switch">
          <input type="checkbox" checked={entry} onChange={(e) => setEntry(e.target.checked)} />
          <span>Student / entry level only (hides senior, lead, manager and mid-level roles)</span>
        </label>
        {entry && (
          <>
            <label htmlFor="years">Hide postings asking for more than <b>{years}</b> {years === 1 ? "year" : "years"} of experience</label>
            <input id="years" type="range" min={0} max={5} value={years} onChange={(e) => setYears(Number(e.target.value))} />
          </>
        )}
      </fieldset>

      <fieldset>
        <legend>How old</legend>
        <label htmlFor="age">Hide postings older than</label>
        <select id="age" value={ageDays} onChange={(e) => setAgeDays(Number(e.target.value))}>
          <option value={1}>1 day</option>
          <option value={3}>3 days</option>
          <option value={7}>1 week</option>
          <option value={14}>2 weeks</option>
          <option value={30}>1 month</option>
          <option value={0}>No limit</option>
        </select>
      </fieldset>

      <fieldset>
        <legend>Notifications</legend>
        <label htmlFor="push">Push at once when a new posting scores at least <b>{pushFit}</b></label>
        <input id="push" type="range" min={30} max={100} step={5} value={pushFit} onChange={(e) => setPushFit(Number(e.target.value))} />
        <label htmlFor="digest">Daily digest (18:00) includes postings scoring at least <b>{digestFit}</b></label>
        <input id="digest" type="range" min={0} max={100} step={5} value={digestFit} onChange={(e) => setDigestFit(Number(e.target.value))} />
      </fieldset>

      <fieldset>
        <legend>Where</legend>
        <label htmlFor="cities">Cities (comma separated)</label>
        <input id="cities" value={cities} onChange={(e) => setCities(e.target.value)} />
        <label className="switch">
          <input type="checkbox" checked={remote} onChange={(e) => setRemote(e.target.checked)} />
          <span>Include remote roles open to Egypt</span>
        </label>
      </fieldset>

      <fieldset>
        <legend>Job type</legend>
        <div className="chips">
          {JOB_TYPES.map((t) => (
            <button type="button" key={t.id} className={`chip ${jobTypes.includes(t.id) ? "on" : ""}`} aria-pressed={jobTypes.includes(t.id)} onClick={() => setJobTypes(toggle(jobTypes, t.id))}>
              {t.label}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend>Never show titles with</legend>
        <input aria-label="Excluded words" value={excludes} onChange={(e) => setExcludes(e.target.value)} />
      </fieldset>

      <button className="primary" type="submit" disabled={busy || (presets.length === 0 && splitList(extraKeywords).length === 0) || jobTypes.length === 0}>
        {busy ? "Saving…" : submitLabel}
      </button>
      {message && <p className="muted" role="status">{message}</p>}
    </form>
  );
}

function Onboarding({ initial, onDone }: { initial: Preferences | null; onDone: () => void }) {
  return (
    <main className="onboard">
      <h1>Set up your search</h1>
      <p className="muted">Tell JobRadar what you want so it can hide everything else. You can change this any time in Settings.</p>
      <PreferenceForm initial={initial} submitLabel="Start matching" onSubmit={async (p) => { await savePreferences(p); onDone(); }} />
    </main>
  );
}

function Settings({ prefs, onSaved }: { prefs: Preferences; onSaved: () => void }) {
  const [summaries, setSummaries] = useState<SummaryRow[]>([]);
  useEffect(() => { fetchSummaries().then(setSummaries).catch(() => setSummaries([])); }, []);
  const [cvs, setCvs] = useState<BaseCv[]>([]);
  const [cvError, setCvError] = useState<string | null>(null);
  const loadCvs = useCallback(() => fetchBaseCvs().then(setCvs).catch(() => setCvs([])), []);
  useEffect(() => { loadCvs(); }, [loadCvs]);
  return (
    <section>
      <h2>Your CVs</h2>
      <p className="muted small">
        Upload your finished PDFs. They are stored unchanged and every posting is scored against them.
      </p>
      <ul className="list">
        {cvs.map((c) => (
          <li key={c.id} className="card">
            <span className="row"><b>{c.label}</b><span className="muted small">{c.filename}</span></span>
            <a href={`/api/dashboard/base-cvs/${c.id}/file`} target="_blank" rel="noreferrer">Open</a>
            <button onClick={async () => { await deleteBaseCv(c.id); loadCvs(); }}>Remove</button>
          </li>
        ))}
        {cvs.length === 0 && <li className="empty">No CV uploaded yet.</li>}
      </ul>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const form = e.currentTarget;
          const label = (form.elements.namedItem("label") as HTMLInputElement).value;
          const file = (form.elements.namedItem("file") as HTMLInputElement).files?.[0];
          if (!file) return;
          try {
            setCvError(null);
            await uploadBaseCv(label, file);
            form.reset();
            loadCvs();
          } catch (err) {
            setCvError((err as Error).message);
          }
        }}
      >
        <label>Name <input name="label" required placeholder="Backend" /></label>
        <label>PDF <input name="file" type="file" accept="application/pdf" required /></label>
        <button type="submit">Upload CV</button>
        {cvError && <p className="error">{cvError}</p>}
      </form>

      <h2>Search preferences</h2>
      <PreferenceForm initial={prefs} submitLabel="Save preferences" onSubmit={async (p) => { await savePreferences(p); onSaved(); }} />

      <h2>CV summary lines</h2>
      <p className="muted small">
        One of these is printed at the top of each tailored CV, chosen by the posting's keywords. They are written only from
        what your CV already says. A line is used only after you confirm it is true for you.
      </p>
      <ul className="list">
        {summaries.map((s) => (
          <li key={s.id} className="card">
            <span className="row"><b>{s.label}</b><span className={`tag ${s.confirmed ? "cv" : ""}`}>{s.confirmed ? "confirmed" : "needs your check"}</span></span>
            <span dir="auto">{s.text}</span>
            <button
              onClick={async () => {
                await confirmSummary(s.id, !s.confirmed);
                setSummaries(await fetchSummaries());
              }}
            >
              {s.confirmed ? "Unconfirm" : "Confirm: this is true"}
            </button>
          </li>
        ))}
        {summaries.length === 0 && <li className="empty">No summary lines in your profile seed yet.</li>}
      </ul>
    </section>
  );
}
