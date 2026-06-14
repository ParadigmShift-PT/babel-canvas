"use strict";

// babel-canvas web UI: poll /api/state a few times a second, render the shared
// grid + this node's HyParView neighbours, and POST paint ops on click.

const PALETTE = [
    0x000000, 0xffffff, 0xe6194b, 0xf58231, 0xffe119, 0x3cb44b,
    0x42d4f4, 0x4363d8, 0x911eb4, 0xf032e6, 0x9a6324, 0x808080,
];

const canvas = document.getElementById("canvas");
const ctx = canvas.getContext("2d");
const paletteEl = document.getElementById("palette");
const nodeEl = document.getElementById("node");
const paintedEl = document.getElementById("painted");
const gridEl = document.getElementById("grid");
const neighboursEl = document.getElementById("neighbours");
const navStatusEl = document.getElementById("navStatus");
const statusTextEl = document.getElementById("statusText");

// Brand status pill: teal "live" while the node answers, red "offline" when it stops.
function setStatus(live) {
    if (navStatusEl) {
        navStatusEl.classList.toggle("live", live);
        navStatusEl.classList.toggle("gone", !live);
    }
    if (statusTextEl) statusTextEl.textContent = live ? "live" : "offline";
}

let gridW = 48, gridH = 48;
let cellPx = canvas.width / gridW;
let selectedColor = PALETTE[2]; // a friendly default (red)

// ─── Palette ──────────────────────────────────────────────────────────────
PALETTE.forEach((color) => {
    const sw = document.createElement("div");
    sw.className = "swatch" + (color === selectedColor ? " selected" : "");
    sw.style.background = cssColor(color);
    sw.title = "#" + color.toString(16).padStart(6, "0");
    sw.addEventListener("click", () => {
        selectedColor = color;
        document.querySelectorAll(".swatch").forEach((s) => s.classList.remove("selected"));
        sw.classList.add("selected");
    });
    paletteEl.appendChild(sw);
});

// ─── Painting ─────────────────────────────────────────────────────────────
canvas.addEventListener("click", (ev) => {
    const rect = canvas.getBoundingClientRect();
    const x = Math.floor((ev.clientX - rect.left) / rect.width * gridW);
    const y = Math.floor((ev.clientY - rect.top) / rect.height * gridH);
    if (x < 0 || x >= gridW || y < 0 || y >= gridH) return;
    fetch("/api/paint", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ x, y, color: selectedColor }),
    }).catch(() => { /* transient; next poll reflects reality anyway */ });
});

// ─── Rendering ────────────────────────────────────────────────────────────
function cssColor(rgb) {
    return "#" + (rgb & 0xffffff).toString(16).padStart(6, "0");
}

function render(state) {
    if (state.width !== gridW || state.height !== gridH) {
        gridW = state.width;
        gridH = state.height;
        cellPx = canvas.width / gridW;
    }
    const cells = state.cells;
    for (let i = 0; i < cells.length; i++) {
        const argb = cells[i];
        const x = i % gridW;
        const y = Math.floor(i / gridW);
        // Alpha 0 == unpainted: leave the canvas background showing.
        if ((argb & 0xff000000) === 0) {
            ctx.clearRect(x * cellPx, y * cellPx, cellPx, cellPx);
        } else {
            ctx.fillStyle = cssColor(argb);
            ctx.fillRect(x * cellPx, y * cellPx, cellPx, cellPx);
        }
    }
}

function renderInfo(state) {
    nodeEl.textContent = state.node;
    paintedEl.textContent = state.painted;
    gridEl.textContent = state.width + " × " + state.height;

    neighboursEl.innerHTML = "";
    if (!state.neighbours || state.neighbours.length === 0) {
        const li = document.createElement("li");
        li.className = "muted";
        li.textContent = "(none yet)";
        neighboursEl.appendChild(li);
    } else {
        state.neighbours.forEach((n) => {
            const li = document.createElement("li");
            li.textContent = n;
            neighboursEl.appendChild(li);
        });
    }
}

// ─── Poll loop ──────────────────────────────────────────────────────────────
async function poll() {
    try {
        const resp = await fetch("/api/state", { cache: "no-store" });
        if (resp.ok) {
            const state = await resp.json();
            render(state);
            renderInfo(state);
            setStatus(true);
        } else {
            setStatus(false);
        }
    } catch (e) {
        // Node not ready / transient — keep polling.
        setStatus(false);
    }
}

setInterval(poll, 500);
poll();
