const app = document.getElementById("app");
const navLinks = [...document.querySelectorAll("[data-nav]")];
let routeRefreshTimer = null;
let workstationSelectEvent = null;
let eventTypesRequest = null;
const api = async (url, options = {}) => {
  const response = await fetch(url, { headers: { "Content-Type": "application/json", ...(options.headers || {}) }, ...options });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try { const body = await response.json(); message = body.message || message; } catch {}
    throw new Error(message);
  }
  if (response.status === 204) return null;
  return response.json();
};
const esc = value => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
const seconds = ms => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}.${String(ms % 1000).padStart(3, "0")}`;
const duration = ms => `${(ms / 1000).toFixed(1)} s`;
const route = () => location.hash.replace(/^#\/?/, "").split("?")[0].split("/").filter(Boolean);
const routeQuery = () => new URLSearchParams(location.hash.split("?")[1] || "");
const assetTitle = asset => asset.title || asset.source || `Media asset ${asset.id}`;
const matchPartLabels = {
  FIRST_HALF: "First half",
  SECOND_HALF: "Second half",
  FULL_MATCH: "Full match",
  OTHER: "Other asset"
};
const matchPartValue = asset => {
  if (asset.matchPart && asset.matchPart !== "OTHER") return asset.matchPart;
  const name = `${asset.originalFilename || ""} ${asset.title || ""}`.toLowerCase();
  if (/\b(first|1st)[\s._-]*half\b/.test(name)) return "FIRST_HALF";
  if (/\b(second|2nd)[\s._-]*half\b/.test(name)) return "SECOND_HALF";
  return asset.matchPart || "OTHER";
};
const matchPartLabel = asset => matchPartLabels[matchPartValue(asset)] || "Other asset";
const matchIdentity = asset => {
  const home = asset.homeTeam?.trim().toLocaleLowerCase();
  const away = asset.awayTeam?.trim().toLocaleLowerCase();
  if (!home || !away || !asset.matchDate) return `asset:${asset.id}`;
  return `match:${[asset.competition || "", asset.matchDate, home, away]
    .map(value => value.trim().toLocaleLowerCase()).join("|")}`;
};
const groupAssetsByMatch = assets => {
  const groups = new Map();
  for (const asset of assets) {
    const key = matchIdentity(asset);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(asset);
  }
  return [...groups.values()];
};
const latestDetectionRun = runs => runs
  .filter(run => !run.legacy)
  .sort((a, b) => Date.parse(b.createdAt || 0) - Date.parse(a.createdAt || 0))[0] || null;
const toast = text => {
  const node = document.createElement("div");
  node.className = "toast"; node.textContent = text; document.body.append(node);
  setTimeout(() => node.remove(), 2800);
};
const errorBox = error => `<div class="error">${esc(error.message || error)}</div>`;
const setActiveNav = current => navLinks.forEach(link => link.classList.toggle("active", link.dataset.nav === current));
const card = (heading, body) => `<section class="panel"><h2>${heading}</h2>${body}</section>`;
const badge = (text, className = "") => `<span class="badge ${esc(className)}">${esc(text)}</span>`;
const linkButton = (href, text, extra = "") => `<a class="button ${extra}" href="${esc(href)}">${esc(text)}</a>`;
const assetUrl = id => `/api/media-assets/${encodeURIComponent(id)}`;
const candidateUrl = (assetId, candidateId) => `${assetUrl(assetId)}/candidates/${encodeURIComponent(candidateId)}`;
const rejectionReasonLabels = {
  NOT_A_FOOTBALL_EVENT: "Not a football event",
  PRE_MATCH_NOISE: "Pre-match noise",
  HALF_TIME_NOISE: "Half-time noise",
  POST_MATCH_NOISE: "Post-match noise",
  CROWD_REACTION: "Crowd reaction",
  COMMENTATOR_EXCITEMENT: "Commentator excitement",
  REPLAY: "Replay",
  RETROSPECTIVE_COMMENTARY: "Retrospective commentary",
  SHOT_NO_GOAL: "Shot but no goal",
  SHOT_NOT_GOAL: "Shot, not a goal",
  NORMAL_PLAY: "Normal play",
  WRONG_EVENT_TYPE: "Wrong event type",
  DUPLICATE: "Duplicate",
  INSUFFICIENT_EVIDENCE: "Insufficient evidence",
  OTHER: "Other"
};
const systemRejectionLabels = {
  INSUFFICIENT_EVENT_SPECIFIC_EVIDENCE: "Insufficient event-specific evidence",
  GOAL_WITHOUT_REACTION_CORROBORATION: "Goal signal lacked reaction corroboration",
  LOW_LIVE_EVENT_PROBABILITY: "Low live-event probability",
  LOW_CANDIDATE_SCORE: "Candidate score below threshold",
  ATTACK_WITHOUT_LOCAL_BUILDUP_AND_REACTION: "Attack lacked connected buildup and reaction"
};
const structureMarkerLabels = {
  BROADCAST_START: "Broadcast start",
  PRE_MATCH: "Pre-match",
  KICKOFF: "Kickoff",
  FIRST_HALF_START: "First-half start",
  HALF_TIME: "Half-time",
  SECOND_HALF_START: "Second-half start",
  FULL_TIME: "Full-time",
  POST_MATCH: "Post-match",
  BROADCAST_END: "Broadcast end"
};

async function allAssets() {
  let page = 0, items = [], total = 0;
  do {
    const result = await api(`/api/media-assets?page=${page}&size=100`);
    items = items.concat(result.items); total = result.totalElements; page++;
  } while (items.length < total);
  return items;
}

async function allClips() {
  let page = 0, items = [], total = 0;
  do {
    const result = await api(`/api/clips?page=${page}&size=100`);
    items = items.concat(result.items); total = result.totalElements; page++;
  } while (items.length < total);
  return items;
}

async function allEventTypes() {
  if (!eventTypesRequest) {
    eventTypesRequest = api("/api/event-types").catch(error => {
      eventTypesRequest = null;
      throw error;
    });
  }
  return eventTypesRequest;
}

function navigate(path) { location.hash = `#/${path}`; }

function uploadFormMarkup(formId, buttonLabel = "Upload video") {
  return `<form id="${esc(formId)}" class="upload-form" data-video-upload-form>
    <label>Video file<input name="file" type="file" accept="video/*,.mkv,.mov,.webm" data-upload-file required></label>
    <div class="upload-file-info muted" data-upload-file-info>No file selected</div>
    <div class="form-grid"><label>Title<input name="title" maxlength="500" placeholder="Defaults to filename"></label>
      <label>Competition<input name="competition" maxlength="255" placeholder="Competition"></label>
      <label>Home team<input name="homeTeam" maxlength="255" placeholder="Home team"></label>
      <label>Away team<input name="awayTeam" maxlength="255" placeholder="Away team"></label>
      <label>Match date<input name="matchDate" type="date"></label>
      <label>Match part<select name="matchPart"><option value="FIRST_HALF">First half</option><option value="SECOND_HALF">Second half</option>
        <option value="FULL_MATCH">Full match</option><option value="OTHER" selected>Other asset</option></select></label>
      <label>Language<input name="language" maxlength="35" placeholder="e.g. en"></label>
      <label>Content type<select name="contentType"><option>SPORTS</option><option>GENERIC</option><option>GAMING</option>
        <option>IRL</option><option>PODCAST</option><option>INTERVIEW</option><option>POLITICAL_SPEECH</option>
        <option>NEWS</option><option>EDUCATIONAL</option></select></label>
      <label>Source<input name="source" value="LOCAL_UPLOAD" maxlength="100"></label></div>
    <div class="upload-progress" aria-live="polite"><progress max="100" value="0" data-upload-progress></progress>
      <span data-upload-progress-label>Ready to upload</span></div>
    <button type="submit" data-upload-submit>${esc(buttonLabel)}</button>
  </form><div class="upload-feedback" data-upload-feedback role="status"></div>`;
}

function bindVideoUploadForm(form) {
  const input = form.querySelector("[data-upload-file]");
  const fileInfo = form.querySelector("[data-upload-file-info]");
  const progress = form.querySelector("[data-upload-progress]");
  const label = form.querySelector("[data-upload-progress-label]");
  const button = form.querySelector("[data-upload-submit]");
  const feedback = form.querySelector("[data-upload-feedback]") || form.nextElementSibling;
  input.addEventListener("change", () => {
    const file = input.files[0];
    fileInfo.textContent = file ? `${file.name} · ${formatBytes(file.size)}` : "No file selected";
    progress.value = 0;
    label.textContent = file ? "Ready to upload" : "Select a video to upload";
    feedback.innerHTML = "";
  });
  form.addEventListener("submit", event => {
    event.preventDefault();
    const file = input.files[0];
    if (!file) return;
    const body = new FormData(form);
    body.set("processImmediately", "false");
    button.disabled = true;
    label.textContent = "Starting upload…";
    feedback.innerHTML = "";
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/media-assets/upload");
    xhr.upload.onprogress = progressEvent => {
      if (progressEvent.lengthComputable) {
        const percent = Math.round(progressEvent.loaded / progressEvent.total * 100);
        progress.value = percent;
        label.textContent = `Uploading ${percent}% · ${formatBytes(progressEvent.loaded)} of ${formatBytes(progressEvent.total)}`;
      } else {
        label.textContent = "Uploading · transfer progress unavailable";
      }
    };
    xhr.onload = () => {
      button.disabled = false;
      if (xhr.status < 200 || xhr.status >= 300) {
        let message = `Upload failed (${xhr.status})`;
        try { message = JSON.parse(xhr.responseText).message || message; } catch {}
        feedback.innerHTML = errorBox(message);
        label.textContent = "Upload failed";
        return;
      }
      let uploaded;
      try {
        uploaded = JSON.parse(xhr.responseText);
      } catch {
        feedback.innerHTML = errorBox("The server accepted the upload but returned an unreadable response.");
        label.textContent = "Upload response unavailable";
        return;
      }
      const part = matchPartLabels[form.elements.namedItem("matchPart").value] || "Other asset";
      sessionStorage.setItem("clip-ai-upload-notice", `${file.name} uploaded as ${part}. Processing has not started.`);
      label.textContent = "Upload complete · processing has not started";
      feedback.innerHTML = `<div class="upload-success"><strong>${esc(file.name)}</strong> is now MediaAsset
        <code>${esc(uploaded.id)}</code> · ${esc(uploaded.status)} · ${esc(part)}.
        <p>Processing was not started automatically.</p></div>`;
      toast(`${file.name} uploaded. Processing has not started.`);
      navigate(`assets/${encodeURIComponent(uploaded.id)}/review`);
    };
    xhr.onerror = () => {
      button.disabled = false;
      label.textContent = "Upload failed";
      feedback.innerHTML = errorBox("Upload failed. Check the connection and retry.");
    };
    xhr.send(body);
  });
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "Size unavailable";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024, unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}

function renderVideoPlayer({ playerId, sourceUrl, label, currentTimeMs = 0, durationMs = null,
                             markers = [], jumpPoints = [], preload = "metadata", showReviewWindow = true }) {
  const scaleMs = durationMs || Math.max(1, ...markers.map(marker => marker.position), currentTimeMs);
  const markerMarkup = markers.map(marker => {
    const kind = String(marker.kind || "event").replace(/[^a-z0-9_-]/gi, "").toLowerCase();
    const markerData = marker.eventId
      ? `data-review-event-marker="${esc(marker.eventId)}"`
      : `data-player-seek-to="${marker.position}"`;
    return `<button type="button" class="video-marker ${kind}" ${markerData}
      data-timestamp-ms="${marker.position}" style="left:${timelinePercent(marker.position, scaleMs)}%"
      title="${esc(marker.title)}" aria-label="${esc(marker.title)}">${esc(marker.label || "")}</button>`;
  }).join("");
  return `<div class="video-player" data-video-player data-duration-ms="${durationMs || 0}"
      data-center-ms="${currentTimeMs}" data-window-ms="30000" data-jump-ms="${currentTimeMs}">
    <div class="video-wrap"><video id="${esc(playerId)}" controls playsinline preload="${esc(preload)}"
      src="${esc(sourceUrl)}" aria-label="${esc(label)}"></video></div>
    <div class="video-player-toolbar">
      <button type="button" class="secondary" data-player-step="-5000">−5s</button>
      <button type="button" class="secondary" data-player-jump>Jump to moment</button>
      ${jumpPoints.map(point => `<button type="button" class="secondary" data-player-jump-to="${point.timeMs}">${esc(point.label)}</button>`).join("")}
      <button type="button" class="secondary" data-player-step="5000">+5s</button>
      ${showReviewWindow ? `<label>Review window<select data-player-window>
        <option value="15000">±15s</option><option value="30000" selected>±30s</option>
        <option value="60000">±60s</option><option value="90000">±90s</option></select></label>` : ""}
      <label>Speed<select data-player-speed><option value="0.5">0.5×</option><option value="0.75">0.75×</option>
        <option value="1" selected>1×</option><option value="1.25">1.25×</option><option value="1.5">1.5×</option>
        <option value="2">2×</option></select></label>
      <output data-player-time aria-live="off">00:00.000 / --:--.---</output>
    </div>
    ${showReviewWindow || markers.length ? `<div class="video-marker-track" data-player-track aria-label="Video event timeline">
      <span class="video-context-range" data-player-context-range ${showReviewWindow ? "" : "hidden"}></span>${markerMarkup}
    </div>` : ""}
  </div>`;
}

function updateVideoPlayer(player) {
  const video = player.querySelector("video");
  if (!video) return;
  const declaredDurationMs = Number(player.dataset.durationMs);
  const durationMs = Number.isFinite(video.duration) && video.duration > 0
    ? video.duration * 1000 : declaredDurationMs;
  const currentTimeMs = Number.isFinite(video.currentTime) ? video.currentTime * 1000 : 0;
  const timeOutput = player.querySelector("[data-player-time]");
  if (timeOutput) timeOutput.textContent = `${seconds(currentTimeMs)} / ${durationMs > 0 ? seconds(durationMs) : "--:--.---"}`;
  const centerMs = Number(player.dataset.centerMs) || 0;
  const windowMs = Number(player.dataset.windowMs) || 0;
  const range = player.querySelector("[data-player-context-range]");
  if (range && durationMs > 0 && windowMs > 0) {
    const startMs = Math.max(0, centerMs - windowMs);
    const endMs = Math.min(durationMs, centerMs + windowMs);
    range.style.left = `${timelinePercent(startMs, durationMs)}%`;
    range.style.width = `${Math.max(0, timelinePercent(endMs - startMs, durationMs))}%`;
  }
  player.querySelectorAll("[data-timestamp-ms]").forEach(marker => {
    if (durationMs > 0) marker.style.left = `${timelinePercent(Number(marker.dataset.timestampMs), durationMs)}%`;
  });
}

function seekVideoPlayer(player, timestampMs) {
  const video = player?.querySelector("video");
  if (!video) return;
  const seek = () => {
    const maximum = Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Infinity;
    video.currentTime = Math.max(0, Math.min(maximum, timestampMs / 1000));
    updateVideoPlayer(player);
  };
  if (video.readyState < 1) video.addEventListener("loadedmetadata", seek, { once: true });
  else seek();
}

function bindVideoPlayers(root = app) {
  root.querySelectorAll("[data-video-player]:not([data-player-bound])").forEach(player => {
    player.dataset.playerBound = "true";
    const video = player.querySelector("video");
    const jump = () => seekVideoPlayer(player, Number(player.dataset.jumpMs) || 0);
    player.querySelectorAll("[data-player-step]").forEach(button => button.addEventListener("click", () => {
      const timeMs = Math.max(0, video.currentTime * 1000 + Number(button.dataset.playerStep));
      seekVideoPlayer(player, timeMs);
    }));
    player.querySelector("[data-player-jump]")?.addEventListener("click", jump);
    player.querySelectorAll("[data-player-jump-to]").forEach(button => button.addEventListener("click", () => {
      const timestampMs = Number(button.dataset.playerJumpTo);
      player.dataset.centerMs = timestampMs;
      seekVideoPlayer(player, timestampMs);
      updateVideoPlayer(player);
    }));
    player.querySelector("[data-player-speed]")?.addEventListener("change", event => {
      video.playbackRate = Number(event.currentTarget.value);
    });
    player.querySelector("[data-player-window]")?.addEventListener("change", event => {
      player.dataset.windowMs = event.currentTarget.value;
      updateVideoPlayer(player);
    });
    player.querySelectorAll("[data-player-seek-to], [data-review-event-marker]").forEach(marker => {
      marker.addEventListener("click", () => {
        const timestampMs = Number(marker.dataset.timestampMs || marker.dataset.playerSeekTo);
        seekVideoPlayer(player, timestampMs);
        player.dataset.centerMs = timestampMs;
        player.dataset.jumpMs = timestampMs;
        updateVideoPlayer(player);
        if (marker.dataset.reviewEventMarker) {
          player.dispatchEvent(new CustomEvent("clip-ai-event-marker", {
            bubbles: true, detail: { eventId: marker.dataset.reviewEventMarker }
          }));
        }
      });
    });
    ["loadedmetadata", "durationchange", "timeupdate", "seeked"].forEach(name =>
      video.addEventListener(name, () => updateVideoPlayer(player)));
    if (Number(player.dataset.jumpMs) > 0) seekVideoPlayer(player, Number(player.dataset.jumpMs));
    updateVideoPlayer(player);
  });
}

async function render() {
  if (routeRefreshTimer) {
    clearTimeout(routeRefreshTimer);
    routeRefreshTimer = null;
  }
  const parts = route();
  workstationSelectEvent = null;
  app.dataset.reviewHotkeys = "false";
  app.setAttribute("aria-busy", "true");
  app.innerHTML = `<div class="loading">Loading review workbench…</div>`;
  setActiveNav(parts[0] === "assets" ? "matches" : parts[0] || "matches");
  try {
    if (!parts[0] || parts[0] === "dashboard") return await renderDashboard();
    if (parts[0] === "matches") return await renderMatches();
    if (parts[0] === "processing") return await renderProcessing(parts[1] || null, routeQuery().get("runId"));
    if (parts[0] === "events") return await renderEvents();
    if (parts[0] === "review") return await renderReviewQueue();
    if (parts[0] === "approved") return await renderApproved();
    if (parts[0] === "search") return await renderSearch(routeQuery().get("q") || "");
    if (parts[0] === "ground-truth") return await renderGroundTruth(parts[1]);
    if (parts[0] === "analytics") return await renderAnalytics();
    if (parts[0] === "clips") return await renderClips();
    if (parts[0] === "assets" && parts[2] === "rejected") {
      return await renderRejectedReview(parts[1], parts[3] || null);
    }
    if (parts[0] === "assets" && parts.length >= 4 && parts[2] === "candidates") {
      return await renderCandidate(parts[1], parts[3]);
    }
    if (parts[0] === "assets" && parts[1]) return await renderMatchWorkspace(parts[1], parts[2] || "review");
    return await renderAssets();
  } catch (error) {
    app.innerHTML = `${errorBox(error)}<div class="empty">The requested view could not be loaded.</div>`;
  } finally {
    app.setAttribute("aria-busy", "false");
  }
}

