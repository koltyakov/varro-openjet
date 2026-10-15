export const MAX_SIDE_QUESTION_LENGTH = 8_000;
export const MAX_SIDE_QUESTION_ANSWER_LENGTH = 64_000;
export const SIDE_QUESTION_TIMEOUT_MS = 35_000;
export const MAX_SIDE_QUESTION_TURNS = 12;
export const MAX_SIDE_QUESTION_CONTEXT_LENGTH = 128_000;

export type SideQuestionAnswer = { text: string };
export type SideQuestionTurn = { question: string; answer: string };
export type SideQuestionRequest = { question: string; history?: SideQuestionTurn[] };

export function getSideQuestionText(text: string): string | null {
  const match = text.trim().match(/^\/btw(?:\s+([\s\S]*))?$/i);
  return match ? (match[1]?.trim() ?? '') : null;
}
