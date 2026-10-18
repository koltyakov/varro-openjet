/* oxlint-disable anti-slop/no-runtime-typeof, anti-slop/no-unknown-parameters -- Native HTTP/event records and answer values are parsed at this shared protocol boundary. */
import type { FormField } from '@opencode/client';
import { asRecord } from './type-utils';
import type { UnknownRecord } from './type-utils';

export type NativeFormValue = string | number | boolean | string[];
export type NativeFormAnswer = Record<string, NativeFormValue>;
// Non-finite numeric wire markers are visible as unsupported, never coerced into answers.
type NativeNumericField = Omit<
  Extract<FormField, { type: 'number' | 'integer' }>,
  'minimum' | 'maximum' | 'default'
> & {
  minimum?: number;
  maximum?: number;
  default?: number;
};
export type NativeFormField =
  | Exclude<FormField, { type: 'number' | 'integer' }>
  | NativeNumericField
  | {
      type: 'unsupported';
      key: string;
      title?: string;
      description?: string;
      nativeType: string;
      raw: UnknownRecord;
    };
export type NativeForm = {
  id: string;
  sessionID: string;
  title: string;
  fields: NativeFormField[];
  metadata?: UnknownRecord;
};
export type NativeFormReply = {
  kind: 'native-form';
  sessionID: string;
  answer: NativeFormAnswer;
};
export type QuestionAnswer = string[][] | NativeFormReply;

const BASE_PROPERTIES = ['key', 'type', 'title', 'description', 'required', 'hidden', 'when'];
const FIELD_PROPERTIES = new Map<string, ReadonlySet<string>>([
  [
    'string',
    new Set([
      ...BASE_PROPERTIES,
      'format',
      'minLength',
      'maxLength',
      'pattern',
      'placeholder',
      'default',
      'options',
      'custom',
    ]),
  ],
  ['number', new Set([...BASE_PROPERTIES, 'minimum', 'maximum', 'default'])],
  ['integer', new Set([...BASE_PROPERTIES, 'minimum', 'maximum', 'default'])],
  ['boolean', new Set([...BASE_PROPERTIES, 'default'])],
  [
    'multiselect',
    new Set([...BASE_PROPERTIES, 'options', 'minItems', 'maxItems', 'custom', 'default']),
  ],
  ['external', new Set(['key', 'type', 'title', 'description', 'url'])],
]);

export function isNativeFormAnswer(value: unknown): value is NativeFormAnswer {
  const record = asRecord(value);
  return (
    !!record &&
    !Array.isArray(value) &&
    Object.values(record).every(
      (item) =>
        typeof item === 'string' ||
        typeof item === 'boolean' ||
        (typeof item === 'number' && Number.isFinite(item)) ||
        (Array.isArray(item) && item.every((entry) => typeof entry === 'string'))
    )
  );
}

/** Decode native HTTP, event, and persisted metadata through the same boundary. */
export function parseNativeForm(value: unknown): NativeForm | null {
  const form = asRecord(value);
  if (
    !form ||
    typeof form.id !== 'string' ||
    typeof form.sessionID !== 'string' ||
    typeof form.title !== 'string' ||
    !Array.isArray(form.fields) ||
    !form.fields.length
  )
    return null;
  const keys = new Set<string>();
  const fields = form.fields.map((entry, index): NativeFormField => {
    const field = asRecord(entry) ?? {};
    const key = typeof field.key === 'string' ? field.key : `unsupported-${index}`;
    const duplicate = keys.has(key);
    keys.add(key);
    if (!duplicate && isSupportedField(field)) return field;
    return {
      type: 'unsupported',
      key,
      title: typeof field.title === 'string' ? field.title : undefined,
      description: typeof field.description === 'string' ? field.description : undefined,
      nativeType:
        typeof field.nativeType === 'string'
          ? field.nativeType
          : typeof field.type === 'string'
            ? field.type
            : 'unknown',
      raw: asRecord(field.raw) ?? field,
    };
  });
  const result: NativeForm = {
    id: form.id,
    sessionID: form.sessionID,
    title: form.title,
    fields,
  };
  const metadata = asRecord(form.metadata);
  if (metadata) result.metadata = metadata;
  return result;
}

function isSupportedField(
  field: UnknownRecord
): field is UnknownRecord & Exclude<NativeFormField, { type: 'unsupported' }> {
  if (typeof field.key !== 'string') return false;
  const properties = FIELD_PROPERTIES.get(String(field.type));
  if (!properties || Object.keys(field).some((key) => !properties.has(key))) return false;
  for (const key of ['title', 'description']) {
    if (field[key] !== undefined && typeof field[key] !== 'string') return false;
  }
  if (field.type === 'external') return typeof field.url === 'string';
  if (!['string', 'number', 'integer', 'boolean', 'multiselect'].includes(String(field.type)))
    return false;
  for (const key of ['required', 'hidden', 'custom']) {
    if (field[key] !== undefined && typeof field[key] !== 'boolean') return false;
  }
  if (
    field.when !== undefined &&
    (!Array.isArray(field.when) ||
      !field.when.every((value) => {
        const condition = asRecord(value);
        return (
          condition &&
          typeof condition.key === 'string' &&
          ['eq', 'neq'].includes(String(condition.op)) &&
          ['string', 'number', 'boolean'].includes(typeof condition.value)
        );
      }))
  )
    return false;
  if (
    field.options !== undefined &&
    (!Array.isArray(field.options) ||
      !field.options.every((value) => {
        const option = asRecord(value);
        return (
          option &&
          typeof option.value === 'string' &&
          typeof option.label === 'string' &&
          (option.description === undefined || typeof option.description === 'string')
        );
      }))
  )
    return false;
  for (const key of ['minimum', 'maximum', 'minLength', 'maxLength', 'minItems', 'maxItems']) {
    if (
      field[key] !== undefined &&
      (typeof field[key] !== 'number' || !Number.isFinite(field[key]))
    )
      return false;
  }
  for (const key of ['placeholder', 'pattern']) {
    if (field[key] !== undefined && typeof field[key] !== 'string') return false;
  }
  if (
    field.format !== undefined &&
    !['email', 'uri', 'date', 'date-time'].includes(String(field.format))
  )
    return false;
  if (field.type === 'multiselect' && !Array.isArray(field.options)) return false;
  if (field.default === undefined) return true;
  if (field.type === 'multiselect')
    return (
      Array.isArray(field.default) && field.default.every((value) => typeof value === 'string')
    );
  return (
    typeof field.default === (field.type === 'integer' ? 'number' : field.type) &&
    (typeof field.default !== 'number' || Number.isFinite(field.default))
  );
}

