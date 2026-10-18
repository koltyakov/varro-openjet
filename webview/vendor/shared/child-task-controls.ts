export type ChildTaskControls = {
  parentID: string | null;
  stopChild: boolean;
  backgroundEligible: boolean;
};

export type ChildInterruptResult = { interrupted: boolean };
export type BackgroundHandoffResult = { backgrounded: boolean };
