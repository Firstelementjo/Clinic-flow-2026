import { useEffect, useMemo, useState } from 'react';
import { getClinicConfig, IntakeApiError, submitIntakeForm, verifyIntakeCode } from './api';
import {
  COPY,
  FALLBACK_ALLERGIES,
  FALLBACK_CONDITIONS,
  FALLBACK_MEDICATIONS,
  FALLBACK_SYMPTOMS,
  labelOf,
} from './i18n';
import type {
  IntakeChiefComplaint,
  IntakeDemographics,
  IntakeDentalHistory,
  IntakeMedicalHistory,
  Locale,
  PublicClinicConfig,
} from './types';

type Screen = 'code' | 'confirm' | 'wizard' | 'success';

const TOTAL_CARDS = 7;

function resolveClinicKeyFromLocation(): string {
  const path = window.location.pathname;
  const match = path.match(/\/c\/([^/]+)/);
  if (match && match[1]) {
    return decodeURIComponent(match[1]);
  }
  const params = new URLSearchParams(window.location.search);
  return params.get('clinic') || '';
}

function createBlankDemographics(): IntakeDemographics {
  return {
    fullName: '',
    dob: '',
    gender: '',
    occupation: '',
    address: '',
    phone: '',
    email: '',
    emergencyName: '',
    emergencyPhone: '',
  };
}

function createBlankMedical(): IntakeMedicalHistory {
  return {
    underTreatment: 'No',
    treatmentSpecify: '',
    hospitalizedLastYear: 'No',
    hospitalizedReason: '',
    conditions: [],
    otherCondition: '',
    pregnantOrBreastfeeding: 'N/A',
    medications: [],
    otherMedications: '',
    allergies: [],
    otherAllergies: '',
  };
}

function createBlankDental(): IntakeDentalHistory {
  return {
    lastDentalVisitDate: '',
    lastVisitReason: '',
    symptoms: [],
    pastProcedures: '',
    anxietyLevel: 1,
  };
}

function createBlankComplaint(): IntakeChiefComplaint {
  return {
    primaryComplaint: '',
    painScale: 0,
    duration: '',
    triggers: [],
  };
}

function toggleItem(list: string[], key: string): string[] {
  return list.includes(key) ? list.filter((i) => i !== key) : [...list, key];
}