export function nativeFormDefaults(form: NativeForm): NativeFormAnswer {
  return Object.fromEntries(
    form.fields.flatMap((field) =>
      'default' in field && field.default !== undefined
        ? [[field.key, Array.isArray(field.default) ? [...field.default] : field.default]]
        : []
    )
  );
}

/** Field order is significant: a hidden answer must not activate its dependents. */
export function activeNativeFormFields(
  form: NativeForm,
  draft: NativeFormAnswer
): NativeFormField[] {
  const answer: NativeFormAnswer = Object.create(null);
  return form.fields.filter((field) => {
    const active =
      !('when' in field) ||
      !field.when ||
      field.when.every((condition) => {
        const value = answer[condition.key];
        if (value === undefined) return false;
        const hit = Array.isArray(value)
          ? value.some((item) => item === condition.value)
          : value === condition.value;
        return condition.op === 'eq' ? hit : !hit;
      });
    if (active && Object.hasOwn(draft, field.key)) answer[field.key] = draft[field.key]!;
    return active;
  });
}

export function nativeFormAnswer(form: NativeForm, draft: NativeFormAnswer): NativeFormAnswer {
  return Object.fromEntries(
    activeNativeFormFields(form, draft).flatMap((field) =>
      field.type !== 'unsupported' && Object.hasOwn(draft, field.key)
        ? [[field.key, draft[field.key]!]]
        : []
    )
  );
}

export function safeNativeFormURL(value: string): boolean {
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password;
  } catch {
    return false;
  }
}

/** Feedback only. The native reply endpoint remains authoritative. */
export function nativeFormFieldError(
  field: NativeFormField,
  value: NativeFormValue | undefined
): string | undefined {
  if (field.type === 'unsupported')
    return `Unsupported field type: ${field.nativeType}. Answer in another OpenCode client or skip this form.`;
  if (field.type === 'external')
    return value === true ? undefined : 'Confirm that you completed the external action.';
  if (value === undefined) return field.required ? 'Answer required.' : undefined;
  if (field.required && (value === '' || (Array.isArray(value) && !value.length)))
    return 'Answer required.';
  if (field.type === 'string') {
    if (typeof value !== 'string') return 'Enter text.';
    if (field.minLength !== undefined && value.length < field.minLength)
      return `Enter at least ${field.minLength} characters.`;
    if (field.maxLength !== undefined && value.length > field.maxLength)
      return `Enter at most ${field.maxLength} characters.`;
    if (field.pattern !== undefined) {
      try {
        if (!new RegExp(field.pattern).test(value))
          return 'The answer does not match the required pattern.';
      } catch {
        return 'The server supplied an invalid pattern. Answer in another client or skip this form.';
      }
    }
    if (field.options && !field.custom && !field.options.some((option) => option.value === value))
      return 'Select an available option.';
    if (field.format === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value))
      return 'Enter an email address.';
    if (field.format === 'uri' && !URL.canParse(value)) return 'Enter a URI.';
    if (field.format === 'date') {
      const date = new Date(`${value}T00:00:00.000Z`);
      if (
        !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
        Number.isNaN(date.getTime()) ||
        date.toISOString().slice(0, 10) !== value
      )
        return 'Enter a date as YYYY-MM-DD.';
    }
    if (field.format === 'date-time' && Number.isNaN(new Date(value).getTime()))
      return 'Enter a valid date and time.';
  }
  if (field.type === 'number' || field.type === 'integer') {
    if (typeof value !== 'number' || !Number.isFinite(value)) return 'Enter a number.';
    if (field.type === 'integer' && !Number.isInteger(value)) return 'Enter a whole number.';
    if (field.minimum !== undefined && value < field.minimum)
      return `Enter at least ${field.minimum}.`;
    if (field.maximum !== undefined && value > field.maximum)
      return `Enter at most ${field.maximum}.`;
  }
  if (field.type === 'boolean' && typeof value !== 'boolean') return 'Select Yes or No.';
  if (field.type === 'multiselect') {
    if (!Array.isArray(value)) return 'Select options.';
    if (field.minItems !== undefined && value.length < field.minItems)
      return `Select at least ${field.minItems} options.`;
    if (field.maxItems !== undefined && value.length > field.maxItems)
      return `Select at most ${field.maxItems} options.`;
    if (
      !field.custom &&
      value.some((item) => !field.options.some((option) => option.value === item))
    )
      return 'Select available options.';
  }
  return undefined;
}
