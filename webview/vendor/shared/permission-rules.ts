import type { PermissionMode } from './protocol';
import type { Agent, PermissionRule } from './opencode-types';

export const KNOWN_PERMISSION_NAMES = [
  'read',
  'edit',
  'glob',
  'grep',
  'list',
  'bash',
  'shell',
  'task',
  'external_directory',
  'todowrite',
  'question',
  'webfetch',
  'websearch',
  'codesearch',
  'lsp',
  'doom_loop',
  'skill',
] as const;

const SCALAR_CONFIG_PERMISSION_NAMES = new Set([
  'todowrite',
  'question',
  'webfetch',
  'websearch',
  'doom_loop',
]);

export function isScalarConfigPermission(permission: string): boolean {
  return SCALAR_CONFIG_PERMISSION_NAMES.has(permission);
}

const FULL_ACCESS_PERMISSION_RULES: PermissionRule[] = [
  ...KNOWN_PERMISSION_NAMES.map<PermissionRule>((permission) => ({
    permission,
    pattern: '*',
    action: 'allow',
  })),
  // OpenCode uses the last matching rule, so this must override earlier agent restrictions.
  { permission: '*', pattern: '*', action: 'allow' },
];

const READ_ONLY_PERMISSIONS = new Set(['read', 'glob', 'grep', 'list', 'codesearch', 'lsp']);

const SHARED_DIRECT_PERMISSION_RULES: PermissionRule[] = [
  { permission: 'todowrite', pattern: '*', action: 'allow' },
  { permission: 'question', pattern: '*', action: 'allow' },
];

const SAFE_DEFAULT_PERMISSION_RULES: PermissionRule[] = [
  { permission: '*', pattern: '*', action: 'ask' },
  ...SHARED_DIRECT_PERMISSION_RULES,
];

export function isSharedDirectPermission(permission: string): boolean {
  const normalized = permission.toLowerCase();
  return normalized === 'todowrite' || normalized === 'question';
}

export function getSharedDirectPermissionRules(): PermissionRule[] {
  return SHARED_DIRECT_PERMISSION_RULES;
}

export function getSafeDefaultPermissionRules(): PermissionRule[] {
  return SAFE_DEFAULT_PERMISSION_RULES;
}

export function getResolvedAgentPermissionRules(
  agentPermission: Agent['permission']
): PermissionRule[] {
  if (Array.isArray(agentPermission)) {
    return agentPermission.map(({ permission, pattern, action }) => ({
      permission,
      pattern,
      action,
    }));
  }

  return Object.entries(agentPermission).flatMap(([name, value]): PermissionRule[] => {
    if (value === 'allow' || value === 'ask' || value === 'deny') {
      return [{ permission: name, pattern: '*', action: value }];
    }
    if (!value) return [];
    return Object.entries(value).map(([pattern, action]) => ({
      permission: name,
      pattern,
      action,
    }));
  });
}

export function isKnownReadOnlyPermission(permission: string): boolean {
  return READ_ONLY_PERMISSIONS.has(permission.toLowerCase());
}

function isAutoApprovedPermission(permission: string): boolean {
  const normalized = permission.toLowerCase();
  return (
    normalized === 'task' ||
    normalized === 'todowrite' ||
    normalized === 'question' ||
    isKnownReadOnlyPermission(normalized)
  );
}

const AUTO_APPROVE_PERMISSION_RULES: PermissionRule[] = [
  // Specific safe allowances must follow this catch-all under last-match semantics.
  { permission: '*', pattern: '*', action: 'ask' },
  ...KNOWN_PERMISSION_NAMES.map<PermissionRule>((permission) => ({
    permission,
    pattern: '*',
    action: isAutoApprovedPermission(permission) ? 'allow' : 'ask',
  })),
];

export function getSessionPermissionRulesForMode(
  mode: PermissionMode,
  _target: 'create' | 'update'
): PermissionRule[] {
  if (mode === 'full') return FULL_ACCESS_PERMISSION_RULES;
  if (mode === 'auto') return AUTO_APPROVE_PERMISSION_RULES;
  return [];
}

/** Infer only presets whose effective rules we can recognize without guessing custom policy. */
export function inferSessionPermissionMode(
  rules: readonly PermissionRule[] | undefined
): PermissionMode | undefined {
  if (!rules?.length) return undefined;

  // Rules before the last universal rule cannot affect the resulting policy.
  const catchAllIndex = rules.findLastIndex(
    (rule) => rule.permission === '*' && rule.pattern === '*'
  );
  if (catchAllIndex < 0) return 'default';
  const effective = rules.slice(catchAllIndex);
  if (effective.every((rule) => rule.action === 'allow')) return 'full';

  // Pattern-specific or unknown tool overrides are custom policy, not Auto.
  if (
    effective[0]?.action !== 'ask' ||
    effective.slice(1).some(
      (rule) =>
        rule.pattern !== '*' ||
        !KNOWN_PERMISSION_NAMES.some((name) => name === rule.permission)
    )
  ) {
    return 'default';
  }

  const actions = new Map(effective.map((rule) => [rule.permission, rule.action]));
  return KNOWN_PERMISSION_NAMES.every(
    (name) => (actions.get(name) ?? 'ask') === (isAutoApprovedPermission(name) ? 'allow' : 'ask')
  )
    ? 'auto'
    : 'default';
}
