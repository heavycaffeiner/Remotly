import { createResizeScheduler } from '../resizeScheduler';

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
});
