chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type !== 'PROMPT_CAPTURED') return;
  console.log("[Shadow AI Background] Received event from page:", msg.data.actionType || 'prompt');

  const raw = msg.data || {};
  const prompt = typeof raw.prompt === 'string' ? raw.prompt : null;

  const event = {
    method: raw.method || 'unknown',
    url: raw.url || '',
    prompt,
    model: raw.model || 'unknown',
    timestamp: typeof raw.timestamp === 'number' ? raw.timestamp : Date.now(),
    promptTokenEstimate: typeof raw.promptTokenEstimate === 'number'
      ? raw.promptTokenEstimate
      : (prompt ? Math.ceil(prompt.length / 4) : 0),
    userId: "local-user",
    deviceId: "browser-client",
    browser: getCleanBrowserName(navigator.userAgent),
    sensitivityScore: typeof raw.sensitivityScore === 'number' ? raw.sensitivityScore : 0,
    actionType: raw.actionType || (prompt ? 'prompt' : null),
    fileName: raw.fileName || null,
    fileSize: typeof raw.fileSize === 'number' ? raw.fileSize : null,
    fileType: raw.fileType || null
  };

  // Classify the prompt before shipping
  if (event.prompt) {
    const sensitivity = classifyPrompt(event.prompt);
    event.sensitivityScore = sensitivity;
  }

  // Ship to the local Java backend (BrowserCollector's embedded server)
  fetch('http://localhost:8078/events', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(event)
  }).then(res => {
    console.log("[Shadow AI Background] Successfully posted to agent, status:", res.status);
  }).catch(err => {
    console.error("[Shadow AI Background] Failed to post to local agent:", err);
  });
});

chrome.downloads.onCreated.addListener((item) => {
  console.log("[Shadow AI Background] Download created:", item.finalUrl || item.url);
});

chrome.downloads.onChanged.addListener((delta) => {
  if (!delta?.id) return;
  if (!delta.state || delta.state.current !== 'complete') return;

  chrome.downloads.search({ id: delta.id }, (items) => {
    const item = items && items[0];
    if (!item) return;

    const event = {
      method: 'downloads',
      url: item.finalUrl || item.url || '',
      prompt: null,
      model: 'unknown',
      timestamp: Date.now(),
      promptTokenEstimate: 0,
      userId: "local-user",
      deviceId: "browser-client",
      browser: getCleanBrowserName(navigator.userAgent),
      sensitivityScore: 0,
      actionType: 'download',
      fileName: item.filename ? item.filename.split('\\').pop() : null,
      fileSize: item.fileSize || null,
      fileType: null
    };

    fetch('http://localhost:8078/events', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(event)
    }).catch(() => { });
  });
});

function classifyPrompt(text) {
  const patterns = {
    api_key: /sk-[a-zA-Z0-9]{32,}|AIza[0-9A-Za-z\-_]{35}/,
    private_key: /-----BEGIN .* PRIVATE KEY-----/,
    aws_key: /AKIA[0-9A-Z]{16}/,
    password: /password\s*[:=]\s*\S+/i,
    internal_ip: /192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+/,
    sql_query: /SELECT .+ FROM .+/i,
    source_code: /function\s+\w+\s*\(|class\s+\w+\s*[:{]/
  };
  let score = 0;
  for (const [type, pattern] of Object.entries(patterns)) {
    if (pattern.test(text)) score += 15;
  }
  return Math.min(100, score);
}

function getCleanBrowserName(ua) {
  if (ua.includes("Edg/")) return "Microsoft Edge";
  if (ua.includes("Chrome/") && !ua.includes("Edg/")) return "Google Chrome";
  if (ua.includes("Firefox/")) return "Mozilla Firefox";
  if (ua.includes("Safari/") && !ua.includes("Chrome/")) return "Apple Safari";
  return ua;
}
