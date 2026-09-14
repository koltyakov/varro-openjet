import { expect, it, vi } from 'vitest';
import { installViewStateChannel, type ViewStateHost } from './view-state';

function setup(initial: Record<string, unknown> = {}) {
  const send = vi.fn();
  const host: ViewStateHost = {
    __varroInitialViewState: initial,
    __sendToExtension: send,
  };
  installViewStateChannel(host);
  return { api: host.__vscodeWebviewState!, send };
}

it('keeps reads synchronous and sends only the final draft and problems snapshot', async () => {
  const { api, send } = setup({ route: 'chat' });
  const draft = 'long draft '.repeat(10_000);
  api.setState({ ...api.getState(), draft });
  api.setState({ ...api.getState(), problems: [{ message: 'Missing symbol' }] });
  expect(api.getState()).toEqual({ route: 'chat', draft, problems: [{ message: 'Missing symbol' }] });
  expect(send).not.toHaveBeenCalled();
  await Promise.resolve();
  expect(send).toHaveBeenCalledTimes(1);
  expect(send).toHaveBeenLastCalledWith({ type: 'host/view-state', payload: { state: api.getState() } });
});

it('skips unchanged values without serializing a long draft again', async () => {
  const initial = { draft: 'text '.repeat(10_000), problems: null };
  const { api, send } = setup(initial);
  api.setState({ ...api.getState(), problems: null });
  await Promise.resolve();
  expect(send).not.toHaveBeenCalled();
});

it('persists clearing a draft and subsequent writes in separate turns', async () => {
  const { api, send } = setup({ draft: 'old text', problems: ['old problem'] });
  api.setState({ ...api.getState(), draft: 'pending text' });
  api.setState({});
  await Promise.resolve();
  expect(send).toHaveBeenLastCalledWith({ type: 'host/view-state', payload: { state: {} } });
  api.setState({ draft: 'new text' });
  await Promise.resolve();
  expect(send).toHaveBeenCalledTimes(2);
  expect(send).toHaveBeenLastCalledWith({ type: 'host/view-state', payload: { state: { draft: 'new text' } } });
});

it('retains local state after a transport failure and allows another write', async () => {
  const { api, send } = setup();
  send.mockImplementationOnce(() => { throw new Error('Host closed'); });
  api.setState({ draft: 'retained' });
  await Promise.resolve();
  expect(api.getState()).toEqual({ draft: 'retained' });
  api.setState({ draft: 'updated' });
  await Promise.resolve();
  expect(send).toHaveBeenCalledTimes(2);
});
