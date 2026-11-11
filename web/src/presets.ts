// Role presets for onboarding. Each maps a kind of role to the words its job titles contain; the
// api keeps a posting only if its title contains one of the chosen words (whole words, any case).
export interface RolePreset {
  id: string;
  label: string;
  keywords: string[];
}

export const ROLE_PRESETS: RolePreset[] = [
  { id: "backend", label: "Backend / Software", keywords: ["software", "backend", "back-end", "java", "spring", "api", "developer"] },
  { id: "fullstack", label: "Full-stack / Web", keywords: ["full-stack", "fullstack", "full stack", "frontend", "front-end", "react", "web"] },
  { id: "ai", label: "AI / Machine learning", keywords: ["ai", "ml", "machine learning", "deep learning", "data scientist", "computer vision", "nlp", "llm"] },
  { id: "graphics", label: "Graphics / Game dev", keywords: ["game", "graphics", "unity", "unreal", "opengl", "3d", "rendering", "gameplay"] },
  { id: "data", label: "Data", keywords: ["data engineer", "data analyst", "etl", "bi", "analytics"] },
  { id: "mobile", label: "Mobile", keywords: ["android", "ios", "flutter", "mobile", "react native"] },
  { id: "devops", label: "DevOps / Cloud", keywords: ["devops", "cloud", "sre", "infrastructure"] },
  { id: "qa", label: "QA / Testing", keywords: ["qa", "quality", "test", "testing"] },
];

export const JOB_TYPES: { id: string; label: string }[] = [
  { id: "internship", label: "Internship" },
  { id: "full-time", label: "Full-time" },
  { id: "working-student", label: "Working student" },
];

export const DEFAULT_EXCLUDES = ["sales", "marketing", "recruiter", "accountant", "call center"];

/** Which presets a saved preference list corresponds to (by its role labels). */
export const presetIdsFromRoles = (roles: string[]) =>
  ROLE_PRESETS.filter((p) => roles.includes(p.label)).map((p) => p.id);
