/** Only the directory-specific server error proves that a workspace location is missing. */
export function isMissingWorkspaceDirectoryMessage(message: string): boolean {
  return /^404\b[\s\S]*\blocation not found\b/i.test(message);
}

export function workspaceDirectoryRecoveryMessage(directory: string): string {
  return `Workspace folder unavailable: ${directory}. It may have been deleted or renamed. Restore the folder or reopen the workspace at its new location. The conversation has not been deleted or relocated.`;
}
