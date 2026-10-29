import {
  For,
  Match,
  Show,
  Switch,
  createEffect,
  createMemo,
  createSignal,
  createUniqueId,
} from 'solid-js';
import {
  activeNativeFormFields,
  nativeFormAnswer,
  nativeFormDefaults,
  nativeFormFieldError,
  safeNativeFormURL,
} from '../../shared/native-form';
import { isString } from '../../shared/type-utils';
import type {
  NativeForm,
  NativeFormAnswer,
  NativeFormField,
  NativeFormValue,
} from '../../shared/protocol';
import { rejectQuestion, respondQuestion } from '../hooks/useOpenCode';
import { postMessage } from '../lib/bridge';
import { nativeFormDrafts } from '../lib/native-form-drafts';
import type { NativeMultiSelectDraftRow } from '../lib/native-form-drafts';
import { NativeFormSelect } from './NativeFormSelect';
import { NativeQuestionChoices } from './NativeQuestionChoices';
import { NativeMultiSelect } from './NativeMultiSelect';

const [pendingReplies, setPendingReplies] = createSignal<ReadonlySet<string>>(new Set());

export function NativeFormPrompt(props: { form: NativeForm }) {
  const draftKey = () => `${props.form.sessionID}/${props.form.id}`;
  const isQuestion = () => props.form.metadata?.kind === 'question';
  const saved = nativeFormDrafts.get(props.form.id, props.form.sessionID);
  const [draft, setDraft] = createSignal<NativeFormAnswer>(
    saved?.values ?? nativeFormDefaults(props.form)
  );
  const [customValues, setCustomValues] = createSignal<Record<string, string>>(saved?.custom ?? {});
  const customValue = (key: string) =>
    Object.hasOwn(customValues(), key) ? customValues()[key]! : '';
  const [multiselectRows, setMultiselectRows] = createSignal<
    Record<string, NativeMultiSelectDraftRow[]>
  >(
    Object.fromEntries(
      props.form.fields.flatMap((field) => {
        if (field.type !== 'multiselect') return [];
        const initialValue = draft()[field.key];
        const selected = Array.isArray(initialValue) ? initialValue : [];
        const restored = saved?.multiselectRows?.[field.key];
        const rows = restored
          ? restored
              .filter((row, index) => row.value || index === restored.length - 1)
              .map((row) => ({ ...row, id: createUniqueId() }))
          : [
              ...new Set([
                ...selected.filter(
                  (item) => !field.options.some((option) => option.value === item)
                ),
                ...(field.custom && customValue(field.key) ? [customValue(field.key)] : []),
              ]),
            ].map((value) => ({ id: createUniqueId(), value, selected: true }));
        if (field.custom && (!rows.length || rows.at(-1)?.value))
          rows.push({ id: createUniqueId(), value: '', selected: false });
        return [[field.key, rows]];
      })
    )
  );
  const answers = createMemo<NativeFormAnswer>(() =>
    Object.fromEntries([
      ...Object.entries(draft()),
      ...props.form.fields.flatMap((field) => {
        if (field.type !== 'multiselect') return [];
        const value = Object.hasOwn(draft(), field.key) ? draft()[field.key] : undefined;
        const selected = Array.isArray(value)
          ? value.filter((item) => field.options.some((option) => option.value === item))
          : [];
        const custom = (multiselectRows()[field.key] ?? [])
          .filter((row) => row.selected && row.value)
          .map((row) => row.value);
        if (value === undefined && !custom.length) return [];
        return [[field.key, [...new Set([...selected, ...custom])]]];
      }),
    ])
  );
  const submitting = () => pendingReplies().has(draftKey());
  const setSubmitting = (pending: boolean) =>
    setPendingReplies((previous) => {
      const next = new Set(previous);
      if (pending) next.add(draftKey());
      else next.delete(draftKey());
      return next;
    });
  const [error, setError] = createSignal('');
  const [showErrors, setShowErrors] = createSignal(false);
  const [checkpointed, setCheckpointed] = createSignal(true);
  const [serverInvalidKey, setServerInvalidKey] = createSignal<string>();
  const fields = createMemo(() => activeNativeFormFields(props.form, answers()));
  const answerValue = (key: string) => (Object.hasOwn(answers(), key) ? answers()[key] : undefined);
  const unsupported = () => props.form.fields.some((field) => field.type === 'unsupported');
  let element: HTMLFormElement | undefined;

  createEffect(() => {
    setCheckpointed(
      nativeFormDrafts.save({
        id: props.form.id,
        sessionID: props.form.sessionID,
        values: draft(),
        custom: customValues(),
        multiselectRows: multiselectRows(),
      })
    );
  });

  function update(key: string, value: NativeFormValue | undefined) {
    setDraft((previous) => {
      const next = { ...previous };
      if (value === undefined) delete next[key];
      else
        Object.defineProperty(next, key, {
          value,
          enumerable: true,
          writable: true,
          configurable: true,
        });
      return next;
    });
    setError('');
    setServerInvalidKey(undefined);
  }

  async function submit() {
    if (submitting() || unsupported()) return;
    setServerInvalidKey(undefined);
    setShowErrors(true);
    if (fields().some((field) => nativeFormFieldError(field, answerValue(field.key)))) {
      element?.querySelector<HTMLElement>('[aria-invalid="true"]:not(:disabled)')?.focus();
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      await respondQuestion(
        props.form.id,
        {
          kind: 'native-form',
          sessionID: props.form.sessionID,
          answer: nativeFormAnswer(props.form, answers()),
        },
        { rethrow: true }
      );
      nativeFormDrafts.remove(props.form.id);
    } catch (err) {
      // Do not repeat server messages here: they can include sensitive answer values.
      const rejected =
        err instanceof Error
          ? fields().find((field) => err.message.endsWith(`: ${field.key}`))
          : undefined;
      setServerInvalidKey(rejected?.key);
      setError(
        'The server did not accept this response. Review your answers and retry, or answer in another OpenCode client.'
      );
    } finally {
      setSubmitting(false);
      if (serverInvalidKey())
        element?.querySelector<HTMLElement>('[aria-invalid="true"]:not(:disabled)')?.focus();
    }
  }

  async function skip() {
    if (submitting()) return;
    setSubmitting(true);
    try {
      await rejectQuestion(props.form.id, { rethrow: true });
      nativeFormDrafts.remove(props.form.id);
    } catch {
      setError('Could not skip this form. It is still pending. Try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div class="chat-tool-invocation-part question-prompt-card">
      <form
        class="question-prompt native-form"
        ref={(el) => {
          element = el;
        }}
        noValidate
        aria-label={props.form.title}
        aria-busy={submitting()}
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <div class="question-prompt-header">
          <span class="question-prompt-title">{props.form.title}</span>
        </div>
        <Show when={unsupported()}>
          <p role="alert">
            This form contains unsupported fields. Answer in another OpenCode client or skip this
            form.
          </p>
        </Show>
        <For each={fields()}>
          {(field) => {
            const fieldID = createUniqueId();
            // Keep conditional prop reads outside JSX to avoid creating memos in event handlers.
            const fieldValue = () =>
              Object.hasOwn(draft(), field.key) ? draft()[field.key] : undefined;
            const invalid = () =>
              serverInvalidKey() === field.key
                ? 'The server rejected this answer. Review it and retry.'
                : showErrors()
                  ? nativeFormFieldError(field, answerValue(field.key))
                  : undefined;
            return (
              <fieldset
                class="native-form-field"
                classList={{ 'native-question-field': isQuestion() }}
                disabled={submitting()}
                data-field-key={field.key}
                aria-labelledby={`${fieldID}-label`}
              >
                <div class="native-form-field-heading">
                  <span id={`${fieldID}-label`}>
                    {field.title ?? field.key}
                    <Show
                      when={field.type === 'external' || ('required' in field && field.required)}
                    >
                      <span class="native-form-required" aria-hidden="true">
                        {' '}
                        *
                      </span>
                    </Show>
                  </span>
                  <Show
                    when={
                      !isQuestion() &&
                      field.type !== 'external' &&
                      field.type !== 'unsupported' &&
                      !field.required &&
                      (Object.hasOwn(draft(), field.key) || !!customValue(field.key))
                    }
                  >
                    <button
                      type="button"
                      class="native-form-clear"
                      aria-label={`Clear ${field.title ?? field.key}`}
                      onClick={() => {
                        update(field.key, undefined);
                        setCustomValues((previous) => ({ ...previous, [field.key]: '' }));
                        if (field.type === 'multiselect')
                          setMultiselectRows((previous) => ({
                            ...previous,
                            [field.key]: field.custom
                              ? [{ id: createUniqueId(), value: '', selected: false }]
                              : [],
                          }));
                      }}
                    >
                      Clear
                    </button>
                  </Show>
                </div>
                <Show when={field.description}>
                  <p id={`${fieldID}-description`} class="question-prompt-hint">
                    {field.description}
                  </p>
                </Show>
                <div class="native-form-answer">
                  <NativeFieldControl
                    field={field}
                    value={fieldValue()}
                    custom={customValue(field.key)}
                    customRows={multiselectRows()[field.key] ?? []}
                    updateRows={(rows) => {
                      setMultiselectRows((previous) => ({ ...previous, [field.key]: rows }));
                      update(
                        field.key,
                        Array.isArray(draft()[field.key]) ? draft()[field.key] : []
                      );
                    }}
                    updateCustom={(value) => {
                      setCustomValues((previous) => ({ ...previous, [field.key]: value }));
                      setError('');
                      setServerInvalidKey(undefined);
                    }}
                    id={fieldID}
                    invalid={!!invalid()}
                    disabled={submitting()}
                    question={isQuestion()}
                    update={(value) => update(field.key, value)}
                  />
                  <Show when={invalid()}>
                    <p id={`${fieldID}-error`} class="native-form-error" role="alert">
                      {invalid()?.replace(/\.$/, '')}
                    </p>
                  </Show>
                </div>
              </fieldset>
            );
          }}
        </For>
        <Show when={error()}>
          <p role="alert" class="native-form-error">
            {error().replace(/\.$/, '')}
          </p>
        </Show>
        <Show when={!checkpointed()}>
          <p role="status">
            This draft is too large to restore after reload. Keep this view open until the response
            is accepted.
          </p>
        </Show>
        <div class="question-prompt-actions">
          <button
            type="button"
            class="question-btn question-btn-secondary"
            disabled={submitting()}
            onClick={() => void skip()}
          >
            Skip
          </button>
          <button
            type="submit"
            class="question-btn question-btn-primary"
            disabled={submitting() || unsupported()}
          >
            Submit
          </button>
        </div>
      </form>
    </div>
  );
}

function NativeFieldControl(props: {
  field: NativeFormField;
  value: NativeFormValue | undefined;
  id: string;
  invalid: boolean;
  disabled: boolean;
  question: boolean;
  custom: string;
  customRows: NativeMultiSelectDraftRow[];
  updateRows(rows: NativeMultiSelectDraftRow[]): void;
  updateCustom(value: string): void;
  update(value: NativeFormValue | undefined): void;
}) {
  const [numericText, setNumericText] = createSignal(
    props.value === undefined ? '' : String(props.value)
  );
  createEffect(() => {
    if (props.value === undefined) setNumericText('');
  });
  const label = () => props.field.title ?? props.field.key;
  const booleanSelectValue = () => (props.value === undefined ? '' : String(props.value));
  const attributes = () => ({
    'aria-label': label(),
    'aria-invalid': props.invalid,
    'aria-required':
      props.field.type === 'external' ||
      ('required' in props.field && props.field.required === true),
    'aria-describedby':
      [
        props.field.description ? `${props.id}-description` : '',
        props.invalid ? `${props.id}-error` : '',
      ]
        .filter(Boolean)
        .join(' ') || undefined,
  });
  return (
    <Switch>
      <Match when={props.field.type === 'unsupported'}>
        <p role="alert">Unsupported field. Use another OpenCode client or skip this form.</p>
      </Match>
      <Match when={props.field.type === 'external' ? props.field : undefined}>
        {(field) => (
          <>
            <Show
              when={safeNativeFormURL(field().url)}
              fallback={
                <p role="alert">
                  This external action has an unsafe or unsupported URL. Use another client or skip
                  this form.
                </p>
              }
            >
              <button
                type="button"
                class="question-btn question-btn-secondary"
                onClick={() =>
                  postMessage({ type: 'vscode/open-external', payload: { url: field().url } })
                }
              >
                Open external action
              </button>
            </Show>
            <label class="native-form-option">
              <input
                type="checkbox"
                {...attributes()}
                aria-label={`${label()}: I completed this action`}
                checked={props.value === true}
                onChange={(event) => props.update(event.currentTarget.checked)}
              />{' '}
              I completed this action
            </label>
          </>
        )}
      </Match>
      <Match when={props.field.type === 'boolean'}>
        <Show
          when={props.question}
          fallback={
            <NativeFormSelect
              label={label()}
              invalid={props.invalid}
              required={attributes()['aria-required']}
              describedBy={attributes()['aria-describedby']}
              disabled={props.disabled}
              value={booleanSelectValue()}
              options={[
                { value: '', label: 'Unanswered' },
                { value: 'true', label: 'Yes' },
                { value: 'false', label: 'No' },
              ]}
              onChange={(value) => props.update(value === '' ? undefined : value === 'true')}
            />
          }
        >
          <NativeQuestionChoices
            id={props.id}
            label={label()}
            value={props.value}
            options={[
              { value: true, label: 'Yes' },
              { value: false, label: 'No' },
            ]}
            invalid={props.invalid}
            required={attributes()['aria-required']}
            describedBy={attributes()['aria-describedby']}
            disabled={props.disabled}
            onChange={props.update}
          />
        </Show>
      </Match>
      <Match when={props.field.type === 'number' || props.field.type === 'integer'}>
        <input
          {...attributes()}
          class="question-custom-input"
          type="text"
          inputmode="decimal"
          autocomplete="off"
          value={numericText()}
          onInput={(event) => {
            const value = event.currentTarget.value;
            setNumericText(value);
            // Retain incomplete/invalid numeric drafts as text; never coerce empty input to zero.
            props.update(
              !value.trim() ? undefined : Number.isFinite(Number(value)) ? Number(value) : value
            );
          }}
        />
      </Match>
      <Match when={props.field.type === 'string' ? props.field : undefined}>
        {(field) => (
          <Show
            when={props.question && !field().options?.length}
            fallback={
              <>
                <Show when={field().options}>
                  <Show
                    when={props.question}
                    fallback={
                      <NativeFormSelect
                        label={label()}
                        invalid={props.invalid}
                        required={attributes()['aria-required']}
                        describedBy={attributes()['aria-describedby']}
                        disabled={props.disabled}
                        value={isString(props.value) ? `value:${props.value}` : ''}
                        options={[
                          { value: '', label: 'Unanswered' },
                          ...(field().options ?? []).map((option) => ({
                            ...option,
                            value: `value:${option.value}`,
                          })),
                        ]}
                        onChange={(value) =>
                          props.update(value === '' ? undefined : value.slice(6))
                        }
                      />
                    }
                  >
                    <NativeQuestionChoices
                      id={props.id}
                      label={label()}
                      value={props.value}
                      options={field().options ?? []}
                      invalid={props.invalid}
                      required={attributes()['aria-required']}
                      describedBy={attributes()['aria-describedby']}
                      disabled={props.disabled}
                      custom={field().custom}
                      customValue={props.custom}
                      onChange={props.update}
                      onCustomChange={props.updateCustom}
                    />
                  </Show>
                </Show>
                <Show when={!field().options || (field().custom && !props.question)}>
                  <textarea
                    {...attributes()}
                    class="question-custom-input native-form-text-input"
                    rows={1}
                    autocomplete="off"
                    aria-label={field().options ? `${label()}: custom answer` : label()}
                    placeholder={field().placeholder}
                    value={isString(props.value) ? props.value : ''}
                    onInput={(event) => props.update(event.currentTarget.value)}
                  />
                </Show>
              </>
            }
          >
            <textarea
              {...attributes()}
              class="question-custom-input native-form-text-input"
              rows={1}
              placeholder={field().placeholder ?? 'Type your answer'}
              autocomplete="off"
              disabled={props.disabled}
              value={isString(props.value) ? props.value : ''}
              onInput={(event) => props.update(event.currentTarget.value)}
            />
          </Show>
        )}
      </Match>
      <Match when={props.field.type === 'multiselect' ? props.field : undefined}>
        {(field) => (
          <NativeMultiSelect
            field={field()}
            id={props.id}
            value={props.value}
            rows={props.customRows}
            invalid={props.invalid}
            disabled={props.disabled}
            describedBy={attributes()['aria-describedby']}
            onChange={props.update}
            onRowsChange={props.updateRows}
          />
        )}
      </Match>
    </Switch>
  );
}
