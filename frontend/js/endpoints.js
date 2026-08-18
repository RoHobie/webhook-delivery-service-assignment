import { ApiClient } from './api.js';
import { showToast, formatDate } from './app.js';

export function initEndpointsPanel() {
  const form = document.getElementById('create-endpoint-form');
  const refreshBtn = document.getElementById('refresh-endpoints-btn');

  if (form) {
    form.addEventListener('submit', handleCreateEndpoint);
  }

  if (refreshBtn) {
    refreshBtn.addEventListener('click', loadEndpoints);
  }

  // Modal secret actions
  const copyBtn = document.getElementById('copy-secret-btn');
  const closeModalBtn = document.getElementById('close-modal-btn');
  const modalOverlay = document.getElementById('secret-modal');

  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      const text = document.getElementById('secret-value-text').innerText;
      navigator.clipboard.writeText(text);
      showToast('Signing secret copied to clipboard!', 'success');
    });
  }

  if (closeModalBtn && modalOverlay) {
    closeModalBtn.addEventListener('click', () => {
      modalOverlay.classList.remove('active');
    });
  }
}

export async function loadEndpoints() {
  const tbody = document.getElementById('endpoints-table-body');
  const activeTenantLabel = document.getElementById('active-tenant-label');
  if (activeTenantLabel) {
    activeTenantLabel.innerText = ApiClient.getTenantId();
  }

  if (!tbody) return;
  tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--text-muted);">Loading endpoints...</td></tr>`;

  const res = await ApiClient.getEndpoints();
  if (!res.ok) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--danger);">Failed to load endpoints: ${res.error}</td></tr>`;
    return;
  }

  const endpoints = res.data || [];
  if (endpoints.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--text-muted);">No endpoints registered for tenant "${ApiClient.getTenantId()}". Use the form above to register one.</td></tr>`;
    return;
  }

  tbody.innerHTML = endpoints.map(ep => {
    const isDisableable = ep.status === 'ACTIVE';
    const statusBadgeClass = ep.status === 'ACTIVE' ? 'badge-active' : 'badge-disabled';
    const eventsBadges = (ep.subscribedEventTypes || [])
      .map(ev => `<span class="badge badge-info" style="font-size:0.7rem; text-transform:none;">${ev}</span>`)
      .join(' ');

    return `
      <tr>
        <td style="font-family: var(--font-mono); font-size: 0.8rem; color: #a5b4fc;">${ep.id}</td>
        <td style="font-family: var(--font-mono); font-size: 0.85rem; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${ep.url}</td>
        <td><span class="badge ${statusBadgeClass}">${ep.status}</span></td>
        <td>${eventsBadges}</td>
        <td style="font-size: 0.8rem; color: var(--text-muted);">${formatDate(ep.createdAt)}</td>
        <td>
          <div style="display: flex; gap: 0.4rem;">
            <button class="btn btn-secondary btn-sm btn-test-ep" data-id="${ep.id}" title="Send synthetic ping event">Test Ping</button>
            ${isDisableable ? `<button class="btn btn-danger btn-sm btn-disable-ep" data-id="${ep.id}">Disable</button>` : ''}
            <button class="btn btn-secondary btn-sm btn-view-deliv" data-id="${ep.id}">View Logs</button>
          </div>
        </td>
      </tr>
    `;
  }).join('');

  // Wire row buttons
  document.querySelectorAll('.btn-test-ep').forEach(btn => {
    btn.addEventListener('click', (e) => handleTestEndpoint(e.target.dataset.id));
  });

  document.querySelectorAll('.btn-disable-ep').forEach(btn => {
    btn.addEventListener('click', (e) => handleDisableEndpoint(e.target.dataset.id));
  });

  document.querySelectorAll('.btn-view-deliv').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const epId = e.target.dataset.id;
      const deliveriesTab = document.querySelector('.tab-btn[data-tab="deliveries-panel"]');
      const filterInput = document.getElementById('filter-endpoint');
      if (filterInput) filterInput.value = epId;
      if (deliveriesTab) deliveriesTab.click();
    });
  });
}

async function handleCreateEndpoint(e) {
  e.preventDefault();
  const urlInput = document.getElementById('endpoint-url');
  const eventsInput = document.getElementById('endpoint-events');

  const url = urlInput.value.trim();
  const rawEvents = eventsInput.value.split(',').map(s => s.trim()).filter(Boolean);

  if (!url || rawEvents.length === 0) {
    showToast('Please provide a valid URL and at least one event type.', 'warning');
    return;
  }

  showToast('Registering endpoint...', 'info');
  const res = await ApiClient.createEndpoint(url, rawEvents);

  if (!res.ok) {
    showToast(`Registration failed: ${res.error}`, 'error');
    return;
  }

  showToast('Endpoint registered successfully!', 'success');
  urlInput.value = '';

  // Show secret modal
  if (res.data.secret) {
    document.getElementById('secret-value-text').innerText = res.data.secret;
    document.getElementById('secret-modal').classList.add('active');
  }

  loadEndpoints();
}

async function handleTestEndpoint(id) {
  showToast(`Testing connectivity for ${id}...`, 'info');
  const res = await ApiClient.testEndpoint(id);

  if (!res.ok) {
    showToast(`Test ping failed: ${res.error}`, 'error');
    return;
  }

  const result = res.data;
  if (result.success) {
    showToast(`Ping Succeeded! Code: ${result.statusCode}, Latency: ${result.latencyMs}ms`, 'success');
  } else {
    showToast(`Ping Failed: ${result.error || `HTTP ${result.statusCode}`}`, 'error');
  }
}

async function handleDisableEndpoint(id) {
  if (!confirm(`Are you sure you want to disable endpoint ${id}? Deliveries to this endpoint will stop.`)) {
    return;
  }

  showToast(`Disabling endpoint ${id}...`, 'info');
  const res = await ApiClient.disableEndpoint(id);

  if (!res.ok) {
    showToast(`Failed to disable endpoint: ${res.error}`, 'error');
    return;
  }

  showToast(`Endpoint ${id} disabled.`, 'success');
  loadEndpoints();
}
