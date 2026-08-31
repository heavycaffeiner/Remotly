/**
 * @format
 */

import React from 'react';
import { act } from 'react-test-renderer';

import {
  TerminalViewport,
  type TerminalViewportHandle,
} from '../TerminalViewport';
import * as NativeComponent from '../../specs/RemotlyTerminalNativeComponent';
import { encodeBase64 } from '../../lib/base64';

type Captured = {
  props: Record<string, unknown>;
  ref: React.Ref<unknown>;
} | null;

// The native component is mocked so tests can read the props the wrapper
// forwards (to invoke the event handlers) and assert on the Commands. The
// captured props are exposed through the mock's `__captured` export to avoid
// the jest factory out-of-scope restriction.
jest.mock('../../specs/RemotlyTerminalNativeComponent', () => {
  const ReactMod = require('react');
  const capturedRef: { current: Captured } = { current: null };
  const Commands = {
    write: jest.fn(),
    focusTerminal: jest.fn(),
    selectAll: jest.fn(),
    copySelection: jest.fn(),
  };
  return {
    __esModule: true,
    default: ReactMod.forwardRef(
      (props: Record<string, unknown>, ref: React.Ref<unknown>) => {
        capturedRef.current = { props, ref };
        return ReactMod.createElement('RemotlyTerminalView', { ...props, ref });
      },
    ),
    Commands,
    __captured: capturedRef,
  };
});

function render(
  props: React.ComponentProps<typeof TerminalViewport>,
  handle: { current: TerminalViewportHandle | null },
) {
  let renderer: import('react-test-renderer').ReactTestRenderer;
  act(() => {
    renderer = require('react-test-renderer').create(
      <TerminalViewport {...props} ref={handle} />,
    );
  });
  // react-test-renderer does not materialize a host instance for an unknown
  // tag, so point the internal host ref at a stub; the imperative handle only
  // needs a non-null node to issue a command.
  (captured().ref as unknown as { current: unknown }).current = 'fake-host';
  return renderer!;
}

function captured(): NonNullable<Captured> {
  const c = (NativeComponent as any).__captured.current as Captured;
  if (c == null) throw new Error('native component did not render');
  return c;
}

beforeEach(() => {
  (NativeComponent.Commands as any).write.mockClear();
  (NativeComponent.Commands as any).focusTerminal.mockClear();
  (NativeComponent.Commands as any).selectAll.mockClear();
  (NativeComponent.Commands as any).copySelection.mockClear();
});

test('renders the native component with session and font props and all handlers', () => {
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({ sessionId: 'sess-1', fontSize: 18 }, handle);
  const { props } = captured();
  expect(props.sessionId).toBe('sess-1');
  expect(props.fontSize).toBe(18);
  for (const key of [
    'onReady',
    'onError',
    'onInput',
    'onResizeGrid',
    'onBell',
    'onTitle',
    'onPtyWrite',
    'onCopy',
    'onFocusChange',
    'onFontSizeChange',
  ]) {
    expect(typeof props[key]).toBe('function');
  }
});

test('forwards decoded input bytes to onInput', () => {
  const onInput = jest.fn();
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({ onInput }, handle);
  const { props } = captured();
  const bytes = new Uint8Array([0x01, 0x02, 0x68, 0x69]);
  act(() => {
    (props.onInput as (e: { nativeEvent: { data: string } }) => void)({
      nativeEvent: { data: encodeBase64(bytes) },
    });
  });
  expect(onInput).toHaveBeenCalledTimes(1);
  expect(Array.from(onInput.mock.calls[0][0])).toEqual([
    0x01, 0x02, 0x68, 0x69,
  ]);
});

test('forwards decoded ptywrite bytes (multi-byte) to onPtyWrite', () => {
  const onPtyWrite = jest.fn();
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({ onPtyWrite }, handle);
  const { props } = captured();
  // '한' in UTF-8.
  const bytes = new Uint8Array([0xea, 0xb0, 0x80]);
  act(() => {
    (props.onPtyWrite as (e: { nativeEvent: { data: string } }) => void)({
      nativeEvent: { data: encodeBase64(bytes) },
    });
  });
  expect(onPtyWrite).toHaveBeenCalledTimes(1);
  expect(Array.from(onPtyWrite.mock.calls[0][0])).toEqual([0xea, 0xb0, 0x80]);
});

