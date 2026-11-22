export type Locale = 'ar' | 'en';

export interface SchemaOption {
  key: string;
  labelEn: string;
  labelAr: string;
}

export interface PublicIntakeSchema {
  medicalConditions: SchemaOption[];
  medicationTypes: SchemaOption[];
  allergyTypes: SchemaOption[];
  dentalSymptoms: SchemaOption[];
}

export interface PublicClinicConfig {
  clinicName: string;
  clinicKey: string;
  defaultLocale: string;
  active: boolean;
  schema: PublicIntakeSchema;
}

export interface VerifyCodeResponse {
  valid: boolean;
  clinicName: string;
  patientFirstName: string;
  intakeSessionToken: string;
  sessionExpiresAt: string;
}

export interface IntakeDemographics {
  fullName: string;
  dob: string;
  gender: '' | 'Male' | 'Female';
  occupation: string;
  address: string;
  phone: string;
  email: string;
  emergencyName: string;
  emergencyPhone: string;
}

export interface IntakeMedicalHistory {
  underTreatment: 'Yes' | 'No';
  treatmentSpecify: string;
  hospitalizedLastYear: 'Yes' | 'No';
  hospitalizedReason: string;
  conditions: string[];
  otherCondition: string;
  pregnantOrBreastfeeding: 'Yes' | 'No' | 'N/A';
  medications: string[];
  otherMedications: string;
  allergies: string[];
  otherAllergies: string;
}

export interface IntakeDentalHistory {
  lastDentalVisitDate: string;
  lastVisitReason: string;
  symptoms: string[];
  pastProcedures: string;
  anxietyLevel: number;
}

export interface IntakeChiefComplaint {
  primaryComplaint: string;
  painScale: number;
  duration: string;
  triggers: string[];
}

export interface SubmitIntakeRequest {
  intakeSessionToken: string;
  demographics: IntakeDemographics;
  medicalHistory: IntakeMedicalHistory;
  dentalHistory: IntakeDentalHistory;
  chiefComplaint: IntakeChiefComplaint;
}

export interface SubmitIntakeResult {
  submitted: boolean;
  intakeId: string;
  status: string;
}
