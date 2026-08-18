import { ApiClient } from './api.js';
import { showToast, formatDate } from './app.js';

let pollTimer = null;

export function initDeliveriesPanel() {
  const refreshBtn = document.getElementById('refresh-deliveries-btn');
  const autoRefreshToggle = document.getElementById('auto-refresh-toggle');

  const filterStatus = document.getElementById('filter-status');
  const filterEndpoint = document.getElementById('filter-endpoint');
  const filterEvent = document.getElementById('filter-event');

  if (refreshBtn) {
    refreshBtn.addEventListener('click', loadDeliveries);
  }

  [filterStatus, filterEndpoint, filterEvent].forEach(input => {
    if (input) {
      input.addEventListener('change', loadDeliveries);
      if (input.tagName === 'INPUT') {
        input.addEventListener('keyup', (e) => {
          if (e.key === 'Enter') loadDeliveries();
        });
      }
    }
  });

  if (autoRefreshToggle) {
    autoRefreshToggle.addEventListener('change', (e) => {
      if (e.target.checked) {
        startPolling();
      } else {
        stopPolling();
      }
    });
  }
}

export function startPolling() {
  stopPolling();
  loadDeliveries();
  pollTimer = setInterval(loadDeliveries, 3000);
}

export function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

export async function loadDeliveries() {
  const tbody = document.getElementById('deliveries-table-body');
  if (!tbody) return;

  const status = document.getElementById('filter-status')?.value || '';
  const endpointId = document.getElementById('filter-endpoint')?.value || '';
  const eventId = document.getElementById('filter-event')?.value || '';

  const res = await ApiClient.getDeliveries({ status, endpointId, eventId, size: 50 });

  if (!res.ok) {
    tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: var(--danger);">Failed to load deliveries: ${res.error}</td></tr>`;
    return;
  }

  const pageData = res.data || {};
  const deliveries = pageData.content || [];

  if (deliveries.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: var(--text-muted);">No delivery records match your current filters for tenant "${ApiClient.getTenantId()}".</td></tr>`;
    return;
  }

  tbody.innerHTML = deliveries.map(d => {
    let statusClass = 'badge-pending';
    if (d.status === 'DELIVERED') statusClass = 'badge-delivered';
    else if (d.status === 'DEAD_LETTERED') statusClass = 'badge-dead_lettered';

    const isRedriveable = d.status === 'DEAD_LETTERED';
    const lastCode = d.lastResponseCode ? `<span class="badge ${d.lastResponseCode < 300 ? 'badge-success' : 'badge-danger'}">${d.lastResponseCode}</span>` : '<span style="color: var(--text-dim);">-</span>';
    const snippet = d.lastResponseSnippet ? String(d.lastResponseSnippet).substring(0, 60) : '-';

    return `
      <tr id="del-row-${d.id}">
        <td style="font-family: var(--font-mono); font-size: 0.8rem; color: #a5b4fc;">${d.id}</td>
        <td style="font-family: var(--font-mono); font-size: 0.8rem;">${d.eventId}</td>
        <td style="font-family: var(--font-mono); font-size: 0.8rem;">${d.endpointId}</td>
        <td><span class="badge ${statusClass}">${d.status}</span></td>
        <td style="font-weight: 600;">${d.attemptCount}</td>
        <td>${lastCode}</td>
        <td style="font-family: var(--font-mono); font-size: 0.775rem; color: var(--text-muted); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(d.lastResponseSnippet || '')}">${escapeHtml(snippet)}</td>
        <td>
          <div style="display: flex; gap: 0.4rem;">
            <button class="btn btn-secondary btn-sm btn-attempts" data-id="${d.id}">Attempts</button>
            ${isRedriveable ? `<button class="btn btn-warning btn-sm btn-redrive" data-id="${d.id}">Redrive</button>` : ''}
          </div>
        </td>
      </tr>
      <tr id="attempts-expand-${d.id}" style="display: none; background: rgba(0,0,0,0.3);">
        <td colspan="8" style="padding: 1rem;">
          <div class="attempts-content" id="attempts-content-${d.id}">Loading attempts history...</div>
        </td>
      </tr>
    `;
  }).join('');

  // Wire action buttons
  document.querySelectorAll('.btn-attempts').forEach(btn => {
    btn.addEventListener('click', (e) => toggleAttemptsHistory(e.target.dataset.id));
  });

  document.querySelectorAll('.btn-redrive').forEach(btn => {
    btn.addEventListener('click', (e) => handleRedrive(e.target.dataset.id));
  });
}

async function toggleAttemptsHistory(id) {
  const expandRow = document.getElementById(`attempts-expand-${id}`);
  const contentDiv = document.getElementById(`attempts-content-${id}`);

  if (!expandRow || !contentDiv) return;

  if (expandRow.style.display !== 'none') {
    expandRow.style.display = 'none';
    return;
  }

  expandRow.style.display = 'table-row';
  contentDiv.innerHTML = `<span style="color: var(--text-muted);">Fetching attempt history for ${id}...</span>`;

  const res = await ApiClient.getDeliveryAttempts(id);
  if (!res.ok) {
    contentDiv.innerHTML = `<span style="color: var(--danger);">Failed to load attempts: ${res.error}</span>`;
    return;
  }

  const attempts = res.data || [];
  if (attempts.length === 0) {
    contentDiv.innerHTML = `<span style="color: var(--text-muted);">No attempts recorded yet for this delivery.</span>`;
    return;
  }

  contentDiv.innerHTML = `
    <h5 style="font-size: 0.85rem; color: var(--text-muted); margin-bottom: 0.5rem;">Attempt History Audit Trail:</h5>
    <table class="data-table" style="background: rgba(15, 23, 42, 0.9);">
      <thead>
        <tr>
          <th>Attempt #</th>
          <th>HTTP Code</th>
          <th>Latency</th>
          <th>Error Details</th>
          <th>Attempted At</th>
        </tr>
      </thead>
      <tbody>
        ${attempts.map(att => `
          <tr>
            <td style="font-weight: 600;">${att.attemptNumber}</td>
            <td>${att.responseCode ? `<span class="badge ${att.responseCode < 300 ? 'badge-success' : 'badge-danger'}">${att.responseCode}</span>` : '-'}</td>
            <td style="font-family: var(--font-mono); font-size: 0.8rem;">${att.latencyMs}ms</td>
            <td style="color: var(--danger); font-family: var(--font-mono); font-size: 0.775rem;">${escapeHtml(att.error || 'None')}</td>
            <td style="font-size: 0.8rem; color: var(--text-muted);">${formatDate(att.createdAt)}</td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

async function handleRedrive(id) {
  showToast(`Initiating redrive for ${id}...`, 'info');
  const res = await ApiClient.redriveDelivery(id);

  if (!res.ok) {
    showToast(`Redrive failed: ${res.error}`, 'error');
    return;
  }

  showToast(`Redrive submitted! Status reset to PENDING with attemptCount = 0.`, 'success');
  loadDeliveries();
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
}
