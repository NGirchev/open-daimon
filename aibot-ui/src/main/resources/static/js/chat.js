(function() {
  const API = '/api/v1/session';

  const el = {
    sessionList: null,
    newChatBtn: null,
    deleteChatBtn: null,
    chatTitle: null,
    messages: null,
    form: null,
    input: null,
    sendBtn: null,
    userEmail: null,
    logoutBtn: null,
  };

  let sessions = []; // {sessionId, name, createdAt}
  let currentSessionId = null;
  let isSending = false;
  let userEmail = null;
  let renderScheduled = false; // Flag for DOM update batching

  document.addEventListener('DOMContentLoaded', () => {
    el.sessionList = document.getElementById('sessionList');
    el.newChatBtn = document.getElementById('newChatBtn');
    el.deleteChatBtn = document.getElementById('deleteChatBtn');
    el.chatTitle = document.getElementById('chatTitle');
    el.messages = document.getElementById('messages');
    el.form = document.getElementById('messageForm');
    el.input = document.getElementById('messageInput');
    el.sendBtn = document.getElementById('sendBtn');
    el.userEmail = document.getElementById('userEmail');
    el.logoutBtn = document.getElementById('logoutBtn');

    if (el.newChatBtn) {
      el.newChatBtn.addEventListener('click', startNewChat);
    }
    if (el.deleteChatBtn) {
      el.deleteChatBtn.addEventListener('click', onDeleteChat);
    }
    if (el.logoutBtn) {
      el.logoutBtn.addEventListener('click', onLogout);
    }

    if (el.form) {
      el.form.addEventListener('submit', (e) => {
        e.preventDefault();
        onSend();
      });
    }

    if (el.input) {
      el.input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          if (e.shiftKey) {
            return; // allow newline
          }
          e.preventDefault();
          onSend();
        }
      });

      el.input.addEventListener('input', () => {
        autoResizeTextarea(el.input);
        updateSendButtonState();
      });
    }

    window.addEventListener('hashchange', handleHashChange);

    // Init with error handling
    try {
      init();
    } catch (e) {
      console.error('Error during initialization:', e);
      // Ensure form is visible even on error
      if (el.form && el.form.style) {
        el.form.style.display = '';
      }
    }
  });

  async function init() {
    // Check auth and get user email
    try {
      const userInfo = await getJSON('/api/v1/ui/me');
      userEmail = userInfo.email;
      if (el.userEmail) {
        el.userEmail.textContent = userEmail;
      }
    } catch (e) {
      // If not authenticated, redirect to login
      window.location.href = '/login';
      return;
    }
    
    await loadSessions();
    const fromHash = getHashSessionId();
    if (fromHash) {
      selectSession(fromHash, {updateHash: false});
    } else {
      setUIForNewChat();
    }
  }

  function autoResizeTextarea(ta) {
    ta.style.height = 'auto';
    ta.style.height = Math.min(200, ta.scrollHeight) + 'px';
  }

  function getHashSessionId() {
    const h = (window.location.hash || '').replace('#','').trim();
    return h || null;
  }

  function setHashSessionId(id) {
    if (!id) {
      history.replaceState(null, '', window.location.pathname);
    } else {
      if (getHashSessionId() !== id) {
        window.location.hash = id;
      }
    }
  }

  async function loadSessions() {
    try {
      // Email is taken from server session, no need to pass in request
      const list = await getJSON(`${API}`);
      // sort by createdAt desc
      list.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
      sessions = list;
      renderSessions();
      // Keep selection highlight if still present
      if (currentSessionId && !sessions.find(s => s.sessionId === currentSessionId) && !isSending) {
        setUIForNewChat();
      } else {
        highlightSelected(currentSessionId);
      }
    } catch (e) {
      console.error('Error loading sessions:', e);
      // If status 401, redirect immediately
      if (e.status === 401 || e.redirect) {
        const redirectUrl = e.redirect || '/login';
        console.log('Unauthorized - redirecting to:', redirectUrl);
        window.location.href = redirectUrl;
        return;
      }
      if (!handleAuthError(e)) {
        alert('Failed to load chats');
      }
    }
  }

  function renderSessions() {
    el.sessionList.innerHTML = '';
    sessions.forEach(s => {
      const li = document.createElement('li');
      li.dataset.id = s.sessionId;
      li.addEventListener('click', () => selectSession(s.sessionId));

      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = s.name || 'Untitled';

      const date = document.createElement('div');
      date.className = 'date';
      try {
        const d = new Date(s.createdAt);
        date.textContent = d.toLocaleString();
      } catch (_) { date.textContent = ''; }

      li.appendChild(name);
      li.appendChild(date);
      el.sessionList.appendChild(li);
    });
  }

  function highlightSelected(sessionId) {
    const items = el.sessionList.querySelectorAll('li');
    items.forEach(li => {
      li.classList.toggle('selected', !!sessionId && li.dataset.id === sessionId);
    });
  }

  function startNewChat() {
    currentSessionId = null;
    setHashSessionId(null);
    setDeleteButtonDisabled(true);
    setUIForNewChat();
  }

  function setUIForNewChat() {
    if (el.chatTitle) {
      el.chatTitle.textContent = 'New chat';
    }
    setDeleteButtonDisabled(true);
    if (el.messages) {
      el.messages.innerHTML = '';
    }
    if (el.input) {
      el.input.value = '';
      // Ensure input is unlocked
      setSendingState(false);
      el.input.focus();
    }
    updateSendButtonState();
    highlightSelected(null);
  }

  async function selectSession(sessionId, opts = {updateHash: true}) {
    if (!sessionId) return;
    currentSessionId = sessionId;
    if (opts.updateHash) setHashSessionId(sessionId);

    const s = sessions.find(x => x.sessionId === sessionId);
    el.chatTitle.textContent = s ? (s.name || 'Chat') : 'Chat';
    setDeleteButtonDisabled(false);
    highlightSelected(sessionId);

    await loadHistory(sessionId);
  }

  async function loadHistory(sessionId) {
    try {
      // Email is taken from server session, no need to pass in request
      const data = await getJSON(`${API}/${sessionId}/messages`);
      const items = (data && Array.isArray(data.messages)) ? data.messages : [];
      // filter out SYSTEM
      const filtered = items.filter(m => (m.role || '').toUpperCase() !== 'SYSTEM');
      renderMessages(filtered);
      scrollToBottom();
    } catch (e) {
      console.error('Error loading history:', e);
      // If status 401, redirect immediately
      if (e.status === 401 || e.redirect) {
        const redirectUrl = e.redirect || '/login';
        console.log('Unauthorized - redirecting to:', redirectUrl);
        window.location.href = redirectUrl;
        return;
      }
      if (!handleAuthError(e)) {
        alert('Failed to load chat history');
      }
    }
  }

  function renderMessages(messages) {
    el.messages.innerHTML = '';
    messages.forEach(m => appendMessage(m.role, m.content));
  }

  function appendMessage(role, content) {
    const div = document.createElement('div');
    const r = (role || '').toUpperCase();
    div.className = 'message ' + (r === 'USER' ? 'user' : 'assistant');
    if (r === 'ASSISTANT') {
      renderAssistantContent(div, content);
    } else {
      div.textContent = content || '';
    }
    el.messages.appendChild(div);
    return div;
  }

  function appendStreamingMessage(role) {
    const div = document.createElement('div');
    const r = (role || '').toUpperCase();
    div.className = 'message ' + (r === 'USER' ? 'user' : 'assistant');
    if (r === 'ASSISTANT') {
      // Container for incremental content
      const contentContainer = document.createElement('div');
      // Single textNode for accumulating text — use nodeValue for updates
      const textNode = document.createTextNode('');
      contentContainer.appendChild(textNode);
      div.appendChild(contentContainer);
      div._contentContainer = contentContainer;
      div._textNode = textNode; // Keep reference for updates
      div._contentBuffer = '';
      div._renderTimeout = null; // For periodic re-render of think blocks
    } else {
      div.textContent = '';
    }
    el.messages.appendChild(div);
    return div;
  }

  function appendToStreamingMessage(messageDiv, text) {
    // const timestamp = new Date().toISOString();
    // console.log(`[${timestamp}] Message received:`, JSON.stringify(text));
    
    if (!messageDiv || !messageDiv._contentContainer) {
      console.error('appendToStreamingMessage: invalid messageDiv', messageDiv);
      return;
    }
    
    if (!messageDiv._contentBuffer) {
      messageDiv._contentBuffer = '';
    }
    
    // Accumulate text in buffer
    messageDiv._contentBuffer += text;
    
    // Update single textNode instead of creating new ones — avoids line breaks
    // Use nodeValue instead of textContent for more efficient updates
    if (messageDiv._textNode) {
      if (messageDiv._textNode.nodeType === 3) {
        messageDiv._textNode.nodeValue = messageDiv._contentBuffer;
      } else {
        messageDiv._textNode.textContent = messageDiv._contentBuffer;
      }
    } else {
      // Fallback: create textNode if not present
      messageDiv._textNode = document.createTextNode(messageDiv._contentBuffer);
      messageDiv._contentContainer.appendChild(messageDiv._textNode);
    }
    
    // Scroll down on each update
    scrollToBottom();
    
    // Periodically re-render for think blocks; not on every char (too expensive)
    if (!messageDiv._renderTimeout) {
      messageDiv._renderTimeout = setTimeout(() => {
        messageDiv._renderTimeout = null;
        const currentContent = messageDiv._contentBuffer;
        if (currentContent.includes('<think>')) {
          renderAssistantContent(messageDiv._contentContainer, currentContent);
          messageDiv._textNode = null; // renderAssistantContent creates new nodes
        }
      }, 100);
    }
  }

  function renderAssistantContent(container, content) {
    // Clear container before re-render
    container.innerHTML = '';
    
    if (!content) {
      return;
    }

    const regex = /<think>([\s\S]*?)<\/think>/gi;
    let lastIndex = 0;
    let match;

    while ((match = regex.exec(content)) !== null) {
      const textBefore = content.slice(lastIndex, match.index);
      if (textBefore) {
        container.appendChild(document.createTextNode(textBefore));
      }

      const details = document.createElement('details');
      details.className = 'think-spoiler';
      const summary = document.createElement('summary');
      summary.textContent = 'Thoughts';
      const inner = document.createElement('div');
      inner.textContent = match[1].trim();

      details.appendChild(summary);
      details.appendChild(inner);
      container.appendChild(details);

      lastIndex = regex.lastIndex;
    }

    const tail = content.slice(lastIndex);
    if (tail) {
      container.appendChild(document.createTextNode(tail));
    }
  }

  function scrollToBottom() {
    el.messages.scrollTop = el.messages.scrollHeight;
  }

  function updateSendButtonState() {
    if (!el.sendBtn) return;
    const hasText = (el.input.value || '').trim().length > 0;
    el.sendBtn.disabled = isSending || !hasText;
  }

  function setDeleteButtonDisabled(disabled) {
    if (!el.deleteChatBtn) return;
    el.deleteChatBtn.disabled = disabled;
    if (disabled) {
      el.deleteChatBtn.classList.add('is-disabled');
      el.deleteChatBtn.setAttribute('aria-disabled', 'true');
    } else {
      el.deleteChatBtn.classList.remove('is-disabled');
      el.deleteChatBtn.removeAttribute('aria-disabled');
    }
  }

  function setSendingState(sending) {
    isSending = sending;
    if (el.input) {
      el.input.disabled = sending;
    }
    if (el.sendBtn) {
      if (sending) {
        el.sendBtn.disabled = true;
      } else {
        updateSendButtonState();
      }
    }
  }

  async function onSend() {
    const text = (el.input.value || '').trim();
    if (!text) return;

    if (!currentSessionId) {
      // New chat flow: use streaming
      setSendingState(true);
      appendMessage('USER', text);
      const assistantMessageDiv = appendStreamingMessage('ASSISTANT');
      scrollToBottom();
      
      try {
        await streamMessage(`${API}/stream`, { message: text }, assistantMessageDiv, (sessionId) => {
          currentSessionId = sessionId;
          setHashSessionId(currentSessionId);
        });
        el.input.value = '';
        autoResizeTextarea(el.input);
        updateSendButtonState();
        loadSessions();
      } catch (e) {
        console.error('Error sending message (new chat):', e);
        // Remove user and assistant messages on error
        const messages = el.messages.querySelectorAll('.message');
        if (messages.length >= 2) {
          messages[messages.length - 1].remove(); // assistant
          messages[messages.length - 2].remove(); // user
        } else if (messages.length >= 1) {
          messages[messages.length - 1].remove();
        }
        // Unlock input before handling error
        setSendingState(false);
        
        // If status 401, redirect immediately
        if (e.status === 401 || e.redirect) {
          const redirectUrl = e.redirect || '/login';
          console.log('Unauthorized - redirecting to:', redirectUrl);
          window.location.href = redirectUrl;
          return;
        }
        if (handleAuthError(e)) {
          return; // Redirect happened
        }
        alert('Failed to send message: ' + (e.message || 'Unknown error'));
      } finally {
        // Safeguard: unlock input
        if (isSending) {
          setSendingState(false);
        }
      }
    } else {
      // Existing chat: use streaming
      appendMessage('USER', text);
      scrollToBottom();
      const assistantMessageDiv = appendStreamingMessage('ASSISTANT');
      scrollToBottom();
      setSendingState(true);
      
      try {
        await streamMessage(`${API}/${currentSessionId}/stream`, { message: text }, assistantMessageDiv);
        el.input.value = '';
        autoResizeTextarea(el.input);
        updateSendButtonState();
      } catch (e) {
        console.error('Error sending message (existing chat):', e);
        // Remove user and assistant messages on error
        const messages = el.messages.querySelectorAll('.message');
        if (messages.length >= 2) {
          messages[messages.length - 1].remove(); // assistant
          messages[messages.length - 2].remove(); // user
        } else if (messages.length >= 1) {
          messages[messages.length - 1].remove();
        }
        // Unlock input before handling error
        setSendingState(false);
        
        // If status 401, redirect immediately
        if (e.status === 401 || e.redirect) {
          const redirectUrl = e.redirect || '/login';
          console.log('Unauthorized - redirecting to:', redirectUrl);
          window.location.href = redirectUrl;
          return;
        }
        if (handleAuthError(e)) {
          return; // Redirect happened
        }
        alert('Failed to send message: ' + (e.message || 'Unknown error'));
      } finally {
        // Safeguard: unlock input
        if (isSending) {
          setSendingState(false);
        }
      }
    }
  }

  async function streamMessage(url, body, messageDiv, onSessionCreated = null) {
    return new Promise((resolve, reject) => {
      fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream'
        },
        body: JSON.stringify(body),
        credentials: 'include'
      })
      .then(response => {
        if (!response.ok) {
          // Try to handle error
          return response.text().then(text => {
            const error = new Error(`HTTP ${response.status}: ${text}`);
            error.status = response.status;
            if (response.status === 401) {
              error.redirect = '/login';
            }
            throw error;
          });
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let sessionId = null;
        let currentEventType = null;
        let currentData = null;

        function readChunk() {
          reader.read().then(({ done, value }) => {
            if (done) {
              // Process remaining data if any
              if (currentData !== null && currentEventType === 'metadata') {
                try {
                  const json = JSON.parse(currentData);
                  if (json.sessionId) {
                    sessionId = json.sessionId;
                    if (onSessionCreated) {
                      onSessionCreated(sessionId);
                    }
                  }
                } catch (e) {
                  console.warn('Failed to parse metadata:', e);
                }
              }
              setSendingState(false);
              resolve(sessionId);
              return;
            }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || ''; // Keep incomplete line in buffer

            for (const line of lines) {
              if (line === '') {
                if (currentData !== null && currentData !== undefined && currentData !== '') {
                  if (currentEventType === 'metadata') {
                    // Parse metadata (sessionId)
                    try {
                      const json = JSON.parse(currentData);
                      if (json.sessionId) {
                        sessionId = json.sessionId;
                        if (onSessionCreated) {
                          onSessionCreated(sessionId);
                        }
                      }
                    } catch (e) {
                      console.warn('Failed to parse metadata:', e);
                    }
                  } else {
                    // Message content (no event type or empty event type)
                    if (currentData) {
                      appendToStreamingMessage(messageDiv, currentData);
                    }
                  }
                  currentData = null;
                  currentEventType = null;
                }
                continue;
              }
              
              const trimmedForCheck = line.trim();
              if (trimmedForCheck.startsWith('event:')) {
                const eventPart = trimmedForCheck.substring(6);
                currentEventType = eventPart.trim();
              } else if (trimmedForCheck.startsWith('data:')) {
                const dataPart = line.substring(5);
                const data = dataPart;
                if (data === '') {
                  continue;
                }
                if (currentData === null || currentData === undefined) {
                  currentData = data;
                } else {
                  currentData += data;
                }
              }
            }

            readChunk();
          }).catch(err => {
            setSendingState(false);
            reject(err);
          });
        }

        readChunk();
      })
      .catch(err => {
        setSendingState(false);
        reject(err);
      });
    });
  }

  async function onDeleteChat() {
    if (!currentSessionId) return;
    const ok = window.confirm('Delete current chat?');
    if (!ok) return;
    try {
      // Email is taken from server session, no need to pass in request
      await del(`${API}/${currentSessionId}`);
      currentSessionId = null;
      setHashSessionId(null);
      await loadSessions();
      setUIForNewChat();
    } catch (e) {
      console.error('Error deleting chat:', e);
      // If status 401, redirect immediately
      if (e.status === 401 || e.redirect) {
        const redirectUrl = e.redirect || '/login';
        console.log('Unauthorized - redirecting to:', redirectUrl);
        window.location.href = redirectUrl;
        return;
      }
      if (!handleAuthError(e)) {
        alert('Failed to delete chat');
      }
    }
  }

  function handleHashChange() {
    const id = getHashSessionId();
    if (id && id !== currentSessionId) {
      selectSession(id, {updateHash: false});
    } else if (!id && currentSessionId) {
      startNewChat();
    }
  }

  async function onLogout() {
    try {
      await postJSON('/api/v1/ui/logout', {});
      window.location.href = '/login';
    } catch (e) {
      console.error(e);
      // Redirect to login in any case
      window.location.href = '/login';
    }
  }

  function handleAuthError(e) {
    // Check status 401 or redirect field
    const status = e.status;
    const hasRedirect = e.redirect;
    const message = e.message || '';
    const isUnauthorized = status === 401 || 
                          message.includes('401') || 
                          message.includes('Unauthorized') ||
                          hasRedirect;
    
    if (isUnauthorized) {
      const redirectUrl = hasRedirect ? e.redirect : '/login';
      console.log('Unauthorized error detected. Status:', status, 'Redirect:', redirectUrl, 'Error:', e);
      window.location.href = redirectUrl;
      return true;
    }
    return false;
  }

  async function getJSON(url) {
    const resp = await fetch(url, { 
      headers: { 'Accept': 'application/json' },
      credentials: 'include' // Required to send session cookies
    });
    if (!resp.ok) {
      await ensureOk(resp.clone()); // clone so body is still readable
    }
    return resp.json();
  }

  async function postJSON(url, body) {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify(body || {}),
      credentials: 'include' // Required to send session cookies
    });
    if (!resp.ok) {
      await ensureOk(resp.clone());
    }
    return resp.json();
  }

  async function del(url) {
    const resp = await fetch(url, { 
      method: 'DELETE',
      headers: { 'Accept': 'application/json' },
      credentials: 'include' // Required to send session cookies
    });
    if (!resp.ok) {
      await ensureOk(resp.clone());
    }
    return true;
  }

  async function ensureOk(resp) {
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`;
      let redirectUrl = null;
      const status = resp.status;
      try {
        const contentType = resp.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
          const err = await resp.json();
          if (err && err.message) msg += `: ${err.message}`;
          if (err && err.redirect) redirectUrl = err.redirect;
        } else {
          const text = await resp.text();
          if (text) msg += `: ${text}`;
        }
      } catch(e) {
        console.warn('Failed to parse error response:', e);
      }
      const error = new Error(msg);
      error.status = status;
      if (redirectUrl) {
        error.redirect = redirectUrl;
      }
      if (status === 401) {
        error.redirect = redirectUrl || '/login';
      }
      throw error;
    }
  }
})();
