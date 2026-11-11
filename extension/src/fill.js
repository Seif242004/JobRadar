// Pure form-filling logic. No network, no storage, no clicking: it only sets the value of
// empty text-like fields that it recognises, and reports what it did. Nothing here can submit a
// form, attach a file, or answer a question that is not a plain contact field.
(function (root) {
  "use strict";

  // profile key -> ways a field can announce itself (id, name or autocomplete token), or its label.
  const FIELDS = {
    firstName: { keys: ["first_name", "firstname", "given-name"], label: /^first name\b/i },
    lastName: { keys: ["last_name", "lastname", "family-name"], label: /^last name\b/i },
    email: { keys: ["email"], label: /^email\b/i },
    phone: { keys: ["phone", "input_phone", "tel"], label: /^phone\b/i },
    city: { keys: ["city", "address-level2"], label: /^city\b/i },
    address: { keys: ["address", "street-address"], label: /^address\b/i },
    postcode: { keys: ["postcode", "postal-code", "zip"], label: /^(post(al)? ?code|zip)\b/i },
    linkedin: { keys: [], label: /^linkedin( profile)?\b/i },
    website: { keys: [], label: /^(website|portfolio)\b/i },
  };

  const TEXT_TYPES = new Set(["text", "email", "tel", "url"]);

  function cssEscape(value) {
    return value.replace(/["\\]/g, "\\$&");
  }

  function labelTexts(doc, input) {
    const parts = [];
    if (input.id) {
      const byFor = doc.querySelector('label[for="' + cssEscape(input.id) + '"]');
      if (byFor) parts.push(byFor.textContent);
    }
    const labelledBy = input.getAttribute("aria-labelledby");
    if (labelledBy) {
      for (const id of labelledBy.split(/\s+/)) {
        const el = doc.getElementById(id);
        if (el) parts.push(el.textContent);
      }
    }
    if (input.getAttribute("aria-label")) parts.push(input.getAttribute("aria-label"));
    return parts.map((p) => p.replace(/[*\s]+/g, " ").trim()).filter(Boolean);
  }

  function profileKeyFor(doc, input) {
    const tokens = [input.id, input.getAttribute("name"), input.getAttribute("autocomplete")]
      .filter(Boolean)
      .map((t) => t.toLowerCase());
    for (const [key, spec] of Object.entries(FIELDS)) {
      if (spec.keys.some((k) => tokens.includes(k))) return key;
    }
    const labels = labelTexts(doc, input);
    for (const [key, spec] of Object.entries(FIELDS)) {
      if (labels.some((l) => spec.label.test(l))) return key;
    }
    return null;
  }

  // React and similar frameworks ignore a plain `input.value = x`: go through the native setter
  // and fire the events a person typing would.
  function setValue(input, value) {
    const view = input.ownerDocument.defaultView;
    const proto = input.tagName === "TEXTAREA" ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(input, value);
    input.dispatchEvent(new view.Event("input", { bubbles: true }));
    input.dispatchEvent(new view.Event("change", { bubbles: true }));
  }

  // What would be filled, without touching the page.
  function plan(doc, profile) {
    const planned = [];
    const form = doc.querySelector("form");
    if (!form) return planned;
    const seen = new Set();
    for (const input of form.querySelectorAll("input, textarea")) {
      if (input.tagName === "INPUT") {
        const type = (input.getAttribute("type") || "text").toLowerCase();
        if (!TEXT_TYPES.has(type)) continue; // files, radios, checkboxes, buttons
      }
      if (input.getAttribute("role") === "combobox") continue; // custom dropdowns: left to the person
      if (input.disabled || input.readOnly) continue;
      const key = profileKeyFor(doc, input);
      if (!key || seen.has(key)) continue;
      const value = profile && typeof profile[key] === "string" ? profile[key].trim() : "";
      if (!value) continue;
      seen.add(key);
      planned.push({ input, key, value, skipped: input.value ? "already filled" : null });
    }
    return planned;
  }

  function fill(doc, profile) {
    const filled = [];
    const skipped = [];
    for (const item of plan(doc, profile)) {
      if (item.skipped) {
        skipped.push({ key: item.key, reason: item.skipped });
        continue;
      }
      setValue(item.input, item.value);
      filled.push(item.key);
    }
    return { filled, skipped };
  }

  const api = { plan, fill, profileKeyFor };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.JobRadarFill = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
