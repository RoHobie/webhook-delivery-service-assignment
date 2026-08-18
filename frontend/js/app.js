import { ApiClient } from './api.js';
import { initEndpointsPanel, loadEndpoints } from './endpoints.js';
import { initEventsPanel } from './events.js';
import { initDeliveriesPanel, loadDeliveries, stopPolling } from './deliveries.js';
import { initIsolationPanel } from './isolation.js';

document.addEventListener('DOMContentLoaded', () => {
  initTabs();
  initHeaderControls();
  initEndpointsPanel();
  initEventsPanel();
  initDeliveriesPanel();
  initIsolationPanel();

  // Initial load
  checkBackendHealth();
  loadEndpoints();
});

// Tab navigation handler
function initTabs() {
  const tabBtns = document.querySelectorAll('.tab-btn');
  const panels = document.querySelectorAll('.tab-panel');

  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetPanelId = btn.dataset.tab;

      tabBtns.forEach(b => b.classList.remove('active'));
      panels.forEach(p => p.classList.remove('active'));

      btn.classList.add('active');
      const targetPanel = document.getElementById(targetPanelId);
      if (targetPanel) {
        targetPanel.classList.add('active');
      }

      // Stop polling when switching away from deliveries tab unless auto-poll is on
      const autoPollToggle = document.getElementById('auto-refresh-toggle');
      if (targetPanelId !== 'deliveries-panel' && (!autoPollToggle || !autoPollToggle.checked)) {
        stopPolling();
      }

      // Lazy reload data for active tab
      if (targetPanelId === 'endpoints-panel') {
        loadEndpoints();
      } else if (targetPanelId === 'deliveries-panel') {
        loadDeliveries();
      }
    });
  });
}

// Header & Connection controls
function initHeaderControls() {
  const healthBtn = document.getElementById('health-check-btn');
  const tenantInput = document.getElementById('tenant-id-input');
  const backendUrlInput = document.getElementById('backend-url-input');

  if (healthBtn) {
    healthBtn.addEventListener('click', checkBackendHealth);
  }

  if (tenantInput) {
    tenantInput.addEventListener('change', () => {
      showToast(`Switched active tenant header to "${ApiClient.getTenantId()}"`, 'info');
      loadEndpoints();
      loadDeliveries();
    });
  }

  if (backendUrlInput) {
    backendUrlInput.addEventListener('change', () => {
      checkBackendHealth();
    });
  }
}

// Health check ping
export async function checkBackendHealth() {
  const dot = document.getElementById('health-dot');
  const text = document.getElementById('health-text');

  if (text) text.innerText = 'Ping...';
  if (dot) dot.className = 'health-dot';

  const res = await ApiClient.checkHealth();

  if (res.ok) {
    if (dot) dot.className = 'health-dot online';
    if (text) text.innerText = 'Backend Online';
  } else {
    if (dot) dot.className = 'health-dot offline';
    if (text) text.innerText = 'Backend Offline';
  }
}

// Toast notification helper
export function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;

  const iconMap = {
    success: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>',
    error: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    warning: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
    info: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'
  };

  toast.innerHTML = `
    ${iconMap[type] || iconMap.info}
    <span>${message}</span>
  `;

  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateX(100%)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

// Date formatter
export function formatDate(dateString) {
  if (!dateString) return '-';
  try {
    const d = new Date(dateString);
    return d.toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  } catch {
    return dateString;
  }
}
