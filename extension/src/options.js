const box = document.getElementById("p");
chrome.storage.local.get("profile", ({ profile }) => {
  box.value = JSON.stringify(profile || {}, null, 2);
});
document.getElementById("save").addEventListener("click", () => {
  try {
    chrome.storage.local.set({ profile: JSON.parse(box.value) }, () => {
      document.getElementById("s").textContent = "Saved";
    });
  } catch (e) {
    document.getElementById("s").textContent = "Not valid JSON";
  }
});
