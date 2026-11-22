import type {
  PublicClinicConfig,
  SubmitIntakeRequest,
  SubmitIntakeResult,
  VerifyCodeResponse,
} from './types';

export class IntakeApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message);
  }
}

let cachedBaseUrl: string | null = null;

async function resolveApiBaseUrl(): Promise<string> {
  if (cachedBaseUrl !== null) return cachedBaseUrl;

  const envUrl = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_BASE_URL;
  if (envUrl && envUrl.trim()) {
    cachedBaseUrl = envUrl.trim().replace(/\/+$/, '');
    return cachedBaseUrl;
  }

  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    if (res.ok) {
      const data = (await res.json()) as { apiBaseUrl?: string };
      if (data.apiBaseUrl && data.apiBaseUrl.trim()) {
        cachedBaseUrl = data.apiBaseUrl.trim().replace(/\/+$/, '');
        return cachedBaseUrl;
      }
    }
  } catch {
    // Fallback to relative /api path (works via Vite dev proxy or reverse proxy)
  }

  cachedBaseUrl = '';
  return cachedBaseUrl;
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const base = await resolveApiBaseUrl();
  const response = await fetch(`${base}${path}`, {
    ...init,
    credentials: 'omit',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    let message = 'Request failed';
    let code: string | undefined;
    try {
      const errBody = (await response.json()) as {
        message?: string;
        code?: string;
        details?: { reason?: string };
      };
      message = errBody.message || message;
      code = errBody.details?.reason || errBody.code;
    } catch {
      // ignore non-JSON body
    }
    throw new IntakeApiError(message, response.status, code);
  }

  return (await response.json()) as T;
}

export function getClinicConfig(clinicKey: string): Promise<PublicClinicConfig> {
  return requestJson<PublicClinicConfig>(`/api/v1/public/intake/${encodeURIComponent(clinicKey)}/config`, {
    method: 'GET',
  });
}

export function verifyIntakeCode(clinicKey: string, code: string): Promise<VerifyCodeResponse> {
  return requestJson<VerifyCodeResponse>(`/api/v1/public/intake/${encodeURIComponent(clinicKey)}/verify`, {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
}

export function submitIntakeForm(clinicKey: string, payload: SubmitIntakeRequest): Promise<SubmitIntakeResult> {
  return requestJson<SubmitIntakeResult>(`/api/v1/public/intake/${encodeURIComponent(clinicKey)}/submit`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}
