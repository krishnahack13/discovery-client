const script = document.createElement('script');
script.src = chrome.runtime.getURL('injected.js');
// Must insert at document element level before <head> exists
(document.head || document.documentElement).prepend(script);

// Listen for intercepted prompts from injected.js
window.addEventListener('__shadowai_prompt__', (e) => {
  if (!chrome.runtime?.id) {
    console.warn("[Shadow AI] Extension context invalidated. Please refresh the page.");
    return;
  }
  try {
    chrome.runtime.sendMessage({
      type: 'PROMPT_CAPTURED',
      data: e.detail
    });
  } catch (err) {
    console.error("[Shadow AI] Failed to send message to background:", err);
  }
});
