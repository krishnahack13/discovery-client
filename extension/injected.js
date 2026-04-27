(function () {
  // ── Intercept fetch ──────────────────────────────────────────
  const _fetch = window.fetch;
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : input?.url;

    if (init?.body && isAIEndpoint(url)) {
      console.log("[Shadow AI DEBUG] Intercepted AI Fetch:", url);
      try {
        if (init.body instanceof FormData) {
          console.log("[Shadow AI DEBUG] Detected FormData upload.");
          const files = [];
          for (const [key, value] of init.body.entries()) {
            if (value instanceof File || value instanceof Blob) {
              files.push({
                name: value.name || "uploaded_file",
                size: value.size,
                type: value.type
              });
            }
          }
          if (files.length) {
            emit({
              method: 'fetch', url, prompt: null, model: 'unknown',
              timestamp: Date.now(), promptTokenEstimate: 0,
              actionType: 'upload', fileName: files[0].name,
              fileSize: files[0].size, fileType: files[0].type
            });
          }
        } else if (init.body instanceof Blob || init.body instanceof ArrayBuffer) {
          console.log("[Shadow AI DEBUG] Detected Binary Blob/Buffer upload.");
          emit({
            method: 'fetch', url, prompt: null, model: 'unknown',
            timestamp: Date.now(), promptTokenEstimate: 0,
            actionType: 'upload', fileName: "binary_upload",
            fileSize: init.body.byteLength || init.body.size,
            fileType: init.body.type || 'application/octet-stream'
          });
        } else {
          const body = JSON.parse(init.body);
          const result = extractShadowAiData(body, url);
          if (result) {
            emit({
              method: 'fetch', url,
              prompt: result.prompt,
              model: body.model || body.modelId || 'unknown',
              timestamp: Date.now(),
              promptTokenEstimate: result.prompt ? Math.ceil(result.prompt.length / 4) : 0,
              actionType: result.actionType,
              fileName: result.fileName,
              fileSize: result.fileSize,
              fileType: result.fileType
            });
          }
        }
      } catch (e) {
        console.error("[Shadow AI] Interception error:", e);
      }
    }
    return _fetch.apply(this, arguments);
  };

  // ── Intercept XMLHttpRequest (older tools use this) ──────────
  const _open = XMLHttpRequest.prototype.open;
  const _send = XMLHttpRequest.prototype.send;

  XMLHttpRequest.prototype.open = function (method, url) {
    this.__url = url;
    return _open.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function (body) {
    if (body && isAIEndpoint(this.__url)) {
      try {
        if (body instanceof FormData) {
          const files = [];
          for (const [key, value] of body.entries()) {
            if (value instanceof File || value instanceof Blob) {
              files.push({
                name: value.name || "uploaded_file",
                size: value.size,
                type: value.type
              });
            }
          }
          if (files.length) {
            emit({
              method: 'xhr', url: this.__url, prompt: null, model: 'unknown',
              timestamp: Date.now(), promptTokenEstimate: 0,
              actionType: 'upload', fileName: files[0].name,
              fileSize: files[0].size, fileType: files[0].type
            });
          }
        } else if (body instanceof Blob || body instanceof ArrayBuffer) {
          emit({
            method: 'xhr', url: this.__url, prompt: null, model: 'unknown',
            timestamp: Date.now(), promptTokenEstimate: 0,
            actionType: 'upload', fileName: "binary_upload",
            fileSize: body.byteLength || body.size,
            fileType: body.type || 'application/octet-stream'
          });
        } else {
          const parsed = JSON.parse(body);
          const prompt = extractPrompt(parsed, this.__url);
          if (prompt !== null) {
            emit({
              method: 'xhr', url: this.__url, prompt,
              timestamp: Date.now(), actionType: 'prompt'
            });
          }
        }
      } catch (e) { }
    }
    return _send.apply(this, arguments);
  };

  // ── Intercept WebSocket (some tools stream via WS) ────────────
  const _WS = window.WebSocket;
  window.WebSocket = function (url, protocols) {
    const ws = new _WS(url, protocols);
    const _wsSend = ws.send.bind(ws);
    ws.send = function (data) {
      if (typeof data === 'string' && isAIEndpoint(url)) {
        try {
          const parsed = JSON.parse(data);
          const prompt = extractPrompt(parsed, url);
          if (prompt) emit({ method: 'websocket', url, prompt, timestamp: Date.now(), actionType: 'prompt' });
        } catch (e) { }
      }
      return _wsSend(data);
    };
    return ws;
  };

  // ── Helpers ──────────────────────────────────────────────────
  const AI_ENDPOINTS = [
    'api.openai.com',
    'chatgpt.com/backend-api',
    'files.openai.com',
    'claude.ai/api',
    'claude.ai/attachments',
    'generativelanguage.googleapis.com',
    'copilot.microsoft.com',
    'api.anthropic.com',
    'api.perplexity.ai',
    'api.mistral.ai',
    'api.cohere.com'
  ];

  function isAIEndpoint(url) {
    if (!url) return false;
    return AI_ENDPOINTS.some(ep => url.includes(ep));
  }

  function extractShadowAiData(body, url) {
    if (!body) return null;
    let data = { actionType: 'prompt', prompt: null, fileName: null, fileSize: null, fileType: null };

    // ChatGPT Web UI / Backend-API
    if (url.includes('chatgpt.com/backend-api') && body.messages) {
      const userMsgs = body.messages.filter(m => m.author?.role === 'user');
      const last = userMsgs[userMsgs.length - 1];

      if (last) {
        // Extract Text
        if (last.content?.parts) {
          data.prompt = last.content.parts.map(p => typeof p === 'string' ? p : JSON.stringify(p)).join('\n');
        }

        // Detect Files in Metadata or Attachments
        const attachments = last.metadata?.attachments || [];
        const fileNames = attachments.map(a => a.name).filter(Boolean);

        if (fileNames.length > 0) {
          data.actionType = 'upload';
          data.fileName = fileNames.join(', ');
          console.log("[Shadow AI DEBUG] Found files in ChatGPT metadata:", data.fileName);
        } else if (JSON.stringify(last).includes('file-')) {
          // Heuristic check for file-IDs if we can't find the explicit name
          data.actionType = 'upload';
          data.fileName = "attached_resource";
        }
        return data;
      }
    }

    // Generic OpenAI-style
    if (body.messages?.length) {
      const last = body.messages.filter(m => m.role === 'user').at(-1);
      if (last?.content) {
        data.prompt = typeof last.content === 'string' ? last.content : JSON.stringify(last.content);
        return data;
      }
    }

    // Gemini
    if (body?.contents?.length) {
      data.prompt = body.contents.at(-1)?.parts?.map(p => p.text || '').join(' ');
      return data;
    }

    if (body.prompt) { data.prompt = body.prompt; return data; }
    if (body.input) { data.prompt = body.input; return data; }

    return null;
  }

  function emit(data) {
    window.dispatchEvent(new CustomEvent('__shadowai_prompt__', { detail: data }));
  }
})();
