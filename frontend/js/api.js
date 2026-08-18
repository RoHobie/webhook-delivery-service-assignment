/**
 * Standardized API client for Webhook Delivery Service backend.
 */

export class ApiClient {
  static getBaseUrl() {
    const input = document.getElementById('backend-url-input');
    return input ? input.value.trim().replace(/\/+$/, '') : 'http://localhost:8080';
  }

  static getTenantId() {
    const input = document.getElementById('tenant-id-input');
    return input ? input.value.trim() : 'tenant-alpha';
  }

  static async request(path, options = {}) {
    const baseUrl = this.getBaseUrl();
    const tenantId = this.getTenantId();
    const url = `${baseUrl}${path}`;

    const headers = {
      'Content-Type': 'application/json',
      'X-Tenant-Id': tenantId,
      ...(options.headers || {})
    };

    const config = {
      ...options,
      headers
    };

    try {
      const response = await fetch(url, config);

      if (response.status === 204) {
        return { ok: true, status: 204, data: null };
      }

      let data;
      const contentType = response.headers.get('content-type');
      if (contentType && contentType.includes('application/json')) {
        data = await response.json();
      } else {
        data = await response.text();
      }

      if (!response.ok) {
        return {
          ok: false,
          status: response.status,
          error: data?.error || (typeof data === 'string' ? data : `HTTP ${response.status}`),
          data
        };
      }

      return { ok: true, status: response.status, data };
    } catch (err) {
      console.error('Fetch error:', err);
      return {
        ok: false,
        status: 0,
        error: `Network Error: ${err.message}. Is the backend running at ${baseUrl}?`
      };
    }
  }

  // Endpoints API
  static async getEndpoints() {
    return this.request('/api/v1/endpoints', { method: 'GET' });
  }

  static async getEndpoint(id) {
    return this.request(`/api/v1/endpoints/${id}`, { method: 'GET' });
  }

  static async createEndpoint(url, subscribedEventTypes) {
    return this.request('/api/v1/endpoints', {
      method: 'POST',
      body: JSON.stringify({ url, subscribedEventTypes })
    });
  }

  static async disableEndpoint(id) {
    return this.request(`/api/v1/endpoints/${id}`, { method: 'DELETE' });
  }

  static async testEndpoint(id) {
    return this.request(`/api/v1/endpoints/${id}/test`, { method: 'POST' });
  }

  // Events API
  static async ingestEvent(eventId, type, payload) {
    return this.request('/api/v1/events', {
      method: 'POST',
      body: JSON.stringify({ eventId, type, payload })
    });
  }

  // Deliveries API
  static async getDeliveries(params = {}) {
    const searchParams = new URLSearchParams();
    if (params.endpointId) searchParams.append('endpointId', params.endpointId);
    if (params.eventId) searchParams.append('eventId', params.eventId);
    if (params.status) searchParams.append('status', params.status);
    if (params.page !== undefined) searchParams.append('page', params.page);
    if (params.size !== undefined) searchParams.append('size', params.size);

    const queryString = searchParams.toString();
    const path = `/api/v1/deliveries${queryString ? `?${queryString}` : ''}`;
    return this.request(path, { method: 'GET' });
  }

  static async getDelivery(id) {
    return this.request(`/api/v1/deliveries/${id}`, { method: 'GET' });
  }

  static async getDeliveryAttempts(id) {
    return this.request(`/api/v1/deliveries/${id}/attempts`, { method: 'GET' });
  }

  static async redriveDelivery(id) {
    return this.request(`/api/v1/deliveries/${id}/redrive`, { method: 'POST' });
  }

  // Health API
  static async checkHealth() {
    const baseUrl = this.getBaseUrl();
    try {
      const response = await fetch(`${baseUrl}/actuator/health`);
      if (response.ok) {
        const data = await response.json();
        return { ok: true, data };
      }
      return { ok: false };
    } catch {
      return { ok: false };
    }
  }
}
