import { ApiClient } from './api.js';
import { showToast } from './app.js';

export function initIsolationPanel() {
  const btn = document.getElementById('run-isolation-test-btn');
  if (btn) {
    btn.addEventListener('click', runIsolationTest);
  }
}

async function runIsolationTest() {
  const victimInput = document.getElementById('iso-victim-tenant');
  const attackerInput = document.getElementById('iso-attacker-tenant');
  const resourceInput = document.getElementById('iso-resource-id');
  const responseBox = document.getElementById('isolation-response-box');

  const victimTenant = victimInput ? victimInput.value.trim() : 'tenant-alpha';
  const attackerTenant = attackerInput ? attackerInput.value.trim() : 'tenant-malicious';
  let resourceId = resourceInput ? resourceInput.value.trim() : '';

  if (!resourceId) {
    // Attempt to fetch first endpoint from victim tenant to get a real ID if none supplied
    showToast(`Fetching target resource ID from ${victimTenant}...`, 'info');
    const origTenantInput = document.getElementById('tenant-id-input');
    const currentTenant = origTenantInput.value;
    origTenantInput.value = victimTenant;

    const listRes = await ApiClient.getEndpoints();
    origTenantInput.value = currentTenant;

    if (listRes.ok && listRes.data && listRes.data.length > 0) {
      resourceId = listRes.data[0].id;
      if (resourceInput) resourceInput.value = resourceId;
    } else {
      showToast(`No existing resource found for ${victimTenant}. Register an endpoint first!`, 'warning');
      return;
    }
  }

  showToast(`Attempting cross-tenant access to ${resourceId} as "${attackerTenant}"...`, 'warning');

  // Execute request with attacker tenant header
  const baseUrl = ApiClient.getBaseUrl();
  const url = `${baseUrl}/api/v1/endpoints/${resourceId}`;

  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': attackerTenant
      }
    });

    let bodyText;
    try {
      bodyText = await response.json();
    } catch {
      bodyText = await response.text();
    }

    const output = {
      testSummary: `Cross-Tenant Security Access Test`,
      targetResource: resourceId,
      ownerTenant: victimTenant,
      attackerTenantHeader: attackerTenant,
      httpStatus: `${response.status} ${response.statusText}`,
      isolationEnforced: response.status === 404 || response.status === 401 || response.status === 403,
      responseBody: bodyText
    };

    if (responseBox) {
      responseBox.innerText = JSON.stringify(output, null, 2);
    }

    if (output.isolationEnforced) {
      showToast(`ISOLATION PASSED! Request rejected with HTTP ${response.status} (Resource not found for attacker tenant).`, 'success');
    } else {
      showToast(`ISOLATION FAILED! Attacker was able to read victim resource!`, 'error');
    }
  } catch (err) {
    if (responseBox) {
      responseBox.innerText = `Network Error: ${err.message}`;
    }
    showToast(`Network Error running isolation check: ${err.message}`, 'error');
  }
}
