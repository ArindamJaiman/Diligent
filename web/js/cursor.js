/*
 * Cursor follow: a dot on the pointer and a liquid blob that chases it on a spring, stretching
 * along its velocity like a drop. Over buttons and links the blob swells; elements marked
 * [data-magnetic] lean toward the pointer; cards get a spotlight that follows it (--mx/--my);
 * every press drops a ripple. Off on touch screens and with reduced motion.
 */
(function () {
  "use strict";
  const D = (window.Diligent = window.Diligent || {});

  // Ripples and spotlights work everywhere, cursor or not.
  function wireEffects() {
    document.addEventListener("pointerdown", (e) => {
      const host = e.target.closest("button, .btn, .ripple");
      if (!host || host.disabled) return;
      const rect = host.getBoundingClientRect();
      const drop = document.createElement("span");
      drop.className = "ripple-drop";
      const size = Math.max(rect.width, rect.height) * 2.2;
      drop.style.width = drop.style.height = size + "px";
      drop.style.left = e.clientX - rect.left - size / 2 + "px";
      drop.style.top = e.clientY - rect.top - size / 2 + "px";
      host.appendChild(drop);
      setTimeout(() => drop.remove(), 700);
    });

    document.addEventListener("pointermove", (e) => {
      const card = e.target.closest(".spot");
      if (!card) return;
      const r = card.getBoundingClientRect();
      card.style.setProperty("--mx", e.clientX - r.left + "px");
      card.style.setProperty("--my", e.clientY - r.top + "px");
    }, { passive: true });
  }
  wireEffects();

  let made = false;
  /** Shows the liquid cursor (made on first use). Returns false on touch screens / reduced motion. */
  function enable(on) {
    const fine = window.matchMedia("(pointer: fine)").matches;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!fine || reduced) return false;
    document.documentElement.classList.toggle("has-cursor", !!on);
    if (on && !made) { made = true; build(); }
    return true;
  }

  function build() {
    const dot = document.createElement("div");
    dot.className = "cursor-dot";
    const blob = document.createElement("div");
    blob.className = "cursor-blob";
    const label = document.createElement("span");
    label.className = "cursor-label";
    blob.appendChild(label);
    document.body.append(blob, dot);

    const s = { x: innerWidth / 2, y: innerHeight / 2, tx: innerWidth / 2, ty: innerHeight / 2, vx: 0, vy: 0, scale: 1, targetScale: 1, shown: false };
    let magnet = null;

    document.addEventListener("pointermove", (e) => {
      s.tx = e.clientX; s.ty = e.clientY;
      dot.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0)`;
      if (!s.shown) { s.shown = true; s.x = s.tx; s.y = s.ty; document.documentElement.classList.add("cursor-on"); }
      if (magnet) pull(magnet, e);
    }, { passive: true });
    document.addEventListener("pointerleave", () => document.documentElement.classList.remove("cursor-on"));
    document.addEventListener("pointerenter", () => document.documentElement.classList.add("cursor-on"));
    document.addEventListener("pointerdown", () => blob.classList.add("down"));
    document.addEventListener("pointerup", () => blob.classList.remove("down"));

    const INTERACTIVE = "a, button, .btn, input, textarea, select, label, [data-magnetic], [data-cursor], .clickable";
    document.addEventListener("pointerover", (e) => {
      const el = e.target.closest(INTERACTIVE);
      const text = el && (el.matches("input:not([type=checkbox]):not([type=range]), textarea"));
      blob.classList.toggle("hover", !!el && !text);
      blob.classList.toggle("text", !!text);
      label.textContent = (el && el.getAttribute("data-cursor")) || "";
      blob.classList.toggle("labelled", !!label.textContent);
      const m = e.target.closest("[data-magnetic]");
      if (m !== magnet) {
        if (magnet) release(magnet);
        magnet = m;
      }
    });

    function pull(el, e) {
      const r = el.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      const k = parseFloat(el.getAttribute("data-magnetic")) || 0.25;
      el.style.transform = `translate(${(e.clientX - cx) * k}px, ${(e.clientY - cy) * k}px)`;
      // The blob is drawn toward the element's centre too, like a drop clinging to it.
      s.tx = e.clientX + (cx - e.clientX) * 0.35;
      s.ty = e.clientY + (cy - e.clientY) * 0.35;
    }
    function release(el) { el.style.transform = ""; }

    function frame() {
      // Critically-damped-ish spring.
      const k = 0.16, damp = 0.72;
      s.vx = (s.vx + (s.tx - s.x) * k) * damp;
      s.vy = (s.vy + (s.ty - s.y) * k) * damp;
      s.x += s.vx; s.y += s.vy;
      const speed = Math.min(Math.hypot(s.vx, s.vy), 60);
      const angle = Math.atan2(s.vy, s.vx) * 180 / Math.PI;
      const stretch = 1 + speed / 70, squash = 1 - speed / 220;
      blob.style.transform = `translate3d(${s.x}px, ${s.y}px, 0) rotate(${angle}deg) scale(${stretch}, ${squash})`;
      label.style.transform = `rotate(${-angle}deg) scale(${1 / stretch}, ${1 / squash})`;
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  D.cursor = { enable };
})();