test('surfaces ready, renderer error, resize, bell and title events', () => {
  const onReady = jest.fn();
  const onError = jest.fn();
  const onResize = jest.fn();
  const onBell = jest.fn();
  const onTitle = jest.fn();
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render(
    { sessionId: 's', onReady, onError, onResize, onBell, onTitle },
    handle,
  );
  const { props } = captured();
  act(() => {
    (
      props.onReady as (e: {
        nativeEvent: { cols: number; rows: number };
      }) => void
    )({
      nativeEvent: { cols: 80, rows: 24 },
    });
    (props.onError as (e: { nativeEvent: { code: string } }) => void)({
      nativeEvent: { code: 'create_failed' },
    });
    (
      props.onResizeGrid as (e: {
        nativeEvent: { cols: number; rows: number };
      }) => void
    )({
      nativeEvent: { cols: 100, rows: 30 },
    });
    (props.onBell as () => void)();
    (props.onTitle as (e: { nativeEvent: { title: string } }) => void)({
      nativeEvent: { title: 'myterm' },
    });
  });
  expect(onReady).toHaveBeenCalledWith({ sessionId: 's', cols: 80, rows: 24 });
  expect(onError).toHaveBeenCalledWith('create_failed');
  expect(onResize).toHaveBeenCalledWith({ cols: 100, rows: 30 });
  expect(onBell).toHaveBeenCalledTimes(1);
  expect(onTitle).toHaveBeenCalledWith('myterm');
});

test('forwards a bounded whole-sp font size from pinch zoom', () => {
  const onFontSizeChange = jest.fn();
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({ onFontSizeChange }, handle);
  const { props } = captured();
  const emit = props.onFontSizeChange as (e: {
    nativeEvent: { fontSize: number };
  }) => void;
  act(() => {
    emit({ nativeEvent: { fontSize: 17.4 } });
    emit({ nativeEvent: { fontSize: 200 } });
  });
  expect(onFontSizeChange).toHaveBeenCalledTimes(1);
  expect(onFontSizeChange).toHaveBeenCalledWith(17);
});

test('write encodes bytes and issues the write command', async () => {
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({}, handle);
  expect(handle.current).not.toBeNull();
  const bytes = new Uint8Array([0x01, 0x02, 0x03]);
  await handle.current!.write(bytes);
  expect(NativeComponent.Commands.write).toHaveBeenCalledTimes(1);
  const [node, b64] = (NativeComponent.Commands.write as jest.Mock).mock
    .calls[0];
  expect(b64).toBe(encodeBase64(bytes));
  expect(node).toBeTruthy();
});

test('focus and selectAll issue their commands', async () => {
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({}, handle);
  await handle.current!.focus();
  await handle.current!.selectAll();
  expect(NativeComponent.Commands.focusTerminal).toHaveBeenCalledTimes(1);
  expect(NativeComponent.Commands.selectAll).toHaveBeenCalledTimes(1);
});

test('copy resolves with the text reported by onCopy', async () => {
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({}, handle);
  const { props } = captured();
  const promise = handle.current!.copy();
  expect(NativeComponent.Commands.copySelection).toHaveBeenCalledTimes(1);
  await act(async () => {
    (
      props.onCopy as (e: {
        nativeEvent: { ok: boolean; data: string };
      }) => void
    )({
      nativeEvent: { ok: true, data: 'hello' },
    });
  });
  await expect(promise).resolves.toBe('hello');
});

test('copy resolves null when there is no selection', async () => {
  const handle: { current: TerminalViewportHandle | null } = { current: null };
  render({}, handle);
  const { props } = captured();
  const promise = handle.current!.copy();
  await act(async () => {
    (
      props.onCopy as (e: {
        nativeEvent: { ok: boolean; data: string };
      }) => void
    )({
      nativeEvent: { ok: false, data: '' },
    });
  });
  await expect(promise).resolves.toBe(null);
});