async function renderDashboard() {
  const stats = await api("/api/analytics/dashboard");
  const mediaWaiting = ["STORED", "READY", "AUDIO_EXTRACTED"]
    .reduce((count, status) => count + (stats.mediaByStatus?.[status] || 0), 0);
  const activeRuns = (stats.detectionRunsByStatus?.PENDING || 0) + (stats.detectionRunsByStatus?.RUNNING || 0);
  const mediaProcessing = Object.entries(stats.mediaByStatus || {}).map(([key, value]) =>
    `<div class="metric-row"><span>${esc(key)}</span><strong>${value}</strong></div>`).join("");
  const stageRows = Object.entries(stats.processingStagesByStatus || {}).map(([key, value]) =>
    `<div class="metric-row"><span>${esc(key)}</span><strong>${value}</strong></div>`).join("");
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Project operations</div><h1>Control Center</h1>
    <p class="subtitle">Persisted project-wide pipeline and review workload. Match evaluation remains scoped to an individual video.</p></div>
    <span class="badge">${stats.mediaAssets} MEDIA ASSETS</span></div>
    <div class="stat-grid">
      <a class="stat-card" href="#/processing"><span>Media awaiting preparation</span><strong>${mediaWaiting}</strong></a>
      <a class="stat-card" href="#/processing"><span>Active DetectionRuns</span><strong>${activeRuns}</strong></a>
      <a class="stat-card" href="#/processing"><span>Failed DetectionRuns</span><strong>${stats.detectionRunsByStatus?.FAILED || 0}</strong></a>
      <a class="stat-card" href="#/review"><span>Detected events awaiting review</span><strong>${stats.eventsAwaitingReview}</strong></a>
      <a class="stat-card" href="#/review"><span>Rejected candidates to inspect</span><strong>${stats.rejectedCandidatesAwaitingReview}</strong></a>
      <a class="stat-card" href="#/clips"><span>Generated clips</span><strong>${stats.generatedClips}</strong></a>
      <a class="stat-card" href="#/ground-truth"><span>Ground-truth events</span><strong>${stats.groundTruthEvents}</strong></a>
    </div>
    <div class="dashboard-columns">
      ${card("Media processing", mediaProcessing || `<p class="muted">No media has been uploaded.</p>`)}
      ${card("Processing stages", stageRows || `<p class="muted">No processing stages have been recorded.</p>`)}
      ${card("Review coverage", `<div class="key-values"><span>Matches reviewed</span><strong>${stats.reviewedMatches}</strong>
        <span>Completed annotation</span><strong>${stats.completedGroundTruthMatches}</strong>
        <span>Evaluation-ready matches</span><strong>${stats.evaluationReadyMatches}</strong></div>
        <p class="muted">${esc(stats.evaluationSufficiencyRule)}</p>`)}
    </div>
    <div class="button-row"><a class="button" href="#/matches">Upload / select media</a>
      <a class="button secondary" href="#/processing">Open processing queue</a>
      <a class="button secondary" href="#/analytics">View analytics</a></div>`;
}

async function renderMatches() {
  const [assets, clips] = await Promise.all([allAssets(), allClips()]);
  const summaries = await Promise.all(assets.map(async asset => {
    const [result, processing, runs] = await Promise.all([
      api(`${assetUrl(asset.id)}/candidates?sort=timestamp`),
      api(`${assetUrl(asset.id)}/processing`),
      api(`${assetUrl(asset.id)}/detection-runs`)
    ]);
    const candidates = result.events;
    const detected = candidates.filter(item => item.status !== "REJECTED" && item.status !== "MANUAL");
    const reviews = await Promise.all(detected.map(item => api(`${candidateUrl(asset.id, item.id)}/review`)));
    return {
      asset, candidates: candidates.filter(item => item.status !== "MANUAL").length, detected: detected.length,
      rejected: candidates.filter(item => item.status === "REJECTED").length,
      needsReview: reviews.filter(item => item.reviewStatus === "UNREVIEWED").length,
      approved: reviews.filter(item => item.reviewStatus === "CONFIRMED").length,
      clips: clips.filter(clip => clip.mediaAssetId === asset.id).length,
      transcriptStatus: processing.transcriptStatus || asset.transcript?.status || "NOT_STARTED",
      processingStages: processing.stages || [],
      latestRun: latestDetectionRun(runs),
      detectionStatus: result.detectionStatus || asset.candidateDetectionStatus || "NOT_STARTED",
      mediaStatus: processing.mediaStatus || asset.status
    };
  }));
  const summariesById = new Map(summaries.map(item => [item.asset.id, item]));
  const groups = groupAssetsByMatch(assets).map(group => group.map(asset => summariesById.get(asset.id)));
  const groupTotals = group => ({
    candidates: group.reduce((sum, item) => sum + item.candidates, 0),
    detected: group.reduce((sum, item) => sum + item.detected, 0),
    rejected: group.reduce((sum, item) => sum + item.rejected, 0),
    needsReview: group.reduce((sum, item) => sum + item.needsReview, 0),
    approved: group.reduce((sum, item) => sum + item.approved, 0),
    clips: group.reduce((sum, item) => sum + item.clips, 0),
    processing: group.some(item => /PROCESS|TRANSCRIB/i.test(item.mediaStatus)
      || ["PENDING", "RUNNING"].includes(item.latestRun?.status)),
    completed: group.some(item => item.latestRun?.status === "COMPLETED")
  });
  const filters = [
    ["ALL", "All", groups.length],
    ["NEEDS_REVIEW", "Needs review", groups.filter(group => groupTotals(group).needsReview > 0).length],
    ["REVIEWED", "Reviewed", groups.filter(group => {
      const totals = groupTotals(group);
      return totals.detected > 0 && totals.needsReview === 0;
    }).length],
    ["APPROVED", "Has approved events", groups.filter(group => groupTotals(group).approved > 0).length],
    ["PROCESSING", "Processing", groups.filter(group => groupTotals(group).processing).length],
    ["COMPLETED", "Completed", groups.filter(group => groupTotals(group).completed).length]
  ];
  app.innerHTML = `<div class="page-head library-head"><div><div class="eyebrow">Your football library</div><h1>Matches &amp; videos</h1>
    <p class="subtitle">Related video assets can be grouped into a match. Processing and review stay specific to each asset.</p></div>
    <button id="show-upload">＋ Upload video</button></div>
    <details class="upload-disclosure" id="upload-disclosure"><summary>Upload a video</summary>
      <section class="panel upload-panel"><p class="muted">A video can be processed without match metadata. Team, competition and date fields group related assets when known.</p>
        ${uploadFormMarkup("upload-form")}</section></details>
    <div class="match-filter-bar" role="group" aria-label="Filter matches">
      ${filters.map(([value, label, count], index) => `<button class="filter-chip ${index === 0 ? "active" : ""}" data-match-filter="${value}">${label}<span>${count}</span></button>`).join("")}
    </div>
    <div class="library-toolbar"><span class="muted" id="match-result-count">${groups.length} matches</span>
      <input id="match-search" type="search" aria-label="Search matches" placeholder="Search teams or competition"></div>
    <div class="match-library" id="match-cards"></div>`;
  const draw = () => {
    const term = document.getElementById("match-search").value.toLowerCase().trim();
    const activeFilter = document.querySelector("[data-match-filter].active")?.dataset.matchFilter || "ALL";
    const rows = groups.filter(group => {
      const totals = groupTotals(group);
      const matchesQuery = group.map(item => `${assetTitle(item.asset)} ${item.asset.originalFilename || ""}
        ${item.asset.homeTeam || ""} ${item.asset.awayTeam || ""} ${item.asset.competition || ""}`)
        .join(" ").toLowerCase().includes(term);
      const matchesFilter = activeFilter === "ALL"
        || activeFilter === "NEEDS_REVIEW" && totals.needsReview > 0
        || activeFilter === "REVIEWED" && totals.detected > 0 && totals.needsReview === 0
        || activeFilter === "APPROVED" && totals.approved > 0
        || activeFilter === "PROCESSING" && totals.processing
        || activeFilter === "COMPLETED" && totals.completed;
      return matchesQuery && matchesFilter;
    });
    document.getElementById("match-result-count").textContent = `${rows.length} ${rows.length === 1 ? "match" : "matches"}`;
    document.getElementById("match-cards").innerHTML = rows.map(group => {
      const first = group[0].asset;
      const totals = groupTotals(group);
      const teams = [first.homeTeam, first.awayTeam].filter(Boolean).join(" vs ");
      const assetRows = group.map(item => {
        const asset = item.asset, run = item.latestRun;
        const created = asset.createdAt ? new Date(asset.createdAt).toLocaleString() : "Created time unavailable";
        return `<article class="match-asset-row"><div class="match-asset-primary">
          <div><strong>${esc(matchPartLabel(asset))}</strong>${badge(item.mediaStatus, item.mediaStatus === "COMPLETED" ? "detected" : "")}</div>
          <span>${esc(asset.originalFilename || assetTitle(asset))}</span>
          <small>${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))} · Added ${esc(created)}</small>
        </div><div class="match-asset-state">
          <span>Transcript ${badge(item.transcriptStatus)}</span>
          <span>Detection ${badge(run?.status || item.detectionStatus, run?.status === "COMPLETED" ? "detected" : "")}</span>
          ${run ? `<small>${run.observationCount} observations · ${run.candidateCount} candidates · ${run.detectedCount} detected · ${run.rejectedCount} rejected</small>` : `<small>No detection run yet</small>`}
          ${renderActiveTaskProgress({ stages: item.processingStages, mediaStatus: item.mediaStatus,
            transcriptStatus: item.transcriptStatus }, run)}
        </div><div class="button-row">${linkButton(`#/assets/${encodeURIComponent(asset.id)}/review`, "Open asset", "secondary")}</div></article>`;
      }).join("");
      return `<article class="match-card match-group-card">
        <div class="match-card-main"><div class="eyebrow">${esc(first.competition || first.contentType || "Match")}</div>
          <h2>${esc(teams || assetTitle(first))}</h2>
          <p class="match-card-title">${esc(first.matchDate || "Match date not set")} · ${group.length} ${group.length === 1 ? "asset" : "assets"}</p>
        </div>
        <div class="match-card-counts" aria-label="Match review summary">
          <span><strong>${totals.candidates}</strong> candidates</span><span><strong>${totals.detected}</strong> detected</span>
          <span class="${totals.needsReview ? "needs-attention" : ""}"><strong>${totals.needsReview}</strong> to review</span>
          <span><strong>${totals.approved}</strong> approved</span><span><strong>${totals.clips}</strong> clips</span>
          <span class="quiet-count">${totals.rejected} system rejected</span>
        </div>
        <div class="match-assets-list">${assetRows}</div>
        <div class="match-card-actions">${linkButton(`#/assets/${encodeURIComponent(first.id)}/review`, "Open match workspace")}
          <button class="secondary" data-upload-match="${esc(first.id)}">＋ Add video asset</button>
          ${linkButton(`#/assets/${encodeURIComponent(first.id)}/approved`, "Approved", "secondary")}</div>
      </article>`;
    }).join("") || `<div class="empty"><strong>No matches found.</strong><p>Try another filter or upload a match video.</p></div>`;
  };
  document.getElementById("match-search").addEventListener("input", draw);
  document.querySelectorAll("[data-match-filter]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-match-filter]").forEach(item => item.classList.toggle("active", item === button));
    draw();
  }));
  document.getElementById("show-upload").addEventListener("click", () => {
    const disclosure = document.getElementById("upload-disclosure");
    disclosure.open = !disclosure.open;
    if (disclosure.open) disclosure.scrollIntoView({ behavior: "smooth", block: "start" });
  });
  draw();
  bindVideoUploadForm(document.getElementById("upload-form"));
  document.querySelectorAll("[data-upload-match]").forEach(button => button.addEventListener("click", () => {
    const selected = summariesById.get(button.dataset.uploadMatch)?.asset;
    if (!selected) return;
    const form = document.getElementById("upload-form");
    form.elements.namedItem("competition").value = selected.competition || "";
    form.elements.namedItem("homeTeam").value = selected.homeTeam || "";
    form.elements.namedItem("awayTeam").value = selected.awayTeam || "";
    form.elements.namedItem("matchDate").value = selected.matchDate || "";
    form.elements.namedItem("contentType").value = selected.contentType || "SPORTS";
    form.elements.namedItem("source").value = "LOCAL_UPLOAD";
    form.elements.namedItem("matchPart").value = "OTHER";
    form.elements.namedItem("title").value = "";
    const disclosure = document.getElementById("upload-disclosure");
    disclosure.open = true;
    disclosure.scrollIntoView({ behavior: "smooth", block: "start" });
    form.querySelector("[data-upload-file]").focus();
  }));
}

async function renderProcessing(assetId, requestedRunId = null) {
  if (!assetId) {
    const assets = await allAssets();
    const states = await Promise.all(assets.map(async asset => {
      const [status, runs] = await Promise.all([
        api(`${assetUrl(asset.id)}/processing`),
        api(`${assetUrl(asset.id)}/detection-runs`)
      ]);
      return { asset, status, run: latestDetectionRun(runs) };
    }));
    app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Pipeline</div><h1>Processing queue</h1>
      <p class="subtitle">Open an asset to see persisted media stages and its DetectionRun lifecycle.</p></div>
      <button class="secondary" id="refresh-processing">Refresh</button></div>
      <div class="processing-queue">${states.map(({ asset, status, run }) => `<article class="processing-queue-row">
        <div><strong>${esc([asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ") || assetTitle(asset))}</strong>
          <span>${esc(matchPartLabel(asset))} · ${esc(asset.originalFilename || assetTitle(asset))}</span>
          <small>${esc(asset.matchDate || "Match date not set")} · ${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}</small></div>
        <div>${badge(status.mediaStatus)} ${badge(status.transcriptStatus || "NOT_STARTED")}
          ${badge(run?.status || "NO_RUN", run?.status === "COMPLETED" ? "detected" : "")}
          ${run ? `<small>${run.observationCount} observations · ${run.candidateCount} candidates</small>` : ""}</div>
        ${renderActiveTaskProgress(status, run)}
        ${linkButton(`#/processing/${encodeURIComponent(asset.id)}`, "Open processing", "secondary")}</article>`).join("")
        || `<div class="empty">No media assets are available yet.</div>`}</div>`;
    document.getElementById("refresh-processing").addEventListener("click", () => renderProcessing());
    if (states.some(({ status, run }) => isMediaProcessing(status) || ["PENDING", "RUNNING"].includes(run?.status))) {
      scheduleProcessingRefresh(null, null);
    }
    return;
  }

  const [asset, status, runs] = await Promise.all([
    api(assetUrl(assetId)),
    api(`${assetUrl(assetId)}/processing`),
    api(`${assetUrl(assetId)}/detection-runs`)
  ]);
  const activeRun = runs.find(run => !run.legacy && ["PENDING", "RUNNING"].includes(run.status));
  const latestRun = latestDetectionRun(runs);
  const selectedRun = requestedRunId
    ? runs.find(run => run.runId === requestedRunId) || await api(`${assetUrl(assetId)}/detection-runs/${encodeURIComponent(requestedRunId)}`)
    : activeRun || latestRun;
  let runCandidates = [];
  let observations = [];
  let transcript = null;
  let matchContext = { structureMarkers: [], scoreTransitions: [] };
  if (selectedRun && selectedRun.status !== "PENDING" && selectedRun.status !== "RUNNING") {
    runCandidates = await api(`${assetUrl(assetId)}/detection-runs/${encodeURIComponent(selectedRun.runId)}/candidates`);
    const contextResult = await Promise.allSettled([
      api(`${assetUrl(assetId)}/match-context`),
      api(`${assetUrl(assetId)}/transcript`)
    ]);
    if (contextResult[0].status === "fulfilled") matchContext = contextResult[0].value;
    if (contextResult[1].status === "fulfilled") transcript = contextResult[1].value;
    const observationEnd = Math.max(asset.durationMs || 0, ...runCandidates.map(item => item.endTimeMs));
    if (observationEnd > 0 && selectedRun.observationCount > 0) {
      observations = await api(`${assetUrl(assetId)}/detection-runs/${encodeURIComponent(selectedRun.runId)}`
        + `/observations?startTimeMs=0&endTimeMs=${observationEnd}&limit=1000`);
    }
  }
  if (sessionStorage.getItem("clip-ai-upload-notice")) {
    toast(sessionStorage.getItem("clip-ai-upload-notice"));
    sessionStorage.removeItem("clip-ai-upload-notice");
  }
  const runStatus = selectedRun?.status || "NOT_STARTED";
  const audioStage = status.stages.find(stage => stage.stage === "AUDIO_EXTRACTION");
  const transcriptionStage = status.stages.find(stage => stage.stage === "TRANSCRIPTION");
  const detected = runCandidates.filter(event => event.status !== "REJECTED" && event.status !== "MANUAL");
  const rejected = runCandidates.filter(event => event.status === "REJECTED");
  const canRunDetection = status.mediaStatus === "COMPLETED"
    && status.transcriptStatus === "COMPLETED"
    && !["PENDING", "RUNNING"].includes(activeRun?.status);
  const processingStages = [
    ["Media available", status.mediaStatus === "FAILED" ? "FAILED"
      : ["STORED", "READY", "PROCESSING", "AUDIO_EXTRACTED", "TRANSCRIBING", "COMPLETED"].includes(status.mediaStatus) ? "COMPLETED" : "PENDING",
      status.mediaStatus === "STORED" ? "The uploaded asset is stored; media processing has not started." : status.mediaStatus],
    ["Audio / media preparation", audioStage?.status
      || (["AUDIO_EXTRACTED", "TRANSCRIBING", "COMPLETED"].includes(status.mediaStatus) ? "COMPLETED" : "NOT_AVAILABLE"),
      audioStage?.message || "Only persisted extraction status is shown.", audioStage?.progress, true],
    ["Transcript available", transcriptionStage?.status || status.transcriptStatus || "NOT_AVAILABLE",
      transcriptionStage?.message || (status.transcriptStatus === "COMPLETED"
        ? "Transcript is persisted and ready for inspection." : "Transcript state from the processing API."),
      transcriptionStage?.progress, true],
    ["Signal / observation extraction", selectedRun ? runStatus : "PENDING",
      selectedRun ? `${selectedRun.observationCount} observations persisted so far.` : "No DetectionRun yet."],
    ["Candidate discovery", "NOT_AVAILABLE", "The API persists run-level status, not a separate discovery stage."],
    ["Candidate assembly / clustering", "NOT_AVAILABLE", "The API persists run-level status, not a separate assembly stage."],
    ["Detection result persistence", selectedRun ? runStatus : "PENDING",
      selectedRun ? `${selectedRun.candidateCount} candidates persisted so far.` : "No DetectionRun yet."],
    ["Detection outcome", selectedRun ? runStatus : "NOT_STARTED",
      selectedRun?.failureReason || (runStatus === "COMPLETED" ? "Detection complete." : "No detection run yet.")]
  ];
  const lifecycle = processingStages.map(([name, stageStatus, detail, progress, hasPersistedProgress]) => {
    const progressBar = hasPersistedProgress && isTaskRunning(stageStatus)
      ? renderTaskProgress(name, progress)
      : hasPersistedProgress && stageStatus === "COMPLETED" && progress === 100
        ? renderTaskProgress(name, progress, true) : "";
    return `<li class="lifecycle-stage"><span class="lifecycle-indicator ${esc(String(stageStatus).toLowerCase())}"></span>
      <div><strong>${esc(name)}</strong><small>${esc(lifecycleStatusLabel(stageStatus))} · ${esc(detail || "")}</small>
        ${progressBar}</div></li>`;
  }).join("");
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow"><a href="#/matches">Match</a> / Processing</div>
    <h1>${esc([asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ") || assetTitle(asset))}</h1>
    <p class="subtitle">${esc(matchPartLabel(asset))} · ${esc(asset.originalFilename || assetTitle(asset))}
      · ${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}</p></div>
    <div class="button-row"><a class="button secondary" href="#/assets/${encodeURIComponent(assetId)}/review">Match workspace</a>
      <button class="secondary" id="refresh-processing">Refresh</button></div></div>
    <section class="panel processing-lifecycle-panel"><div class="section-heading"><div><div class="eyebrow">Persisted pipeline state</div>
      <h2>${runStatus === "COMPLETED" ? "Detection complete" : runStatus === "FAILED" ? "Detection failed"
        : ["PENDING", "RUNNING"].includes(runStatus) ? "Detection running"
          : status.mediaStatus === "COMPLETED" ? "No detection run yet" : "Media processing"}</h2>
      <p class="muted">Run-level state and media stages come from the backend. Individual detector substages are not persisted separately.</p></div>
      ${badge(runStatus, runStatus === "COMPLETED" ? "detected" : runStatus === "FAILED" ? "rejected" : "unreviewed")}</div>
      <ol class="processing-lifecycle">${lifecycle}</ol>
      ${status.failureReason || selectedRun?.failureReason ? errorBox(status.failureReason || selectedRun.failureReason) : ""}
      <div class="button-row processing-actions">
        ${["STORED", "READY"].includes(status.mediaStatus) ? `<button data-media-action="start">Start media preparation</button>` : ""}
        ${status.mediaStatus === "AUDIO_EXTRACTED" ? `<button data-media-action="transcription">Start transcription</button>` : ""}
        ${status.mediaStatus === "FAILED" ? `<button data-media-action="retry">Retry media processing</button>` : ""}
        ${canRunDetection ? `<button data-run-detection>Run detection</button>` : ""}
        ${status.mediaStatus === "COMPLETED" && status.transcriptStatus !== "COMPLETED" ? `<span class="muted">Detection is unavailable until the transcript is complete.</span>` : ""}
      </div><div id="processing-feedback"></div></section>
    ${selectedRun ? renderProcessingRunSummary(asset, selectedRun, runCandidates, observations, transcript, matchContext)
      : `<section class="panel"><div class="empty"><strong>No detection run yet.</strong>
        <p>When media preparation and transcription are complete, start a new DetectionRun here.</p></div></section>`}`;
  document.getElementById("refresh-processing").addEventListener("click", () => renderProcessing(assetId, selectedRun?.runId || null));
  document.querySelectorAll("[data-media-action]").forEach(button => button.addEventListener("click", async () => {
    button.disabled = true;
    const action = button.dataset.mediaAction;
    const endpoint = action === "start" ? "" : `/${action}`;
    try {
      await api(`${assetUrl(assetId)}/processing${endpoint}`, { method: "POST" });
      toast("Media processing request accepted.");
      renderProcessing(assetId, selectedRun?.runId || null);
    } catch (error) {
      document.getElementById("processing-feedback").innerHTML = errorBox(error);
      button.disabled = false;
    }
  }));
  document.querySelector("[data-run-detection]")?.addEventListener("click", async event => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
      const run = await api(`${assetUrl(assetId)}/detection-runs`, { method: "POST" });
      toast("Detection run accepted.");
      navigate(`processing/${encodeURIComponent(assetId)}?runId=${encodeURIComponent(run.runId)}`);
    } catch (error) {
      document.getElementById("processing-feedback").innerHTML = errorBox(error);
      button.disabled = false;
    }
  });
  if (isMediaProcessing(status) || ["PENDING", "RUNNING"].includes(runStatus)) {
    scheduleProcessingRefresh(assetId, selectedRun?.runId || null);
  }
}

function isMediaProcessing(status) {
  return ["PROCESSING", "TRANSCRIBING"].includes(status.mediaStatus)
    || status.stages.some(stage => stage.status === "QUEUED" || stage.status === "RUNNING");
}

function isTaskRunning(status) {
  return ["RUNNING", "PROCESSING", "TRANSCRIBING"].includes(String(status).toUpperCase());
}

function renderTaskProgress(label, progress = null, completed = false) {
  const measuredProgress = Number.isInteger(progress) && progress > 0
      && (progress < 100 || completed && progress === 100)
    ? progress : null;
  const ariaValue = measuredProgress === null
    ? 'aria-valuetext="In progress; exact completion percentage is not reported"'
    : `aria-valuenow="${measuredProgress}"`;
  return `<div class="task-progress">
    <div class="task-progress-track${measuredProgress === null ? " indeterminate" : ""}" role="progressbar"
      aria-label="${esc(label)}" aria-valuemin="0" aria-valuemax="100" ${ariaValue}>
      ${measuredProgress === null ? "" : `<span style="width:${measuredProgress}%"></span>`}</div>
    <small>${measuredProgress === null ? "In progress · exact completion percentage not reported"
      : `${measuredProgress}% complete`}</small>
  </div>`;
}

function renderActiveTaskProgress(status, run) {
  const activeStage = status.stages?.find(stage => isTaskRunning(stage.status));
  if (activeStage) {
    const label = activeStage.stage === "TRANSCRIPTION" ? "Transcription"
      : activeStage.stage === "AUDIO_EXTRACTION" ? "Audio extraction" : "Media processing";
    return renderTaskProgress(label, activeStage.progress);
  }
  if (status.transcriptStatus === "PROCESSING" || status.mediaStatus === "TRANSCRIBING") {
    return renderTaskProgress("Transcription");
  }
  if (status.mediaStatus === "PROCESSING") return renderTaskProgress("Media preparation");
  return run?.status === "RUNNING" ? renderTaskProgress("DetectionRun", run.progress) : "";
}

function lifecycleStatusLabel(status) {
  return status === "NOT_AVAILABLE" ? "Not available"
    : status === "NOT_STARTED" ? "No run yet"
      : String(status).toLowerCase().replaceAll("_", " ");
}

function scheduleProcessingRefresh(assetId, runId) {
  if (routeRefreshTimer) clearTimeout(routeRefreshTimer);
  const currentHash = location.hash;
  routeRefreshTimer = setTimeout(() => {
    if (location.hash !== currentHash && !(assetId && route()[0] === "processing" && route()[1] === assetId)) return;
    renderProcessing(assetId, runId).catch(error => {
      app.innerHTML = `${errorBox(error)}<div class="empty">Processing status could not be refreshed.</div>`;
    });
  }, 2500);
}

function scheduleWorkspaceRefresh(assetId, tab) {
  if (routeRefreshTimer) clearTimeout(routeRefreshTimer);
  const currentHash = location.hash;
  routeRefreshTimer = setTimeout(() => {
    if (location.hash !== currentHash && !(route()[0] === "assets" && route()[1] === assetId)) return;
    renderMatchWorkspace(assetId, tab).catch(error => {
      app.insertAdjacentHTML("afterbegin", errorBox(error));
    });
  }, 2500);
}

function renderProcessingRunSummary(asset, run, events, observations, transcript, context) {
  const detected = events.filter(event => event.status !== "REJECTED" && event.status !== "MANUAL");
  const rejected = events.filter(event => event.status === "REJECTED");
  const types = [...new Set(events.map(event => event.eventType))].sort();
  const breakdown = types.length ? `<div class="run-type-breakdown"><table class="table"><thead><tr>
    <th>Event type</th><th>Detected</th><th>Rejected</th></tr></thead><tbody>${types.map(type => `<tr><td>${esc(type.replaceAll("_", " "))}</td>
      <td>${detected.filter(event => event.eventType === type).length}</td>
      <td>${rejected.filter(event => event.eventType === type).length}</td></tr>`).join("")}</tbody></table></div>`
    : `<div class="empty">No candidate breakdown is available for this run.</div>`;
  const createdAt = value => value ? new Date(value).toLocaleString() : "Not recorded";
  return `<section class="panel detection-run-summary">
    <div class="section-heading"><div><div class="eyebrow">DetectionRun</div><h2>${esc(run.detectorVersion || "Detector version unavailable")}</h2></div>
      ${badge(run.status, run.status === "COMPLETED" ? "detected" : run.status === "FAILED" ? "rejected" : "unreviewed")}</div>
    ${run.status === "RUNNING" ? renderTaskProgress("DetectionRun", run.progress) : ""}
    <div class="run-metadata"><div><span>Run ID</span><code>${esc(run.runId)}</code></div>
      <div><span>Configuration hash</span><code>${esc(run.configurationHash || "Not available")}</code></div>
      <div><span>Created</span><strong>${esc(createdAt(run.createdAt))}</strong></div>
      <div><span>Started</span><strong>${esc(createdAt(run.startedAt))}</strong></div>
      <div><span>Completed</span><strong>${esc(createdAt(run.completedAt))}</strong></div></div>
    <div class="workspace-summary run-count-summary">
      ${workspaceMetric("Observations", run.observationCount, "run-observations")}
      ${workspaceMetric("Candidates", run.candidateCount, "run-candidates")}
      ${workspaceMetric("Detected", run.detectedCount, "run-detected")}
      ${workspaceMetric("Rejected", run.rejectedCount, "run-rejected")}
    </div>
    ${run.status === "COMPLETED" ? `<h3>Results by event type</h3>${breakdown}` : ""}
    <div class="run-result-columns">
      <section><div class="section-heading"><h3>Detected events · ${detected.length}</h3></div>
        ${detected.map(event => renderProcessingCandidate(asset.id, event, observations, transcript, context)).join("")
          || `<div class="empty">0 detected events${run.status === "COMPLETED" ? " in this run" : " so far"}.</div>`}</section>
      <section><div class="section-heading"><h3>Rejected candidates · ${rejected.length}</h3></div>
        ${rejected.map(event => renderProcessingCandidate(asset.id, event, observations, transcript, context)).join("")
          || `<div class="empty">0 rejected candidates${run.status === "COMPLETED" ? " in this run" : " so far"}.</div>`}</section>
    </div>
    ${observations.length ? `<details class="run-observation-details"><summary>Persisted observations · ${observations.length}${observations.length < run.observationCount ? ` shown of ${run.observationCount}` : ""}</summary>
      <div class="diagnostic-observation-list">${observations.map(item => `<div><time>${esc(seconds(item.timestampMs))}</time>
        <strong>${esc(item.type)}</strong><span>${Math.round(item.confidence * 100)}%</span><small>${esc(item.evidence)}</small></div>`).join("")}</div></details>`
      : run.observationCount ? `<p class="muted">Observation count is persisted; no time-bounded observation sample is available.</p>` : ""}
    <div class="button-row">${linkButton(`#/assets/${encodeURIComponent(asset.mediaAssetId)}/candidates`, "Open detected and rejected review")}
      ${linkButton(`#/assets/${encodeURIComponent(asset.mediaAssetId)}/review`, "Open match timeline", "secondary")}</div>
    </section>`;
}

function renderProcessingCandidate(assetId, event, observations, transcript, context) {
  const eventObservations = observations.filter(item => event.sourceObservationIds?.includes(item.id));
  const audioSignals = event.signals.filter(signal => /AUDIO|CROWD|PITCH|SPEECH_RATE/.test(signal.type));
  const replaySignals = event.signals.filter(signal => /REPLAY|RETROSPECTIVE|EVENT_ASSOCIATION/.test(signal.type));
  const scoreTransitions = context.scoreTransitions.filter(item =>
    Math.abs(item.timestampMs - event.triggerTimestampMs) <= 60000);
  const detailLink = event.status === "REJECTED"
    ? `#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(event.id)}`
    : `#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}`;
  const outcomeLabel = event.status === "REJECTED" ? "Rejected"
    : event.status === "MANUAL" ? "Manual" : "Detected";
  return `<article class="processing-candidate">
    <div class="processing-candidate-heading"><div><strong>${esc(event.eventType.replaceAll("_", " "))}</strong>
      ${badge(outcomeLabel, event.status === "REJECTED" ? "rejected" : "detected")}</div>
      <a href="${esc(detailLink)}">${esc(seconds(event.triggerTimestampMs))} · Inspect</a></div>
    <div class="processing-candidate-meta"><span>Window ${esc(seconds(event.startTimeMs))}–${esc(seconds(event.endTimeMs))}</span>
      <span>Score ${Number(event.score).toFixed(3)}</span>
      <span>Live ${Math.round(event.liveEventProbability * 100)}%</span>
      <span>Replay ${Math.round(event.replayProbability * 100)}%</span>
      <span>Phase ${esc((event.matchPhase || "UNKNOWN").replaceAll("_", " "))}</span></div>
    ${event.transcriptContext ? `<p class="candidate-transcript-context">${esc(event.transcriptContext)}</p>` : `<p class="muted">No candidate transcript context stored.</p>`}
    ${event.rejectionReasons?.length ? `<p class="candidate-rejection-reasons"><strong>Why rejected:</strong> ${event.rejectionReasons.map(systemRejectionLabel).map(esc).join(" · ")}</p>` : ""}
    <details><summary>Evidence, observations and context</summary>
      <div class="evidence-columns"><section><h4>Transcript / event signals</h4>${renderSignals(event.signals)}</section>
        <section><h4>Audio evidence</h4>${audioSignals.length ? renderSignals(audioSignals) : `<p class="muted">No audio-specific signals are attached.</p>`}</section></div>
      ${replaySignals.length ? `<h4>Replay / retrospective evidence</h4>${renderSignals(replaySignals)}` : ""}
      ${eventObservations.length ? `<h4>Source observations · ${eventObservations.length}</h4>
        <ul>${eventObservations.map(item => `<li>${esc(seconds(item.timestampMs))} · ${esc(item.type)} · ${Math.round(item.confidence * 100)}% · ${esc(item.evidence)}</li>`).join("")}</ul>`
        : `<p class="muted">${event.sourceObservationIds?.length || 0} source observation IDs; detailed observations are not in the loaded sample.</p>`}
      ${scoreTransitions.length ? `<h4>Nearby persisted score transitions</h4><ul>${scoreTransitions.map(item =>
        `<li>${esc(seconds(item.timestampMs))} · ${item.homeScore}-${item.awayScore} · ${esc(item.source)}</li>`).join("")}</ul>` : ""}
      ${transcript ? `<h4>Nearby transcript</h4>${renderTranscript(transcript.segments, event.triggerTimestampMs)}` : ""}
      <h4>Score contributions</h4>${renderScoreContributions(event.scoreContributions)}
      <p class="muted">Candidate ${esc(event.id)} · run ${esc(event.detectionRunId || "")}</p>
    </details>
  </article>`;
}

function renderStage(stage) {
  return stage ? `<div>${badge(stage.status)}
    ${isTaskRunning(stage.status) ? renderTaskProgress(stage.stage || "Processing task", stage.progress) : ""}
    <small>${esc(stage.message || "")}</small></div>` : `<span class="muted">Not started</span>`;
}

async function loadAllEvents() {
  const assets = await allAssets();
  const groups = await Promise.all(assets.map(async asset => {
    const result = await api(`${assetUrl(asset.id)}/candidates?sort=timestamp`);
    return result.events.map(event => ({ asset, event }));
  }));
  return groups.flat();
}

async function renderEvents() {
  const [all, eventAssets, eventTypes] = await Promise.all([loadAllEvents(), allAssets(), allEventTypes()]);
  const entries = await Promise.all(all.map(async ({ asset, event }) => ({
    asset, event, review: await api(`${candidateUrl(asset.id, event.id)}/review`)
  })));
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Candidate records</div><h1>Candidate audit</h1>
    <p class="subtitle">System detection and human review are separate decisions. Open a match for the primary review workflow.</p></div><span class="badge">${all.length} CANDIDATES</span></div>
    <section class="panel"><h2>Create manual candidate</h2><p class="muted">This adds a reviewable event; it does not alter or rerun detection.</p>
      <form id="manual-candidate-form" class="form-grid">
      <label>Media asset<select name="mediaAssetId" required>${eventAssets.map(asset => `<option value="${asset.id}">${esc(assetTitle(asset))}</option>`).join("")}</select></label>
      <label>Event type<select name="eventType">${eventTypes.map(type => `<option value="${esc(type.code)}">${esc(type.label)}</option>`).join("")}</select></label>
      <label>Event time (seconds)<input name="timestamp" type="number" min="0" step="0.001" required></label>
      <label>Clip start (seconds)<input name="start" type="number" min="0" step="0.001" required></label>
      <label>Clip end (seconds)<input name="end" type="number" min="0" step="0.001" required></label>
      <label>Note<input name="note" maxlength="2000" placeholder="Optional context"></label><button type="submit">Create candidate</button></form><div id="manual-event-feedback"></div></section>
    <div class="filters"><input id="event-global-search" type="search" placeholder="Search event, media, evidence">
    <select id="event-global-type"><option value="">All event types</option>${[...new Set(all.map(x => x.event.eventType))].sort().map(t => `<option>${esc(t)}</option>`).join("")}</select>
    <select id="event-global-status"><option value="">All system decisions</option>${[...new Set(all.map(x => x.event.status))].sort().map(t => `<option>${esc(t)}</option>`).join("")}</select></div>
    <div class="table-wrap"><table class="table"><thead><tr><th>Candidate</th><th>Media</th><th>Trigger</th><th>Score</th><th>System decision</th><th>Human review</th><th>Actions</th></tr></thead><tbody id="event-global-rows"></tbody></table></div>`;
  const rows = entries.map(({ asset, event, review }) => ({ asset, event, review,
    search: `${event.eventType} ${event.transcriptContext || ""} ${assetTitle(asset)}`.toLowerCase() }));
  const draw = () => {
    const q = document.getElementById("event-global-search").value.toLowerCase().trim();
    const type = document.getElementById("event-global-type").value, status = document.getElementById("event-global-status").value;
    const selected = rows.filter(row => (!q || row.search.includes(q)) && (!type || row.event.eventType === type) && (!status || row.event.status === status));
    document.getElementById("event-global-rows").innerHTML = selected.map(({ asset, event, review }) => `<tr>
      <td><strong>${esc(event.eventType)}</strong><div class="muted">${esc(event.id)}</div></td><td>${esc(assetTitle(asset))}</td>
      <td>${esc(seconds(event.triggerTimestampMs))}</td><td>${Math.round(event.score * 100)}%</td>
      <td>${badge(event.status === "REJECTED" ? "System rejected" : event.status === "MANUAL" ? "Manual event" : "Detected", event.status === "REJECTED" ? "rejected" : "detected")}</td>
      <td>${event.status === "REJECTED" ? `<span class="muted">Not in event review</span>` : badge(humanReviewLabel(review.reviewStatus), review.reviewStatus === "REJECTED" ? "human-rejected" : review.reviewStatus.toLowerCase())}</td>
      <td>${linkButton(`#/assets/${encodeURIComponent(asset.id)}/candidates/${encodeURIComponent(event.id)}`, "Review", "secondary")}</td></tr>`).join("")
      || `<tr><td colspan="7" class="muted">No events match these filters.</td></tr>`;
  };
  ["event-global-search", "event-global-type", "event-global-status"].forEach(id => document.getElementById(id).addEventListener(id.endsWith("search") ? "input" : "change", draw));
  draw();
  document.getElementById("manual-candidate-form").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget), id = form.get("mediaAssetId");
    const ms = key => Math.round(Number(form.get(key)) * 1000);
    try {
      const created = await api(`${assetUrl(id)}/candidates`, { method: "POST", body: JSON.stringify({
        eventType: form.get("eventType"), timestampMs: ms("timestamp"), startTimeMs: ms("start"),
        endTimeMs: ms("end"), note: form.get("note") || null
      }) });
      navigate(`assets/${id}/candidates/${created.id}`);
    } catch (error) { document.getElementById("manual-event-feedback").innerHTML = errorBox(error); }
  });
}

async function renderReviewQueue() {
  const assets = await allAssets();
  const groups = await Promise.all(assets.map(async asset => {
    const result = await api(`${assetUrl(asset.id)}/candidates?sort=timestamp`);
    const detected = result.events.filter(event => event.status !== "REJECTED" && event.status !== "MANUAL");
    const rejectedCandidates = result.events.filter(event => event.status === "REJECTED");
    const [reviewed, rejected] = await Promise.all([
      Promise.all(detected.map(async event => ({
      event, review: await api(`${candidateUrl(asset.id, event.id)}/review`)
      }))),
      Promise.all(rejectedCandidates.map(async event => ({
        event, review: await api(`${candidateUrl(asset.id, event.id)}/review`)
      })))
    ]);
    const pending = reviewed.filter(item => item.review.reviewStatus === "UNREVIEWED");
    const rejectedPending = rejected.filter(item => item.review.reviewStatus !== "REJECTED");
    return { asset, pending, rejectedPending };
  }));
  const pendingCount = groups.reduce((count, group) => count + group.pending.length, 0);
  const rejectedPendingCount = groups.reduce((count, group) => count + group.rejectedPending.length, 0);
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Human decisions</div><h1>Review queue</h1>
    <p class="subtitle">Only events accepted by detection and still awaiting your decision appear here. System-rejected candidates are separate.</p></div>
    <span class="badge">${pendingCount} EVENTS · ${rejectedPendingCount} REJECTED CANDIDATES</span></div>
    <div class="review-match-list">${groups.filter(group => group.pending.length || group.rejectedPending.length).map(({ asset, pending, rejectedPending }) => {
      const first = pending[0]?.event;
      return `<article class="review-match"><div><div class="eyebrow">${esc([asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ") || asset.competition || "Match")}</div>
        <h2>${esc(assetTitle(asset))}</h2>
        ${pending.length ? `<p class="muted">${pending.length} detected event${pending.length === 1 ? "" : "s"} need review · next at ${esc(seconds(first.triggerTimestampMs))}</p>
          <div class="queue-progress-label">${pending.length} event decisions remaining</div>` : ""}
        ${rejectedPending.length ? `<div class="queue-progress-label">${rejectedPending.length} system-rejected candidates need review</div>` : ""}</div>
        <div class="button-row">${pending.length ? linkButton(`#/assets/${encodeURIComponent(asset.id)}/candidates/${encodeURIComponent(first.id)}`, "Review next event") : ""}
        ${rejectedPending.length ? linkButton(`#/assets/${encodeURIComponent(asset.id)}/rejected`, `Review rejected · ${rejectedPending.length}`, "secondary") : ""}</div></article>`;
    }).join("") || `<div class="empty"><strong>No items need review.</strong><p>Detected events and system-rejected candidates have separate queues.</p>
      ${linkButton("#/approved", "Open approved events", "secondary")}</div>`}</div>`;
}

async function renderApproved() {
  const [all, clips] = await Promise.all([loadAllEvents(), allClips()]);
  const entries = await Promise.all(all
    .filter(({ event }) => event.status !== "REJECTED")
    .map(async ({ asset, event }) => ({ asset, event, review: await api(`${candidateUrl(asset.id, event.id)}/review`) })));
  const approved = entries.filter(item => item.review.reviewStatus === "CONFIRMED");
  const types = [...new Set(approved.map(item => item.event.eventType))].sort();
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Your trusted collection</div><h1>Approved events</h1>
    <p class="subtitle">Events you have confirmed. Ground Truth remains a separate reference annotation.</p></div>
    <span class="badge confirmed">${approved.length} APPROVED</span></div>
    <div class="library-toolbar"><span class="muted">${approved.length} confirmed event${approved.length === 1 ? "" : "s"}</span>
      <div class="approved-filters"><input id="approved-search" type="search" placeholder="Search approved events or matches" aria-label="Search approved events">
        <select id="approved-type"><option value="">All event types</option>${types.map(type => `<option value="${esc(type)}">${esc(type.replaceAll("_", " "))}</option>`).join("")}</select></div></div>
    <div id="approved-grid" class="approved-event-grid"></div>`;
  const draw = () => {
    const query = document.getElementById("approved-search").value.trim().toLowerCase();
    const type = document.getElementById("approved-type").value;
    const visible = approved.filter(item => (!type || item.event.eventType === type)
      && (!query || `${item.event.eventType} ${assetTitle(item.asset)} ${item.event.transcriptContext || ""}`.toLowerCase().includes(query)));
    document.getElementById("approved-grid").innerHTML = visible.map(item => renderApprovedEventCard(item, item.asset,
      clips.find(clip => clip.mediaAssetId === item.asset.id && clip.candidateId === item.event.id))).join("")
      || `<div class="empty"><strong>${approved.length ? "No approved events match these filters." : "No approved events yet."}</strong>
        <p>${approved.length ? "Try another search or event type." : "Review detected events to build your approved event library."}</p>
        ${approved.length ? "" : linkButton("#/review", "Go to Review Queue")}</div>`;
    bindVideoPlayers(app);
  };
  document.getElementById("approved-search").addEventListener("input", draw);
  document.getElementById("approved-type").addEventListener("change", draw);
  draw();
}

async function renderSearch(query) {
  const [assets, clips, eventRows] = await Promise.all([allAssets(), allClips(), loadAllEvents()]);
  const entries = await Promise.all(eventRows.map(async ({ asset, event }) => ({
    asset, event, review: event.status === "REJECTED" ? null
      : await api(`${candidateUrl(asset.id, event.id)}/review`)
  })));
  const term = query.trim().toLowerCase();
  const matches = assets.filter(asset => `${assetTitle(asset)} ${asset.homeTeam || ""} ${asset.awayTeam || ""} ${asset.competition || ""}`.toLowerCase().includes(term));
  const approved = entries.filter(item => item.review?.reviewStatus === "CONFIRMED"
    && `${item.event.eventType} ${assetTitle(item.asset)} ${item.event.transcriptContext || ""}`.toLowerCase().includes(term));
  const detected = entries.filter(item => item.event.status !== "REJECTED" && item.review?.reviewStatus !== "CONFIRMED"
    && `${item.event.eventType} ${assetTitle(item.asset)} ${item.event.transcriptContext || ""}`.toLowerCase().includes(term));
  const candidates = entries.filter(item => item.event.status === "REJECTED"
    && `${item.event.eventType} ${assetTitle(item.asset)} ${item.event.transcriptContext || ""} ${item.event.rejectionReasons.join(" ")}`.toLowerCase().includes(term));
  const matchingClips = clips.filter(item => `${item.eventType} ${item.mediaAssetTitle}`.toLowerCase().includes(term));
  const resultLink = (path, label) => `<a class="search-result-link" href="${esc(path)}">${esc(label)} →</a>`;
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Find a moment</div><h1>Search</h1>
    <p class="subtitle">${term ? `Results for “${esc(query.trim())}”` : "Search matches, events, candidates and clips."}</p></div></div>
    ${!term ? `<div class="empty">Enter a team, match, event type, transcript phrase or clip to search.</div>` : ""}
    <section class="search-results-section"><div class="section-heading"><h2>Approved events <span>${approved.length}</span></h2></div>
      ${approved.map(item => `<article class="search-result"><div>${eventGlyph(item.event.eventType)} <strong>${esc(item.event.eventType.replaceAll("_", " "))}</strong>
        <span class="muted">${esc(assetTitle(item.asset))} · ${esc(seconds(item.event.triggerTimestampMs))}</span></div>
        ${resultLink(`#/assets/${encodeURIComponent(item.asset.id)}/candidates/${encodeURIComponent(item.event.id)}`, "Open approved event")}</article>`).join("") || `<p class="muted">No approved events match.</p>`}</section>
    <section class="search-results-section"><div class="section-heading"><h2>Matches <span>${matches.length}</span></h2></div>
      ${matches.map(asset => `<article class="search-result"><div><strong>${esc(assetTitle(asset))}</strong><span class="muted">${esc([asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ") || asset.competition || "")}</span></div>
        ${resultLink(`#/assets/${encodeURIComponent(asset.id)}/review`, "Open match")}</article>`).join("") || `<p class="muted">No matches found.</p>`}</section>
    <section class="search-results-section"><div class="section-heading"><h2>Clips <span>${matchingClips.length}</span></h2></div>
      ${matchingClips.map(item => `<article class="search-result"><div><strong>${eventGlyph(item.eventType)} ${esc(item.eventType.replaceAll("_", " "))}</strong>
        <span class="muted">${esc(item.mediaAssetTitle)} · ${esc(seconds(item.startTimeMs))}</span></div>
        ${resultLink(`#/assets/${encodeURIComponent(item.mediaAssetId)}/candidates/${encodeURIComponent(item.candidateId)}`, "Open clip context")}</article>`).join("") || `<p class="muted">No clips match.</p>`}</section>
    <section class="search-results-section"><div class="section-heading"><h2>Detected events <span>${detected.length}</span></h2></div>
      ${detected.map(item => `<article class="search-result"><div><strong>${esc(item.event.eventType.replaceAll("_", " "))}</strong>${badge(humanReviewLabel(item.review?.reviewStatus), "unreviewed")}
        <span class="muted">${esc(assetTitle(item.asset))} · ${esc(seconds(item.event.triggerTimestampMs))}</span></div>
        ${resultLink(`#/assets/${encodeURIComponent(item.asset.id)}/candidates/${encodeURIComponent(item.event.id)}`, "Review event")}</article>`).join("") || `<p class="muted">No detected events match.</p>`}</section>
    <details class="secondary-disclosure"><summary>System-rejected candidates · ${candidates.length}</summary>
      ${candidates.map(item => `<article class="search-result"><div><strong>${esc(item.event.eventType.replaceAll("_", " "))}</strong>${badge("System rejected", "rejected")}
        <span class="muted">${esc(assetTitle(item.asset))} · ${esc(seconds(item.event.triggerTimestampMs))}</span></div>
        ${resultLink(`#/assets/${encodeURIComponent(item.asset.id)}/candidates/${encodeURIComponent(item.event.id)}`, "Inspect candidate")}</article>`).join("") || `<p class="muted">No rejected candidates match.</p>`}</details>`;
}

async function renderGroundTruth(assetId) {
  const assets = await allAssets();
  if (!assetId && assets.length) return navigate(`ground-truth/${assets[0].id}`);
  if (!assetId) {
    app.innerHTML = `<div class="empty">Upload media before starting ground-truth annotation.</div>`; return;
  }
  const asset = assets.find(item => item.id === assetId);
  if (!asset) throw new Error("Media asset not found");
  const [truth, candidates, eventTypes] = await Promise.all([
    api(`${assetUrl(assetId)}/ground-truth`),
    api(`${assetUrl(assetId)}/candidates?sort=timestamp`),
    allEventTypes()
  ]);
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Evaluation data</div><h1>Ground truth</h1>
    <p class="subtitle">${esc(assetTitle(asset))} · annotations are stored separately from detector candidates.</p></div>
    <select id="ground-truth-asset">${assets.map(item => `<option value="${item.id}" ${item.id === assetId ? "selected" : ""}>${esc(assetTitle(item))}</option>`).join("")}</select></div>
    <div class="stat-grid"><div class="stat-card"><span>Ground-truth events · match scope</span><strong>${truth.metrics.groundTruthCount}</strong></div>
    <div class="stat-card"><span>System detections · match scope</span><strong>${truth.metrics.detectedCount}</strong></div>
    <div class="stat-card"><span>Match evaluation</span><strong>${truth.metrics.sufficientForEvaluation ? "Ready" : "Not ready"}</strong></div></div>
    ${asset.localStoragePath ? `<section class="panel"><div class="player-label"><h2>Ground-truth source review</h2><span class="muted">Reference annotations are independent from system detections.</span></div>
      ${renderVideoPlayer({ playerId: "ground-truth-source-player", sourceUrl: `${assetUrl(assetId)}/source`,
        label: `${assetTitle(asset)} ground-truth review`, durationMs: asset.durationMs,
        markers: [
          ...truth.events.map(event => ({ position: event.timestampMs, kind: "ground-truth", label: "GT",
            title: `Ground truth · ${event.eventType} · ${seconds(event.timestampMs)}` })),
          ...candidates.events.map(event => ({ position: event.triggerTimestampMs, kind: "event",
            title: `System ${event.status.toLowerCase()} · ${event.eventType} · ${seconds(event.triggerTimestampMs)}` }))
        ]})}</section>` : ""}
    <section class="panel"><div class="player-label"><h2>Confirm a candidate</h2><span class="muted">Only explicit confirmation creates ground truth.</span></div>
      <div class="button-row"><select id="ground-truth-candidate"><option value="">Select a detected candidate</option>${candidates.events.filter(event => event.status !== "REJECTED").map(event => `<option value="${event.id}">${esc(event.eventType)} · ${esc(seconds(event.triggerTimestampMs))} · ${event.id}</option>`).join("")}</select>
      <input id="ground-truth-note" maxlength="2000" placeholder="Optional annotation note"><button id="confirm-ground-truth">Confirm as ground truth</button></div></section>
    <section class="panel"><h2>Add ground-truth event manually</h2><form id="ground-truth-form" class="form-grid">
    <label>Event type<select name="eventType">${eventTypes.map(type => `<option value="${esc(type.code)}">${esc(type.label)}</option>`).join("")}</select></label>
      <label>Event time (seconds)<input name="timestamp" type="number" min="0" step="0.001" required></label>
      <label>Clip start (seconds)<input name="start" type="number" min="0" step="0.001" required></label>
      <label>Clip end (seconds)<input name="end" type="number" min="0" step="0.001" required></label>
      <label>Note<input name="note" maxlength="2000" placeholder="Optional context"></label><button type="submit">Add ground-truth event</button></form></section>
    <section class="panel"><div class="player-label"><h2>Annotated events</h2><button id="complete-ground-truth" class="secondary">${truth.reviewStatus === "COMPLETED" ? "Reopen match" : "Complete match review"}</button></div>
      <div class="table-wrap"><table class="table"><thead><tr><th>Type</th><th>Timestamp</th><th>Clip window</th><th>Source</th><th>Note</th><th></th></tr></thead><tbody>
      ${truth.events.map(event => `<tr><td>${badge(event.eventType)}</td><td>${esc(seconds(event.timestampMs))}</td>
        <td>${esc(seconds(event.startTimeMs))} → ${esc(seconds(event.endTimeMs))}</td>
        <td>${event.sourceCandidateId ? "Candidate confirmation" : "Manual"}</td><td>${esc(event.note || "")}</td>
        <td><button class="danger" data-delete-ground-truth="${event.id}">Remove</button></td></tr>`).join("")
        || `<tr><td colspan="6" class="muted">No ground-truth events yet.</td></tr>`}</tbody></table></div>
      <p class="muted">Match-scoped evaluation · ${esc(assetTitle(asset))}. ${esc(truth.metrics.sufficiencyMessage)}</p>
      ${truth.metrics.sufficientForEvaluation ? `<div class="key-values"><span>True positives</span><strong>${truth.metrics.truePositive}</strong>
        <span>False positives</span><strong>${truth.metrics.falsePositive}</strong><span>False negatives</span><strong>${truth.metrics.falseNegative}</strong></div>` : ""}</section>
      <div id="ground-truth-feedback"></div>`;
  bindVideoPlayers(app);
  document.getElementById("ground-truth-asset").addEventListener("change", event => navigate(`ground-truth/${event.target.value}`));
  document.getElementById("confirm-ground-truth").addEventListener("click", async () => {
    const candidateId = document.getElementById("ground-truth-candidate").value;
    if (!candidateId) return toast("Select a candidate first.");
    try {
      await api(`${assetUrl(assetId)}/ground-truth/events/from-candidates/${candidateId}`, {
        method: "POST", body: JSON.stringify({ note: document.getElementById("ground-truth-note").value || null })
      }); await renderGroundTruth(assetId);
    } catch (error) { document.getElementById("ground-truth-feedback").innerHTML = errorBox(error); }
  });
  document.getElementById("ground-truth-form").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget), ms = key => Math.round(Number(form.get(key)) * 1000);
    try {
      await api(`${assetUrl(assetId)}/ground-truth/events`, { method: "POST", body: JSON.stringify({
        eventType: form.get("eventType"), timestampMs: ms("timestamp"), startTimeMs: ms("start"),
        endTimeMs: ms("end"), note: form.get("note") || null
      }) }); await renderGroundTruth(assetId);
    } catch (error) { document.getElementById("ground-truth-feedback").innerHTML = errorBox(error); }
  });
  document.getElementById("complete-ground-truth").addEventListener("click", async () => {
    const status = truth.reviewStatus === "COMPLETED" ? "IN_PROGRESS" : "COMPLETED";
    try { await api(`${assetUrl(assetId)}/ground-truth`, { method: "PUT", body: JSON.stringify({ status }) }); await renderGroundTruth(assetId); }
    catch (error) { document.getElementById("ground-truth-feedback").innerHTML = errorBox(error); }
  });
  document.querySelectorAll("[data-delete-ground-truth]").forEach(button => button.addEventListener("click", async () => {
    try { await api(`${assetUrl(assetId)}/ground-truth/events/${button.dataset.deleteGroundTruth}`, { method: "DELETE" }); await renderGroundTruth(assetId); }
    catch (error) { document.getElementById("ground-truth-feedback").innerHTML = errorBox(error); }
  }));
}

async function renderAnalytics() {
  const stats = await api("/api/analytics/dashboard");
  const table = values => Object.entries(values || {}).map(([key, value]) =>
    `<div class="metric-row"><span>${esc(key)}</span><strong>${value}</strong></div>`).join("") || `<p class="muted">No data.</p>`;
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Measurement</div><h1>Analytics</h1>
    <p class="subtitle">Project-wide operational counts and review workload. No project-wide precision/recall is inferred from match-scoped annotations.</p></div></div>
    <div class="dashboard-columns">${card("Media lifecycle", table(stats.mediaByStatus))}
      ${card("Events by type", table(stats.eventsByType))}
      ${card("Detection outcomes", table(stats.eventsByDetectionStatus))}
      ${card("DetectionRun outcomes · project scope", table(stats.detectionRunsByStatus))}
      ${card("Stage outcomes", table(stats.processingStagesByStatus))}
      ${card("Human review workload · project scope", `<div class="key-values">
        <span>Detected events awaiting review</span><strong>${stats.eventsAwaitingReview}</strong>
        <span>System-rejected candidates awaiting inspection</span><strong>${stats.rejectedCandidatesAwaitingReview}</strong></div>`)}
      ${card("Ground-truth coverage", `<div class="key-values"><span>Matches reviewed</span><strong>${stats.reviewedMatches}</strong>
        <span>Completed</span><strong>${stats.completedGroundTruthMatches}</strong><span>Evaluation ready</span><strong>${stats.evaluationReadyMatches}</strong>
        <span>Ground-truth events</span><strong>${stats.groundTruthEvents}</strong></div><p class="muted">${esc(stats.evaluationSufficiencyRule)}</p>`)}</div>`;
}

async function renderAssets() {
  const assets = await allAssets();
  const clips = await allClips();
  const clipCounts = new Map(clips.reduce((counts, item) => {
    counts.set(item.mediaAssetId, (counts.get(item.mediaAssetId) || 0) + 1);
    return counts;
  }, new Map()));
  const summaries = await Promise.all(assets.map(async asset => {
    const candidates = await api(`${assetUrl(asset.id)}/candidates?sort=timestamp`);
    const active = candidates.events.filter(item => item.status !== "REJECTED");
    const reviews = await Promise.all(active.map(item => api(`${candidateUrl(asset.id, item.id)}/review`)));
    return {
      asset,
      candidates: candidates.events.length,
      detected: candidates.events.filter(item => item.status !== "REJECTED" && item.status !== "MANUAL").length,
      rejected: candidates.events.filter(item => item.status === "REJECTED").length,
      needsReview: reviews.filter(item => item.reviewStatus === "UNREVIEWED").length,
      candidateStatus: candidates.detectionStatus,
      clipCount: clipCounts.get(asset.id) || 0
    };
  }));
  app.innerHTML = `
    <div class="page-head"><div><div class="eyebrow">Workspace</div><h1>Media assets</h1><p class="subtitle">Inspect source media, detected events, rejected hypotheses, and generated clips.</p></div>
      <span class="badge">${assets.length} ASSETS</span></div>
    ${assets.length ? `<div class="grid">${summaries.map(({ asset, candidates, detected, rejected, needsReview, candidateStatus, clipCount }) => `
      <article class="card asset-card"><div class="grow"><div class="asset-title">${esc(assetTitle(asset))}</div>
      <div class="meta-row"><span>${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}</span><span>${esc(asset.status)}</span><span>Detection: ${esc(candidateStatus)}</span></div>
      <p class="muted">${candidates} candidates · ${detected} detected · ${rejected} rejected candidates · ${needsReview} need review · ${clipCount} clips</p></div>
      <div class="card-actions">${linkButton(`#/assets/${encodeURIComponent(asset.id)}`, "Open review")}</div></article>`).join("")}</div>`
      : `<div class="empty">No media assets are available yet. Uploaded or registered assets will appear here.</div>`}`;
}

function renderVideoReviewStation(asset, entries, clips) {
  const ordered = [...entries].sort((left, right) =>
    left.event.triggerTimestampMs - right.event.triggerTimestampMs);
  const selected = ordered.find(item => item.event.status !== "REJECTED"
    && item.review.reviewStatus === "UNREVIEWED") || ordered[0] || null;
  const markers = ordered.map(({ event, review }) => ({
    eventId: event.id,
    position: event.triggerTimestampMs,
    kind: event.status === "REJECTED" ? "rejected" : review.reviewStatus === "CONFIRMED" ? "approved" : "event",
    title: `${event.status === "REJECTED" ? "System rejected" : "System detected"} · ${event.eventType} · ${seconds(event.triggerTimestampMs)}`,
    label: ""
  }));
  return `<section class="review-station panel">
    <div class="section-heading"><div><div class="eyebrow">Video-first review</div><h2>Source video and event context</h2>
      <p class="muted">Select an event or timeline marker to seek directly to its moment.</p></div>
      ${selected ? `<label class="review-station-select">Selected event<select data-workstation-event>
        ${ordered.map(({ event, review }) => `<option value="${esc(event.id)}" ${event.id === selected.event.id ? "selected" : ""}>
          ${esc(seconds(event.triggerTimestampMs))} · ${esc(event.eventType.replaceAll("_", " "))} · ${event.status === "REJECTED" ? "System rejected" : humanReviewLabel(review.reviewStatus)}</option>`).join("")}
      </select></label>` : ""}</div>
    <div class="review-station-layout">
      <div class="review-station-source">${asset.localStoragePath
        ? renderVideoPlayer({ playerId: "workstation-source-player", sourceUrl: `${assetUrl(asset.id)}/source`,
          label: `${assetTitle(asset)} source video`, currentTimeMs: selected?.event.triggerTimestampMs || 0,
          durationMs: asset.durationMs, markers })
        : `<div class="empty">Source video is unavailable for this media asset.</div>`}</div>
      <aside class="review-station-detail" data-workstation-detail>
        ${renderReviewStationDetail(selected, asset.id, selected ? clips.get(selected.event.id) : null)}
      </aside>
    </div>
  </section>`;
}

function renderReviewStationDetail(entry, assetId, clip) {
  if (!entry) return `<div class="empty"><strong>No event candidates yet.</strong>
    <p>Process this asset to create observations and candidates for review.</p></div>`;
  const { event, review } = entry;
  const rejected = event.status === "REJECTED";
  const detailPath = rejected
    ? `#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(event.id)}`
    : `#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}`;
  const clipContent = clip
    ? `<div class="review-station-clip"><div class="eyebrow">Retained event clip</div>
        ${renderVideoPlayer({ playerId: `station-clip-${event.id}`, sourceUrl: clip.downloadUrl,
          label: `${event.eventType} event clip`, durationMs: clip.endTimeMs - clip.startTimeMs,
          currentTimeMs: Math.max(0, event.triggerTimestampMs - clip.startTimeMs),
          markers: [{ position: Math.max(0, event.triggerTimestampMs - clip.startTimeMs),
            kind: "event", title: `Event moment · ${seconds(event.triggerTimestampMs)}`, label: "E" }],
          showReviewWindow: false, preload: "none" })}
        <p class="muted">${seconds(clip.startTimeMs)}–${seconds(clip.endTimeMs)} · ${duration(clip.endTimeMs - clip.startTimeMs)}</p>
        <a class="text-link" href="${esc(clip.downloadUrl.replace("?inline=true", ""))}">Download event clip</a></div>`
    : rejected
      ? `<div class="review-station-clip"><div class="eyebrow">Temporary review clip</div>
          <p class="muted">Generated on demand for this rejected candidate and removed after confirmed rejection.</p>
          ${linkButton(detailPath, "Review playable candidate", "secondary")}</div>`
      : `<div class="review-station-clip"><div class="eyebrow">Event clip</div>
          <p class="muted">No retained clip yet. Generate one from the current event boundaries.</p>
          <button type="button" data-workstation-generate-clip="${esc(event.id)}">Generate event clip</button></div>`;
  return `<div class="review-station-event">
    <div class="review-station-event-heading"><div><div class="eyebrow">${esc(event.matchPhase && event.matchPhase !== "UNKNOWN"
        ? event.matchPhase.replaceAll("_", " ") : "Match phase unavailable")}</div>
      <h3>${esc(event.eventType.replaceAll("_", " "))}</h3><time>${esc(seconds(event.triggerTimestampMs))}</time></div>
      ${badge(rejected ? "System rejected" : event.status === "MANUAL" ? "Manual event" : "System detected",
        rejected ? "rejected" : event.status === "MANUAL" ? "manual" : "detected")}</div>
    <div class="review-station-review-state"><span>Human review</span>${badge(humanReviewLabel(review.reviewStatus),
      review.reviewStatus === "REJECTED" ? "human-rejected" : review.reviewStatus.toLowerCase())}</div>
    <div class="system-assessment-grid"><span>Detection score</span><strong>${Math.round(event.score * 100)}%</strong>
      <span>Live probability</span><strong>${Number.isFinite(event.liveEventProbability) ? `${Math.round(event.liveEventProbability * 100)}%` : "Unavailable"}</strong>
      <span>Replay probability</span><strong>${Number.isFinite(event.replayProbability) ? `${Math.round(event.replayProbability * 100)}%` : "Unavailable"}</strong></div>
    ${event.transcriptContext ? `<div class="review-station-transcript"><div class="eyebrow">Transcript context</div><p>${esc(event.transcriptContext)}</p></div>`
      : `<p class="muted review-station-transcript">No transcript context was persisted for this candidate.</p>`}
    ${rejected ? `<div class="review-station-rejection"><strong>Why rejected?</strong>
      <ul>${(event.rejectionReasons || []).map(reason => `<li>${esc(systemRejectionLabel(reason))}</li>`).join("")
        || "<li>No explicit system rejection reason was stored.</li>"}</ul></div>` : ""}
    <div class="review-station-evidence"><strong>Evidence</strong>${renderEvidenceSummary(event)}</div>
    ${clipContent}
    <div class="button-row">${linkButton(detailPath, rejected ? "Inspect rejection" : "Open event review")}</div>
  </div>`;
}

function bindReviewWorkstation(assetId, entries, clips) {
  const selector = app.querySelector("[data-workstation-event]");
  const detail = app.querySelector("[data-workstation-detail]");
  const sourcePlayer = app.querySelector("#workstation-source-player")?.closest("[data-video-player]");
  if (!selector || !detail) return;
  const selectEvent = (eventId, shouldSeek = true) => {
    const entry = entries.find(item => item.event.id === eventId);
    if (!entry) return;
    selector.value = eventId;
    detail.innerHTML = renderReviewStationDetail(entry, assetId, clips.get(eventId));
    bindVideoPlayers(detail);
    if (sourcePlayer) {
      sourcePlayer.dataset.centerMs = entry.event.triggerTimestampMs;
      sourcePlayer.dataset.jumpMs = entry.event.triggerTimestampMs;
      if (shouldSeek) seekVideoPlayer(sourcePlayer, entry.event.triggerTimestampMs);
      updateVideoPlayer(sourcePlayer);
    }
    app.querySelectorAll("[data-review-event-marker]").forEach(marker => {
      const active = marker.dataset.reviewEventMarker === eventId;
      marker.classList.toggle("selected", active);
      marker.setAttribute("aria-pressed", String(active));
    });
  };
  workstationSelectEvent = eventId => selectEvent(eventId);
  selector.addEventListener("change", () => selectEvent(selector.value));
  detail.addEventListener("click", async event => {
    const button = event.target.closest("[data-workstation-generate-clip]");
    if (!button) return;
    const candidateId = button.dataset.workstationGenerateClip;
    const entry = entries.find(item => item.event.id === candidateId);
    if (!entry) return;
    button.disabled = true;
    button.textContent = "Generating event clip…";
    try {
      await api(`${candidateUrl(assetId, candidateId)}/clip`, { method: "POST" });
      clips.set(candidateId, {
        downloadUrl: `${candidateUrl(assetId, candidateId)}/clip?inline=true`,
        startTimeMs: entry.review.effectiveStartTimeMs,
        endTimeMs: entry.review.effectiveEndTimeMs
      });
      selectEvent(candidateId, false);
      toast("Retained event clip is ready.");
    } catch (error) {
      button.disabled = false;
      button.textContent = "Retry clip generation";
      detail.insertAdjacentHTML("afterbegin", errorBox(error));
    }
  });
  selectEvent(selector.value, false);
}

app.addEventListener("clip-ai-event-marker", event => {
  workstationSelectEvent?.(event.detail.eventId);
});
app.addEventListener("click", event => {
  const marker = event.target.closest?.("[data-review-event-marker]");
  if (marker && !marker.closest("[data-video-player]")) {
    workstationSelectEvent?.(marker.dataset.reviewEventMarker);
  }
  const seekMarker = event.target.closest?.("[data-player-seek-to]");
  if (seekMarker && !seekMarker.closest("[data-video-player]")) {
    const player = app.querySelector("#workstation-source-player")?.closest("[data-video-player]");
    const timestampMs = Number(seekMarker.dataset.playerSeekTo);
    if (player) {
      player.dataset.centerMs = timestampMs;
      player.dataset.jumpMs = timestampMs;
      seekVideoPlayer(player, timestampMs);
      updateVideoPlayer(player);
    }
  }
});

async function renderAsset(assetId) {
  const [asset, candidateResult, context, truth] = await Promise.all([
    api(assetUrl(assetId)),
    api(`${assetUrl(assetId)}/candidates?sort=timestamp`),
    api(`${assetUrl(assetId)}/match-context`),
    api(`${assetUrl(assetId)}/ground-truth`)
  ]);
  let timelineDurationMs = asset.durationMs;
  if (timelineDurationMs == null) {
    try {
      const transcript = await api(`${assetUrl(assetId)}/transcript`);
      timelineDurationMs = Math.max(0, ...transcript.segments.map(segment => segment.endTimeMs));
    } catch {
      timelineDurationMs = Math.max(0, ...candidateResult.events.map(event => event.endTimeMs));
    }
  }
  let observations = [];
  let observationError = null;
  if (timelineDurationMs > 0) {
    try {
      observations = await api(
        `${assetUrl(assetId)}/observations?startTimeMs=0&endTimeMs=${timelineDurationMs}&limit=1000`);
    } catch (error) {
      observationError = error.message || "The observations endpoint is unavailable.";
    }
  }
  const entries = await Promise.all(candidateResult.events.map(async event => ({
    event, review: await api(`${candidateUrl(assetId, event.id)}/review`)
  })));
  const detected = entries.filter(item => item.event.status !== "REJECTED" && item.event.status !== "MANUAL");
  const manual = entries.filter(item => item.event.status === "MANUAL");
  const rejected = entries.filter(item => item.event.status === "REJECTED");
  const pending = detected.filter(item => item.review.reviewStatus === "UNREVIEWED");
  const confirmed = detected.filter(item => item.review.reviewStatus === "CONFIRMED");
  const humanRejected = detected.filter(item => item.review.reviewStatus === "REJECTED");
  const systemCandidates = detected.concat(rejected);
  const groundTruthCandidates = new Set(truth.events.map(item => item.sourceCandidateId).filter(Boolean));
  const types = [...new Set(detected.map(item => item.event.eventType))].sort();
  app.innerHTML = `
    <div class="page-head workspace-head"><div><div class="eyebrow"><a href="#/matches">Matches</a> / Review Workspace</div>
      <h1>${esc(assetTitle(asset))}</h1>
      <p class="subtitle">${esc([asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ") || asset.competition || "Media asset")}
        · ${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}
        · Analysis ${esc(candidateResult.detectionStatus || "NOT STARTED")}</p></div>
      <div class="button-row">
        <button class="secondary" data-process-match>Process</button>
        <button class="secondary" data-review-match ${pending.length ? "" : "disabled"}>Review next</button>
        <button data-generate-clips ${detected.length ? "" : "disabled"}>Generate Clips</button>
        ${asset.localStoragePath ? linkButton(`${assetUrl(assetId)}/source`, "Open source", "secondary") : ""}
      </div></div>
    ${candidateResult.failureReason ? errorBox(candidateResult.failureReason) : ""}
    <section class="workspace-summary" aria-label="Analysis summary">
      ${workspaceMetric("Candidates considered", systemCandidates.length, "candidate-count")}
      ${workspaceMetric("Detected events", detected.length, "detected-count")}
      ${workspaceMetric("Rejected candidates", rejected.length, "system-rejected-count")}
      ${workspaceMetric("Needs review", pending.length, "pending-count", "primary")}
      ${workspaceMetric("Confirmed", confirmed.length, "confirmed-count")}
      ${workspaceMetric("Human rejected", humanRejected.length, "human-rejected-count")}
      ${workspaceMetric("Clips ready", entries.filter(item => item.review.clipGenerated).length, "clips-count")}
      ${workspaceMetric("Manual events", manual.length, "manual-count")}
    </section>
    <section class="panel match-timeline-panel">
      <div class="player-label"><div><h2>Match timeline</h2><p class="muted">Context and event layers stay separate. Select a layer to focus the timeline.</p></div>
        <a href="#/ground-truth/${encodeURIComponent(assetId)}" class="text-link">Ground truth · ${truth.events.length}</a></div>
      ${renderMatchTimeline(asset, entries, context, observations, observationError, timelineDurationMs,
        truth.events, scoreboardObservations)}
      ${renderMatchContextEditor(context)}</section>
    ${renderScoreboardAnalysisPanel(scoreboardAnalyses, scoreboardObservations, scoreboardError)}
    <section class="panel workspace-events">
      <div class="player-label"><div><div class="eyebrow">Primary</div><h2>Detected Events</h2></div><span class="badge detected">${detected.length}</span></div>
      <div class="event-toolbar">
        <div class="filter-chips" role="group" aria-label="Filter detected events">
          <button class="filter-chip active" data-review-filter="UNREVIEWED">Needs review <span>${pending.length}</span></button>
          <button class="filter-chip" data-review-filter="CONFIRMED">Confirmed <span>${confirmed.length}</span></button>
          <button class="filter-chip" data-review-filter="REJECTED">Human Rejected <span>${humanRejected.length}</span></button>
          <button class="filter-chip" data-review-filter="ALL">All detected <span>${detected.length}</span></button>
        </div>
        <div class="filters compact-filters"><input id="workspace-search" type="search" placeholder="Search type or timestamp">
          <select id="workspace-type"><option value="">All event types</option>${types.map(type => `<option value="${esc(type)}">${esc(type)}</option>`).join("")}</select></div>
      </div>
      <div class="event-table" id="detected-event-list">
        ${detected.map(item => renderDetectedEventRow(item, assetId, groundTruthCandidates.has(item.event.id))).join("")
          || `<div class="empty">No detected events are available yet.</div>`}
      </div>
      <div class="workspace-footnote">Detected events are system-accepted candidates. Human review and ground-truth status are shown separately.</div>
    </section>
    ${manual.length ? `<details class="manual-events-section"><summary>Manual Events <span class="badge manual">${manual.length}</span></summary>
      <div class="event-table">${manual.map(item => renderDetectedEventRow(item, assetId, groundTruthCandidates.has(item.event.id))).join("")}</div></details>` : ""}
    <details class="rejected-candidates-section">
      <summary><span>Rejected Candidates</span><span class="badge rejected">${rejected.length}</span>
        <span class="muted">System considered but did not accept these as events.</span></summary>
      <div class="candidate-list rejected-candidate-list">
        ${rejected.map(item => renderRejectedCandidateRow(item, assetId)).join("")
          || `<div class="empty">No candidates were rejected by the system.</div>`}
      </div>
    </details>
    <div id="asset-feedback"></div>`;

  let activeReviewFilter = "UNREVIEWED";
  const setEventFilter = (filter, button) => {
    activeReviewFilter = filter;
    document.querySelectorAll("[data-review-filter]").forEach(item => item.classList.toggle("active", item === button));
    applyWorkspaceFilters();
  };
  document.querySelectorAll("[data-review-filter]").forEach(button => button.addEventListener("click", () => {
    setEventFilter(button.dataset.reviewFilter, button);
  }));
  setEventFilter(activeReviewFilter, document.querySelector('[data-review-filter="UNREVIEWED"]'));
  document.getElementById("workspace-search").addEventListener("input", applyWorkspaceFilters);
  document.getElementById("workspace-type").addEventListener("change", applyWorkspaceFilters);
  function applyWorkspaceFilters() {
    const query = document.getElementById("workspace-search").value.trim().toLowerCase();
    const type = document.getElementById("workspace-type").value;
    for (const row of document.querySelectorAll("[data-detected-row]")) {
      row.hidden = (activeReviewFilter !== "ALL" && row.dataset.review !== activeReviewFilter)
        || Boolean(query && !row.dataset.search.includes(query))
        || Boolean(type && row.dataset.type !== type);
    }
  }
  document.querySelector("[data-process-match]").addEventListener("click", () => navigate(`processing/${assetId}`));
  document.querySelector("[data-review-match]").addEventListener("click", () => {
    if (pending.length) navigate(`assets/${assetId}/candidates/${pending[0].event.id}`);
  });
  document.querySelector("[data-generate-clips]").addEventListener("click", async event => {
    const button = event.currentTarget;
    button.disabled = true;
    const feedback = document.getElementById("asset-feedback");
    try {
      let batch = await api(`${assetUrl(assetId)}/candidates/clips`, { method: "POST" });
      feedback.innerHTML = `<div class="panel" id="batch-progress"></div>`;
      while (batch.status === "PROCESSING") {
        const progress = document.getElementById("batch-progress");
        if (progress) progress.textContent = `Generating clips… ${batch.completedCandidates} / ${batch.totalCandidates}`;
        await new Promise(resolve => setTimeout(resolve, 1500));
        batch = await api(`${assetUrl(assetId)}/candidates/clips`);
      }
      toast(`Clip batch ${batch.status.toLowerCase().replaceAll("_", " ")}.`);
      await renderAsset(assetId);
    } catch (error) {
      feedback.innerHTML = errorBox(error);
      button.disabled = false;
    }
  });
  bindMatchTimelineLayers();
  bindMatchContextEditor(assetId);
  bindScoreboardAnalysis(assetId, tab);
}

function renderScoreboardAnalysisPanel(analyses, observations, error) {
  const latest = analyses[0] || null;
  const status = latest?.status || "NOT STARTED";
  const transitions = observations.filter(item => item.kind === "SCORE_TRANSITION");
  const reversals = observations.filter(item => item.kind === "SCORE_REVERSAL");
  const states = observations.filter(item => item.kind === "SCORE_STATE");
  const raw = observations.filter(item => item.kind === "OCR_OBSERVATION").slice(0, 80);
  const pending = latest && ["PENDING", "RUNNING"].includes(latest.status);
  return `<section class="panel scoreboard-analysis-panel">
    <div class="player-label"><div><div class="eyebrow">Independent evidence · does not affect DetectionRuns</div>
      <h2>Scoreboard OCR</h2><p class="muted">Sampled-frame score observations only. OCR does not create or confirm football events.</p></div>
      <button type="button" data-start-scoreboard ${pending || error ? "disabled" : ""}>
        ${pending ? "OCR analysis running…" : latest ? "Run another OCR analysis" : "Run OCR analysis"}</button></div>
    ${error ? `<p class="error" role="status">${esc(error)}</p>` : ""}
    ${latest ? `<div class="key-values scoreboard-summary">
      <span>Analysis status</span><strong>${esc(status)}</strong>
      <span>OCR model</span><strong>${esc(latest.ocrModel)}</strong>
      <span>Frames sampled / OCR calls</span><strong>${latest.sampledFrameCount} / ${latest.ocrCallCount}</strong>
      <span>Raw OCR observations</span><strong>${latest.rawObservationCount}</strong>
      <span>Stable score states</span><strong>${latest.scoreStateCount}</strong>
      <span>Score transitions</span><strong>${latest.scoreTransitionCount}</strong>
      <span>Score reversals</span><strong>${latest.scoreReversalCount}</strong>
      <span>Frame processing errors</span><strong>${latest.processingErrorCount}</strong>
      <span>OCR processing time</span><strong>${duration(latest.processingDurationMs)}</strong></div>
      ${pending ? `<div class="task-progress" role="status" aria-live="polite">
        <div class="task-progress-track indeterminate" aria-label="Scoreboard OCR is processing"></div>
        <span>OCR is sampling frames; no percentage is available.</span></div>` : ""}
      ${latest.failureReason ? `<p class="error">${esc(latest.failureReason)}</p>` : ""}
      ${transitions.length || reversals.length || states.length ? `<details class="scoreboard-evidence">
        <summary>Score evidence · ${states.length} states · ${transitions.length} transitions · ${reversals.length} reversals</summary>
        <ul class="diagnostic-context-list">${[...states, ...transitions, ...reversals]
          .sort((a, b) => a.timestampMs - b.timestampMs).map(item => `<li>
            <time>${esc(seconds(item.timestampMs))}</time><span><strong>${esc(item.kind.replaceAll("_", " "))}</strong>
              · ${esc(item.rawText)} · ${Math.round((item.confidence || 0) * 100)}%</span></li>`).join("")}</ul>
        <details><summary>Raw OCR diagnostics · first ${raw.length}</summary>
          <ul class="diagnostic-observation-list">${raw.map(item => `<li><time>${esc(seconds(item.timestampMs))}</time>
            <div><strong>${item.homeScore == null ? "No score parsed" : `${item.homeScore}-${item.awayScore}`}</strong>
              <span>${Math.round((item.confidence || 0) * 100)}%</span><span>${esc(item.rawText || "(no text)")}</span></div>
            <small>${esc(item.detailsJson)}</small></li>`).join("") || `<li>No raw OCR observations.</li>`}</ul>
        </details></details>` : `<p class="muted">No stable score changes were recorded in this analysis.</p>`}
    ` : `<p class="muted">No OCR analysis has been run for this match.</p>`}
  </section>`;
}

function bindScoreboardAnalysis(assetId, tab) {
  const button = app.querySelector("[data-start-scoreboard]");
  if (!button) return;
  button.addEventListener("click", async () => {
    button.disabled = true;
    button.textContent = "Starting OCR analysis…";
    try {
      await api(`${assetUrl(assetId)}/scoreboard-analyses`, { method: "POST" });
      await renderMatchWorkspace(assetId, tab);
    } catch (error) {
      button.disabled = false;
      button.textContent = "Retry OCR analysis";
      button.insertAdjacentHTML("afterend", errorBox(error));
    }
  });
  const latest = app.querySelector(".scoreboard-analysis-panel .scoreboard-summary strong");
  if (latest && latest.textContent === "PENDING" || latest && latest.textContent === "RUNNING") {
    setTimeout(() => renderMatchWorkspace(assetId, tab), 3000);
  }
}

async function renderMatchWorkspace(assetId, requestedTab = "review") {
  const tabs = ["review", "approved", "candidates", "clips"];
  const tab = tabs.includes(requestedTab) ? requestedTab : "review";
  const [asset, candidateResult, context, truth, allClipItems, detectionRuns, libraryAssets] = await Promise.all([
    api(assetUrl(assetId)),
    api(`${assetUrl(assetId)}/candidates?sort=timestamp`),
    api(`${assetUrl(assetId)}/match-context`),
    api(`${assetUrl(assetId)}/ground-truth`),
    allClips(),
    api(`${assetUrl(assetId)}/detection-runs`),
    allAssets()
  ]);
  let scoreboardAnalyses = [];
  let scoreboardError = null;
  try {
    scoreboardAnalyses = await api(`${assetUrl(assetId)}/scoreboard-analyses`);
  } catch (error) {
    scoreboardError = error.message || "Scoreboard OCR is unavailable.";
  }
  const scoreboardAnalysis = scoreboardAnalyses[0] || null;
  let scoreboardObservations = [];
  if (scoreboardAnalysis?.status === "COMPLETED") {
    try {
      const endTimeMs = Math.max(asset.durationMs || 0,
        ...candidateResult.events.map(item => item.endTimeMs), 1);
      scoreboardObservations = await api(`${assetUrl(assetId)}/scoreboard-analyses/${scoreboardAnalysis.id}`
        + `/observations?startTimeMs=0&endTimeMs=${endTimeMs}&limit=10000`);
    } catch (error) {
      scoreboardError = error.message || "Scoreboard observations are unavailable.";
    }
  }
  const relatedAssets = groupAssetsByMatch(libraryAssets)
    .find(group => group.some(item => item.id === assetId)) || [asset];
  const matchAssetDetails = await Promise.all(relatedAssets.map(async relatedAsset => {
    const [processing, runs] = await Promise.all([
      api(`${assetUrl(relatedAsset.id)}/processing`),
      relatedAsset.id === assetId ? Promise.resolve(detectionRuns)
        : api(`${assetUrl(relatedAsset.id)}/detection-runs`)
    ]);
    return { asset: relatedAsset, processing, run: latestDetectionRun(runs) };
  }));
  let timelineObservations = [];
  let timelineObservationError = null;
  try {
    const observationEnd = asset.durationMs > 0 ? asset.durationMs : Number.MAX_SAFE_INTEGER;
    timelineObservations = await api(`${assetUrl(assetId)}/observations?startTimeMs=0`
      + `&endTimeMs=${observationEnd}&limit=1000`);
  } catch (error) {
    timelineObservationError = error.message || "The observation endpoint is unavailable.";
  }
  const entries = await Promise.all(candidateResult.events.map(async event => ({
    event, review: await api(`${candidateUrl(assetId, event.id)}/review`)
  })));
  const detected = entries.filter(item => item.event.status !== "REJECTED" && item.event.status !== "MANUAL");
  const manual = entries.filter(item => item.event.status === "MANUAL");
  const rejected = entries.filter(item => item.event.status === "REJECTED");
  const pending = detected.filter(item => item.review.reviewStatus === "UNREVIEWED");
  const approved = [...detected, ...manual].filter(item => item.review.reviewStatus === "CONFIRMED");
  const assetClips = allClipItems.filter(item => item.mediaAssetId === assetId);
  const clipByCandidate = new Map(assetClips.map(item => [item.candidateId, item]));
  const groundTruthCandidates = new Set(truth.events.map(item => item.sourceCandidateId).filter(Boolean));
  const summaryCount = candidateResult.events.filter(item => item.status !== "MANUAL").length;
  const teamLine = [asset.homeTeam, asset.awayTeam].filter(Boolean).join(" vs ");
  const tabLink = (key, label, count) => `<a class="workspace-tab ${tab === key ? "active" : ""}" href="#/assets/${encodeURIComponent(assetId)}/${key}" ${tab === key ? 'aria-current="page"' : ""}>
    ${label}<span>${count}</span></a>`;
  const clipsTabContent = assetClips.length
    ? `<div class="clip-library-grid">${assetClips.map(item => renderClipCard(item)).join("")}</div>`
    : `<div class="empty"><strong>No clips generated for this match.</strong><p>Open a detected or approved event to generate its clip.</p></div>`;
  const approvedTabContent = approved.length
    ? `<div class="approved-event-grid">${approved.map(item => renderApprovedEventCard(item, asset, clipByCandidate.get(item.event.id))).join("")}</div>`
    : `<div class="empty"><strong>No approved events yet.</strong><p>Review detected events to build your approved event library.</p></div>`;
  const reviewStation = renderVideoReviewStation(asset, entries, clipByCandidate);
  const reviewTabContent = pending.length
    ? `${reviewStation}<div class="review-workspace">
        <section class="review-queue-panel"><div class="section-heading"><div><div class="eyebrow">Review queue</div><h2>Needs your attention</h2></div>${badge(`${pending.length} TO REVIEW`, "unreviewed")}</div>
          <div class="review-event-list">${pending.map((item, index) => renderReviewQueueEvent(item, assetId, index, pending.length)).join("")}</div>
        </section>
        <section class="timeline-panel"><div class="section-heading"><div><div class="eyebrow">Match context</div><h2>Timeline</h2></div>
          <a href="#/ground-truth/${encodeURIComponent(assetId)}" class="text-link">Ground truth · ${truth.events.length}</a></div>
          ${renderMatchTimeline(asset, entries, context, timelineObservations, timelineObservationError,
            asset.durationMs, truth.events)}
          ${renderMatchContextEditor(context)}
        </section>
      </div>`
    : `${reviewStation}<section class="panel"><div class="empty"><strong>No detected events need review.</strong><p>Newly detected events will appear here. System-rejected candidates stay under Candidates.</p>
      ${linkButton(`#/assets/${encodeURIComponent(assetId)}/approved`, `View approved events · ${approved.length}`, "secondary")}</div></section>
      <section class="panel timeline-panel"><div class="section-heading"><div><div class="eyebrow">Match context</div><h2>Timeline</h2></div>
        <a href="#/ground-truth/${encodeURIComponent(assetId)}" class="text-link">Ground truth · ${truth.events.length}</a></div>
        ${renderMatchTimeline(asset, entries, context, timelineObservations, timelineObservationError,
          asset.durationMs, truth.events)}${renderMatchContextEditor(context)}</section>`;
  const allSystemCandidates = [...detected, ...rejected];
  const candidateTypes = [...new Set(allSystemCandidates.map(item => item.event.eventType))].sort();
  const candidatesTabContent = `<section class="panel"><div class="section-heading"><div><div class="eyebrow">Persisted detector output</div>
      <h2>Detected and rejected candidates</h2><p class="muted">System decisions are separate from human review and ground truth.</p></div>
      <div class="candidate-result-counts">${badge(`${detected.length} DETECTED`, "detected")} ${badge(`${rejected.length} REJECTED`, "rejected")}</div></div>
      <div class="candidate-filters">
        <input type="search" data-candidate-search placeholder="Search transcript or evidence" aria-label="Search candidates">
        <select data-candidate-status aria-label="Filter candidate status"><option value="">All outcomes</option>
          <option value="DETECTED">Detected</option><option value="REJECTED">Rejected</option></select>
        <select data-candidate-type aria-label="Filter candidate event type"><option value="">All event types</option>
          ${candidateTypes.map(type => `<option value="${esc(type)}">${esc(type.replaceAll("_", " "))}</option>`).join("")}</select>
        <select data-candidate-sort aria-label="Sort candidates"><option value="timestamp">Timestamp</option>
          <option value="confidence">Confidence</option><option value="status">Status</option></select>
      </div>
      <div class="candidate-inventory" data-candidate-inventory>
        ${allSystemCandidates.map(item => renderCandidateInventoryRow(item, assetId)).join("")
          || `<div class="empty"><strong>No candidates yet.</strong><p>Start a DetectionRun from the Processing view.</p></div>`}
      </div>
      ${manual.length ? `<details class="secondary-disclosure"><summary>Manually added events · ${manual.length}</summary>
        <div class="candidate-list">${manual.map(item => renderDetectedEventRow(item, assetId,
          groundTruthCandidates.has(item.event.id)).join(""))}</div></details>` : ""}
    </section>
    ${renderDetectionRunsSection(detectionRuns)}`;

  app.innerHTML = `<div class="match-workbench">
    <header class="match-workspace-header">
      <div><a class="back-link" href="#/matches">← Matches</a><div class="eyebrow">${esc(asset.competition || asset.contentType || "Match video")}</div>
        <h1>${esc(teamLine || assetTitle(asset))}</h1>${teamLine ? `<p class="match-card-title">${esc(assetTitle(asset))}</p>` : ""}
        <p class="subtitle">${esc(asset.matchDate || "Date not set")} · ${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}</p>
      </div><div class="button-row match-workspace-actions"><button class="secondary" data-process-match>Processing</button>
        <details class="more-actions"><summary>More</summary><div class="more-actions-menu">
          <a href="#/ground-truth/${encodeURIComponent(assetId)}">Ground Truth reference</a>
          <a href="#/processing/${encodeURIComponent(assetId)}">Processing status</a>
          ${asset.localStoragePath ? `<a href="${assetUrl(assetId)}/source" target="_blank" rel="noreferrer">Open source video</a>` : ""}
        </div></details></div>
    </header>
    ${renderMatchAssetsPanel(assetId, matchAssetDetails)}
    ${candidateResult.failureReason ? errorBox(candidateResult.failureReason) : ""}
    <section class="match-review-summary" aria-label="Match review summary">
      <div><span>System candidates</span><strong>${summaryCount}</strong><small><a href="#/assets/${encodeURIComponent(assetId)}/rejected">${rejected.length} rejected candidates · review</a></small></div>
      <div><span>Detected events</span><strong>${detected.length}</strong><small>System accepted</small></div>
      <div class="${pending.length ? "summary-attention" : ""}"><span>Needs my review</span><strong>${pending.length}</strong><small>Awaiting decision</small></div>
      <div class="summary-approved"><span>Approved by me</span><strong>${approved.length}</strong><small>Confirmed events</small></div>
      <div><span>Clips</span><strong>${assetClips.length}</strong><small>Generated media</small></div>
    </section>
    <nav class="workspace-tabs" aria-label="Match workspace sections">
      ${tabLink("review", "Review", pending.length)}${tabLink("approved", "Approved", approved.length)}
      ${tabLink("candidates", "Candidates", summaryCount)}${tabLink("clips", "Clips", assetClips.length)}
      <a class="workspace-context-link" href="#/ground-truth/${encodeURIComponent(assetId)}">Ground Truth</a>
    </nav>
    <div class="workspace-tab-content">
      ${tab === "review" ? reviewTabContent : ""}
      ${tab === "approved" ? approvedTabContent : ""}
      ${tab === "candidates" ? candidatesTabContent : ""}
      ${tab === "clips" ? clipsTabContent : ""}
    </div><div id="asset-feedback"></div></div>`;
  document.querySelector("[data-process-match]").addEventListener("click", () => navigate(`processing/${assetId}`));
    const uploadNotice = sessionStorage.getItem("clip-ai-upload-notice");
    if (uploadNotice) {
      toast(uploadNotice);
      sessionStorage.removeItem("clip-ai-upload-notice");
    }
    bindMatchAssetsUpload(asset);
    bindDetectionRuns(assetId, detectionRuns);
  bindVideoPlayers(app);
  if (tab === "review") {
    bindReviewWorkstation(assetId, entries, clipByCandidate);
    bindMatchTimelineLayers();
    bindMatchContextEditor(assetId);
  }
  document.querySelectorAll("[data-rejected-filter]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-rejected-filter]").forEach(item => item.classList.toggle("active", item === button));
    document.querySelectorAll("[data-rejected-row]").forEach(row => {
      row.hidden = button.dataset.rejectedFilter === "PENDING" ? row.dataset.humanStatus === "REJECTED"
        : button.dataset.rejectedFilter === "CONFIRMED" && row.dataset.humanStatus !== "REJECTED";
    });
  }));
  if (tab === "candidates") bindCandidateInventory(assetId, allSystemCandidates);
  if (detectionRuns.some(run => !run.legacy && ["PENDING", "RUNNING"].includes(run.status))) {
    scheduleWorkspaceRefresh(assetId, tab);
  }
}

function renderMatchAssetsPanel(currentAssetId, assets) {
  const assetRows = assets.map(({ asset, processing, run }) => `<article class="match-asset-row">
    <div class="match-asset-primary"><div><strong>${esc(matchPartLabel(asset))}</strong>
      ${badge(processing.mediaStatus, processing.mediaStatus === "COMPLETED" ? "detected" : "")}</div>
      <span>${esc(asset.originalFilename || assetTitle(asset))}</span>
      <small>${asset.durationMs == null ? "Duration unavailable" : esc(duration(asset.durationMs))}
        · Added ${esc(asset.createdAt ? new Date(asset.createdAt).toLocaleString() : "time unavailable")}</small></div>
    <div class="match-asset-state"><span>Transcript ${badge(processing.transcriptStatus || "NOT_STARTED")}</span>
      <span>Detection ${badge(run?.status || processing.candidateDetectionStatus || "NOT_STARTED",
        run?.status === "COMPLETED" ? "detected" : "")}</span>
      ${run ? `<small>${run.observationCount} observations · ${run.candidateCount} candidates ·
        ${run.detectedCount} detected · ${run.rejectedCount} rejected</small>` : `<small>No detection run yet</small>`}
      ${renderActiveTaskProgress(processing, run)}</div>
    <div class="button-row">${linkButton(`#/assets/${encodeURIComponent(asset.id)}/review`,
      asset.id === currentAssetId ? "Current asset" : "Open asset", "secondary")}</div>
  </article>`).join("");
  return `<section class="panel match-assets-panel"><div class="section-heading"><div><div class="eyebrow">Match media</div>
      <h2>Video assets · ${assets.length}</h2><p class="muted">Each asset has independent media preparation, transcription and DetectionRuns.</p></div>
      <button data-open-match-upload>＋ Add video asset</button></div>
    <div class="match-assets-list">${assetRows}</div>
    <details class="match-upload-details" id="match-upload-details"><summary>Upload another video asset to this match</summary>
      <section class="upload-panel"><p class="muted">Metadata is prefilled from this match. Processing will not start automatically.</p>
        ${uploadFormMarkup("match-upload-form")}</section></details>
  </section>`;
}

function bindMatchAssetsUpload(asset) {
  const form = document.getElementById("match-upload-form");
  if (!form) return;
  form.elements.namedItem("competition").value = asset.competition || "";
  form.elements.namedItem("homeTeam").value = asset.homeTeam || "";
  form.elements.namedItem("awayTeam").value = asset.awayTeam || "";
  form.elements.namedItem("matchDate").value = asset.matchDate || "";
  form.elements.namedItem("contentType").value = asset.contentType || "SPORTS";
  form.elements.namedItem("source").value = "LOCAL_UPLOAD";
  form.elements.namedItem("matchPart").value = "OTHER";
  bindVideoUploadForm(form);
  document.querySelector("[data-open-match-upload]").addEventListener("click", () => {
    const details = document.getElementById("match-upload-details");
    details.open = true;
    details.scrollIntoView({ behavior: "smooth", block: "start" });
    form.querySelector("[data-upload-file]").focus();
  });
}

function renderCandidateInventoryRow({ event, review }, assetId) {
  const rejected = event.status === "REJECTED";
  const outcome = rejected ? "REJECTED" : "DETECTED";
  const statusLabel = rejected ? "System rejected" : "Detected";
  const search = `${event.eventType} ${event.transcriptContext || ""} ${event.signals.map(signal => signal.evidence).join(" ")} ${event.rejectionReasons.join(" ")}`.toLowerCase();
  const href = rejected
    ? `#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(event.id)}`
    : `#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}`;
  const cluster = event.mergeReason || (event.sourceCandidateIds.length > 1
    ? `${event.sourceCandidateIds.length} source candidates associated` : null);
  return `<article class="candidate-inventory-row" data-candidate-row data-status="${outcome}"
      data-type="${esc(event.eventType)}" data-time="${event.triggerTimestampMs}" data-score="${event.score}"
      data-search="${esc(search)}">
    <div class="candidate-inventory-main"><div><strong>${esc(event.eventType.replaceAll("_", " "))}</strong>
      ${badge(statusLabel, rejected ? "rejected" : "detected")}</div>
      <span class="event-time">${esc(seconds(event.triggerTimestampMs))}</span>
      <p>${esc(event.transcriptContext || "No transcript context stored.")}</p>
      <small>${esc(event.matchPhase && event.matchPhase !== "UNKNOWN"
        ? event.matchPhase.replaceAll("_", " ") : "Match phase unavailable")} ·
        ${esc(eventEvidenceTags(event).join(" · "))}</small>
      ${rejected ? `<small class="candidate-rejection-reasons">${event.rejectionReasons.map(systemRejectionLabel).map(esc).join(" · ") || "No explicit rejection reason stored"}</small>` : ""}
      ${cluster ? `<small>Cluster / association: ${esc(cluster)}</small>` : ""}
    </div>
    <div class="candidate-inventory-metrics"><strong>${Number(event.score).toFixed(3)}</strong><span>Score</span>
      <strong>${Number.isFinite(event.liveEventProbability) ? `${Math.round(event.liveEventProbability * 100)}%` : "Unavailable"}</strong><span>Live probability</span>
      <span>${rejected ? humanReviewLabel(review.reviewStatus) : humanReviewLabel(review.reviewStatus)}</span></div>
    <a class="button secondary" data-candidate-link href="${esc(href)}">${rejected ? "Inspect rejection" : "Open event"}</a>
  </article>`;
}

function bindCandidateInventory(assetId, candidates) {
  const inventory = document.querySelector("[data-candidate-inventory]");
  if (!inventory) return;
  const stateKey = `clip-ai-candidate-filter-${assetId}`;
  let saved = {};
  try { saved = JSON.parse(sessionStorage.getItem(stateKey) || "{}"); } catch {}
  const search = document.querySelector("[data-candidate-search]");
  const status = document.querySelector("[data-candidate-status]");
  const type = document.querySelector("[data-candidate-type]");
  const sort = document.querySelector("[data-candidate-sort]");
  search.value = saved.search || "";
  status.value = saved.status || "";
  type.value = saved.type || "";
  sort.value = saved.sort || "timestamp";
  const filterState = () => ({
    search: search.value,
    status: status.value,
    type: type.value,
    sort: sort.value,
    scroll: window.scrollY
  });
  const draw = () => {
    const selected = candidates.filter(({ event }) => {
      const rejected = event.status === "REJECTED";
      const query = search.value.trim().toLowerCase();
      const text = `${event.eventType} ${event.transcriptContext || ""} ${event.signals.map(signal => signal.evidence).join(" ")} ${event.rejectionReasons.join(" ")}`.toLowerCase();
      return (!status.value || (rejected ? "REJECTED" : "DETECTED") === status.value)
        && (!type.value || event.eventType === type.value)
        && (!query || text.includes(query));
    });
    selected.sort((left, right) => sort.value === "confidence"
      ? right.event.score - left.event.score
      : sort.value === "status"
        ? left.event.status.localeCompare(right.event.status) || left.event.triggerTimestampMs - right.event.triggerTimestampMs
        : left.event.triggerTimestampMs - right.event.triggerTimestampMs);
    inventory.innerHTML = selected.map(item => renderCandidateInventoryRow(item, assetId)).join("")
      || `<div class="empty">No candidates match these filters.</div>`;
    sessionStorage.setItem(stateKey, JSON.stringify(filterState()));
    inventory.querySelectorAll("[data-candidate-link]").forEach(link => link.addEventListener("click", () => {
      sessionStorage.setItem(`clip-ai-candidate-return-${assetId}`, JSON.stringify({
        tab: "candidates", ...filterState()
      }));
    }));
  };
  [search, status, type, sort].forEach(control => control.addEventListener(
    control === search ? "input" : "change", draw));
  draw();
  if (Number.isFinite(saved.scroll) && saved.scroll > 0) setTimeout(() => window.scrollTo(0, saved.scroll), 0);
}

function workspaceMetric(label, value, kind, emphasis = "") {
  return `<div class="workspace-metric ${emphasis}"><span>${esc(label)}</span><strong data-metric="${esc(kind)}">${value}</strong></div>`;
}

function renderDetectionRunsSection(runs) {
  const legacy = runs.find(run => run.legacy);
  const current = runs.find(run => !run.legacy);
  const inspectDefault = current?.runId || legacy?.runId || "";
  const leftDefault = legacy?.runId || current?.runId || "";
  const rightDefault = current?.runId || "";
  const optionsFor = selectedRunId => runs.map(run => `<option value="${esc(run.runId)}" ${run.runId === selectedRunId ? "selected" : ""}>${esc(run.legacy
    ? `Legacy baseline · ${run.candidateCount} candidates`
    : `${run.detectorVersion} · ${run.status} · ${run.candidateCount} candidates`)}</option>`).join("");
  return `<details class="panel detection-runs-panel" data-detection-runs>
    <summary><span>Detection Runs</span><span class="muted">${runs.length} saved · append-only</span></summary>
    <p class="muted">Runs keep their candidates and observations separate. The legacy baseline is read-only and has no fabricated historical run ID.</p>
    ${runs.length ? `<div class="run-summary-list">${runs.map(run => `<div class="run-summary-item">
      <strong>${esc(run.legacy ? "Legacy baseline · historical" : run.detectorVersion)}</strong>
      ${badge(run.legacy ? "LEGACY" : run.status, run.legacy ? "manual" : run.status === "COMPLETED" ? "detected" : "unreviewed")}
      <span>${run.observationCount} observations · ${run.candidateCount} candidates · ${run.detectedCount} detected · ${run.rejectedCount} rejected</span>
      ${run.configurationHash ? `<small>Config ${esc(run.configurationHash.slice(0, 12))}</small>` : ""}
    </div>`).join("")}</div>` : `<div class="empty">No detector runs or legacy candidates are available.</div>`}
    <div class="detection-run-controls">
      <label>Inspect run<select data-run-inspect>${optionsFor(inspectDefault)}</select></label>
      <button data-run-create ${runs.some(run => run.status === "PENDING" || run.status === "RUNNING") ? "disabled" : ""}>Run detection safely</button>
    </div>
    <div class="detection-run-controls">
      <label>Compare left<select data-run-left>${optionsFor(leftDefault)}</select></label>
      <label>Compare right<select data-run-right>${optionsFor(rightDefault)}</select></label>
      <label>Optional timestamp (seconds)<input type="number" min="0" step="0.1" data-run-center placeholder="e.g. 504"></label>
      <button class="secondary" data-run-compare ${runs.length < 2 ? "disabled" : ""}>Compare runs</button>
    </div>
    <div data-run-inspection class="detection-run-results"></div>
    <div data-run-comparison class="detection-run-results"></div>
  </details>`;
}

function bindDetectionRuns(assetId, runs) {
  const section = document.querySelector("[data-detection-runs]");
  if (!section) return;
  const inspectSelect = section.querySelector("[data-run-inspect]");
  const inspection = section.querySelector("[data-run-inspection]");
  const comparison = section.querySelector("[data-run-comparison]");
  const runCandidates = async runId => {
    const [run, events] = await Promise.all([
      api(`${assetUrl(assetId)}/detection-runs/${encodeURIComponent(runId)}`),
      api(`${assetUrl(assetId)}/detection-runs/${encodeURIComponent(runId)}/candidates`)
    ]);
    inspection.innerHTML = `<div class="run-result-heading"><h3>${esc(run.legacy ? "Legacy baseline" : run.detectorVersion)}</h3>
      ${badge(run.status, run.legacy ? "manual" : run.status === "COMPLETED" ? "detected" : "unreviewed")}</div>
      <p class="muted">${run.observationCount} observations · ${run.candidateCount} candidates · ${run.detectedCount} detected · ${run.rejectedCount} rejected
        ${run.failureReason ? ` · ${esc(run.failureReason)}` : ""}</p>
      ${events.length ? `<div class="detection-run-candidate-list">${events.map(event => `<article class="detection-run-candidate">
        <div><strong>${esc(event.eventType.replaceAll("_", " "))}</strong> ${badge(event.status, event.status === "REJECTED" ? "rejected" : "detected")}
          <span>${esc(seconds(event.triggerTimestampMs))} · score ${Number(event.score).toFixed(3)} · ${event.endTimeMs - event.startTimeMs} ms</span></div>
        <a class="text-link" href="#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}">Open candidate</a>
        <small>${event.sourceObservationIds?.length || 0} source observations · run ${esc(event.detectionRunId || run.runId)}</small>
        ${event.rejectionReasons?.length ? `<p class="muted">${event.rejectionReasons.map(esc).join(" · ")}</p>` : ""}
        <details><summary>Evidence and score provenance</summary>
          <ul>${(event.signals || []).map(signal => `<li>${esc(signal.type)} · ${Math.round(signal.confidence * 100)}% · ${esc(signal.evidence)}</li>`).join("")}</ul>
          <pre>${esc(JSON.stringify(event.scoreContributions || [], null, 2))}</pre>
        </details></article>`).join("")}</div>` : `<div class="empty">This run has no candidates yet.</div>`}`;
  };
  const compareRuns = async () => {
    const leftRunId = section.querySelector("[data-run-left]").value;
    const rightRunId = section.querySelector("[data-run-right]").value;
    if (!leftRunId || !rightRunId || leftRunId === rightRunId) {
      comparison.innerHTML = errorBox("Choose two different runs to compare.");
      return;
    }
    const params = new URLSearchParams({ leftRunId, rightRunId });
    const centerSeconds = section.querySelector("[data-run-center]").value;
    if (centerSeconds !== "") {
      const centerTimestampMs = Math.round(Number(centerSeconds) * 1000);
      if (!Number.isFinite(centerTimestampMs) || centerTimestampMs < 0) {
        comparison.innerHTML = errorBox("Timestamp must be a non-negative number of seconds.");
        return;
      }
      params.set("centerTimestampMs", String(centerTimestampMs));
      params.set("windowMs", "60000");
    }
    const result = await api(`${assetUrl(assetId)}/detection-runs/compare?${params}`);
    const matched = result.matchingCandidates.map(item => `<li>${esc(item.left.eventType)} ${esc(seconds(item.left.triggerTimestampMs))}
      ↔ ${esc(item.right.eventType)} ${esc(seconds(item.right.triggerTimestampMs))} · ${esc(item.matchingReason)}
      ${item.scoreChanged ? " · score changed" : ""}${item.eventTypeChanged ? " · type changed" : ""}
      ${item.systemStatusChanged ? " · status changed" : ""}</li>`).join("");
    const observationGroups = result.observations ? `<div class="run-observation-comparison">
      ${renderRunObservationGroups("Left", result.observations.left)}
      ${renderRunObservationGroups("Right", result.observations.right)}</div>` : "";
    comparison.innerHTML = `<div class="run-result-heading"><h3>Run comparison</h3>
      <span>${result.matchingCandidates.length} matched · ${result.onlyInLeft.length} left-only · ${result.onlyInRight.length} right-only</span></div>
      <p class="muted">Left: ${result.leftRun.observationCount} observations, ${result.leftRun.candidateCount} candidates, ${result.leftRun.detectedCount} detected, ${result.leftRun.rejectedCount} rejected.
        Right: ${result.rightRun.observationCount} observations, ${result.rightRun.candidateCount} candidates, ${result.rightRun.detectedCount} detected, ${result.rightRun.rejectedCount} rejected.</p>
      <details><summary>Matched candidates</summary><ul>${matched || "<li>No matches.</li>"}</ul></details>
      <details><summary>Only in left · ${result.onlyInLeft.length}</summary><ul>${result.onlyInLeft.map(event =>
        `<li>${esc(event.eventType)} · ${esc(seconds(event.triggerTimestampMs))} · ${Number(event.score).toFixed(3)}</li>`).join("")}</ul></details>
      <details><summary>Only in right · ${result.onlyInRight.length}</summary><ul>${result.onlyInRight.map(event =>
        `<li>${esc(event.eventType)} · ${esc(seconds(event.triggerTimestampMs))} · ${Number(event.score).toFixed(3)}</li>`).join("")}</ul></details>
      ${observationGroups}`;
  };
  if (inspectSelect.value) {
    runCandidates(inspectSelect.value).catch(error => {
      inspection.innerHTML = errorBox(error);
    });
  }
  inspectSelect.addEventListener("change", () => {
    runCandidates(inspectSelect.value).catch(error => {
      inspection.innerHTML = errorBox(error);
    });
  });
  section.querySelector("[data-run-create]").addEventListener("click", async event => {
    event.currentTarget.disabled = true;
    try {
      const run = await api(`${assetUrl(assetId)}/detection-runs`, { method: "POST" });
      navigate(`processing/${encodeURIComponent(assetId)}?runId=${encodeURIComponent(run.runId)}`);
    } catch (error) {
      inspection.innerHTML = errorBox(error);
      event.currentTarget.disabled = false;
    }
  });
  section.querySelector("[data-run-compare]").addEventListener("click", () => {
    compareRuns().catch(error => {
      comparison.innerHTML = errorBox(error);
    });
  });
}

function renderRunObservationGroups(label, groups) {
  const entries = Object.entries(groups);
  return `<section><h4>${esc(label)} observations</h4>${entries.map(([family, observations]) => `<details>
    <summary>${esc(family)} · ${observations.length}</summary><ul>${observations.slice(0, 30).map(item =>
      `<li>${esc(seconds(item.timestampMs))} · ${esc(item.type)} · ${Math.round(item.confidence * 100)}% · ${esc(item.evidence)}</li>`
    ).join("")}${observations.length > 30 ? `<li>Showing 30 of ${observations.length} observations.</li>` : ""}</ul></details>`
  ).join("") || `<p class="muted">No observations in this time window.</p>`}</section>`;
}

function humanReviewLabel(status) {
  return status === "UNREVIEWED" ? "Needs review"
    : status === "CONFIRMED" ? "Confirmed"
      : status === "REJECTED" ? "Human Rejected" : status;
}

function eventGlyph(eventType) {
  return eventType ? "◉" : "•";
}

function eventEvidenceTags(event) {
  const signals = event.signals || [];
  const tags = [];
  if (signals.some(signal => /AUDIO_SPIKE|AUDIO_SUSTAINED|AUDIO_ENERGY_RISE|CROWD_REACTION_PROXY/.test(signal.type))) tags.push("Audio reaction");
  if (signals.some(signal => /PITCH_RISE|PITCH_VARIANCE/.test(signal.type))) tags.push("Pitch change");
  if (signals.some(signal => /SPEECH_RATE_SPIKE/.test(signal.type))) tags.push("Speech-rate change");
  if (signals.some(signal => /TRANSCRIPT_/.test(signal.type)) || event.transcriptContext) tags.push("Transcript");
  if (signals.some(signal => /ATTACK_BUILDUP|LIVE_EVENT_CONTEXT|SCORE_STATE/.test(signal.type))) tags.push("Live context");
  return tags.length ? tags : ["Evidence details available"];
}

function renderReviewQueueEvent({ event }, assetId, index, total) {
  return `<article class="review-event-card">
    <div class="review-event-time">${esc(seconds(event.triggerTimestampMs))}</div>
    <div class="review-event-body"><div class="review-event-title"><span aria-hidden="true">${eventGlyph(event.eventType)}</span>
      <strong>${esc(event.eventType.replaceAll("_", " "))}</strong>${badge("System detected", "detected")}</div>
      <div class="review-evidence-tags">${eventEvidenceTags(event).map(tag => `<span>${esc(tag)}</span>`).join("")}</div>
      <small class="muted">Detection score ${Math.round(event.score * 100)}%</small>
    </div>
    ${linkButton(`#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}`, index === 0 ? `Review next · 1 of ${total}` : "Review event")}
  </article>`;
}

function renderApprovedEventCard({ event, review }, asset, clip) {
  const clipArea = clip
    ? `<div class="approved-clip-player">${renderVideoPlayer({
        playerId: `approved-clip-${event.id}`, sourceUrl: clip.downloadUrl,
        label: `${event.eventType} clip at ${seconds(event.triggerTimestampMs)}`,
        currentTimeMs: Math.max(0, event.triggerTimestampMs - clip.startTimeMs),
        durationMs: clip.endTimeMs - clip.startTimeMs,
        markers: [{ position: Math.max(0, event.triggerTimestampMs - clip.startTimeMs),
          kind: "event", title: `Event moment · ${seconds(event.triggerTimestampMs)}`, label: "E" }],
        showReviewWindow: false, preload: "none"
      })}
        <div class="meta-row"><span>Clip ${esc(seconds(clip.startTimeMs))} → ${esc(seconds(clip.endTimeMs))}</span>
          <span>${esc(duration(clip.endTimeMs - clip.startTimeMs))}</span></div></div>
       <div class="card-actions">${linkButton(clip.downloadUrl.replace("?inline=true", ""), "Download clip", "secondary")}
         ${linkButton(`#/assets/${encodeURIComponent(asset.id)}/candidates/${encodeURIComponent(event.id)}`, "Event details", "secondary")}</div>`
    : `<div class="approved-no-clip">${badge("Clip not generated", "muted-badge")}<p>Generate a clip from this approved event.</p>
        ${linkButton(`#/assets/${encodeURIComponent(asset.id)}/candidates/${encodeURIComponent(event.id)}`, "Open event")}</div>`;
  return `<article class="approved-event-card">
    <div class="approved-event-heading"><div><div class="eyebrow">Approved event</div>
      <h2><span aria-hidden="true">${eventGlyph(event.eventType)}</span> ${esc(event.eventType.replaceAll("_", " "))}</h2>
      <div class="approved-time">${esc(seconds(event.triggerTimestampMs))}</div>
      <p class="muted">${esc(assetTitle(asset))}</p></div><span class="approved-stamp">✓ Approved</span></div>
    <div class="approved-event-status">${badge("System " + (event.status === "MANUAL" ? "manual" : "detected"), event.status === "MANUAL" ? "manual" : "detected")}
      ${badge("Confirmed by user", "confirmed")} ${clip ? badge("Clip ready", "clip-ready") : ""}</div>
    ${clipArea}
    <details class="provenance-disclosure"><summary>Details and evidence</summary>
      <div class="key-values"><span>Detection score</span><strong>${Math.round(event.score * 100)}%</strong>
        <span>Decision</span><strong>Confirmed by user</strong>
        <span>Ground Truth</span><strong>Tracked separately</strong></div>
      <ul class="reason-list">${eventEvidenceTags(event).map(tag => `<li><span aria-hidden="true">✓</span>${esc(tag)}</li>`).join("")}</ul>
      ${review.note ? `<p><strong>Review note:</strong> ${esc(review.note)}</p>` : ""}
      <p class="muted technical-id">Technical event ID: <code>${esc(event.id)}</code></p>
    </details>
  </article>`;
}

function renderClipCard(item) {
  const reviewLabel = humanReviewLabel(item.reviewStatus);
  const reviewClass = item.reviewStatus === "CONFIRMED" ? "confirmed"
    : item.reviewStatus === "REJECTED" ? "human-rejected" : "unreviewed";
  return `<article class="clip-card">
    <div class="clip-card-heading"><div><div class="eyebrow">${esc(item.mediaAssetTitle)}</div>
      <h2><span aria-hidden="true">${eventGlyph(item.eventType)}</span> ${esc(item.eventType.replaceAll("_", " "))}</h2>
      <p class="approved-time">${esc(seconds(item.startTimeMs))}</p></div>
      <div>${badge("Clip ready", "clip-ready")} ${badge(reviewLabel, reviewClass)}</div></div>
    <div class="clip-player">${renderVideoPlayer({
      playerId: `library-clip-${item.candidateId}`, sourceUrl: item.downloadUrl,
      label: `${item.eventType} clip from ${item.mediaAssetTitle}`,
      durationMs: item.endTimeMs - item.startTimeMs, showReviewWindow: false, preload: "none"
    })}</div>
    <div class="meta-row"><span>${esc(seconds(item.startTimeMs))} → ${esc(seconds(item.endTimeMs))}</span>
      <span>${esc(duration(item.endTimeMs - item.startTimeMs))}</span>
      <span title="${esc(item.detectionRunId || "No DetectionRun recorded")}">Run ${esc(item.detectionRunId?.slice(0, 8) || "legacy")}</span></div>
    <div class="card-actions">${linkButton(item.downloadUrl.replace("?inline=true", ""), "Download", "secondary")}
      ${linkButton(`#/assets/${encodeURIComponent(item.mediaAssetId)}/candidates/${encodeURIComponent(item.candidateId)}`, "Open event context")}</div>
  </article>`;
}

function renderDetectedEventRow({ event, review }, assetId, isGroundTruth) {
  const reviewLabel = humanReviewLabel(review.reviewStatus);
  const detectionLabel = event.status === "MANUAL" ? "Manual event" : "Detected";
  const search = `${event.eventType} ${seconds(event.triggerTimestampMs)} ${detectionLabel}`.toLowerCase();
  return `<article class="event-row" data-detected-row data-review="${esc(review.reviewStatus)}"
      data-type="${esc(event.eventType)}" data-search="${esc(search)}">
    <span class="event-time">${esc(seconds(event.triggerTimestampMs))}</span>
    <span class="event-type-cell"><strong>${esc(event.eventType)}</strong>
      ${event.matchPhase && event.matchPhase !== "UNKNOWN" ? `<small>${esc(event.matchPhase.replaceAll("_", " ").toLowerCase())}</small>` : ""}
      <small>${badge(detectionLabel, event.status === "MANUAL" ? "manual" : "detected")}</small></span>
    <span class="event-score">${Math.round(event.score * 100)}%</span>
    <span>${badge(reviewLabel, review.reviewStatus === "REJECTED" ? "human-rejected" : review.reviewStatus.toLowerCase())}</span>
    <span>${review.clipGenerated ? badge("Clip ready", "clip-ready") : badge("No clip", "muted-badge")}</span>
    <span class="event-flags">${isGroundTruth ? badge("Ground truth", "ground-truth-badge") : ""}</span>
    ${linkButton(`#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(event.id)}`, "Open event", "secondary")}</article>`;
}

function renderRejectedCandidateRow({ event, review }, assetId) {
  const rejectedSignals = event.signals.map(signal => signal.type.replaceAll("_", " ").toLowerCase());
  const summary = rejectedSignals.slice(0, 2).join(" · ") || "No evidence summary";
  const status = review?.reviewStatus || "UNREVIEWED";
  return `<article class="rejected-candidate-row" data-rejected-row data-human-status="${esc(status)}">
    <span class="event-time">${esc(seconds(event.triggerTimestampMs))}</span>
    <span><strong>${esc(event.eventType)}</strong><small>${esc(summary)}</small></span>
    <span>Score ${Math.round(event.score * 100)}%</span>
    <span>${badge("System rejected", "rejected")}<small>${status === "REJECTED" ? `Human confirmed · ${esc(rejectionReasonLabels[review.humanRejectionReason] || "Reason not recorded")}` : "Human: not reviewed"}</small></span>
    ${linkButton(`#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(event.id)}`, status === "REJECTED" ? "View decision" : "Review short clip", "secondary")}</article>`;
}

async function loadRejectedCandidates(assetId) {
  const [asset, candidates] = await Promise.all([
    api(assetUrl(assetId)),
    api(`${assetUrl(assetId)}/candidates?sort=timestamp`)
  ]);
  const entries = await Promise.all(candidates.events
    .filter(event => event.status === "REJECTED")
    .map(async event => ({
      event,
      review: await api(`${candidateUrl(assetId, event.id)}/review`)
    })));
  return { asset, entries };
}

async function renderRejectedReview(assetId, candidateId) {
  const { asset, entries } = await loadRejectedCandidates(assetId);
  const pending = entries.filter(item => item.review.reviewStatus !== "REJECTED")
    .sort((a, b) => a.event.triggerTimestampMs - b.event.triggerTimestampMs);
  const reviewedCount = entries.length - pending.length;
  if (!candidateId) {
    app.innerHTML = `<div class="rejected-review-page">
      <div class="page-head"><div><div class="eyebrow"><a href="#/assets/${encodeURIComponent(assetId)}/candidates">Match candidates</a> / Human review</div>
        <h1>Rejected Candidates</h1><p class="subtitle">${esc(assetTitle(asset))} · Review system rejections using short clips only.</p></div>
        <div class="rejected-review-count"><strong>${pending.length}</strong><span>to review</span></div></div>
      <section class="panel rejected-review-list-panel">
        <div class="section-heading"><div><div class="eyebrow">Human review progress</div><h2>${reviewedCount} / ${entries.length} reviewed</h2></div>
          ${pending.length ? linkButton(`#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(pending[0].event.id)}`, "Start review") : ""}</div>
        <progress max="${Math.max(1, entries.length)}" value="${reviewedCount}" aria-label="Human review progress">${reviewedCount} of ${entries.length}</progress>
        <div class="rejected-review-filters" role="group" aria-label="Filter rejected candidates">
          <button class="filter-chip active" data-rejected-list-filter="ALL">All ${entries.length}</button>
          <button class="filter-chip" data-rejected-list-filter="PENDING">Pending review ${pending.length}</button>
          <button class="filter-chip" data-rejected-list-filter="CONFIRMED">Confirmed rejection ${reviewedCount}</button>
        </div>
        <div class="rejected-review-list">${entries.map(item => {
          const reason = item.review.humanRejectionReason
            ? rejectionReasonLabels[item.review.humanRejectionReason] || item.review.humanRejectionReason : null;
          return `<article class="rejected-review-list-row" data-rejected-list-row data-human-status="${esc(item.review.reviewStatus)}">
            <span class="event-time">${esc(seconds(item.event.triggerTimestampMs))}</span>
            <div><strong>${esc(item.event.eventType)} · score ${item.event.score.toFixed(2)}</strong>
              <span>${esc(item.event.signals.slice(0, 2).map(signal => signal.type.replaceAll("_", " ").toLowerCase()).join(" · ") || "Evidence unavailable")}</span>
              <small>System reason: ${esc(item.event.rejectionReasons.map(systemRejectionLabel).join(", ") || "Not recorded")}</small></div>
            <div class="rejected-human-state">${item.review.reviewStatus === "REJECTED"
              ? `<strong>Confirmed rejection</strong><span>${esc(reason || "Reason not recorded")}</span>`
              : "<span>Human: not reviewed</span>"}</div>
            ${linkButton(`#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(item.event.id)}`,
              item.review.reviewStatus === "REJECTED" ? "View decision" : "Review", "secondary")}</article>`;
        }).join("") || `<div class="empty">This match has no system-rejected candidates.</div>`}</div>
      </section></div>`;
    document.querySelectorAll("[data-rejected-list-filter]").forEach(button => button.addEventListener("click", () => {
      document.querySelectorAll("[data-rejected-list-filter]").forEach(item => item.classList.toggle("active", item === button));
      document.querySelectorAll("[data-rejected-list-row]").forEach(row => {
        row.hidden = button.dataset.rejectedListFilter === "PENDING" ? row.dataset.humanStatus === "REJECTED"
          : button.dataset.rejectedListFilter === "CONFIRMED" && row.dataset.humanStatus !== "REJECTED";
      });
    }));
    return;
  }

  const current = entries.find(item => item.event.id === candidateId);
  if (!current) throw new Error("Rejected candidate not found in this match.");
  const ordered = pending;
  const currentIndex = ordered.findIndex(item => item.event.id === candidateId);
  const pendingIndex = currentIndex >= 0 ? currentIndex : entries
    .sort((a, b) => a.event.triggerTimestampMs - b.event.triggerTimestampMs)
    .findIndex(item => item.event.id === candidateId);
  const previous = currentIndex > 0 ? ordered[currentIndex - 1] : null;
  const next = currentIndex >= 0 ? ordered[currentIndex + 1] : ordered[0];
  const [clipStatus, matchContext] = await Promise.all([
    api(`${candidateUrl(assetId, candidateId)}/rejected-review-clip`),
    api(`${assetUrl(assetId)}/match-context`)
  ]);
  let transcript = null;
  try { transcript = await api(`${assetUrl(assetId)}/transcript`); } catch {}
  const diagnosticStartMs = Math.max(0, current.event.triggerTimestampMs - 30000);
  const diagnosticEndMs = Math.min(current.event.triggerTimestampMs + 30000,
    asset.durationMs ?? Number.MAX_SAFE_INTEGER);
  const [observationsResult] = await Promise.allSettled([
    api(`${assetUrl(assetId)}/observations?startTimeMs=${diagnosticStartMs}&endTimeMs=${diagnosticEndMs}&limit=500`)
  ]);
  const observations = observationsResult.status === "fulfilled" ? observationsResult.value : [];
  const observationError = observationsResult.status === "rejected"
    ? observationsResult.reason?.message || "The observations endpoint is unavailable." : null;
  const marker = matchContext.structureMarkers
    .filter(item => item.timestampMs <= current.event.triggerTimestampMs)
    .sort((a, b) => b.timestampMs - a.timestampMs)[0];
  const queuePosition = currentIndex >= 0 ? currentIndex + 1 : pendingIndex + 1;
  const reasonOptions = Object.entries(rejectionReasonLabels).map(([value, label]) =>
    `<option value="${value}" ${current.review.humanRejectionReason === value ? "selected" : ""}>${esc(label)}</option>`).join("");

  app.dataset.reviewHotkeys = "false";
  app.innerHTML = `<div class="rejected-review-page">
    <div class="page-head rejected-review-header"><div>
      <div class="eyebrow"><a href="#/matches">Matches</a> / <a href="#/assets/${encodeURIComponent(assetId)}/candidates">Candidates</a> / <a href="#/assets/${encodeURIComponent(assetId)}/rejected">Rejected review</a></div>
      <h1>Rejected Candidate <span class="muted">${entries.length ? `${Math.max(1, queuePosition)} / ${entries.length}` : ""}</span></h1>
      <p class="subtitle">${esc(assetTitle(asset))} · ${esc(seconds(current.event.triggerTimestampMs))}</p>
      <div class="review-progress"><span>${reviewedCount} / ${entries.length} reviewed</span><span class="progress-track"><i style="width:${entries.length ? reviewedCount / entries.length * 100 : 0}%"></i></span></div>
    </div><div class="button-row event-navigation">
      ${previous ? linkButton(`#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(previous.event.id)}`, "← Previous", "secondary") : ""}
      ${next && next.event.id !== candidateId ? linkButton(`#/assets/${encodeURIComponent(assetId)}/rejected/${encodeURIComponent(next.event.id)}`, "Next →") : ""}
    </div></div>
    <section class="panel rejected-review-player-panel">
      <div class="player-label"><div><div class="eyebrow">Temporary review clip</div><h2>${esc(seconds(current.event.triggerTimestampMs))}</h2></div>
        ${badge(current.review.reviewStatus === "REJECTED" ? "Human confirmed rejection" : "System rejected · Human not reviewed", current.review.reviewStatus === "REJECTED" ? "human-rejected" : "rejected")}</div>
      <div id="rejected-review-clip-state">${renderRejectedReviewClipState(clipStatus, current.event.triggerTimestampMs)}</div>
      <p id="rejected-review-clip-marker" class="muted" ${clipStatus.status === "READY" ? "" : "hidden"}>Candidate marker: ${esc(seconds(clipStatus.triggerTimestampMs - clipStatus.startTimeMs))} into ${esc(duration(clipStatus.endTimeMs - clipStatus.startTimeMs))} clip</p>
    </section>
    <div class="rejected-review-information">
      <section class="panel"><div class="eyebrow">Rejected by system</div><h2>${esc(current.event.eventType)} · score ${current.event.score.toFixed(2)}</h2>
        <h3>Why considered?</h3>${renderEvidenceSummary(current.event)}
        <h3>Why rejected?</h3><ul class="reason-list rejected-reasons">${current.event.rejectionReasons.map(reason => `<li>${esc(systemRejectionLabel(reason))}</li>`).join("") || "<li>No explicit system reason stored</li>"}</ul>
        ${marker ? `<p><strong>Match context:</strong> ${esc(structureMarkerLabels[marker.type] || marker.type.replaceAll("_", " "))}</p>` : ""}
        <details class="technical-details rejected-technical"><summary>Technical Evidence</summary>
          <h3>Detector signals</h3>${renderSignals(current.event.signals)}
          <h3>Score breakdown</h3>${renderScoreContributions(current.event.scoreContributions)}
          <h3>Observation timeline</h3>${renderCandidateObservationTimeline(
            observations, observationError, diagnosticStartMs, diagnosticEndMs)}
          <h3>Match context</h3>${renderCandidateMatchContext(
            current.event, matchContext, null, entries.map(item => item.event), observations,
            diagnosticStartMs, diagnosticEndMs, observationError)}
          <div class="key-values"><span>Live probability</span><strong>${Number.isFinite(current.event.liveEventProbability) ? `${Math.round(current.event.liveEventProbability * 100)}%` : "Unavailable"}</strong>
            <span>Replay probability</span><strong>${Number.isFinite(current.event.replayProbability) ? `${Math.round(current.event.replayProbability * 100)}%` : "Unavailable"}</strong></div>
          ${current.event.transcriptContext ? `<h3>Candidate transcript context</h3><p>${esc(current.event.transcriptContext)}</p>` : ""}
          ${transcript ? `<h3>Nearby transcript</h3>${renderTranscript(transcript.segments, current.event.triggerTimestampMs)}` : ""}
        </details>
      </section>
      <section class="panel rejected-human-review"><div class="eyebrow">Human review</div><h2>${current.review.reviewStatus === "REJECTED" ? "Confirmed rejection" : "Decision needed"}</h2>
        ${current.review.reviewStatus === "REJECTED" ? `<p><strong>Reason:</strong> ${esc(rejectionReasonLabels[current.review.humanRejectionReason] || current.review.humanRejectionReason || "Not recorded")}</p>
          ${current.review.note ? `<p><strong>Notes:</strong> ${esc(current.review.note)}</p>` : ""}
          <p class="muted">The detector decision and original evidence remain unchanged. The temporary clip is removed after confirmation.</p>` :
          `<label class="field-label">Reason <select id="rejected-human-reason"><option value="">Select a reason</option>${reasonOptions}</select></label>
          <label class="field-label">Notes <textarea id="rejected-human-note" rows="3" maxlength="2000" placeholder="Optional notes">${esc(current.review.note || "")}</textarea></label>
          <div class="review-actions"><button class="secondary" type="button" id="keep-rejected-later">Keep / Review Later</button>
            <button class="danger" type="button" id="confirm-rejected">Confirm Rejection</button></div>
          <p class="muted">Confirming stores the human decision separately and removes only this temporary review clip.</p>`}
      </section>
    </div>
  </div>`;

  bindVideoPlayers(app);
  const reviewVideo = () => document.querySelector("#rejected-review-clip-state video");
  document.getElementById("keep-rejected-later")?.addEventListener("click", () => {
    if (next && next.event.id !== candidateId) navigate(`assets/${assetId}/rejected/${next.event.id}`);
    else navigate(`assets/${assetId}/rejected`);
  });
  const confirmRejection = async () => {
    const reason = document.getElementById("rejected-human-reason")?.value;
    if (!reason) return toast("Select a human rejection reason first.");
    if (!window.confirm("Confirm this system-rejected candidate as a false positive? Its temporary review clip will be deleted.")) return;
    const note = document.getElementById("rejected-human-note")?.value || null;
    try {
      const savedReview = await api(`${candidateUrl(assetId, candidateId)}/review`, {
        method: "PUT",
        body: JSON.stringify({ status: "REJECTED", manualStartTimeMs: null, manualEndTimeMs: null,
          note, humanRejectionReason: reason })
      });
      toast(savedReview.reviewClipCleanupFailed
        ? "Human rejection saved, but temporary clip cleanup failed. The cleanup failure was logged."
        : "Human rejection saved. Temporary review clip cleanup requested.");
      const following = pending.find(item => item.event.id !== candidateId
        && item.event.triggerTimestampMs > current.event.triggerTimestampMs) || pending.find(item => item.event.id !== candidateId);
      if (following) navigate(`assets/${assetId}/rejected/${following.event.id}`);
      else navigate(`assets/${assetId}/rejected`);
    } catch (error) { toast(error.message || "Unable to save rejection."); }
  };
  document.getElementById("confirm-rejected")?.addEventListener("click", confirmRejection);
  if (window.clipRejectedReviewKeyHandler) window.removeEventListener("keydown", window.clipRejectedReviewKeyHandler);
  window.clipRejectedReviewKeyHandler = event => {
    if (["INPUT", "TEXTAREA", "SELECT", "BUTTON", "A"].includes(document.activeElement?.tagName)) return;
    if (event.code === "Space") {
      const video = reviewVideo();
      if (!video) return;
      event.preventDefault();
      if (video.paused) video.play().catch(() => toast("Unable to play review clip."));
      else video.pause();
    } else if (event.key === "ArrowLeft" && previous) {
      event.preventDefault(); navigate(`assets/${assetId}/rejected/${previous.event.id}`);
    } else if (event.key === "ArrowRight" && next && next.event.id !== candidateId) {
      event.preventDefault(); navigate(`assets/${assetId}/rejected/${next.event.id}`);
    } else if (event.key.toLowerCase() === "r" && current.review.reviewStatus !== "REJECTED") {
      event.preventDefault(); confirmRejection();
    }
  };
  window.addEventListener("keydown", window.clipRejectedReviewKeyHandler);

  const clipArea = document.getElementById("rejected-review-clip-state");
  const bindGenerate = () => clipArea.querySelector("[data-generate-review-clip]")?.addEventListener("click", async buttonEvent => {
    const button = buttonEvent.currentTarget;
    button.disabled = true;
    try {
      const updated = await api(`${candidateUrl(assetId, candidateId)}/rejected-review-clip`, { method: "POST" });
      updateClipArea(updated);
      if (updated.status === "PROCESSING") pollReviewClip();
    } catch (error) {
      clipArea.innerHTML = `${errorBox(error)}<button type="button" data-generate-review-clip>Retry generation</button>`;
      bindGenerate();
    }
  });
  const updateClipArea = result => {
    if (!clipArea.isConnected) return;
    clipArea.innerHTML = renderRejectedReviewClipState(result, current.event.triggerTimestampMs);
    bindVideoPlayers(clipArea);
    const marker = document.getElementById("rejected-review-clip-marker");
    if (marker) {
      marker.hidden = !ready;
      if (ready) {
        marker.textContent = `Candidate marker: ${seconds(result.triggerTimestampMs - result.startTimeMs)}`
          + ` into ${duration(result.endTimeMs - result.startTimeMs)} clip`;
      }
    }
    bindGenerate();
    const video = reviewVideo();
    if (video) video.addEventListener("error", () => {
      const status = document.getElementById("rejected-review-clip-status");
      if (status) status.textContent = "Unable to load this review clip.";
    });
  };
  const pollReviewClip = async () => {
    if (!clipArea.isConnected || location.hash !== `#/assets/${assetId}/rejected/${candidateId}`) return;
    try {
      const status = await api(`${candidateUrl(assetId, candidateId)}/rejected-review-clip`);
      updateClipArea(status);
      if (status.status === "PROCESSING") setTimeout(pollReviewClip, 1200);
    } catch (error) {
      clipArea.innerHTML = errorBox(error);
    }
  };
  bindGenerate();
  if (clipStatus.status === "PROCESSING") pollReviewClip();
}

function renderRejectedReviewClipState(status, triggerTimestampMs) {
  if (status.status === "READY" && status.videoUrl) {
    return `${renderVideoPlayer({ playerId: `rejected-review-${triggerTimestampMs}`,
      sourceUrl: status.videoUrl, label: `Temporary review clip around ${seconds(triggerTimestampMs)}`,
      currentTimeMs: Math.max(0, triggerTimestampMs - status.startTimeMs),
      durationMs: status.endTimeMs - status.startTimeMs,
      markers: [{ position: Math.max(0, triggerTimestampMs - status.startTimeMs), kind: "rejected",
        title: `Rejected candidate · ${seconds(triggerTimestampMs)}`, label: "C" }],
      showReviewWindow: false })}
      <div class="rejected-clip-timeline"><span>${esc(seconds(status.startTimeMs))}</span><div><i style="left:${timelinePercent(status.triggerTimestampMs - status.startTimeMs, status.endTimeMs - status.startTimeMs)}%"></i></div><span>${esc(seconds(status.endTimeMs))}</span></div>`;
  }
  if (status.status === "PROCESSING") return `<div id="rejected-review-clip-status" class="empty">Generating review clip…</div>`;
  if (status.status === "FAILED") return `<div class="empty"><p>${esc(status.failureReason || "Review clip generation failed.")}</p><button type="button" data-generate-review-clip>Try again</button></div>`;
  if (status.status === "DELETED") return `<div id="rejected-review-clip-status" class="empty">Temporary review clip removed after confirmed rejection.</div>`;
  return `<div class="empty"><p>Only a short clip around this candidate will be generated.</p><button type="button" data-generate-review-clip>Generate Review Clip</button></div>`;
}

function renderMatchTimeline(asset, entries, context, observations = [], observationError = null,
                             timelineDurationMs = null, groundTruthEvents = [], scoreboardObservations = []) {
  const durationMs = asset.durationMs || timelineDurationMs || Math.max(
    1,
    ...entries.map(item => item.event.endTimeMs),
    ...context.structureMarkers.map(item => item.timestampMs),
    ...context.scoreTransitions.map(item => item.timestampMs),
    ...observations.map(item => item.timestampMs),
    ...scoreboardObservations.map(item => item.timestampMs)
  );
  const active = entries.filter(item => item.event.status !== "REJECTED" && item.event.status !== "MANUAL");
  const manual = entries.filter(item => item.event.status === "MANUAL");
  const rejected = entries.filter(item => item.event.status === "REJECTED");
  const confirmed = active.filter(item => item.review.reviewStatus === "CONFIRMED");
  const humanRejected = entries.filter(item => item.review.reviewStatus === "REJECTED");
  const track = (name, key, markers) => `<div class="timeline-lane" data-timeline-layer="${key}" ${key === "rejected" ? "hidden" : ""}>
    <span class="timeline-lane-name">${esc(name)}</span><div class="timeline-lane-track">
      ${markers.map(({ position, title, href, kind, label, eventId, seekToMs }) => eventId
        ? `<button type="button" class="context-marker ${kind}" style="left:${timelinePercent(position, durationMs)}%"
            data-review-event-marker="${esc(eventId)}" data-timestamp-ms="${position}" title="${esc(title)}"
            aria-label="Seek to ${esc(title)}">${label ? `<span>${esc(label)}</span>` : ""}</button>`
        : seekToMs !== undefined
          ? `<button type="button" class="context-marker ${kind}" style="left:${timelinePercent(position, durationMs)}%"
              data-player-seek-to="${seekToMs}" title="${esc(title)}" aria-label="Seek to ${esc(title)}">
              ${label ? `<span>${esc(label)}</span>` : ""}</button>`
          : href
            ? `<a class="context-marker ${kind}" style="left:${timelinePercent(position, durationMs)}%" href="${esc(href)}" title="${esc(title)}" aria-label="${esc(title)}">${label ? `<span>${esc(label)}</span>` : ""}</a>`
            : `<span class="context-marker ${kind}" style="left:${timelinePercent(position, durationMs)}%" title="${esc(title)}" aria-label="${esc(title)}">${label ? `<span>${esc(label)}</span>` : ""}</span>`
      ).join("")}</div></div>`;
  const eventMarkers = items => items.map(({ event }) => ({
    position: event.triggerTimestampMs,
    title: `${event.eventType} · ${seconds(event.triggerTimestampMs)}`,
    eventId: event.id,
    kind: `event-marker ${event.eventType.toLowerCase()}`,
    label: ""
  }));
  const structureMarkers = context.structureMarkers.map(marker => ({
    position: marker.timestampMs,
    title: `${structureMarkerLabels[marker.type]} · ${seconds(marker.timestampMs)}`,
    seekToMs: marker.timestampMs,
    kind: "structure-marker",
    label: marker.type.replaceAll("_", " ").slice(0, 1)
  }));
  const scoreMarkers = context.scoreTransitions.map(transition => ({
    position: transition.timestampMs,
    title: `${transition.homeScore}-${transition.awayScore} · ${seconds(transition.timestampMs)} · ${transition.source}`,
    seekToMs: transition.timestampMs,
    kind: "score-marker",
    label: `${transition.homeScore}-${transition.awayScore}`
  }));
  const observationMarkers = observations.map(observation => ({
    position: observation.timestampMs,
    title: `${observation.type.replaceAll("_", " ")} · ${Math.round(observation.confidence * 100)}% · ${seconds(observation.timestampMs)} · ${observation.evidence}`,
    seekToMs: observation.timestampMs,
    kind: "observation-marker",
    label: ""
  }));
  const groundTruthMarkers = groundTruthEvents.map(event => ({
    position: event.timestampMs,
    title: `Ground truth · ${event.eventType.replaceAll("_", " ")} · ${seconds(event.timestampMs)}`,
    seekToMs: event.timestampMs,
    kind: "ground-truth-marker",
    label: "GT"
  }));
  const scoreboardMarkers = scoreboardObservations.filter(item =>
    ["SCORE_STATE", "SCORE_TRANSITION", "SCORE_REVERSAL"].includes(item.kind)).map(item => ({
    position: item.timestampMs,
    title: `OCR ${item.kind.replaceAll("_", " ")} · ${item.rawText} · ${Math.round((item.confidence || 0) * 100)}%`,
    seekToMs: item.timestampMs,
    kind: `scoreboard-marker ${item.kind.toLowerCase().replaceAll("_", "-")}`,
    label: item.kind === "SCORE_TRANSITION" ? `${item.previousHomeScore}-${item.previousAwayScore}→${item.homeScore}-${item.awayScore}`
      : item.kind === "SCORE_REVERSAL" ? "↩" : `${item.homeScore}-${item.awayScore}`
  }));
  return `<div class="timeline-legend">
      ${["structure", "observations", "rejected", "detected", "confirmed", "humanrejected", "manual", "score", "groundtruth", "ocr"].map(layer => `<label><input type="checkbox" ${layer === "rejected" || layer === "humanrejected" ? "" : "checked"} data-layer-toggle="${layer}">${esc(layer === "structure" ? "Match structure" : layer === "observations" ? `Detector observations (${observations.length})` : layer === "rejected" ? "System-rejected candidates" : layer === "detected" ? "Detected events" : layer === "confirmed" ? "Approved events" : layer === "humanrejected" ? "Human-rejected events" : layer === "manual" ? "Manual events" : layer === "groundtruth" ? `Ground truth (${groundTruthEvents.length})` : layer === "ocr" ? `OCR score evidence (${scoreboardMarkers.length})` : "Score changes")}</label>`).join("")}
    </div>
    ${observationError ? `<p class="muted" role="status">Detector observations unavailable: ${esc(observationError)}</p>` : ""}
    ${!asset.durationMs && !observationError ? `<p class="muted">Media duration is not persisted; timeline scale uses the latest available candidate, observation, or match-context timestamp.</p>` : ""}
    <div class="match-timeline" role="group" aria-label="Match timeline with separately toggleable observations, structure, candidates, events, and score">
      ${track("Match structure", "structure", structureMarkers)}
      ${track("Detector observations", "observations", observationMarkers)}
      ${track("System-rejected candidates", "rejected", eventMarkers(rejected))}
      ${track("Detected events", "detected", eventMarkers(active))}
      ${track("Approved events", "confirmed", eventMarkers(confirmed))}
      ${track("Human-rejected events", "humanrejected", eventMarkers(humanRejected))}
      ${track("Manual events", "manual", eventMarkers(manual))}
      ${track("Score", "score", [{ position: 0, title: "Starting score 0-0", kind: "score-marker", label: "0-0" }, ...scoreMarkers])}
      ${track("Ground truth · independent", "groundtruth", groundTruthMarkers)}
      ${track("OCR score evidence · independent", "ocr", scoreboardMarkers)}
      <div class="timeline-scale"><span>00:00</span><span>${esc(seconds(durationMs))}</span></div>
    </div>`;
}

function renderMatchContextEditor(context) {
  const markerRows = context.structureMarkers.map(marker => `<div class="context-edit-row">
    <span>${esc(structureMarkerLabels[marker.type])}</span><input type="number" min="0" step="0.001" data-marker-time="${esc(marker.type)}" value="${(marker.timestampMs / 1000).toFixed(3)}">
    <button class="secondary" data-save-marker="${esc(marker.type)}">Save</button><button class="danger" data-delete-marker="${esc(marker.type)}" aria-label="Remove ${esc(structureMarkerLabels[marker.type])}">Remove</button></div>`).join("");
  const scoreRows = context.scoreTransitions.map(item => `<div class="context-edit-row score-edit-row" data-score-row="${esc(item.id)}">
    <select data-score-source aria-label="Score source"><option value="MANUAL" ${item.source === "MANUAL" ? "selected" : ""}>Manual</option><option value="SYSTEM_INFERRED" ${item.source === "SYSTEM_INFERRED" ? "selected" : ""}>System inferred</option></select>
    <input type="number" min="0" step="0.001" data-score-time value="${(item.timestampMs / 1000).toFixed(3)}" aria-label="Score time in seconds">
    <input type="number" min="0" data-home-score value="${item.homeScore}" aria-label="Home score">
    <input type="number" min="0" data-away-score value="${item.awayScore}" aria-label="Away score">
    <input type="number" min="0" max="1" step="0.01" data-score-confidence value="${item.confidence ?? ""}" placeholder="Confidence" aria-label="Confidence">
    <button class="secondary" data-save-score="${esc(item.id)}">Save</button><button class="danger" data-delete-score="${esc(item.id)}">Remove</button>
    <small>${esc(seconds(item.timestampMs))} · ${item.homeScore}-${item.awayScore}</small></div>`).join("");
  return `<details class="timeline-context-editor"><summary>Edit match structure and score</summary>
    <div class="context-editor-grid">
      <section><h3>Match structure</h3><div class="context-edit-list">${markerRows || `<p class="muted">No structure markers added.</p>`}</div>
        <div class="context-add-row"><select id="new-marker-type">${Object.entries(structureMarkerLabels).map(([key, label]) => `<option value="${key}">${esc(label)}</option>`).join("")}</select>
          <input id="new-marker-time" type="number" min="0" step="0.001" placeholder="Time (seconds)"><button id="add-structure-marker">Add marker</button></div></section>
      <section><h3>Score transitions</h3><div class="context-edit-list">${scoreRows || `<p class="muted">No score transitions added.</p>`}</div>
        <form id="new-score-transition" class="context-add-row">
          <input name="timestamp" type="number" min="0" step="0.001" placeholder="Time (seconds)" required>
          <input name="homeScore" type="number" min="0" placeholder="Home" required>
          <input name="awayScore" type="number" min="0" placeholder="Away" required>
          <select name="source"><option value="MANUAL">Manual</option><option value="SYSTEM_INFERRED">System inferred</option></select>
          <input name="confidence" type="number" min="0" max="1" step="0.01" placeholder="Confidence">
          <button type="submit">Add score</button></form></section>
    </div><div id="context-feedback"></div></details>`;
}

function bindMatchTimelineLayers() {
  document.querySelectorAll("[data-layer-toggle]").forEach(toggle => toggle.addEventListener("change", () => {
    document.querySelectorAll(`[data-timeline-layer="${toggle.dataset.layerToggle}"]`)
      .forEach(layer => layer.hidden = !toggle.checked);
  }));
}

function bindMatchContextEditor(assetId) {
  const feedback = document.getElementById("context-feedback");
  const report = error => { if (feedback) feedback.innerHTML = errorBox(error); };
  const ms = value => Math.round(Number(value) * 1000);
  document.getElementById("add-structure-marker").addEventListener("click", async () => {
    const type = document.getElementById("new-marker-type").value;
    const timestampMs = ms(document.getElementById("new-marker-time").value);
    try {
      await api(`${assetUrl(assetId)}/match-context/structure-markers/${type}`, {
        method: "PUT", body: JSON.stringify({ timestampMs })
      });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  });
  document.querySelectorAll("[data-save-marker]").forEach(button => button.addEventListener("click", async () => {
    const type = button.dataset.saveMarker;
    const timestampMs = ms(document.querySelector(`[data-marker-time="${type}"]`).value);
    try {
      await api(`${assetUrl(assetId)}/match-context/structure-markers/${type}`, {
        method: "PUT", body: JSON.stringify({ timestampMs })
      });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  }));
  document.querySelectorAll("[data-delete-marker]").forEach(button => button.addEventListener("click", async () => {
    try {
      await api(`${assetUrl(assetId)}/match-context/structure-markers/${button.dataset.deleteMarker}`, { method: "DELETE" });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  }));
  document.getElementById("new-score-transition").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const confidence = form.get("confidence");
    try {
      await api(`${assetUrl(assetId)}/match-context/score-transitions`, {
        method: "POST",
        body: JSON.stringify({
          timestampMs: ms(form.get("timestamp")),
          homeScore: Number(form.get("homeScore")),
          awayScore: Number(form.get("awayScore")),
          source: form.get("source"),
          confidence: confidence ? Number(confidence) : null
        })
      });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  });
  document.querySelectorAll("[data-save-score]").forEach(button => button.addEventListener("click", async () => {
    const row = button.closest("[data-score-row]");
    const confidenceValue = row.querySelector("[data-score-confidence]").value;
    try {
      await api(`${assetUrl(assetId)}/match-context/score-transitions/${button.dataset.saveScore}`, {
        method: "PUT",
        body: JSON.stringify({
          timestampMs: ms(row.querySelector("[data-score-time]").value),
          homeScore: Number(row.querySelector("[data-home-score]").value),
          awayScore: Number(row.querySelector("[data-away-score]").value),
          source: row.querySelector("[data-score-source]").value,
          confidence: confidenceValue ? Number(confidenceValue) : null
        })
      });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  }));
  document.querySelectorAll("[data-delete-score]").forEach(button => button.addEventListener("click", async () => {
    try {
      await api(`${assetUrl(assetId)}/match-context/score-transitions/${button.dataset.deleteScore}`, { method: "DELETE" });
      await renderAsset(assetId);
    } catch (error) { report(error); }
  }));
}

async function renderCandidate(assetId, candidateId) {
  const [asset, event, review, truth, eventTypes] = await Promise.all([
    api(assetUrl(assetId)),
    api(candidateUrl(assetId, candidateId)),
    api(`${candidateUrl(assetId, candidateId)}/review`),
    api(`${assetUrl(assetId)}/ground-truth`),
    allEventTypes()
  ]);
  const candidateResult = await api(`${assetUrl(assetId)}/candidates?sort=timestamp`);
  const activeEvents = candidateResult.events.filter(item => item.status !== "REJECTED");
  const activeReviews = await Promise.all(activeEvents.map(async item => ({
    event: item, review: await api(`${candidateUrl(assetId, item.id)}/review`)
  })));
  const ordered = activeReviews.sort((a, b) => a.event.triggerTimestampMs - b.event.triggerTimestampMs);
  const currentIndex = ordered.findIndex(item => item.event.id === candidateId);
  const previous = currentIndex > 0 ? ordered[currentIndex - 1].event : null;
  const pendingQueue = ordered.filter(item => item.review.reviewStatus === "UNREVIEWED");
  let returnState = {};
  try {
    returnState = JSON.parse(sessionStorage.getItem(`clip-ai-candidate-return-${assetId}`) || "{}");
  } catch {}
  const workspaceReturnUrl = `#/assets/${encodeURIComponent(assetId)}/${returnState.tab || "review"}`;
  const pendingIndex = pendingQueue.findIndex(item => item.event.id === candidateId);
  const nextPending = pendingQueue.find(item => item.event.id !== candidateId
    && item.review.reviewStatus === "UNREVIEWED"
    && item.event.triggerTimestampMs > event.triggerTimestampMs)
    || pendingQueue.find(item => item.event.id !== candidateId);
  const isSystemRejected = event.status === "REJECTED";
  const isGroundTruth = truth.events.some(item => item.sourceCandidateId === event.id);
  const isApproved = !isSystemRejected && review.reviewStatus === "CONFIRMED";
  let transcript = null;
  try { transcript = await api(`${assetUrl(assetId)}/transcript`); } catch {}
  const diagnosticStartMs = Math.max(0, event.triggerTimestampMs - 30000);
  const diagnosticEndMs = Math.min(event.triggerTimestampMs + 30000,
    asset.durationMs ?? Number.MAX_SAFE_INTEGER);
  const [matchContextResult, observationsResult] = await Promise.allSettled([
    api(`${assetUrl(assetId)}/match-context`),
    api(`${assetUrl(assetId)}/observations?startTimeMs=${diagnosticStartMs}&endTimeMs=${diagnosticEndMs}&limit=500`)
  ]);
  const matchContext = matchContextResult.status === "fulfilled" ? matchContextResult.value : null;
  const observations = observationsResult.status === "fulfilled" ? observationsResult.value : [];
  const observationError = observationsResult.status === "rejected"
    ? observationsResult.reason?.message || "The observations endpoint is unavailable." : null;
  const matchContextError = matchContextResult.status === "rejected"
    ? matchContextResult.reason?.message || "The match-context endpoint is unavailable." : null;
  const replaySignals = event.signals.filter(signal => /REPLAY|RETROSPECTIVE|EVENT_ASSOCIATION/.test(signal.type));
  const maxDuration = review.maximumClipDurationMs;
  const start = review.effectiveStartTimeMs, end = review.effectiveEndTimeMs;
  const rejectionOptions = Object.entries(rejectionReasonLabels).map(([value, label]) =>
    `<option value="${value}" ${review.humanRejectionReason === value ? "selected" : ""}>${esc(label)}</option>`).join("");
  app.dataset.reviewHotkeys = isSystemRejected ? "false" : "true";
  app.innerHTML = `
    <div class="page-head workspace-head"><div><div class="eyebrow"><a href="#/matches">Matches</a> / <a href="#/assets/${encodeURIComponent(assetId)}/review">${esc(assetTitle(asset))}</a> / ${isSystemRejected ? "System-rejected candidate" : isApproved ? "Approved event" : "Review event"}</div>
      <h1>${isSystemRejected ? "Rejected candidate" : `${eventGlyph(event.eventType)} ${esc(event.eventType.replaceAll("_", " "))}`} <span class="muted">· ${esc(seconds(event.triggerTimestampMs))}</span></h1>
      <p class="subtitle">System decision ${badge(event.status === "REJECTED" ? "Rejected" : event.status === "MANUAL" ? "Manual event" : "Detected", event.status === "REJECTED" ? "rejected" : "detected")}
        ${event.matchPhase && event.matchPhase !== "UNKNOWN" ? ` · Phase ${badge(event.matchPhase.replaceAll("_", " "), "phase-badge")}` : ""}
        ${isSystemRejected ? " · Not a detected event" : ` · Human review ${badge(humanReviewLabel(review.reviewStatus), review.reviewStatus.toLowerCase())}`}
        ${isApproved ? ` · ${badge("Approved by you", "confirmed")}` : ""}</p>
      ${!isSystemRejected ? `<div class="review-progress"><span>${pendingIndex >= 0 ? `Reviewing ${pendingIndex + 1} of ${pendingQueue.length}` : `${pendingQueue.length} still need review`}</span>
        <span class="progress-track"><i style="width:${pendingQueue.length ? `${Math.max(0, pendingIndex + 1) / Math.max(1, ordered.length) * 100}%` : "100%"}"></i></span></div>` : ""}</div>
      <div class="button-row event-navigation">
        ${previous ? `<a class="button secondary" data-prev-event href="#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(previous.id)}">← Previous</a>` : ""}
        ${nextPending ? `<a class="button" data-next-event href="#/assets/${encodeURIComponent(assetId)}/candidates/${encodeURIComponent(nextPending.event.id)}">Next to review →</a>` : ""}
        ${linkButton(workspaceReturnUrl, "Match workspace", "secondary")}</div></div>
    <div class="detail-layout">
      <div>
        <section class="panel"><div class="player-label"><h2>Source video</h2><span class="muted">${esc(assetTitle(asset))}</span></div>
          ${asset.localStoragePath ? `<div class="source-review-player">${renderVideoPlayer({ playerId: "source-player", sourceUrl: `${assetUrl(assetId)}/source`,
            label: `${assetTitle(asset)} source video`, currentTimeMs: event.triggerTimestampMs,
            durationMs: asset.durationMs, markers: candidateResult.events.map(item => ({
              eventId: item.id, position: item.triggerTimestampMs,
              kind: item.status === "REJECTED" ? "rejected" : "event",
              title: `${item.status === "REJECTED" ? "System rejected" : "System detected"} · ${item.eventType} · ${seconds(item.triggerTimestampMs)}`
            })), jumpPoints: [{ timeMs: start, label: "Clip start" }, { timeMs: end, label: "Clip end" }]})}
          <div class="timeline"><div class="meta-row"><span>Start ${esc(seconds(start))}</span><span>Trigger ${esc(seconds(event.triggerTimestampMs))}</span><span>End ${esc(seconds(end))}</span></div>
          <div class="timeline-track"><i class="timeline-mark start" style="left:${timelinePercent(start, asset.durationMs)}%"></i><i class="timeline-mark" style="left:${timelinePercent(event.triggerTimestampMs, asset.durationMs)}%"></i><i class="timeline-mark end" style="left:${timelinePercent(end, asset.durationMs)}%"></i></div></div></div>`
          : `<div class="empty">Source video is unavailable for this media asset.</div>`}
        </section>
        ${review.clipGenerated ? card("Retained event clip", `${renderVideoPlayer({
          playerId: `event-clip-${candidateId}`, sourceUrl: `${candidateUrl(assetId, candidateId)}/clip?inline=true`,
          label: `${event.eventType} event clip`, currentTimeMs: Math.max(0, event.triggerTimestampMs - start),
          durationMs: end - start, markers: [{ position: Math.max(0, event.triggerTimestampMs - start),
            kind: "event", title: `Event moment · ${seconds(event.triggerTimestampMs)}`, label: "E" }],
          showReviewWindow: false })}
          <div class="button-row" style="margin-top:11px"><a class="button" href="${candidateUrl(assetId, candidateId)}/clip">Download clip</a><button class="secondary" data-export="true">Regenerate clip</button></div>`)
          : card("Clip", `<p>${badge("Not generated", "muted-badge")}</p><p class="muted">This clip will use the effective boundaries shown here.</p>
            <button data-export="false" ${isSystemRejected ? "disabled" : ""}>Generate clip</button>`)}
        <div id="detail-feedback"></div>
      </div>
      <aside>
        ${card(isSystemRejected ? "Candidate assessment" : "System assessment", isSystemRejected
          ? `<div class="key-values"><span>System decision</span><strong>Rejected candidate</strong><span>Original type</span><strong>${esc(event.eventType)}</strong><span>Candidate score</span><strong>${Math.round(event.score * 100)}%</strong></div>
            <h3>Why considered?</h3>${renderEvidenceSummary(event)}
            <h3>Why rejected?</h3>${event.rejectionReasons.length ? `<ul class="reason-list rejected-reasons">${event.rejectionReasons.map(reason => `<li>${esc(systemRejectionLabel(reason))}</li>`).join("")}</ul>` : `<p class="muted">No explicit rejection reason was stored.</p>`}`
          : `<div class="event-detail-title"><strong>${esc(event.eventType.replaceAll("_", " "))}</strong><span>${esc(seconds(event.triggerTimestampMs))}</span></div>
            <div class="system-assessment-grid">
              <span>Detection score</span><strong>${Math.round(event.score * 100)}%</strong>
              <span>Live probability</span><strong>${Math.round(event.liveEventProbability * 100)}%</strong>
              <span>Replay probability</span><strong>${Math.round(event.replayProbability * 100)}%</strong>
            </div><h3>Why detected?</h3>${eventDetectionSummary(event)}`)}
        ${!isSystemRejected ? card("Clip boundaries", `<div class="key-values"><span>Automatic start</span><strong>${esc(seconds(review.automaticStartTimeMs))}</strong><span>Automatic end</span><strong>${esc(seconds(review.automaticEndTimeMs))}</strong><span>Effective start</span><strong>${esc(seconds(start))}</strong><span>Effective end</span><strong>${esc(seconds(end))}</strong><span>Duration</span><strong>${esc(duration(end - start))}</strong><span>Maximum</span><strong>${esc(duration(maxDuration))}</strong></div>
          <form id="boundary-form"><div class="boundary-form"><label>Start (seconds)<input id="manual-start" type="number" min="0" step="0.001" value="${(start / 1000).toFixed(3)}" required></label><label>End (seconds)<input id="manual-end" type="number" min="0" step="0.001" value="${(end / 1000).toFixed(3)}" required></label></div>
          <p class="muted" id="boundary-preview">Preview: ${esc(seconds(end - start))} total</p><div class="button-row"><button type="submit">Save boundary</button><button type="button" class="secondary" id="reset-boundary">Use automatic</button></div></form>`) : ""}
        ${!isSystemRejected ? card(isApproved ? "Approved event" : "Your decision", `<p>${isApproved ? "This event has been confirmed and belongs to your approved collection." : "Confirm a real event or reject a false detection."}</p>
          <p>Status: ${badge(humanReviewLabel(review.reviewStatus), review.reviewStatus.toLowerCase())}</p>
          <div class="review-actions"><button class="success" data-review-status="CONFIRMED">${isApproved ? "Keep approved" : "Approve event"}</button><button class="danger" data-review-status="REJECTED">Reject event</button><button class="secondary" data-review-status="UNREVIEWED">Return to queue</button></div>
          <label class="field-label">Reason <select id="human-rejection-reason"><option value="">Select a reason</option>${rejectionOptions}</select></label>
          <label class="field-label">Review note<textarea id="review-note" rows="3" maxlength="2000">${esc(review.note || "")}</textarea></label>
          <label class="field-label">Event type override<select id="event-type-override"><option value="">Use detected type</option>${eventTypes.map(type => `<option value="${esc(type.code)}" ${review.eventTypeOverride === type.code ? "selected" : ""}>${esc(type.label)}</option>`).join("")}</select></label>
          <button class="secondary" id="save-review-note" type="button">Save review details</button>`) : ""}
        ${!isSystemRejected && review.reviewStatus === "CONFIRMED"
          ? card("Ground truth reference", isGroundTruth
            ? `<p>${badge("Ground truth", "ground-truth-badge")} This confirmed event is also a manually validated reference.</p>`
            : `<p class="muted">Ground truth is a separate reference layer and does not change detection results.</p><button id="add-ground-truth">Add to Ground Truth</button>`)
          : ""}
        <details class="technical-details"><summary>Technical details</summary>
          ${card("Detection evidence", renderSignals(event.signals))}
          ${card("Score breakdown", renderScoreContributions(event.scoreContributions))}
          ${card("Observation timeline", renderCandidateObservationTimeline(
            observations, observationError, diagnosticStartMs, diagnosticEndMs))}
          ${card("Match context", renderCandidateMatchContext(
            event, matchContext, matchContextError, candidateResult.events, observations,
            diagnosticStartMs, diagnosticEndMs, observationError))}
          ${card("Replay information", replaySignals.length
            ? `<div class="replay-box">${isSystemRejected && event.replayProbability > 0 ? "Replay-only candidate." : "Replay association evidence is present."} It is separate from the event's live trigger.</div>${renderSignals(replaySignals)}`
            : `<p class="muted">No replay-associated signals are present on this candidate.</p>`)}
          ${card("Temporal event sequence", renderTemporalSequence(event.temporalSequence))}
          ${event.mergeReason ? card("Canonical association", `<p>${esc(event.mergeReason)}</p><p class="muted">Source candidates: ${event.sourceCandidateIds.map(esc).join(", ")}</p>`) : ""}
          ${transcript ? card("Transcript context", renderTranscript(transcript.segments, event.triggerTimestampMs)) : ""}
        </details>
      </aside>
    </div>`;
  bindVideoPlayers(app);
  const updateBoundaryPreview = () => {
    const startMs = Math.round(Number(document.getElementById("manual-start").value) * 1000);
    const endMs = Math.round(Number(document.getElementById("manual-end").value) * 1000);
    const preview = document.getElementById("boundary-preview");
    const valid = Number.isFinite(startMs) && Number.isFinite(endMs) && startMs >= 0
      && endMs > startMs && endMs <= (asset.durationMs ?? Number.MAX_SAFE_INTEGER)
      && endMs - startMs <= maxDuration;
    preview.textContent = valid ? `Preview: ${seconds(endMs - startMs)} total`
      : `Preview: invalid range (maximum ${duration(maxDuration)}; media duration ${asset.durationMs == null ? "unknown" : duration(asset.durationMs)})`;
    preview.classList.toggle("error", !valid);
  };
  const currentReviewDetails = () => ({
    note: document.getElementById("review-note").value || null,
    eventTypeOverride: document.getElementById("event-type-override").value || null,
    humanRejectionReason: document.getElementById("human-rejection-reason").value || null
  });
  if (!isSystemRejected) {
    ["manual-start", "manual-end"].forEach(id => document.getElementById(id).addEventListener("input", updateBoundaryPreview));
    document.querySelectorAll("[data-review-status]").forEach(button => button.addEventListener("click", async () => {
    try {
      const status = button.dataset.reviewStatus;
      const details = currentReviewDetails();
      if (status === "REJECTED" && !details.humanRejectionReason) {
        return toast("Select a human rejection reason first.");
      }
      await saveReview(assetId, candidateId, status, review.manualStartTimeMs,
        review.manualEndTimeMs, details.note, details.eventTypeOverride,
        status === "REJECTED" ? details.humanRejectionReason : null);
      if (status === "CONFIRMED" || status === "REJECTED") {
        const next = nextPending?.event;
        toast(status === "CONFIRMED" ? "Event confirmed." : "Event marked Human Rejected.");
        if (next) navigate(`assets/${assetId}/candidates/${next.id}`);
        else navigate(`assets/${assetId}`);
      } else {
        toast("Event returned to Needs review.");
        await renderCandidate(assetId, candidateId);
      }
    } catch (error) { showDetailError(error); }
    }));
    document.getElementById("boundary-form").addEventListener("submit", async formEvent => {
    formEvent.preventDefault();
    const startMs = Math.round(Number(document.getElementById("manual-start").value) * 1000);
    const endMs = Math.round(Number(document.getElementById("manual-end").value) * 1000);
    try {
      const details = currentReviewDetails();
      const saved = await saveReview(assetId, candidateId, review.reviewStatus, startMs, endMs,
        details.note, details.eventTypeOverride, details.humanRejectionReason);
      toast(`Manual boundary saved: ${seconds(saved.effectiveStartTimeMs)}–${seconds(saved.effectiveEndTimeMs)}.`); render();
    } catch (error) { showDetailError(error); }
    });
    document.getElementById("reset-boundary").addEventListener("click", async () => {
    try {
      const details = currentReviewDetails();
      await saveReview(assetId, candidateId, review.reviewStatus, null, null,
        details.note, details.eventTypeOverride, details.humanRejectionReason);
      toast("Automatic boundaries restored."); render();
    } catch (error) { showDetailError(error); }
    });
  }
  document.querySelectorAll("[data-export]").forEach(button => button.addEventListener("click", async () => {
    const regenerate = button.dataset.export === "true";
    button.disabled = true;
    button.textContent = regenerate ? "Regenerating clip…" : "Generating clip…";
    try {
      await api(`${candidateUrl(assetId, candidateId)}/clip${regenerate ? "?regenerate=true" : ""}`, { method: "POST" });
      toast("Clip is ready.");
      await renderCandidate(assetId, candidateId);
    } catch (error) {
      button.textContent = regenerate ? "Retry regeneration" : "Retry clip generation";
      showDetailError(error);
    }
    finally { button.disabled = false; }
  }));
  document.getElementById("save-review-note")?.addEventListener("click", async () => {
    try {
      const details = currentReviewDetails();
      await saveReview(assetId, candidateId, review.reviewStatus, review.manualStartTimeMs,
        review.manualEndTimeMs, details.note, details.eventTypeOverride, details.humanRejectionReason);
      toast("Review details saved."); render();
    } catch (error) { showDetailError(error); }
  });
  document.getElementById("add-ground-truth")?.addEventListener("click", async () => {
    try {
      await api(`${assetUrl(assetId)}/ground-truth/events/from-candidates/${candidateId}`, {
        method: "POST", body: JSON.stringify({ note: review.note || null })
      });
      toast("Confirmed event added to Ground Truth.");
      await renderCandidate(assetId, candidateId);
    } catch (error) { showDetailError(error); }
  });
}

function eventDetectionSummary(event) {
  const signals = event.signals || [];
  const summary = [];
  if (signals.some(signal => /AUDIO_SPIKE|AUDIO_SUSTAINED|AUDIO_ENERGY_RISE|CROWD_REACTION_PROXY/.test(signal.type))) {
    summary.push("Audio or crowd-reaction signal");
  }
  if (signals.some(signal => /PITCH_RISE|PITCH_VARIANCE/.test(signal.type))) summary.push("Pitch rise or variation");
  if (signals.some(signal => /SPEECH_RATE_SPIKE/.test(signal.type))) summary.push("Speech-rate spike");
  if (signals.some(signal => /TRANSCRIPT_/.test(signal.type)) || event.transcriptContext) {
    summary.push("Football transcript evidence");
  }
  if (signals.some(signal => /ATTACK_BUILDUP|LIVE_EVENT_CONTEXT|SCORE_STATE/.test(signal.type))) {
    summary.push("Live-event or attacking context");
  }
  if (!summary.length && signals.length) summary.push("Structured event evidence is available");
  return renderEvidenceSummary(event, summary);
}

function renderEvidenceSummary(event, summary = null) {
  const reasons = summary || [...new Set((event.signals || []).map(signal =>
    signal.type.replaceAll("_", " ").toLowerCase()))];
  return reasons.length
    ? `<ul class="reason-list">${reasons.map(reason => `<li><span aria-hidden="true">✓</span>${esc(reason)}</li>`).join("")}</ul>`
    : `<p class="muted">No structured evidence summary is available. Open Technical details to inspect the candidate record.</p>`;
}

function systemRejectionLabel(reason) {
  return systemRejectionLabels[reason] || reason.replaceAll("_", " ").toLowerCase();
}

function renderTemporalSequence(sequence) {
  if (!sequence || !sequence.events?.length) {
    return `<p class="muted">No typed temporal events could be reconstructed from the available evidence.</p>`;
  }
  const events = sequence.events.map(item => `<li>
    <strong>${esc(seconds(item.timestampMs))} · ${esc(item.type)}</strong>
    <span class="muted">${esc(item.sourceSignalType)} · ${Math.round(item.confidence * 100)}% · ${esc((item.evidenceFamilies || []).join(", "))}</span>
    <div>${esc(item.evidence)}</div>
    ${item.sourceCandidateIds?.length ? `<div class="muted">Source candidates: ${item.sourceCandidateIds.map(esc).join(", ")}</div>` : ""}
    ${item.negativeOutcome ? `<span class="badge rejected">NEGATIVE OUTCOME</span>` : ""}
  </li>`).join("");
  const relationships = sequence.relationships?.length
    ? `<h3>Evidence relationships</h3><ul>${sequence.relationships.map(link => `<li>
        ${esc(sequence.events[link.fromOrdinal]?.type || "Evidence")} → ${esc(sequence.events[link.toOrdinal]?.type || "Evidence")}
        <span class="muted">${esc(link.type)} · ${esc(link.explanation)}</span></li>`).join("")}</ul>` : "";
  const negative = sequence.negativeEvidence?.length
    ? `<div class="replay-box"><strong>Negative outcome evidence</strong><ul>${sequence.negativeEvidence.map(item => `<li>${esc(item)}</li>`).join("")}</ul></div>` : "";
  return `<p class="muted">Assessment: <strong>${esc(sequence.assessment)}</strong>. Ordered atoms retain their source signal, timestamp, evidence family, and confidence.</p>
    <ol class="temporal-events">${events}</ol>${relationships}${negative}`;
}

function timelinePercent(value, total) {
  return total && total > 0 ? Math.max(0, Math.min(100, value / total * 100)).toFixed(3) : "0";
}

function renderTranscript(segments, trigger) {
  const rows = segments.filter(segment => segment.endTimeMs >= trigger - 30000 && segment.startTimeMs <= trigger + 30000);
  return rows.length ? `<div class="transcript-list">${rows.map(segment => `<div class="transcript-line"><time>${esc(seconds(segment.startTimeMs))}</time>${esc(segment.text)}</div>`).join("")}</div>`
    : `<p class="muted">No transcript segments are available in the 30-second event context window.</p>`;
}

function renderSignals(signals) {
  return signals.length ? `<div class="signal-list">${signals.map(signal => `<div class="signal"><strong>${esc(signal.type)}${signal.eventType ? ` · ${esc(signal.eventType)}` : ""}</strong><span>${Math.round(signal.confidence * 100)}% · ${esc(seconds(signal.timestampMs))}</span><div class="evidence">${esc(signal.evidence)}</div></div>`).join("")}</div>`
    : `<p class="muted">No structured signals are available.</p>`;
}

function renderScoreContributions(components = []) {
  if (!Array.isArray(components)) components = [];
  return components.length ? `<div class="score-breakdown">${components.map(component => `
    <div class="score-breakdown-row"><strong>${esc(component.type.replaceAll("_", " "))}</strong>
      <span>${Math.round(component.evidenceConfidence * 100)}% evidence × ${component.weight.toFixed(3)} = ${component.contribution.toFixed(4)}</span>
      <small>${esc(component.explanation)}${component.supportingSignalTypes?.length
        ? ` · ${esc(component.supportingSignalTypes.join(", "))}` : ""}</small></div>`).join("")}
    <p class="muted">Contributions are the stored, explainable score components. Their sum corresponds to the event score, subject to the final clamp.</p></div>`
    : `<p class="muted">No score-component breakdown was stored for this candidate.</p>`;
}

function renderCandidateObservationTimeline(observations, error, startTimeMs, endTimeMs) {
  if (error) {
    return `<p class="muted" role="status">Persisted detector observations could not be loaded: ${esc(error)}</p>`;
  }
  if (!Array.isArray(observations) || !observations.length) {
    return `<p class="muted">No persisted observations are available from ${esc(seconds(startTimeMs))}
      to ${esc(seconds(endTimeMs))}. An empty stored range does not prove that no signal was produced.</p>`;
  }
  const rows = [...observations].sort((a, b) => a.timestampMs - b.timestampMs).map(item => `<li>
    <time>${esc(seconds(item.timestampMs))}</time>
    <div><strong>${esc(item.type.replaceAll("_", " "))}${item.eventType ? ` · ${esc(item.eventType)}` : ""}</strong>
      <span>${Math.round(item.confidence * 100)}%</span><small>${esc(item.evidence)}</small></div>
  </li>`).join("");
  return `<p class="muted">Persisted detector observations · ${esc(seconds(startTimeMs))}–${esc(seconds(endTimeMs))}</p>
    <ol class="diagnostic-observation-list">${rows}</ol>`;
}

function renderCandidateMatchContext(event, context, error, candidates, observations,
                                     startTimeMs, endTimeMs, observationError = null) {
  const structureMarkers = context?.structureMarkers || [];
  const scoreTransitions = context?.scoreTransitions || [];
  const previousMarker = structureMarkers.filter(item => item.timestampMs <= event.triggerTimestampMs)
    .sort((a, b) => b.timestampMs - a.timestampMs)[0];
  const nearbyMarkers = structureMarkers.filter(item =>
    item.timestampMs >= startTimeMs && item.timestampMs <= endTimeMs);
  const visibleMarkers = [...new Map(
    [...(previousMarker ? [previousMarker] : []), ...nearbyMarkers]
      .map(item => [`${item.type}:${item.timestampMs}`, item])
  ).values()].sort((a, b) => a.timestampMs - b.timestampMs);
  const currentScore = scoreTransitions.filter(item => item.timestampMs <= event.triggerTimestampMs)
    .sort((a, b) => b.timestampMs - a.timestampMs)[0];
  const nearbyScores = scoreTransitions.filter(item =>
    item.timestampMs >= startTimeMs && item.timestampMs <= endTimeMs);
  const nearbyEvents = candidates.filter(item => item.id !== event.id
      && item.triggerTimestampMs >= startTimeMs && item.triggerTimestampMs <= endTimeMs)
    .sort((a, b) => a.triggerTimestampMs - b.triggerTimestampMs);
  const replayTypes = new Set(["REPLAY_CONTEXT", "RETROSPECTIVE_CONTEXT", "EVENT_ASSOCIATION"]);
  const replayEvidence = [
    ...observations.filter(item => replayTypes.has(item.type)),
    ...event.signals.filter(item => replayTypes.has(item.type))
  ];
  const markerContent = error
    ? `<p class="muted">Match structure could not be loaded: ${esc(error)}</p>`
    : `<p><strong>Phase:</strong> ${esc(event.matchPhase || "UNKNOWN")}</p>
      ${visibleMarkers.length ? `<ul class="diagnostic-context-list">${visibleMarkers.map(item => `<li>
        <time>${esc(seconds(item.timestampMs))}</time><span>${esc(structureMarkerLabels[item.type] || item.type.replaceAll("_", " "))}</span>
      </li>`).join("")}</ul>` : `<p class="muted">No match structure markers are stored; phase remains ${esc(event.matchPhase || "UNKNOWN")}.</p>`}`;
  const scoreContent = error
    ? `<p class="muted">Score transitions could not be loaded.</p>`
    : `${currentScore ? `<p><strong>Score state at candidate:</strong> ${currentScore.homeScore}–${currentScore.awayScore}
        <span class="muted">(${esc(currentScore.source)}${currentScore.confidence == null ? "" : ` · ${Math.round(currentScore.confidence * 100)}%`})</span></p>`
      : `<p class="muted">No persisted score transition establishes the score at this timestamp.</p>`}
      ${nearbyScores.length ? `<ul class="diagnostic-context-list">${nearbyScores.map(item => `<li>
        <time>${esc(seconds(item.timestampMs))}</time><span>${item.homeScore}–${item.awayScore} · ${esc(item.source)}</span>
      </li>`).join("")}</ul>` : ""}`;
  const eventContent = nearbyEvents.length
    ? `<ul class="diagnostic-context-list">${nearbyEvents.map(item => `<li>
        <time>${esc(seconds(item.triggerTimestampMs))}</time>
        <a href="${candidateUrl(event.mediaAssetId, item.id)}">${esc(item.eventType.replaceAll("_", " "))}</a>
        <span>${esc(item.status)} · ${Math.round(item.score * 100)}%</span>
      </li>`).join("")}</ul>`
    : `<p class="muted">No other persisted candidates occur in this window.</p>`;
  const replayContent = replayEvidence.length
    ? `<ul class="diagnostic-context-list">${replayEvidence.map(item => `<li>
        <time>${esc(seconds(item.timestampMs))}</time><span>${esc(item.type.replaceAll("_", " "))} · ${Math.round(item.confidence * 100)}%</span>
        <small>${esc(item.evidence)}</small>
      </li>`).join("")}</ul>`
    : `<p class="muted">${observationError
      ? "Replay context is incomplete because persisted observations could not be loaded."
      : "No persisted replay or retrospective evidence is recorded for this event or its nearby observations."}</p>`;
  return `<p class="muted">Context window ${esc(seconds(startTimeMs))}–${esc(seconds(endTimeMs))}.</p>
    ${markerContent}<h4>Score state</h4>${scoreContent}
    <h4>Nearby candidates</h4>${eventContent}<h4>Replay context</h4>${replayContent}`;
}

async function saveReview(assetId, candidateId, status, manualStartTimeMs, manualEndTimeMs,
                          note = null, eventTypeOverride = null, humanRejectionReason = null) {
  return api(`${candidateUrl(assetId, candidateId)}/review`, {
    method: "PUT",
    body: JSON.stringify({ status, manualStartTimeMs, manualEndTimeMs, note, eventTypeOverride, humanRejectionReason })
  });
}

function showDetailError(error) {
  const node = document.getElementById("detail-feedback");
  if (node) node.innerHTML = errorBox(error);
}

async function renderClips() {
  const items = await allClips();
  const assets = [...new Map(items.map(item => [item.mediaAssetId, item.mediaAssetTitle])).entries()]
    .sort((left, right) => left[1].localeCompare(right[1]));
  const matches = assets.map(([, title]) => title);
  const types = [...new Set(items.map(item => item.eventType))].sort();
  const runIds = [...new Set(items.map(item => item.detectionRunId).filter(Boolean))].sort();
  let visibleCount = 12;
  app.innerHTML = `<div class="page-head"><div><div class="eyebrow">Artifact library</div><h1>Clips</h1><p class="subtitle">Generated video artifacts, kept separate from candidate and event lifecycle.</p></div><span class="badge">${items.length} CLIPS</span></div>
    <div class="filters"><input id="clip-search" type="search" placeholder="Search match, event, candidate…"><select id="clip-match"><option value="">All media assets</option>${matches.map(item => `<option>${esc(item)}</option>`).join("")}</select><select id="clip-type"><option value="">All event types</option>${types.map(item => `<option>${esc(item)}</option>`).join("")}</select><select id="clip-sort"><option value="time-asc">Event time · ascending</option><option value="time-desc">Event time · descending</option></select></div>
    <div class="filters"><select id="clip-review"><option value="">All review states</option>
      <option value="UNREVIEWED">Needs review</option><option value="CONFIRMED">Approved</option>
      <option value="REJECTED">Human rejected</option></select>
      <select id="clip-run"><option value="">All DetectionRuns</option><option value="legacy">No recorded run</option>
        ${runIds.map(id => `<option value="${esc(id)}" title="${esc(id)}">Run ${esc(id.slice(0, 8))}…</option>`).join("")}</select></div>
    <div id="clip-grid"></div><div class="clip-pager" id="clip-pager"></div>`;
  const update = () => {
    const query = document.getElementById("clip-search").value.toLowerCase().trim();
    const match = document.getElementById("clip-match").value;
    const type = document.getElementById("clip-type").value;
    const review = document.getElementById("clip-review").value;
    const runId = document.getElementById("clip-run").value;
    const direction = document.getElementById("clip-sort").value === "time-desc" ? -1 : 1;
    const filtered = items.filter(item => (!query || `${item.mediaAssetTitle} ${item.eventType} ${item.candidateId}`.toLowerCase().includes(query))
      && (!match || item.mediaAssetTitle === match) && (!type || item.eventType === type)
      && (!review || item.reviewStatus === review)
      && (!runId || (runId === "legacy" ? !item.detectionRunId : item.detectionRunId === runId)))
      .sort((a, b) => (a.startTimeMs - b.startTimeMs) * direction);
    const visible = filtered.slice(0, visibleCount);
    document.getElementById("clip-grid").innerHTML = visible.length
      ? `<div class="clip-library-grid">${visible.map(renderClipCard).join("")}</div>`
      : `<div class="empty"><strong>${items.length ? "No clips match these filters." : "No clips generated."}</strong>
          <p>${items.length ? "Try another match or event type." : "Select an event and generate its clip."}</p>
          ${items.length ? "" : linkButton("#/matches", "Browse matches", "secondary")}</div>`;
    document.getElementById("clip-pager").innerHTML = visibleCount < filtered.length
      ? `<button type="button" class="secondary" id="clip-more">Show more clips (${filtered.length - visibleCount} remaining)</button>`
      : filtered.length ? `<span>${filtered.length} clips</span>` : "";
    document.getElementById("clip-more")?.addEventListener("click", () => {
      visibleCount += 12;
      update();
    });
    bindVideoPlayers(app);
  };
  ["clip-search", "clip-match", "clip-type", "clip-review", "clip-run", "clip-sort"].forEach(id =>
    document.getElementById(id).addEventListener(id === "clip-search" ? "input" : "change", () => {
      visibleCount = 12;
      update();
    }));
  update();
}

document.getElementById("global-search-form").addEventListener("submit", event => {
  event.preventDefault();
  const query = document.getElementById("global-search-input").value.trim();
  if (query) navigate(`search?q=${encodeURIComponent(query)}`);
});

document.addEventListener("keydown", event => {
  if (app.dataset.reviewHotkeys !== "true" || event.altKey || event.ctrlKey || event.metaKey) return;
  const target = event.target;
  if (target instanceof HTMLElement && (target.isContentEditable || /INPUT|TEXTAREA|SELECT|BUTTON/.test(target.tagName))) return;
  const key = event.key.toLowerCase();
  const source = document.getElementById("source-player");
  if (event.code === "Space" && source) {
    event.preventDefault();
    if (source.paused) source.play(); else source.pause();
  } else if ((key === "arrowleft" || key === "arrowright") && source) {
    event.preventDefault();
    source.currentTime = Math.max(0, source.currentTime + (key === "arrowleft" ? -5 : 5));
  } else if (key === "n") {
    document.querySelector("[data-next-event]")?.click();
  } else if (key === "p") {
    document.querySelector("[data-prev-event]")?.click();
  } else if (key === "a") {
    document.querySelector('[data-review-status="CONFIRMED"]')?.click();
  } else if (key === "r") {
    const reject = document.querySelector('[data-review-status="REJECTED"]');
    if (reject && window.confirm("Reject this detected event? This saves a human review decision.")) {
      const reason = document.getElementById("human-rejection-reason");
      if (reason && !reason.value) reason.value = "OTHER";
      reject.click();
    }
  }
});

document.addEventListener("click", event => {
  const anchor = event.target.closest("a[href^='#/']");
  if (anchor && anchor.hash === location.hash) render();
});
window.addEventListener("hashchange", render);
render();
