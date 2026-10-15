import { Show } from 'solid-js';
import {
  chatBubbleQuestionIcon,
  transitionUpIcon,
  transitionUpSolidIcon,
  xmarkIcon,
} from '../../lib/ui-icons';
import { UiIcon } from '../UiIcon';
import { QUEUE_ONLY_SELECTION_TOOLTIP } from './active-turn-selection';

export function BusySendMenu(props: {
  ref?: HTMLDivElement | ((el: HTMLDivElement) => void);
  onQueue: () => void;
  onSteer: () => void;
  onSideQuestion?: () => void;
  sideQuestionDisabledReason?: string;
  onStopAndSend: () => void;
  queueOnly?: boolean;
}) {
  return (
    <div ref={props.ref} class="toolbar-popover busy-menu" onClick={(e) => e.stopPropagation()}>
      <button class="toolbar-popover-item" onClick={props.onQueue}>
        <span class="busy-menu-icon">
          <UiIcon source={transitionUpIcon} width={14} height={14} />
        </span>
        <span class="busy-menu-label">Queue</span>
        <span class="busy-menu-hint">Enter</span>
      </button>
      <button
        class="toolbar-popover-item"
        onClick={props.onSteer}
        disabled={props.queueOnly}
        title={props.queueOnly ? QUEUE_ONLY_SELECTION_TOOLTIP : undefined}
      >
        <span class="busy-menu-icon">
          <UiIcon source={transitionUpSolidIcon} width={14} height={14} />
        </span>
        <span class="busy-menu-label">Steer</span>
        <span class="busy-menu-hint">{'\u2303'}Enter</span>
      </button>
      <Show when={props.onSideQuestion}>
        <button
          class="toolbar-popover-item"
          onClick={() => props.onSideQuestion?.()}
          disabled={!!props.sideQuestionDisabledReason}
          title={props.sideQuestionDisabledReason}
        >
          <span class="busy-menu-icon">
            <UiIcon source={chatBubbleQuestionIcon} width={14} height={14} />
          </span>
          <span class="busy-menu-label">Side question</span>
        </button>
      </Show>
      <button
        class="toolbar-popover-item"
        onClick={props.onStopAndSend}
        disabled={props.queueOnly}
        title={props.queueOnly ? QUEUE_ONLY_SELECTION_TOOLTIP : undefined}
      >
        <span class="busy-menu-icon" style={{ color: 'var(--color-vscode-error)' }}>
          <UiIcon source={xmarkIcon} width={14} height={14} />
        </span>
        <span class="busy-menu-label">Stop and Send</span>
      </button>
    </div>
  );
}
