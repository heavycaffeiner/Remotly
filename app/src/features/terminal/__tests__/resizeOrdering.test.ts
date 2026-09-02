import {
  createResizeScheduler,
  type ResizeScheduler,
} from '../resizeScheduler';

/**
 * The terminal must not change size before the pty knows the new size.
 *
 * The measurement and the resize used to be one step, so the local grid
 * changed the moment the view was measured while the pty was told on a
 * debounce. For that window the application was still drawing for the size it
 * believed in, and anything it positioned from an edge, a status line, a
 * scroll region, a panel border, was applied against a grid that had already
 * moved underneath it.
 *
 * This drives the scheduler the way TerminalScreen does and records the order
 * the two sides are told, which is the property that broke.
 */
describe('resize ordering', () => {
  jest.useFakeTimers();

  interface Step {
    who: 'pty' | 'terminal';
    cols: number;
    rows: number;
  }

  /** Mirrors TerminalScreen: the pty is told, then the terminal follows. */
  function harness(delayMs = 400) {
    const steps: Step[] = [];
    const scheduler = createResizeScheduler(size => {
      steps.push({ who: 'pty', ...size });
      steps.push({ who: 'terminal', ...size });
    }, delayMs);
    return { steps, scheduler };
  }

  it('tells the pty before resizing the terminal', () => {
    const h = harness();
    h.scheduler.report({ cols: 80, rows: 40 });
    jest.advanceTimersByTime(400);

    expect(h.steps.map(s => s.who)).toEqual(['pty', 'terminal']);
  });

  it('never resizes the terminal to a size the pty was not given', () => {
    const h = harness();
    h.scheduler.report({ cols: 80, rows: 40 });
    h.scheduler.report({ cols: 80, rows: 24 });
    h.scheduler.report({ cols: 80, rows: 35 });
    jest.advanceTimersByTime(400);

    const sizes = (who: Step['who']): string[] =>
      h.steps.filter(s => s.who === who).map(s => `${s.cols}x${s.rows}`);
    expect(sizes('terminal')).toEqual(sizes('pty'));
  });

  /**
   * A keyboard opening and closing reports several sizes in quick succession.
   * Only the size that settles reaches either side, so the terminal is not
   * dragged through the intermediate grids.
   */
  it('applies only the settled size after a burst', () => {
    const h = harness();
    for (const rows of [40, 24, 25, 24, 35]) {
      h.scheduler.report({ cols: 80, rows });
      jest.advanceTimersByTime(50);
    }
    jest.advanceTimersByTime(400);

    expect(h.steps).toEqual([
      { who: 'pty', cols: 80, rows: 35 },
      { who: 'terminal', cols: 80, rows: 35 },
    ]);
  });

  it('does nothing when the size has not changed', () => {
    const h = harness();
    h.scheduler.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(400);
    const after = h.steps.length;

    h.scheduler.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(400);
    expect(h.steps.length).toBe(after);
  });

  /** Nothing is applied while the size is still settling. */
  it('leaves both sides alone until the debounce elapses', () => {
    const h = harness();
    h.scheduler.report({ cols: 80, rows: 40 });
    jest.advanceTimersByTime(399);
    expect(h.steps).toEqual([]);
  });

  /**
   * A resize that never reaches the terminal is recovered when the view comes
   * back, not by retrying on a timer.
   *
   * The apply is asynchronous and is dropped when the viewport is unmounted or
   * owns no terminal yet. The scheduler has recorded the size as sent by then,
   * so it would drop every later report of it; the failure clears that record
   * and the reattach re-offers the same measurement. This drives that whole
   * sequence, including the microtask the rejection is delivered on.
   */
  it('re-applies a dropped size when the view is attached again', async () => {
    const applied: Step[] = [];
    let attached = false;
    let scheduler: ResizeScheduler;

    // Mirrors TerminalScreen.sendResize: the pty is always told, and the
    // terminal only when the view can take it.
    scheduler = createResizeScheduler(size => {
      applied.push({ who: 'pty', ...size });
      const apply = attached
        ? Promise.resolve()
        : Promise.reject(new Error('terminal is not attached'));
      void apply
        .then(() => {
          applied.push({ who: 'terminal', ...size });
        })
        .catch(() => {
          scheduler.forget(size);
        });
    }, 400);

    // The keyboard settles while the view is detached: the pty is told, the
    // terminal is not.
    scheduler.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(400);
    await Promise.resolve();
    await Promise.resolve();
    expect(applied.map(s => s.who)).toEqual(['pty']);
    expect(scheduler.current()).toBeNull();

    // The view attaches and re-measures. Without the cleared record this
    // report would be deduped and the terminal would stay on the old grid.
    attached = true;
    scheduler.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(400);
    await Promise.resolve();
    await Promise.resolve();
    expect(applied.map(s => s.who)).toEqual(['pty', 'pty', 'terminal']);
    expect(scheduler.current()).toEqual({ cols: 80, rows: 24 });
  });
});
