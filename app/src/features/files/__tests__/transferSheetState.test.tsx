/**
 * @format
 */

// The transfer sheet is opened from two places and mounted once.
//
// It used to be rendered by both FilesScreen and the app-wide indicator, so
// opening it from the files toolbar drew one sheet on top of another and the
// "transfers running" notice showed through the one in front.

import React from 'react';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { TransferSheet, openTransferSheet } from '../TransferSheet';
import { registerTransfer, resetTransfers } from '../../../lib/transfers';

jest.mock('../../../specs/NativeRemotlyFileIO', () => ({
  __esModule: true,
  default: { setTransfersActive: jest.fn().mockResolvedValue(undefined) },
}));

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

/** Every string the tree renders, which is what a user can actually read. */
function texts(tree: ReactTestRenderer): string[] {
  const out: string[] = [];
  const walk = (node: unknown): void => {
    if (node == null) return;
    if (typeof node === 'string') {
      out.push(node);
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    walk((node as { children?: unknown }).children);
  };
  walk(tree.toJSON());
  return out;
}

function mount(): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(
      <SafeAreaProvider initialMetrics={METRICS}>
        <TransferSheet />
      </SafeAreaProvider>,
    );
  });
  return tree;
}

beforeEach(() => {
  act(() => {
    resetTransfers();
  });
});

afterEach(() => {
  // Settling drops the foreground service, which would land after teardown.
  act(() => {
    resetTransfers();
  });
});

describe('the transfer sheet', () => {
  it('stays closed until something opens it', () => {
    const tree = mount();
    expect(texts(tree)).not.toContain('Transfers');
  });

  it('opens from anywhere through the shared opener', () => {
    const tree = mount();
    act(() => {
      openTransferSheet();
    });
    expect(texts(tree)).toContain('Transfers');
  });

  it('opens with nothing transferring, so the toolbar can reach it', () => {
    const tree = mount();
    act(() => {
      openTransferSheet();
    });
    expect(texts(tree)).toContain('Nothing transferring');
  });

  it('lists a running transfer once, not once per mount', () => {
    const tree = mount();
    act(() => {
      registerTransfer(
        {
          id: 'd1',
          direction: 'download',
          path: '/remote/report.pdf',
          name: 'report.pdf',
          hostId: 'h1',
          total: 100,
        },
        () => {},
      );
      openTransferSheet();
    });
    expect(texts(tree).filter(t => t === 'report.pdf')).toHaveLength(1);
  });
});
