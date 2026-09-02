import { createResizeScheduler } from '../resizeScheduler';

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
});

describe('createResizeScheduler', () => {
  it('debounces a burst into one send', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 20 });
    s.report({ cols: 80, rows: 22 });
    s.report({ cols: 80, rows: 24 });
    expect(send).not.toHaveBeenCalled();

    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ cols: 80, rows: 24 });
  });

  it('drops a size identical to the one already sent', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('sends again once the size actually changes', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    s.report({ cols: 100, rows: 30 });
    jest.advanceTimersByTime(100);

    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenLastCalledWith({ cols: 100, rows: 30 });
  });

  it('flush sends the pending size immediately and only once', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 500);

    s.report({ cols: 90, rows: 40 });
    s.flush();
    expect(send).toHaveBeenCalledWith({ cols: 90, rows: 40 });

    jest.advanceTimersByTime(500);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('flush with nothing pending does nothing', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);
    s.flush();
    expect(send).not.toHaveBeenCalled();
  });

  it('cancel drops the pending size', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    s.cancel();
    jest.advanceTimersByTime(100);
    expect(send).not.toHaveBeenCalled();
  });

  it('rejects a zero or negative grid', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 0, rows: 24 });
    jest.advanceTimersByTime(100);
    s.report({ cols: 80, rows: -1 });
    jest.advanceTimersByTime(100);
    s.report({ cols: 80, rows: 0 });
    jest.advanceTimersByTime(100);

    expect(send).not.toHaveBeenCalled();
  });

  it('reports the last size actually sent', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    expect(s.current()).toBeNull();
    s.report({ cols: 80, rows: 24 });
    expect(s.current()).toBeNull();

    jest.advanceTimersByTime(100);
    expect(s.current()).toEqual({ cols: 80, rows: 24 });
  });

  it('reset drops a size measured for a previous session', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    s.reset();
    jest.advanceTimersByTime(100);
    expect(send).not.toHaveBeenCalled();
  });

  it('reset clears the baseline so the same size sends for a new session', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);

    s.reset();
    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('a later report supersedes an earlier pending one', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(50);
    s.report({ cols: 120, rows: 40 });
    jest.advanceTimersByTime(50);
    // The window restarted, so nothing has been sent yet.
    expect(send).not.toHaveBeenCalled();

    jest.advanceTimersByTime(50);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ cols: 120, rows: 40 });
  });

  it('reporting the live size cancels a pending different size', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);

    // Transient size during a keyboard animation, settling back to the live one.
    s.report({ cols: 80, rows: 12 });
    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);
  });

  /**
   * A send that throws never reached the terminal, so the size must stay
   * offerable. Recording it anyway left the grid at the old size with nothing
   * able to correct it: every later report of the same size was deduped
   * against a send that did not land.
   */
  it('re-sends a size whose send threw', () => {
    const send = jest.fn(() => {
      throw new Error('viewport is not mounted');
    });
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);
    expect(s.current()).toBeNull();

    // The same measurement again: it is offered rather than dropped.
    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(2);
  });

  /**
   * The apply is asynchronous, so a size can be recorded as sent and then fail
   * to reach the terminal. Clearing the baseline is what lets the next
   * measurement offer it again.
   */
  it('re-sends a size after the baseline is forgotten', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);

    // Deduped while the baseline holds it.
    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(1);

    // The apply failed after the fact.
    s.forget({ cols: 80, rows: 24 });
    expect(s.current()).toBeNull();

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    expect(send).toHaveBeenCalledTimes(2);
  });

  /** Forgetting a size that is not the baseline leaves it alone. */
  it('ignores a forget for a different size', () => {
    const send = jest.fn();
    const s = createResizeScheduler(send, 100);

    s.report({ cols: 80, rows: 24 });
    jest.advanceTimersByTime(100);
    s.forget({ cols: 80, rows: 12 });
    expect(s.current()).toEqual({ cols: 80, rows: 24 });
  });
});
