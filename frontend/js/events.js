import { ApiClient } from './api.js';
import { showToast } from './app.js';

export function initEventsPanel() {
  const form = document.getElementById('ingest-event-form');
  const genUuidBtn = document.getElementById('gen-uuid-btn');
  const duplicateBtn = document.getElementById('send-duplicate-btn');
  const eventIdInput = document.getElementById('event-id');

  if (genUuidBtn && eventIdInput) {
    eventIdInput.value = generateUUID();
    genUuidBtn.addEventListener('click', () => {
      eventIdInput.value = generateUUID();
    });
  }

  if (form) {
    form.addEventListener('submit', (e) => handleSendEvent(e, false));
  }

  if (duplicateBtn) {
    duplicateBtn.addEventListener('click', (e) => handleSendEvent(e, true));
  }
}

function generateUUID() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'evt_' + Math.random().toString(36).substring(2, 11) + '_' + Date.now().toString(36);
}

async function handleSendEvent(e, isDuplicateDemo) {
  e.preventDefault();

  const eventIdInput = document.getElementById('event-id');
  const typeInput = document.getElementById('event-type');
  const payloadInput = document.getElementById('event-payload');
  const resultBox = document.getElementById('ingest-result-box');
  const resultJson = document.getElementById('ingest-result-json');

  const eventId = eventIdInput.value.trim();
  const type = typeInput.value.trim();
  const rawPayload = payloadInput.value.trim();

  if (!eventId || !type || !rawPayload) {
    showToast('All fields are required.', 'warning');
    return;
  }

  let parsedPayload;
  try {
    parsedPayload = JSON.parse(rawPayload);
  } catch (err) {
    showToast('Invalid JSON payload syntax. Please check syntax.', 'error');
    return;
  }

  const actionText = isDuplicateDemo ? 'Sending duplicate event (Idempotency Check)...' : 'Publishing event...';
  showToast(actionText, 'info');

  const res = await ApiClient.ingestEvent(eventId, type, parsedPayload);

  if (resultBox && resultJson) {
    resultBox.style.display = 'block';
    resultJson.innerText = JSON.stringify(res, null, 2);
  }

  if (!res.ok) {
    if (res.status === 429) {
      showToast(`Rate limit exceeded for tenant "${ApiClient.getTenantId()}". HTTP 429 Too Many Requests.`, 'warning');
    } else {
      showToast(`Event ingestion failed (${res.status}): ${res.error}`, 'error');
    }
    return;
  }

  if (isDuplicateDemo) {
    showToast(`Idempotency Check Succeeded! Re-submitted event return HTTP 202 without duplicate delivery fan-out.`, 'success');
  } else {
    showToast(`Event ingested successfully! Deliveries created: ${res.data.deliveriesCreated}`, 'success');
  }
}
