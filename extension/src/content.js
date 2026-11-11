// Shows a small bar on a Greenhouse or Workable application page. Nothing is filled until the
// person presses "Fill". The form is never submitted: that stays the person's click.
(function () {
  "use strict";
  if (document.getElementById("jobradar-bar")) return;

  chrome.storage.local.get("profile", ({ profile }) => {
    const bar = document.createElement("div");
    bar.id = "jobradar-bar";
    bar.style.cssText =
      "position:fixed;bottom:12px;right:12px;z-index:2147483647;background:#111;color:#fff;" +
      "font:13px system-ui,sans-serif;padding:10px 12px;border-radius:8px;box-shadow:0 2px 8px #0006;max-width:280px";
    const text = document.createElement("div");
    const button = document.createElement("button");
    button.type = "button";
    button.style.cssText = "margin-top:8px;margin-right:6px;cursor:pointer";
    const close = document.createElement("button");
    close.type = "button";
    close.textContent = "Dismiss";
    close.style.cssText = "margin-top:8px;cursor:pointer";
    close.addEventListener("click", () => bar.remove());

    if (!profile) {
      text.textContent = "JobRadar: no profile saved yet. Open the extension options to add one.";
      bar.append(text, close);
    } else {
      const count = JobRadarFill.plan(document, profile).filter((p) => !p.skipped).length;
      text.textContent = "JobRadar can fill " + count + " empty field(s). Review everything and submit yourself.";
      button.textContent = "Fill";
      button.addEventListener("click", () => {
        const result = JobRadarFill.fill(document, profile);
        text.textContent =
          "Filled: " + (result.filled.join(", ") || "nothing") + ". Check each field, attach your CV, then submit yourself.";
        button.remove();
      });
      bar.append(text, button, close);
    }
    document.body.appendChild(bar);
  });
})();
