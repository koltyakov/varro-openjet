import { createSignal } from 'solid-js';

const cards = new Map<Element, { sessionId: string; visible: boolean }>();
const [visibleSessionIds, setVisibleSessionIds] = createSignal<ReadonlySet<string>>(new Set());
let observer: IntersectionObserver | undefined;

function publishVisibility() {
  const next = new Set(
    [...cards.values()].filter((card) => card.visible).map((card) => card.sessionId)
  );
  setVisibleSessionIds((previous) =>
    previous.size === next.size && [...next].every((id) => previous.has(id)) ? previous : next
  );
}

export function isSubagentCardVisible(sessionId: string) {
  return visibleSessionIds().has(sessionId);
}

export function observeSubagentCard(element: HTMLElement, sessionId: string) {
  if (typeof IntersectionObserver === 'undefined') return () => {};
  observer ??= new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const card = cards.get(entry.target);
        if (card) card.visible = entry.isIntersecting && entry.intersectionRatio >= 0.5;
      }
      publishVisibility();
    },
    { threshold: [0, 0.5] }
  );
  const card = { sessionId, visible: false };
  cards.set(element, card);
  observer.observe(element);
  return () => {
    if (cards.get(element) !== card) return;
    observer?.unobserve(element);
    cards.delete(element);
    publishVisibility();
    if (cards.size === 0) {
      observer?.disconnect();
      observer = undefined;
    }
  };
}
