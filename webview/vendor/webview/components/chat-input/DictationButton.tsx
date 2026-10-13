import { Show, createSignal, onCleanup, onMount } from 'solid-js';
import { onMessage, postMessage } from '../../lib/bridge';
import { microphoneIcon, microphoneSpeakingSolidIcon } from '../../lib/ui-icons';
import { Tooltip } from '../Tooltip';
import { UiIcon } from '../UiIcon';
import { showSessionActionFeedback } from '../chat/SessionActionFeedback';

type DictationAction = 'status' | 'toggle' | 'cancel';
const REQUEST_TIMEOUT_MS = 8_000;

export function DictationButton(props: {
  focusComposer: () => void;
  remote: boolean;
  onAvailabilityChange?: () => void;
}) {
  const [available, setAvailable] = createSignal(false);
  const [running, setRunning] = createSignal(false);
  // Handy's CLI has no recording-status response. This is only our last toggle request.
  const [activationRequested, setActivationRequested] = createSignal(false);
  const [busy, setBusy] = createSignal(false);
  let pending:
    | { id: string; action: DictationAction; timer: ReturnType<typeof setTimeout> }
    | undefined;

  const label = () => (activationRequested() ? 'Stop Handy dictation' : 'Start Handy dictation');
  const tooltip = () =>
    !running()
      ? 'Start Handy first to use dictation'
      : `${label()}. Right-click to cancel. The icon tracks Varro toggles only; Handy shortcuts may change recording state.`;

  function request(action: DictationAction) {
    if (props.remote || pending) return;
    if (action !== 'status') props.focusComposer();
    const id = crypto.randomUUID();
    setBusy(action !== 'status');
    const timer = setTimeout(() => {
      pending = undefined;
      setBusy(false);
      if (action !== 'status') {
        setActivationRequested(false);
        showSessionActionFeedback(
          'Handy did not respond. Check its recording state before toggling again.',
          'warning'
        );
      }
    }, REQUEST_TIMEOUT_MS);
    pending = { id, action, timer };
    if (!postMessage({ type: 'handy/request', payload: { requestId: id, action } })) {
      clearTimeout(timer);
      pending = undefined;
      setBusy(false);
      if (action !== 'status')
        showSessionActionFeedback('Handy integration is unavailable.', 'warning');
    }
  }

  const unsubscribe = onMessage((message) => {
    if (message.type !== 'handy/result' || message.payload.requestId !== pending?.id) return;
    const action = pending.action;
    clearTimeout(pending.timer);
    pending = undefined;
    setBusy(false);
    const availabilityChanged = available() !== message.payload.available;
    setAvailable(message.payload.available);
    if (availabilityChanged) props.onAvailabilityChange?.();
    setRunning(message.payload.running);
    if (!message.payload.running || message.payload.error) setActivationRequested(false);
    if (message.payload.error) {
      if (action !== 'status') showSessionActionFeedback(message.payload.error, 'warning');
    } else if (action === 'toggle' && message.payload.available && message.payload.running) {
      setActivationRequested((value) => !value);
    } else if (action === 'cancel') {
      setActivationRequested(false);
    }
  });
  const refresh = () => {
    if (document.visibilityState !== 'hidden') request('status');
  };
  let refreshTimer: ReturnType<typeof setInterval> | undefined;
  onMount(() => {
    if (props.remote) return;
    refresh();
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    refreshTimer = setInterval(refresh, 30_000);
  });
  onCleanup(() => {
    unsubscribe();
    if (pending) clearTimeout(pending.timer);
    clearInterval(refreshTimer);
    window.removeEventListener('focus', refresh);
    document.removeEventListener('visibilitychange', refresh);
  });

  return (
    <Show when={available() && !props.remote}>
      <Tooltip content={tooltip()}>
        <button
          type="button"
          class="toolbar-dictation-button"
          classList={{ 'activation-requested': activationRequested() }}
          aria-label={label()}
          aria-pressed={activationRequested()}
          disabled={!running() || busy()}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => request('toggle')}
          onContextMenu={(event) => {
            event.preventDefault();
            if (running() && !busy()) request('cancel');
          }}
        >
          <UiIcon
            source={activationRequested() ? microphoneSpeakingSolidIcon : microphoneIcon}
            width={15}
            height={15}
          />
        </button>
      </Tooltip>
    </Show>
  );
}