export function App() {
  const clinicKey = useMemo(() => resolveClinicKeyFromLocation(), []);
  const [locale, setLocale] = useState<Locale>('ar');
  const t = COPY[locale];

  const [clinicConfig, setClinicConfig] = useState<PublicClinicConfig | null>(null);
  const [loadingConfig, setLoadingConfig] = useState<boolean>(Boolean(clinicKey));
  const [configNotFound, setConfigNotFound] = useState<boolean>(false);

  const [screen, setScreen] = useState<Screen>('code');
  const [codeInput, setCodeInput] = useState<string>('');
  const [verifying, setVerifying] = useState<boolean>(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Kept strictly in memory (React state) — never written to localStorage or cookies
  const [sessionToken, setSessionToken] = useState<string>('');
  const [patientFirstName, setPatientFirstName] = useState<string>('');

  // Bite-sized wizard step (1..7 across the 4 sections)
  const [cardStep, setCardStep] = useState<number>(1);
  const [submitting, setSubmitting] = useState<boolean>(false);

  const [demographics, setDemographics] = useState<IntakeDemographics>(createBlankDemographics);
  const [medicalHistory, setMedicalHistory] = useState<IntakeMedicalHistory>(createBlankMedical);
  const [dentalHistory, setDentalHistory] = useState<IntakeDentalHistory>(createBlankDental);
  const [chiefComplaint, setChiefComplaint] = useState<IntakeChiefComplaint>(createBlankComplaint);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = locale === 'ar' ? 'rtl' : 'ltr';
  }, [locale]);

  useEffect(() => {
    if (!clinicKey) {
      setLoadingConfig(false);
      return;
    }
    let cancelled = false;
    setLoadingConfig(true);
    setConfigNotFound(false);

    getClinicConfig(clinicKey)
      .then((cfg) => {
        if (cancelled) return;
        setClinicConfig(cfg);
        if (cfg.defaultLocale === 'en') {
          setLocale('en');
        } else {
          setLocale('ar');
        }
      })
      .catch(() => {
        if (!cancelled) setConfigNotFound(true);
      })
      .finally(() => {
        if (!cancelled) setLoadingConfig(false);
      });

    return () => {
      cancelled = true;
    };
  }, [clinicKey]);

  const conditionsList = clinicConfig?.schema?.medicalConditions?.length
    ? clinicConfig.schema.medicalConditions
    : FALLBACK_CONDITIONS;
  const medicationsList = clinicConfig?.schema?.medicationTypes?.length
    ? clinicConfig.schema.medicationTypes
    : FALLBACK_MEDICATIONS;
  const allergiesList = clinicConfig?.schema?.allergyTypes?.length
    ? clinicConfig.schema.allergyTypes
    : FALLBACK_ALLERGIES;
  const symptomsList = clinicConfig?.schema?.dentalSymptoms?.length
    ? clinicConfig.schema.dentalSymptoms
    : FALLBACK_SYMPTOMS;

  const currentSectionId =
    cardStep <= 2 ? 1 : cardStep <= 5 ? 2 : cardStep === 6 ? 3 : 4;
  const progressPct = Math.round((cardStep / TOTAL_CARDS) * 100);

  function wipeTransientState() {
    setSessionToken('');
    setPatientFirstName('');
    setCodeInput('');
    setDemographics(createBlankDemographics());
    setMedicalHistory(createBlankMedical());
    setDentalHistory(createBlankDental());
    setChiefComplaint(createBlankComplaint());
    setCardStep(1);
    setErrorMsg(null);
  }

  async function handleVerifyCode(e: React.FormEvent) {
    e.preventDefault();
    if (!clinicKey || verifying) return;
    const cleaned = codeInput.trim();
    if (!cleaned) {
      setErrorMsg(t.codeInvalid);
      return;
    }

    setVerifying(true);
    setErrorMsg(null);
    try {
      const res = await verifyIntakeCode(clinicKey, cleaned);
      setSessionToken(res.intakeSessionToken);
      setPatientFirstName(res.patientFirstName);
      setDemographics(createBlankDemographics());
      setMedicalHistory(createBlankMedical());
      setDentalHistory(createBlankDental());
      setChiefComplaint(createBlankComplaint());
      setCardStep(1);
      setScreen('confirm');
    } catch (err) {
      if (err instanceof IntakeApiError && err.status === 429) {
        setErrorMsg(t.rateLimited);
      } else {
        setErrorMsg(t.codeInvalid);
      }
    } finally {
      setVerifying(false);
    }
  }

  function handleNotMe() {
    wipeTransientState();
    setScreen('code');
  }

  function handleConfirmContinue() {
    setErrorMsg(null);
    setCardStep(1);
    setScreen('wizard');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function validateCurrentCard(): boolean {
    setErrorMsg(null);
    if (cardStep === 1) {
      if (!demographics.dob) {
        setErrorMsg(t.dobRequiredErr);
        return false;
      }
      if (!demographics.gender) {
        setErrorMsg(t.genderRequiredErr);
        return false;
      }
    }
    if (cardStep === 7) {
      if (!chiefComplaint.primaryComplaint.trim()) {
        setErrorMsg(t.complaintRequiredErr);
        return false;
      }
    }
    return true;
  }

  function handleNext() {
    if (!validateCurrentCard()) return;
    setCardStep((s) => Math.min(TOTAL_CARDS, s + 1));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function handleBack() {
    setErrorMsg(null);
    if (cardStep > 1) {
      setCardStep((s) => s - 1);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } else {
      setScreen('confirm');
    }
  }

  async function handleSubmit() {
    if (!validateCurrentCard() || submitting) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      await submitIntakeForm(clinicKey, {
        intakeSessionToken: sessionToken,
        demographics,
        medicalHistory,
        dentalHistory,
        chiefComplaint,
      });
      wipeTransientState();
      setScreen('success');
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (err) {
      if (err instanceof IntakeApiError) {
        setErrorMsg(err.message);
      } else {
        setErrorMsg(t.codeInvalid);
      }
    } finally {
      setSubmitting(false);
    }
  }

  const clinicDisplayName = clinicConfig?.clinicName || 'ClinicFlow';

  return (
    <div className="portal-shell" dir={locale === 'ar' ? 'rtl' : 'ltr'}>
      {/* Sticky Top Header */}
      <header className="portal-header">
        <div className="header-top-row">
          <div className="clinic-brand">
            <span className="portal-badge">{t.portalTag}</span>
            <h1 className="clinic-title">{clinicDisplayName}</h1>
          </div>
          <button
            type="button"
            className="lang-toggle"
            onClick={() => setLocale((l) => (l === 'ar' ? 'en' : 'ar'))}
          >
            {locale === 'ar' ? 'English' : 'العربية'}
          </button>
        </div>

        {screen === 'wizard' && (
          <div className="progress-wrap">
            <div className="section-pills">
              {t.sections.map((sec) => (
                <div
                  key={sec.id}
                  className={
                    'section-pill' +
                    (sec.id === currentSectionId
                      ? ' is-active'
                      : sec.id < currentSectionId
                        ? ' is-done'
                        : '')
                  }
                >
                  <span>{sec.id}.</span>
                  <span>{sec.label}</span>
                </div>
              ))}
            </div>
            <div className="progress-meta-row">
              <span>
                {t.stepOf} {cardStep} {t.of} {TOTAL_CARDS}
              </span>
              <span>{progressPct}%</span>
            </div>
            <div className="progress-track" role="progressbar" aria-valuenow={progressPct} aria-valuemin={0} aria-valuemax={100}>
              <div className="progress-fill" style={{ width: `${progressPct}%` }} />
            </div>
          </div>
        )}
      </header>

      {/* Main Body */}
      <main className="portal-main">
        {!clinicKey ? (
          <section className="step-card text-center">
            <h2 className="card-title">{t.missingClinicTitle}</h2>
            <p className="card-hint">{t.missingClinicDesc}</p>
          </section>
        ) : loadingConfig ? (
          <section className="step-card text-center">
            <p className="card-hint">{t.loadingClinic}</p>
          </section>
        ) : configNotFound || (clinicConfig && !clinicConfig.active) ? (
          <section className="step-card text-center">
            <h2 className="card-title">{t.clinicUnavailableTitle}</h2>
            <p className="card-hint">{t.clinicUnavailableDesc}</p>
          </section>
        ) : screen === 'code' ? (
          <form className="step-card" onSubmit={handleVerifyCode}>
            <h2 className="card-title">{t.codeTitle}</h2>
            <p className="card-hint">{t.codeSubtitle}</p>

            {errorMsg && (
              <div className="alert-box alert-error" role="alert">
                {errorMsg}
              </div>
            )}

            <div className="field">
              <input
                type="text"
                className="input code-input"
                dir="ltr"
                autoComplete="one-time-code"
                autoCapitalize="characters"
                maxLength={10}
                placeholder={t.codePlaceholder}
                value={codeInput}
                onChange={(e) => setCodeInput(e.target.value.toUpperCase())}
              />
            </div>

            <button type="submit" className="btn-primary" disabled={verifying}>
              {verifying ? t.verifying : t.verifyBtn}
            </button>
          </form>
        ) : screen === 'confirm' ? (
          <section className="step-card">
            <h2 className="card-title">{t.confirmTitle}</h2>
            <div className="confirm-name-box">
              <div className="confirm-greeting">{t.confirmGreeting}</div>
              <div className="confirm-name">{patientFirstName}</div>
            </div>
            <p className="card-hint text-center">{t.confirmQuestion}</p>
            <div className="alert-box alert-info">{t.privacyNote}</div>
            <button type="button" className="btn-primary" onClick={handleConfirmContinue}>
              {t.confirmContinue}
            </button>
            <button type="button" className="btn-secondary" onClick={handleNotMe}>
              {t.confirmNotMe}
            </button>
          </section>
        ) : screen === 'success' ? (
          <section className="step-card text-center">
            <div className="success-icon" aria-hidden="true">✓</div>
            <h2 className="card-title">{t.successTitle}</h2>
            <p className="card-hint">{t.successDesc}</p>
            <div className="alert-box alert-info">{t.successNotice}</div>
          </section>
        ) : (
          /* Bite-Sized 7-Card Wizard ("حبة حبة") */
          <>
            {errorMsg && (
              <div className="alert-box alert-error" role="alert">
                {errorMsg}
              </div>
            )}

            {/* CARD 1: Basic Demographics */}
            {cardStep === 1 && (
              <section className="step-card">
                <h2 className="card-title">{t.step1Title}</h2>
                <p className="card-hint">{t.step1Hint}</p>

                <div className="field">
                  <div className="field-label-row">
                    <label className="field-label" htmlFor="fullName">{t.fullName}</label>
                    <span className="tag-opt">{t.optionalTag}</span>
                  </div>
                  <input
                    id="fullName"
                    type="text"
                    className="input"
                    placeholder={t.fullNamePh}
                    value={demographics.fullName}
                    onChange={(e) => setDemographics({ ...demographics, fullName: e.target.value })}
                  />
                </div>

                <div className="field">
                  <div className="field-label-row">
                    <label className="field-label" htmlFor="dob">{t.dob}</label>
                    <span className="tag-req">{t.requiredTag}</span>
                  </div>
                  <input
                    id="dob"
                    type="date"
                    className="input"
                    value={demographics.dob}
                    max={new Date().toISOString().slice(0, 10)}
                    onChange={(e) => setDemographics({ ...demographics, dob: e.target.value })}
                  />
                </div>

                <div className="field">
                  <div className="field-label-row">
                    <span className="field-label">{t.gender}</span>
                    <span className="tag-req">{t.requiredTag}</span>
                  </div>
                  <div className="choice-grid-2">
                    <button
                      type="button"
                      className={'touch-card' + (demographics.gender === 'Male' ? ' is-selected' : '')}
                      onClick={() => setDemographics({ ...demographics, gender: 'Male' })}
                    >
                      {t.male}
                    </button>
                    <button
                      type="button"
                      className={'touch-card' + (demographics.gender === 'Female' ? ' is-selected' : '')}
                      onClick={() => setDemographics({ ...demographics, gender: 'Female' })}
                    >
                      {t.female}
                    </button>
                  </div>
                </div>
              </section>
            )}

            {/* CARD 2: Contact & Emergency Info */}
            {cardStep === 2 && (
              <section className="step-card">
                <h2 className="card-title">{t.step2Title}</h2>
                <p className="card-hint">{t.step2Hint}</p>

                <div className="field">
                  <label className="field-label" htmlFor="phone">{t.phone}</label>
                  <input
                    id="phone"
                    type="tel"
                    inputMode="tel"
                    dir="ltr"
                    className="input"
                    placeholder={t.phonePh}
                    value={demographics.phone}
                    onChange={(e) => setDemographics({ ...demographics, phone: e.target.value })}
                  />
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="email">{t.email}</label>
                  <input
                    id="email"
                    type="email"
                    inputMode="email"
                    dir="ltr"
                    className="input"
                    placeholder={t.emailPh}
                    value={demographics.email}
                    onChange={(e) => setDemographics({ ...demographics, email: e.target.value })}
                  />
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="occupation">{t.occupation}</label>
                  <input
                    id="occupation"
                    type="text"
                    className="input"
                    placeholder={t.occupationPh}
                    value={demographics.occupation}
                    onChange={(e) => setDemographics({ ...demographics, occupation: e.target.value })}
                  />
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="address">{t.address}</label>
                  <input
                    id="address"
                    type="text"
                    className="input"
                    placeholder={t.addressPh}
                    value={demographics.address}
                    onChange={(e) => setDemographics({ ...demographics, address: e.target.value })}
                  />
                </div>

                <div className="divider-block">
                  <span className="field-label">{t.emergencyTitle}</span>
                  <input
                    type="text"
                    className="input"
                    placeholder={t.emergencyNamePh}
                    value={demographics.emergencyName}
                    onChange={(e) => setDemographics({ ...demographics, emergencyName: e.target.value })}
                  />
                  <input
                    type="tel"
                    inputMode="tel"
                    dir="ltr"
                    className="input"
                    placeholder={t.emergencyPhonePh}
                    value={demographics.emergencyPhone}
                    onChange={(e) => setDemographics({ ...demographics, emergencyPhone: e.target.value })}
                  />
                </div>
              </section>
            )}

            {/* CARD 3: Current Health Status */}
            {cardStep === 3 && (
              <section className="step-card">
                <h2 className="card-title">{t.step3Title}</h2>
                <p className="card-hint">{t.step3Hint}</p>

                <div className="field">
                  <span className="field-label">{t.underTreatmentQ}</span>
                  <div className="choice-grid-2">
                    <button
                      type="button"
                      className={'touch-card' + (medicalHistory.underTreatment === 'Yes' ? ' is-selected' : '')}
                      onClick={() => setMedicalHistory({ ...medicalHistory, underTreatment: 'Yes' })}
                    >
                      {t.yes}
                    </button>
                    <button
                      type="button"
                      className={'touch-card' + (medicalHistory.underTreatment === 'No' ? ' is-selected' : '')}
                      onClick={() =>
                        setMedicalHistory({ ...medicalHistory, underTreatment: 'No', treatmentSpecify: '' })
                      }
                    >
                      {t.no}
                    </button>
                  </div>
                  {medicalHistory.underTreatment === 'Yes' && (
                    <input
                      type="text"
                      className="input"
                      placeholder={t.treatmentSpecifyPh}
                      value={medicalHistory.treatmentSpecify}
                      onChange={(e) => setMedicalHistory({ ...medicalHistory, treatmentSpecify: e.target.value })}
                    />
                  )}
                </div>

                <div className="divider-block">
                  <span className="field-label">{t.hospitalizedQ}</span>
                  <div className="choice-grid-2">
                    <button
                      type="button"
                      className={'touch-card' + (medicalHistory.hospitalizedLastYear === 'Yes' ? ' is-selected' : '')}
                      onClick={() => setMedicalHistory({ ...medicalHistory, hospitalizedLastYear: 'Yes' })}
                    >
                      {t.yes}
                    </button>
                    <button
                      type="button"
                      className={'touch-card' + (medicalHistory.hospitalizedLastYear === 'No' ? ' is-selected' : '')}
                      onClick={() =>
                        setMedicalHistory({ ...medicalHistory, hospitalizedLastYear: 'No', hospitalizedReason: '' })
                      }
                    >
                      {t.no}
                    </button>
                  </div>
                  {medicalHistory.hospitalizedLastYear === 'Yes' && (
                    <input
                      type="text"
                      className="input"
                      placeholder={t.hospitalizedReasonPh}
                      value={medicalHistory.hospitalizedReason}
                      onChange={(e) => setMedicalHistory({ ...medicalHistory, hospitalizedReason: e.target.value })}
                    />
                  )}
                </div>

                {demographics.gender !== 'Male' && (
                  <div className="divider-block">
                    <span className="field-label">{t.pregnantQ}</span>
                    <div className="choice-grid-3">
                      {(['Yes', 'No', 'N/A'] as const).map((val) => (
                        <button
                          key={val}
                          type="button"
                          className={'touch-card' + (medicalHistory.pregnantOrBreastfeeding === val ? ' is-selected' : '')}
                          onClick={() => setMedicalHistory({ ...medicalHistory, pregnantOrBreastfeeding: val })}
                        >
                          {val === 'Yes' ? t.yes : val === 'No' ? t.no : t.na}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </section>
            )}

            {/* CARD 4: Medical Conditions */}
            {cardStep === 4 && (
              <section className="step-card">
                <h2 className="card-title">{t.step4Title}</h2>
                <p className="card-hint">{t.step4Hint}</p>

                <button
                  type="button"
                  className="quick-skip-btn"
                  onClick={() => {
                    setMedicalHistory({ ...medicalHistory, conditions: [], otherCondition: '' });
                    setCardStep(5);
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                  }}
                >
                  {t.noConditionsBtn}
                </button>

                <div className="chips-wrap">
                  {conditionsList.map((opt) => {
                    const active = medicalHistory.conditions.includes(opt.key);
                    return (
                      <button
                        key={opt.key}
                        type="button"
                        className={'chip-btn' + (active ? ' is-selected' : '')}
                        onClick={() =>
                          setMedicalHistory({
                            ...medicalHistory,
                            conditions: toggleItem(medicalHistory.conditions, opt.key),
                          })
                        }
                      >
                        {active && <span aria-hidden="true">✓</span>}
                        <span>{labelOf(opt, locale)}</span>
                      </button>
                    );
                  })}
                </div>

                {medicalHistory.conditions.includes('Other') && (
                  <input
                    type="text"
                    className="input"
                    placeholder={t.otherConditionPh}
                    value={medicalHistory.otherCondition}
                    onChange={(e) => setMedicalHistory({ ...medicalHistory, otherCondition: e.target.value })}
                  />
                )}
              </section>
            )}

            {/* CARD 5: Medications & Allergies */}
            {cardStep === 5 && (
              <section className="step-card">
                <h2 className="card-title">{t.step5Title}</h2>
                <p className="card-hint">{t.step5Hint}</p>

                <div className="field">
                  <span className="field-label">{t.medsLabel}</span>
                  <button
                    type="button"
                    className="quick-skip-btn"
                    onClick={() => setMedicalHistory({ ...medicalHistory, medications: [], otherMedications: '' })}
                  >
                    {t.noMedsBtn}
                  </button>
                  <div className="chips-wrap">
                    {medicationsList.map((opt) => {
                      const active = medicalHistory.medications.includes(opt.key);
                      return (
                        <button
                          key={opt.key}
                          type="button"
                          className={'chip-btn' + (active ? ' is-selected' : '')}
                          onClick={() =>
                            setMedicalHistory({
                              ...medicalHistory,
                              medications: toggleItem(medicalHistory.medications, opt.key),
                            })
                          }
                        >
                          {active && <span aria-hidden="true">✓</span>}
                          <span>{labelOf(opt, locale)}</span>
                        </button>
                      );
                    })}
                  </div>
                  <input
                    type="text"
                    className="input"
                    placeholder={t.otherMedsPh}
                    value={medicalHistory.otherMedications}
                    onChange={(e) => setMedicalHistory({ ...medicalHistory, otherMedications: e.target.value })}
                  />
                </div>

                <div className="divider-block">
                  <span className="field-label">{t.allergiesLabel}</span>
                  <button
                    type="button"
                    className="quick-skip-btn"
                    onClick={() => setMedicalHistory({ ...medicalHistory, allergies: [], otherAllergies: '' })}
                  >
                    {t.noAllergiesBtn}
                  </button>
                  <div className="chips-wrap">
                    {allergiesList.map((opt) => {
                      const active = medicalHistory.allergies.includes(opt.key);
                      return (
                        <button
                          key={opt.key}
                          type="button"
                          className={'chip-btn' + (active ? ' is-selected' : '')}
                          onClick={() =>
                            setMedicalHistory({
                              ...medicalHistory,
                              allergies: toggleItem(medicalHistory.allergies, opt.key),
                            })
                          }
                        >
                          {active && <span aria-hidden="true">✓</span>}
                          <span>{labelOf(opt, locale)}</span>
                        </button>
                      );
                    })}
                  </div>
                  <input
                    type="text"
                    className="input"
                    placeholder={t.otherAllergiesPh}
                    value={medicalHistory.otherAllergies}
                    onChange={(e) => setMedicalHistory({ ...medicalHistory, otherAllergies: e.target.value })}
                  />
                </div>
              </section>
            )}

            {/* CARD 6: Dental History */}
            {cardStep === 6 && (
              <section className="step-card">
                <h2 className="card-title">{t.step6Title}</h2>
                <p className="card-hint">{t.step6Hint}</p>

                <div className="field">
                  <label className="field-label" htmlFor="lastDentalDate">{t.lastVisitDate}</label>
                  <input
                    id="lastDentalDate"
                    type="date"
                    className="input"
                    value={dentalHistory.lastDentalVisitDate}
                    max={new Date().toISOString().slice(0, 10)}
                    onChange={(e) =>
                      setDentalHistory({ ...dentalHistory, lastDentalVisitDate: e.target.value })
                    }
                  />
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="lastVisitReason">{t.lastVisitReason}</label>
                  <input
                    id="lastVisitReason"
                    type="text"
                    className="input"
                    placeholder={t.lastVisitReasonPh}
                    value={dentalHistory.lastVisitReason}
                    onChange={(e) =>
                      setDentalHistory({ ...dentalHistory, lastVisitReason: e.target.value })
                    }
                  />
                </div>

                <div className="field">
                  <span className="field-label">{t.symptomsLabel}</span>
                  <div className="chips-wrap">
                    {symptomsList.map((opt) => {
                      const active = dentalHistory.symptoms.includes(opt.key);
                      return (
                        <button
                          key={opt.key}
                          type="button"
                          className={'chip-btn' + (active ? ' is-selected' : '')}
                          onClick={() =>
                            setDentalHistory({
                              ...dentalHistory,
                              symptoms: toggleItem(dentalHistory.symptoms, opt.key),
                            })
                          }
                        >
                          {active && <span aria-hidden="true">✓</span>}
                          <span>{labelOf(opt, locale)}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="pastProcedures">{t.pastProcedures}</label>
                  <textarea
                    id="pastProcedures"
                    className="textarea"
                    placeholder={t.pastProceduresPh}
                    value={dentalHistory.pastProcedures}
                    onChange={(e) =>
                      setDentalHistory({ ...dentalHistory, pastProcedures: e.target.value })
                    }
                  />
                </div>

                <div className="field">
                  <span className="field-label">{t.anxietyLabel}</span>
                  <div className="stack-list">
                    {t.anxietyLevels.map((lvl) => (
                      <button
                        key={lvl.value}
                        type="button"
                        className={'stack-card' + (dentalHistory.anxietyLevel === lvl.value ? ' is-selected' : '')}
                        onClick={() => setDentalHistory({ ...dentalHistory, anxietyLevel: lvl.value })}
                      >
                        <span>{lvl.label}</span>
                        {dentalHistory.anxietyLevel === lvl.value && <span aria-hidden="true">✓</span>}
                      </button>
                    ))}
                  </div>
                </div>
              </section>
            )}

            {/* CARD 7: Chief Complaint */}
            {cardStep === 7 && (
              <section className="step-card">
                <h2 className="card-title">{t.step7Title}</h2>
                <p className="card-hint">{t.step7Hint}</p>

                <div className="field">
                  <div className="field-label-row">
                    <label className="field-label" htmlFor="primaryComplaint">{t.primaryComplaint}</label>
                    <span className="tag-req">{t.requiredTag}</span>
                  </div>
                  <textarea
                    id="primaryComplaint"
                    className="textarea"
                    placeholder={t.primaryComplaintPh}
                    value={chiefComplaint.primaryComplaint}
                    onChange={(e) =>
                      setChiefComplaint({ ...chiefComplaint, primaryComplaint: e.target.value })
                    }
                  />
                </div>

                <div className="field">
                  <span className="field-label">{t.painScaleLabel}</span>
                  <div className="pain-grid">
                    {Array.from({ length: 11 }, (_, n) => {
                      const tier = n <= 3 ? 'pain-low' : n <= 6 ? 'pain-mid' : 'pain-high';
                      const selected = chiefComplaint.painScale === n;
                      return (
                        <button
                          key={n}
                          type="button"
                          className={`pain-btn ${tier}${selected ? ' is-selected' : ''}`}
                          onClick={() => setChiefComplaint({ ...chiefComplaint, painScale: n })}
                        >
                          {n}
                        </button>
                      );
                    })}
                  </div>
                  <div className="pain-legend">
                    <span>{t.painNone}</span>
                    <span>{t.painMod}</span>
                    <span>{t.painSevere} (10)</span>
                  </div>
                </div>

                <div className="field">
                  <label className="field-label" htmlFor="duration">{t.durationLabel}</label>
                  <div className="chips-wrap">
                    {t.durationChips.map((chip) => (
                      <button
                        key={chip}
                        type="button"
                        className={'chip-btn' + (chiefComplaint.duration === chip ? ' is-selected' : '')}
                        onClick={() => setChiefComplaint({ ...chiefComplaint, duration: chip })}
                      >
                        {chip}
                      </button>
                    ))}
                  </div>
                  <input
                    id="duration"
                    type="text"
                    className="input"
                    placeholder={t.durationPh}
                    value={chiefComplaint.duration}
                    onChange={(e) => setChiefComplaint({ ...chiefComplaint, duration: e.target.value })}
                  />
                </div>
              </section>
            )}
          </>
        )}
      </main>

      {/* Sticky Thumb-Friendly Navigation Footer */}
      {screen === 'wizard' && (
        <footer className="sticky-footer">
          <button type="button" className="btn-secondary" onClick={handleBack} disabled={submitting}>
            {t.back}
          </button>
          {cardStep < TOTAL_CARDS ? (
            <button type="button" className="btn-primary" onClick={handleNext}>
              {t.next}
            </button>
          ) : (
            <button type="button" className="btn-primary" onClick={handleSubmit} disabled={submitting}>
              {submitting ? t.submitting : t.submit}
            </button>
          )}
        </footer>
      )}
    </div>
  );
}
