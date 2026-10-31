const fields = ["url", "key", "owner", "map"];
chrome.storage.local.get("config").then(({ config = {} }) => {
  fields.forEach((f) => { if (config[f] !== undefined) document.getElementById(f).value = config[f]; });
});
document.getElementById("save").onclick = async () => {
  const config = Object.fromEntries(fields.map((f) => [f, document.getElementById(f).value.trim()]));
  await chrome.storage.local.set({ config });
  document.getElementById("status").textContent = "saved";
};
