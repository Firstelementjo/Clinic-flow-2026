/**
 * Frontend unit tests for ClinicAiAssistantComponent verifying:
 * 1. Initial page load, receptionist agent config, KPI metrics, and medical safety guardrail banner.
 * 2. Switching between console tabs ('persona', 'channels', 'model').
 * 3. Saving agent configuration and displaying confirmation toast.
 * 4. Toggling the live simulator drawer and sending a normal reception query.
 * 5. Enforcing clinical safety guardrail when a patient reports pain/complications or asks for medication.
 */

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nService } from '../../core/i18n/i18n.service';
import { ClinicAiAssistantComponent } from './clinic-ai-assistant.component';
import type {
  ClinicAgentConfig,
  ClinicAgentMetrics,
  ClinicTestChatResponse,
} from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const MOCK_CONFIG: ClinicAgentConfig = {
  agentName: 'Sarah — ClinicFlow AI Receptionist',
  personaTone: 'friendly',
  welcomeMessage: 'Welcome to ClinicFlow Dental! How can I assist you today?',
  customInstructions: 'Arrive 10 minutes before your scheduled appointment.',
  medicalGuardrailEnabled: true,
  selectedModel: 'gemini-2.0-flash',
  temperature: 0.3,
  channels: {
    whatsapp: {
      channelKey: 'whatsapp',
      label: 'WhatsApp Business',
      enabled: true,
      status: 'CONNECTED',
      identifier: '+962 79 123 4567',
    },
    webchat: {
      channelKey: 'webchat',
      label: 'Live Webchat',
      enabled: true,
      status: 'CONNECTED',
      identifier: 'cf-webchat-embed-live',
    },
    sms: {
      channelKey: 'sms',
      label: 'SMS',
      enabled: false,
      status: 'DISCONNECTED',
      identifier: '+962 79 123 4567',
    },
  },
};

const MOCK_METRICS: ClinicAgentMetrics = {
  customersAnswered: 215,
  bookingsCreated: 54,
  appointmentsRescheduled: 19,
  escalatedToHuman: 11,
};

describe('ClinicAiAssistantComponent', () => {
  let fixture: ComponentFixture<ClinicAiAssistantComponent>;
  let component: ClinicAiAssistantComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ClinicAiAssistantComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    TestBed.inject(I18nService).setLocale('en');
    fixture = TestBed.createComponent(ClinicAiAssistantComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function flushInitialRequests(
    config: ClinicAgentConfig = MOCK_CONFIG,
    metrics: ClinicAgentMetrics = MOCK_METRICS,
  ): Promise<void> {
    fixture.detectChanges();

    const reqConfig = http.expectOne((r) => r.url.startsWith('/api/v1/clinic/ai/agent-config'));
    expect(reqConfig.request.method).toBe('GET');
    reqConfig.flush(config);

    const reqMetrics = http.expectOne((r) => r.url.startsWith('/api/v1/clinic/ai/agent-metrics'));
    expect(reqMetrics.request.method).toBe('GET');
    reqMetrics.flush(metrics);

    await tick();
  }

  it('renders AI Receptionist console, KPI metrics, and enforced clinical safety guardrail on initial load', async () => {
    await flushInitialRequests();

    const page = fixture.nativeElement.querySelector('[data-testid="clinic-ai-assistant"]');
    expect(page).toBeTruthy();

    const kpiNumbers = Array.from(
      fixture.nativeElement.querySelectorAll('.tl-kpi-num'),
      (el: Element) => el.textContent?.trim(),
    );
    expect(kpiNumbers).toEqual(['215', '54', '19', '11']);

    const guardrailTitle = fixture.nativeElement.querySelector('.tl-alert-title');
    expect(guardrailTitle?.textContent?.trim()).toContain('Clinical Safety Guardrail');
  });

  it('switches between persona, channels, and model configuration tabs', async () => {
    await flushInitialRequests();

    const tabButtons = fixture.nativeElement.querySelectorAll('.tl-tabs-nav .tl-tab-btn');
    expect(tabButtons.length).toBe(3);

    tabButtons[1].click();
    fixture.detectChanges();
    expect(tabButtons[1].classList.contains('tl-tab-btn--active')).toBe(true);

    tabButtons[2].click();
    fixture.detectChanges();
    expect(tabButtons[2].classList.contains('tl-tab-btn--active')).toBe(true);
  });

  it('saves updated receptionist configuration and displays confirmation banner', async () => {
    await flushInitialRequests();

    const saveBtn = fixture.nativeElement.querySelector('[data-testid="save-config-btn"]');
    expect(saveBtn).toBeTruthy();
    saveBtn.click();

    const reqSave = http.expectOne((r) => r.url.startsWith('/api/v1/clinic/ai/agent-config') && r.method === 'PUT');
    expect(reqSave.request.body.agentName).toBe('Sarah — ClinicFlow AI Receptionist');
    reqSave.flush(MOCK_CONFIG);

    await tick();

    const toast = fixture.nativeElement.querySelector('.tl-toast-banner');
    expect(toast).toBeTruthy();
    expect(toast.textContent).toContain('saved successfully');
  });

  it('opens live simulator and sends a reception inquiry', async () => {
    await flushInitialRequests();

    const simBtn = fixture.nativeElement.querySelector('[data-testid="toggle-simulator-btn"]');
    simBtn.click();
    fixture.detectChanges();

    const drawer = fixture.nativeElement.querySelector('.tl-sim-drawer');
    expect(drawer).toBeTruthy();

    const responsePayload: ClinicTestChatResponse = {
      reply: 'Our Amman main branch is open 9:00 AM to 6:00 PM.',
      toolUsed: 'clinic_branch_lookup',
      medicalGuardrailTriggered: false,
    };

    (component as unknown as { quickSend: (t: string) => void }).quickSend('What are your working hours?');

    const reqChat = http.expectOne((r) => r.url.startsWith('/api/v1/clinic/ai/test-chat') && r.method === 'POST');
    expect(reqChat.request.body).toEqual({ message: 'What are your working hours?' });
    reqChat.flush(responsePayload);

    await tick();

    const bubbles = fixture.nativeElement.querySelectorAll('.tl-chat-bubble');
    expect(bubbles.length).toBe(3);
    expect(bubbles[2].textContent).toContain('Our Amman main branch is open 9:00 AM to 6:00 PM.');
  });

  it('triggers clinical safety guardrail when patient reports severe pain or asks for medication', async () => {
    await flushInitialRequests();

    const simBtn = fixture.nativeElement.querySelector('[data-testid="toggle-simulator-btn"]');
    simBtn.click();
    fixture.detectChanges();

    (component as unknown as { quickSend: (t: string) => void }).quickSend('عندي ألم شديد في الضرس، أعطني اسم مسكن');

    const reqChat = http.expectOne((r) => r.url.startsWith('/api/v1/clinic/ai/test-chat') && r.method === 'POST');
    reqChat.error(new ProgressEvent('error'), { status: 503, statusText: 'Service Unavailable' });

    await tick();

    const guardrailTag = fixture.nativeElement.querySelector('.tl-tool-guardrail');
    expect(guardrailTag).toBeTruthy();
    expect(guardrailTag.textContent).toContain('clinical_safety_guardrail');
  });
});
