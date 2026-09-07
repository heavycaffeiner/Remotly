// Desktop notifications a program running in the terminal asked for.
//
// OSC 9 carries a body only; OSC 777 carries a title and a body. The terminal
// core parses both and reports them the same way, so this only decides what
// reaches the system.

import NativeNotify from '../specs/NativeRemotlyNotify';
import { log } from './log';

/**
 * Posts a notification, ignoring one with nothing to say.
 *
 * Failure is not surfaced: the permission may be denied, which is the user's
 * decision and not something the session should be interrupted about.
 */
export function postTerminalNotification(info: {
  title: string;
  body: string;
}): void {
  const title = info.title.trim();
  const body = info.body.trim();
  if (title === '' && body === '') return;
  void NativeNotify.notify(title, body).catch(err => {
    // The message is not logged: it is remote output, and log.ts redacts
    // terminal content for exactly that reason.
    log.warn('terminal notification failed', { message: String(err) });
  });
}
