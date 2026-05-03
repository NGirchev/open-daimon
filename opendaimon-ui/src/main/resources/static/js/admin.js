(function () {
  const API = '/api/v1/admin';
  const PAGE_SIZE = 25;

  const el = {};
  const state = {
    page: 0,
    totalPages: 0,
    filters: { userId: '', scopeKind: '', isActive: '' },
    conversations: [],
    activeConversationId: null,
    activeMessageId: null,
    messages: [],
  };

  document.addEventListener('DOMContentLoaded', init);

  async function init() {
    el.conversationList = document.getElementById('conversationList');
    el.conversationTitle = document.getElementById('conversationTitle');
    el.conversationMeta = document.getElementById('conversationMeta');
    el.messageList = document.getElementById('messageList');
    el.messageDetail = document.getElementById('messageDetail');
    el.filterUser = document.getElementById('filterUser');
    el.filterScope = document.getElementById('filterScope');
    el.filterActive = document.getElementById('filterActive');
    el.prevPageBtn = document.getElementById('prevPageBtn');
    el.nextPageBtn = document.getElementById('nextPageBtn');
    el.pageInfo = document.getElementById('pageInfo');
    el.userEmail = document.getElementById('userEmail');
    el.logoutBtn = document.getElementById('logoutBtn');

    el.filterUser.addEventListener('change', onFilterChange);
    el.filterScope.addEventListener('change', onFilterChange);
    el.filterActive.addEventListener('change', onFilterChange);
    el.prevPageBtn.addEventListener('click', () => changePage(-1));
    el.nextPageBtn.addEventListener('click', () => changePage(1));
    el.logoutBtn.addEventListener('click', onLogout);
    window.addEventListener('hashchange', applyHash);

    const me = await fetchJson(`${API}/me`);
    if (!me) {
      window.location.href = '/login';
      return;
    }
    el.userEmail.textContent = me.email || '';

    await loadUsers();
    await loadConversations();
    applyHash();
  }

  async function loadUsers() {
    const usersPage = await fetchJson(`${API}/users?size=200`);
    if (!usersPage) return;
    const frag = document.createDocumentFragment();
    for (const u of usersPage.content) {
      const opt = document.createElement('option');
      opt.value = u.id;
      opt.textContent = userLabel(u);
      frag.appendChild(opt);
    }
    el.filterUser.appendChild(frag);
  }

  function userLabel(u) {
    const identity = u.emailOrTelegramId || '(no-id)';
    const name = [u.firstName, u.lastName].filter(Boolean).join(' ') || u.username || '';
    return `[${u.userType}] ${identity}${name ? ` — ${name}` : ''}`;
  }

  async function loadConversations() {
    const params = new URLSearchParams();
    params.set('page', state.page);
    params.set('size', PAGE_SIZE);
    if (state.filters.userId) params.set('userId', state.filters.userId);
    if (state.filters.scopeKind) params.set('scopeKind', state.filters.scopeKind);
    if (state.filters.isActive) params.set('isActive', state.filters.isActive);
    const data = await fetchJson(`${API}/conversations?${params.toString()}`);
    if (!data) return;
    state.conversations = data.content;
    state.totalPages = data.totalPages;
    renderConversations();
    renderPager(data);
  }

  function renderConversations() {
    el.conversationList.innerHTML = '';
    if (!state.conversations.length) {
      const li = document.createElement('li');
      li.className = 'admin-hint';
      li.textContent = 'No conversations match the filters.';
      el.conversationList.appendChild(li);
      return;
    }
    const frag = document.createDocumentFragment();
    for (const c of state.conversations) {
      const li = document.createElement('li');
      li.className = 'admin-list-item';
      if (c.id === state.activeConversationId) li.classList.add('active');
      li.dataset.id = c.id;
      li.innerHTML = `
        <div class="title">${escapeHtml(c.title || '(Untitled)')}</div>
        <div class="subline">
          <span class="badge ${c.isActive ? 'active' : 'closed'}">${c.isActive ? 'active' : 'closed'}</span>
          <span class="badge">${escapeHtml(c.scopeKind || '')}</span>
          <span>${escapeHtml(c.user ? userLabel(c.user) : '')}</span>
        </div>
        <div class="subline">
          <span>msgs: ${c.totalMessages ?? 0}</span>
          <span>tokens: ${c.totalTokens ?? 0}</span>
          <span>${formatDate(c.lastActivityAt)}</span>
        </div>
      `;
      li.addEventListener('click', () => openConversation(c.id));
      frag.appendChild(li);
    }
    el.conversationList.appendChild(frag);
  }

  function renderPager(page) {
    el.pageInfo.textContent = `Page ${page.page + 1} / ${Math.max(page.totalPages, 1)} (${page.totalElements} items)`;
    el.prevPageBtn.disabled = page.page <= 0;
    el.nextPageBtn.disabled = page.page + 1 >= page.totalPages;
  }

  function changePage(delta) {
    const next = state.page + delta;
    if (next < 0 || next >= state.totalPages) return;
    state.page = next;
    loadConversations();
  }

  function onFilterChange() {
    state.filters = {
      userId: el.filterUser.value,
      scopeKind: el.filterScope.value,
      isActive: el.filterActive.value,
    };
    state.page = 0;
    loadConversations();
  }

  async function openConversation(id) {
    state.activeConversationId = id;
    state.activeMessageId = null;
    updateHash();
    renderConversations();
    el.messageDetail.innerHTML = '<p class="admin-hint">Click a message to inspect.</p>';
    const [meta, messages] = await Promise.all([
      fetchJson(`${API}/conversations/${id}`),
      fetchJson(`${API}/conversations/${id}/messages`),
    ]);
    if (meta) renderConversationHeader(meta);
    state.messages = messages || [];
    renderMessageList();
  }

  function renderConversationHeader(c) {
    el.conversationTitle.textContent = c.title || '(Untitled)';
    el.conversationMeta.textContent =
      `${c.scopeKind} · ${c.isActive ? 'active' : 'closed'} · ${c.totalMessages ?? 0} msgs · ${c.totalTokens ?? 0} tokens · ${formatDate(c.lastActivityAt)}` +
      (c.user ? ` · owner: ${userLabel(c.user)}` : '');
  }

  function renderMessageList() {
    el.messageList.innerHTML = '';
    if (!state.messages.length) {
      el.messageList.innerHTML = '<p class="admin-hint">No messages.</p>';
      return;
    }
    const frag = document.createDocumentFragment();
    for (const m of state.messages) {
      const div = document.createElement('div');
      div.className = 'admin-message';
      if (m.id === state.activeMessageId) div.classList.add('active');
      div.innerHTML = `
        <span class="role ${m.role}">${m.role}</span>
        <div class="preview">${escapeHtml(m.contentPreview || '')}</div>
        <div class="meta-right">
          #${m.sequenceNumber ?? ''}<br>
          ${m.attachmentCount ? `📎 ${m.attachmentCount}` : ''}
        </div>
      `;
      div.addEventListener('click', () => openMessage(m.id));
      frag.appendChild(div);
    }
    el.messageList.appendChild(frag);
  }

  async function openMessage(id) {
    state.activeMessageId = id;
    updateHash();
    renderMessageList();
    const detail = await fetchJson(`${API}/messages/${id}`);
    if (!detail) return;
    renderMessageDetail(detail);
  }

  function renderMessageDetail(m) {
    const attachments = (m.attachments || []).map((a) => renderAttachment(m.id, a)).join('');
    const metadata = m.metadata ? renderJson(m.metadata) : '';
    const responseData = m.responseData ? renderJson(m.responseData) : '';
    el.messageDetail.innerHTML = `
      <div class="admin-detail-section">
        <h3>Meta</h3>
        <div class="admin-hint">
          #${m.sequenceNumber ?? ''} · role=${m.role} · type=${m.requestType || '—'} · status=${m.status || '—'}
          · ${formatDate(m.createdAt)}
          ${m.serviceName ? ` · service=${escapeHtml(m.serviceName)}` : ''}
          ${m.tokenCount != null ? ` · tokens=${m.tokenCount}` : ''}
          ${m.processingTimeMs != null ? ` · ${m.processingTimeMs}ms` : ''}
        </div>
      </div>
      ${m.errorMessage ? `<div class="admin-detail-section"><h3>Error</h3><pre class="admin-json">${escapeHtml(m.errorMessage)}</pre></div>` : ''}
      <div class="admin-detail-section">
        <h3>Content</h3>
        <div class="admin-content">${escapeHtml(m.content || '')}</div>
      </div>
      ${attachments ? `<div class="admin-detail-section"><h3>Attachments (${m.attachments.length})</h3><div class="admin-attachments">${attachments}</div></div>` : ''}
      ${metadata ? `<div class="admin-detail-section"><h3>metadata</h3>${metadata}</div>` : ''}
      ${responseData ? `<div class="admin-detail-section"><h3>responseData</h3>${responseData}</div>` : ''}
    `;
  }

  function renderAttachment(messageId, a) {
    const url = `${API}/messages/${messageId}/attachment?key=${encodeURIComponent(a.storageKey)}`;
    const isImage = (a.mimeType || '').startsWith('image/');
    return `
      <div class="admin-attachment">
        ${isImage ? `<img src="${url}" alt="${escapeHtml(a.filename || '')}" />` : ''}
        <div class="attachment-meta">
          ${escapeHtml(a.filename || a.storageKey)}<br>
          ${escapeHtml(a.mimeType || '')} ${a.expiresAt ? `· expires ${formatDate(a.expiresAt)}` : ''}
        </div>
        <a class="download" href="${url}" download="${escapeHtml(a.filename || 'attachment')}">Download</a>
      </div>
    `;
  }

  function renderJson(obj) {
    try {
      return `<pre class="admin-json">${escapeHtml(JSON.stringify(obj, null, 2))}</pre>`;
    } catch {
      return '';
    }
  }

  function updateHash() {
    const parts = [];
    if (state.activeConversationId) parts.push(`conv=${state.activeConversationId}`);
    if (state.activeMessageId) parts.push(`msg=${state.activeMessageId}`);
    const hash = parts.join('&');
    if (hash && `#${hash}` !== window.location.hash) {
      history.replaceState(null, '', `#${hash}`);
    }
  }

  async function applyHash() {
    const parsed = parseHash();
    if (parsed.conv && parsed.conv !== state.activeConversationId) {
      await openConversation(parsed.conv);
    }
    if (parsed.msg && parsed.msg !== state.activeMessageId) {
      await openMessage(parsed.msg);
    }
  }

  function parseHash() {
    const h = window.location.hash.replace(/^#/, '');
    const out = {};
    if (!h) return out;
    for (const pair of h.split('&')) {
      const [k, v] = pair.split('=');
      if (!v) continue;
      if (k === 'conv' || k === 'msg') out[k] = Number(v);
    }
    return out;
  }

  async function onLogout() {
    await fetch('/api/v1/ui/logout', { method: 'POST', credentials: 'same-origin' });
    window.location.href = '/login';
  }

  async function fetchJson(url) {
    try {
      const resp = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (resp.status === 401 || resp.status === 403) {
        if (url.endsWith('/me')) return null;
        window.location.href = '/login';
        return null;
      }
      if (!resp.ok) {
        console.error('Admin API error', url, resp.status);
        return null;
      }
      return await resp.json();
    } catch (e) {
      console.error('Admin API fetch failed', url, e);
      return null;
    }
  }

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function formatDate(iso) {
    if (!iso) return '';
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  }
})();
