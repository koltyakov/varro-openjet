export function ThinkingIndicator(props: { label?: string }) {
  return (
    <span class="shimmer-progress loading-verb">
      {props.label ?? 'Thinking'}
      <span class="chat-animated-ellipsis" />
    </span>
  );
}
