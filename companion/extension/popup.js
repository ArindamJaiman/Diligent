async function show() {
  const { minutes = {}, lastUpload } = await chrome.storage.local.get(["minutes", "lastUpload"]);
  const today = minutes[new Date().toLocaleDateString("en-CA")] || {};
  const entries = Object.entries(today).sort((a, b) => b[1] - a[1]);
  const list = document.getElementById("list");
  if (entries.length) {
    list.className = "";
    list.innerHTML = entries.map(([a, m]) => `<div class="row"><span>${a.replace(/</g, "&lt;")}</span><span>${Math.round(m)} min</span></div>`).join("");
  }
  if (lastUpload) {
    document.getElementById("upload").textContent =
      `last upload ${new Date(lastUpload.at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} · ${lastUpload.ok ? "ok" : "HTTP " + lastUpload.status}`;
  }
}
document.getElementById("send").onclick = () => chrome.runtime.sendMessage("upload").then(show);
show();
